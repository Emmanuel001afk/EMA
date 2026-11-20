import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ema_windows.core import Intent, Result, Status, Session, parse, plan, run_plan, SETTINGS
from ema_windows.interaction import Control, Screen, select, effect


class IntentTests(unittest.TestCase):
    def test_polite_forms_keep_intent(self):
        for phrase in ('open Notepad', 'set volume to 40 percent', 'click Settings', 'type Do not open anything'):
            for prefix in ('please ', 'could you please ', 'can you ', 'hey ema please '):
                self.assertEqual(parse(phrase), parse(prefix + phrase))
                self.assertEqual(plan(phrase), plan(prefix + phrase))

    def test_payload_preservation(self):
        body = "Don't open apps then delete files & run $(bad); 9."
        self.assertEqual(plan('type ' + body), [Intent('type', body, confirmation=True)])
        self.assertEqual(plan('search web for ' + body), [Intent('web', body)])
        self.assertEqual(plan('open Notepad then type ' + body), [Intent('open', 'Notepad'), Intent('type', body, confirmation=True)])

    def test_negative_requests_stop_whole_plan(self):
        for phrase in ("don't open Calculator", 'open Notepad then never delete files', 'cancel', 'stop'):
            self.assertEqual([Intent('cancel')], plan(phrase))

    def test_unknown_clause_prevents_partial_execution(self):
        self.assertEqual('clarify', plan('set volume to 20 percent then magically send SMS')[0].kind)
        self.assertEqual('clarify', plan(' then '.join(['help'] * 9))[0].kind)

    def test_settings_are_allowlisted_and_not_toggles(self):
        for feature in SETTINGS:
            self.assertEqual(Intent('settings', feature), parse(f'open {feature} settings'))
        for phrase in ('turn on wifi', 'enable bluetooth', 'do not disturb on'):
            self.assertEqual('guided_setting', parse(phrase).kind)
        self.assertEqual('settings', parse('open unknown-feature settings').kind)
        self.assertNotIn('unknown-feature', SETTINGS)
        self.assertEqual('unsupported', parse('run powershell Get-Process').kind)

    def test_percentage_validation(self):
        for value in (0, 40, 100):
            self.assertEqual(value, parse(f'set volume to {value} percent').value)
        self.assertEqual('clarify', parse('set volume to 101 percent').kind)
        self.assertEqual('unsupported', parse('set volume to -1 percent').kind)
        self.assertEqual('unsupported', parse('set brightness to 20; rm files').kind)

    def test_questions_do_not_authorize_actions(self):
        for phrase in ('how do I open Notepad', 'can you send messages', 'is wifi on', 'what is Bluetooth'):
            self.assertEqual('unsupported', parse(phrase).kind)


class ExecutionTests(unittest.TestCase):
    def test_unverified_prerequisite_stops_dependents(self):
        called = []
        def execute(intent, alive):
            called.append(intent)
            return Result(Status.REQUESTED, 'Launch not observed')
        results = run_plan([Intent('open', 'Notepad'), Intent('type', 'hi', confirmation=True)], execute, lambda _: True, lambda: True)
        self.assertEqual(1, len(called))
        self.assertEqual(Status.REQUESTED, results[0].status)

    def test_confirmation_and_cancellation_before_dispatch(self):
        def forbidden(*args):
            self.fail('must not dispatch')
        intent = Intent('click', 'Send', confirmation=True)
        self.assertEqual(Status.BLOCKED, run_plan([intent], forbidden, lambda _: False, lambda: True)[0].status)
        state = [True]
        def confirm(_):
            state[0] = False
            return True
        self.assertEqual(Status.CANCELLED, run_plan([intent], forbidden, confirm, lambda: state[0])[0].status)

    def test_verified_steps_continue_and_adapter_exceptions_stop(self):
        self.assertEqual(2, len(run_plan([Intent('help'), Intent('help')], lambda *_: Result(Status.VERIFIED, 'ok'), lambda _: True, lambda: True)))
        def broken(*args):
            raise RuntimeError()
        self.assertEqual(Status.FAILED, run_plan([Intent('help')], broken, lambda _: True, lambda: True)[0].status)

    def test_session_never_revives_an_abandoned_request(self):
        session = Session()
        old = session.begin()
        replacement = session.begin()
        self.assertFalse(session.current(old))
        self.assertTrue(session.current(replacement))


class ScreenTests(unittest.TestCase):
    def screen(self, *controls, hwnd=1, pid=2):
        return Screen(hwnd, pid, tuple(controls))

    def test_exact_identity_ambiguity_and_passwords(self):
        control = Control('a', 'Save', 'Button')
        self.assertEqual(control, select(self.screen(control), 'click', 'Save'))
        for controls in ((control, Control('b', 'Save', 'Button')), (Control('a', 'Save all', 'Button'),), (Control('a', 'Save', 'Button', enabled=False),)):
            with self.assertRaises(LookupError):
                select(self.screen(*controls), 'click', 'Save')
        with self.assertRaises(LookupError):
            select(self.screen(Control('a', 'Password', 'Edit', password=True)), 'type', 'secret')

    def test_existing_drafts_are_not_overwritten(self):
        with self.assertRaises(LookupError):
            select(self.screen(Control('a', 'Message', 'Edit', value='private draft')), 'type', 'Hello')
        self.assertEqual('a', select(self.screen(Control('a', 'Message', 'Edit', value='Hello')), 'type', 'Hello').key)

    def test_readback_is_tied_to_field_and_window(self):
        field = Control('field', 'Message', 'Edit')
        before = self.screen(field)
        self.assertFalse(effect(before, field, self.screen(field, Control('label', 'Hello', 'Text')), 'type', 'Hello'))
        self.assertTrue(effect(before, field, self.screen(Control('field', 'Message', 'Edit', value='Hello')), 'type', 'Hello'))
        self.assertFalse(effect(before, field, self.screen(Control('field', 'Message', 'Edit', value='Hello'), hwnd=3), 'type', 'Hello'))

    def test_toggle_requires_own_state_change(self):
        toggle = Control('toggle', 'Option', 'CheckBox', toggle=0)
        before = self.screen(toggle)
        self.assertFalse(effect(before, toggle, self.screen(toggle, Control('noise', 'New ad', 'Text')), 'click', 'Option'))
        self.assertTrue(effect(before, toggle, self.screen(Control('toggle', 'Option', 'CheckBox', toggle=1)), 'click', 'Option'))


if __name__ == '__main__':
    unittest.main()
