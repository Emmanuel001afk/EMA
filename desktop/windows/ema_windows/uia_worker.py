"""Short-lived Windows UI Automation worker. Parent enforces a hard process timeout."""
from collections import deque
from dataclasses import asdict
import ctypes
import json
import sys
import time

from .interaction import Control, Screen, select, effect


def foreground():
    user = ctypes.windll.user32
    user.GetForegroundWindow.restype = ctypes.c_void_p
    hwnd = user.GetForegroundWindow()
    pid = ctypes.c_ulong()
    user.GetWindowThreadProcessId(ctypes.c_void_p(hwnd), ctypes.byref(pid))
    return int(hwnd or 0), pid.value


def snapshot(hwnd, pid):
    if foreground() != (hwnd, pid):
        raise PermissionError("The selected window is no longer foreground. No action is allowed in another window.")
    from pywinauto import Desktop
    root = Desktop(backend="uia").window(handle=hwnd).wrapper_object()
    queue = deque([root]); controls = []; objects = {}
    visited = 0
    while queue and visited < 1200:
        node = queue.popleft(); visited += 1
        try:
            if not node.is_visible():
                continue
            info = node.element_info
            kind = info.control_type
            private = bool(info.element.CurrentIsPassword)
            key = str(tuple(info.runtime_id))
            value = ""
            if kind == "Edit" and not private:
                value = str(node.iface_value.CurrentValue)
            toggle = None
            if kind == "CheckBox":
                toggle = int(node.iface_toggle.CurrentToggleState)
            # Password names/values are not returned to the parent or shown in the transcript.
            control = Control(key, "[protected]" if private else info.name, kind, node.is_enabled(), value, private, toggle)
            controls.append(control); objects[key] = node
            # Limit breadth as well as traversal count.
            queue.extend(node.children()[:max(0, 1200 - visited - len(queue))])
        except Exception:
            continue
    if foreground() != (hwnd, pid):
        raise PermissionError("The foreground window changed during observation.")
    return Screen(hwnd, pid, tuple(controls)), objects


def execute(request):
    hwnd, pid = int(request["hwnd"]), int(request["pid"])
    before, objects = snapshot(hwnd, pid)
    operation = request["operation"]
    if operation == "inspect":
        return {"status": "verified", "message": "\n".join(f"{c.kind}: {c.name}" for c in before.controls if c.name)[:20000] or "No readable controls."}
    if operation == "scroll":
        from pywinauto import Desktop
        root = Desktop(backend="uia").window(handle=hwnd).wrapper_object()
        candidates = []
        for node in objects.values():
            try:
                pattern = node.iface_scroll
                if pattern.CurrentVerticallyScrollable:
                    candidates.append(pattern)
            except Exception:
                pass
        if len(candidates) != 1:
            return {"status": "needs input", "message": "I need one unambiguous scrollable region. Nothing was scrolled."}
        if foreground() != (hwnd, pid):
            raise PermissionError("Foreground changed before scroll.")
        # UIA ScrollAmount: NoAmount=2, SmallDecrement=1, SmallIncrement=4.
        candidates[0].Scroll(2, 1 if request["target"] == "up" else 4)
        for _ in range(12):
            time.sleep(.2)
            after, _ = snapshot(hwnd, pid)
            if before.controls != after.controls:
                return {"status": "verified", "message": "Scrolled; visible controls changed."}
        return {"status": "requested, not verified", "message": "Scroll was requested, but no visible effect was verified. I will not repeat it."}
    chosen = select(before, operation, request["target"])
    node = objects[chosen.key]
    # Bind identity and state again, not only the HWND or an old screen coordinate.
    fresh, objects = snapshot(hwnd, pid)
    if before != fresh:
        return {"status": "needs input", "message": "The screen changed before dispatch. Nothing was dispatched; inspect and retry."}
    node = objects[chosen.key]
    if foreground() != (hwnd, pid):
        raise PermissionError("Foreground changed immediately before dispatch.")
    if operation == "type":
        if chosen.value == request["target"]:
            return {"status": "verified", "message": "The exact text is already in the field."}
        node.iface_value.SetValue(request["target"])
    elif operation == "click":
        if chosen.toggle is not None:
            node.iface_toggle.Toggle()
        elif chosen.kind in {"TabItem", "ListItem", "TreeItem", "RadioButton"}:
            node.iface_selection_item.Select()
        else:
            node.iface_invoke.Invoke()
    else:
        raise ValueError("Unknown operation.")
    stable = 0
    last = None
    for _ in range(25):
        time.sleep(.2)
        after, _ = snapshot(hwnd, pid)
        if effect(before, chosen, after, operation, request["target"]):
            evidence = ("exact" if operation == "type" or chosen.toggle is not None else after.controls)
            stable = stable + 1 if evidence == last else 1
            last = evidence
            if stable >= 3:
                return {"status": "verified", "message": "The interaction's observable effect was verified. This does not prove a larger task such as delivery or payment."}
        else:
            stable = 0
    return {"status": "requested, not verified", "message": "The control accepted an action, but its effect was not verified. It will not be repeated automatically."}


def main():
    try:
        request = json.loads(sys.stdin.read(100000))
        result = execute(request)
    except LookupError as exc:
        result = {"status": "needs input", "message": str(exc)}
    except PermissionError as exc:
        result = {"status": "blocked", "message": str(exc)}
    except Exception:
        # Exceptions may occur AFTER a target accepted an event. Never call that definitely unsent.
        result = {"status": "requested, not verified", "message": "Windows UI Automation could not complete observation. An action may have been accepted; check the target before retrying."}
    print(json.dumps(result, ensure_ascii=True))


if __name__ == "__main__":
    main()
