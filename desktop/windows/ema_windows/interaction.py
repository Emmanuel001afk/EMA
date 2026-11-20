"""Platform-neutral UI decision and evidence policy; no coordinate guessing."""
from dataclasses import dataclass


@dataclass(frozen=True)
class Control:
    key: str
    name: str
    kind: str
    enabled: bool = True
    value: str = ""
    password: bool = False
    toggle: int | None = None


@dataclass(frozen=True)
class Screen:
    hwnd: int
    pid: int
    controls: tuple[Control, ...]


def select(screen: Screen, operation: str, target: str) -> Control:
    if operation == "click":
        matches = [c for c in screen.controls if c.enabled and not c.password and
                   c.name.strip().casefold() == target.strip().casefold() and
                   c.kind in {"Button", "Hyperlink", "TabItem", "MenuItem", "CheckBox", "RadioButton", "ListItem", "TreeItem"}]
    elif operation == "type":
        matches = [c for c in screen.controls if c.enabled and not c.password and c.kind == "Edit"]
    else:
        raise ValueError("Unsupported screen operation.")
    if not matches:
        raise LookupError("No readable, enabled matching control. Protected/password fields are never used.")
    if len(matches) != 1:
        raise LookupError("Several controls match. Use a more specific label or a window with one editable field.")
    control = matches[0]
    if operation == "type" and control.value and control.value != target:
        raise LookupError("The field contains a different draft. Clear or save it yourself before typing.")
    return control


def effect(before: Screen, selected: Control, after: Screen, operation: str, text: str) -> bool:
    if (before.hwnd, before.pid) != (after.hwnd, after.pid):
        return False
    matches = [c for c in after.controls if c.key == selected.key]
    if operation == "type":
        return len(matches) == 1 and not matches[0].password and matches[0].value == text
    if selected.toggle is not None:
        return len(matches) == 1 and matches[0].toggle is not None and matches[0].toggle != selected.toggle
    return before.controls != after.controls
