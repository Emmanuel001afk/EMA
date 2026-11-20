"""Brain tests: the Python port must behave like the Android Kotlin grammar."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ema_windows import brain
from ema_windows.brain import (CallContact, Cancel, ClickControl, FindApp, Help, InstalledApp,
                               ListApps, MediaControl, NavigateTo, OpenApp, OpenAppSection,
                               OpenSettingsPage, PlayMedia, SaveContact, Scroll, SearchInApp,
                               SelectResult, Selection, SendMessage, SetAlarm, SetBrightness,
                               SetMute, SetReminder, SetTimer, SetVolume, TypeText, WebSearch,
                               interpret, interpret_any, parse_duration, parse_reference,
                               parse_time, plan_commands, plan_steps, resolve_app)
from ema_windows.core import Result, Session, Status, run_plan


class GrammarTests(unittest.TestCase):
    def test_message_roles_stay_apart(self):
        self.assertEqual(SendMessage("Daniel", "WhatsApp", "I am on my way"),
                         interpret("send a message to Daniel on WhatsApp saying I am on my way"))
        self.assertEqual(SendMessage("Daniel", None, "a message"),
                         interpret("send a message to Daniel saying a message"))

    def test_message_body_may_contain_then_and_on(self):
        self.assertEqual("I will land then call you",
                         interpret("message Ada saying I will land then call you").body)
        self.assertEqual("meet on the roof", interpret("tell Ada that meet on the roof").body)

    def test_whatsapp_verb_implies_app(self):
        self.assertEqual(SendMessage("Daniel", "WhatsApp", "hi"),
                         interpret("whatsapp Daniel saying hi"))

    def test_recipient_only_has_empty_body(self):
        self.assertEqual(SendMessage("Daniel", None, ""), interpret("message Daniel"))

    def test_leading_app_clause(self):
        self.assertEqual(SendMessage("Daniel", "WhatsApp", "hi"),
                         interpret("on WhatsApp, message Daniel saying hi"))

    def test_politeness_is_stripped(self):
        self.assertEqual(OpenApp("Notepad"), interpret("hey ema please could you open Notepad"))
        self.assertEqual(OpenApp("Notepad"), interpret("can you open the Notepad app"))

    def test_play_forms(self):
        self.assertEqual(PlayMedia("Calm Down", "Spotify"), interpret("play Calm Down on Spotify"))
        self.assertEqual(PlayMedia("Arcade", "Spotify"), interpret("open Spotify and play Arcade"))
        self.assertEqual(PlayMedia("Arcade", "Spotify"), interpret("in Spotify, play Arcade"))
        self.assertEqual(PlayMedia("Despacito", None), interpret("play the song called Despacito"))
        self.assertEqual(PlayMedia("Despacito", None), interpret("listen to Despacito"))

    def test_media_controls_are_not_queries(self):
        self.assertEqual(MediaControl("PAUSE"), interpret("pause"))
        self.assertEqual(MediaControl("NEXT"), interpret("next track"))
        self.assertEqual(MediaControl("TOGGLE"), interpret("play or pause"))
        self.assertEqual(MediaControl("WHATS_PLAYING"), interpret("what is playing"))

    def test_call_forms(self):
        self.assertEqual(CallContact("Ada Lovelace", None), interpret("call Ada Lovelace"))
        self.assertEqual(CallContact("Ada", 2), interpret("call Ada with SIM 2"))
        self.assertEqual(CallContact("Ada", 1), interpret("dial Ada on the first sim"))

    def test_alarm_timer_reminder(self):
        self.assertEqual(SetAlarm("7 am"), interpret("set an alarm for 7 am"))
        self.assertEqual(SetAlarm("7 am"), interpret("wake me at 7 am"))
        self.assertEqual(SetTimer("10 minutes"), interpret("set a timer for 10 minutes"))
        self.assertEqual(SetReminder("call mom", "6 pm"), interpret("remind me to call mom at 6 pm"))
        self.assertEqual(SetReminder("call mom", "at 6 pm"), interpret("remind me at 6 pm to call mom"))
        self.assertIsNone(interpret("set an alarm"))  # no time -> not an alarm command

    def test_open_settings_navigate_search(self):
        self.assertEqual(OpenApp("Notepad"), interpret("open Notepad"))
        self.assertEqual(OpenAppSection("Spotify", "library"), interpret("go to library in Spotify"))
        self.assertEqual(OpenSettingsPage(""), interpret("open settings"))
        self.assertEqual(OpenSettingsPage("bluetooth"), interpret("open bluetooth settings"))
        self.assertEqual(NavigateTo("Lekki"), interpret("navigate to Lekki"))
        self.assertEqual(WebSearch("weather"), interpret("search with google weather"))
        self.assertEqual(SearchInApp("football", "YouTube"), interpret("search football on YouTube"))
        self.assertEqual(SearchInApp("football", None), interpret("search for football"))

    def test_save_contact_and_sms(self):
        self.assertEqual(SaveContact("Ada", "08012345678"),
                         interpret("save 08012345678 as Ada"))
        self.assertEqual(SaveContact("Ada", "08012345678"),
                         interpret("save Ada's number 08012345678"))
        self.assertEqual("Ada", interpret("text Ada saying hi").recipient)

    def test_unknown_is_not_understood(self):
        self.assertIsNone(interpret("magically send a telegram to the moon"))
        self.assertIsNone(interpret(""))


class PlannerTests(unittest.TestCase):
    def test_compound_app_command(self):
        self.assertEqual(["open Spotify", "play Arcade on Spotify"],
                         plan_steps("open Spotify and play Arcade"))

    def test_then_splits_at_action_boundaries(self):
        self.assertEqual(["set volume to 40 percent", "mute"],
                         plan_steps("set volume to 40 percent then mute"))

    def test_message_body_is_never_split(self):
        commands = plan_commands("message Ada saying I will land then call you")
        self.assertEqual(1, len(commands))
        self.assertIsInstance(commands[0], SendMessage)

    def test_unknown_step_blocks_whole_plan(self):
        self.assertIsNone(plan_commands("set volume to 20 percent then magically send SMS"))

    def test_negative_request_cancels(self):
        self.assertEqual([Cancel()], plan_commands("don't open Calculator"))
        self.assertEqual([Cancel()], plan_commands("open Notepad then never delete files"))

    def test_too_many_steps_refused(self):
        self.assertIsNone(plan_commands(" then ".join(["help"] * 9)))


class TimeTests(unittest.TestCase):
    def test_clock_and_relative(self):
        now = 1_700_000_000_000
        moment = parse_time("7 am", now)
        self.assertIsNotNone(moment)
        self.assertEqual("CLOCK", moment.kind)
        duration = parse_duration("in 10 minutes")
        self.assertEqual(600, duration.seconds)
        self.assertEqual(7200, parse_duration("2 hours").seconds)

    def test_spoken_times(self):
        now = 1_700_000_000_000
        self.assertIsNotNone(parse_time("half past six", now))
        self.assertIsNotNone(parse_time("quarter to seven", now))
        self.assertIsNotNone(parse_time("tomorrow", now))
        self.assertIsNotNone(parse_time("tonight at 9", now))
        self.assertIsNone(parse_time("whenever", now))


class SelectionTests(unittest.TestCase):
    def test_references(self):
        self.assertEqual(Selection("ORDINAL", 3), parse_reference("the third result"))
        self.assertEqual(Selection("LAST"), parse_reference("the last one"))
        self.assertEqual(Selection("ANOTHER"), parse_reference("another one"))
        self.assertEqual(Selection("ORDINAL", 2), parse_reference("number 2"))
        self.assertEqual(Selection("TITLED", text="despacito"),
                         parse_reference("the video called despacito"))
        self.assertIsNone(parse_reference("despacito"))


class WindowsVerbTests(unittest.TestCase):
    def test_volume_and_brightness(self):
        self.assertEqual(SetVolume(40), interpret_any("set volume to forty percent"))
        self.assertEqual(SetVolume(25), interpret_any("set volume to twenty five percent"))
        self.assertEqual(SetVolume(100), interpret_any("set volume to one hundred percent"))
        self.assertEqual(SetBrightness(60), interpret_any("set brightness to 60 percent"))
        self.assertEqual(SetMute(True), interpret_any("mute"))
        self.assertIsNone(interpret_any("set volume to 101 percent"))

    def test_screen_verbs(self):
        self.assertEqual(ClickControl("Save"), interpret_any("click Save"))
        self.assertEqual(TypeText("Hello then call me"), interpret_any("type Hello then call me"))
        self.assertEqual(Scroll("down"), interpret_any("scroll down"))
        self.assertEqual(ListApps(), interpret_any("list apps"))
        self.assertEqual(FindApp("Spotify"), interpret_any("find app Spotify"))
        self.assertEqual(Help(), interpret_any("help"))

    def test_settings_toggles_open_settings_page(self):
        self.assertEqual(OpenSettingsPage("wifi"), interpret_any("turn on wifi"))
        self.assertEqual(OpenSettingsPage("bluetooth"), interpret_any("enable bluetooth"))
        self.assertEqual(OpenSettingsPage("do not disturb"), interpret_any("do not disturb on"))
        self.assertEqual(OpenSettingsPage("wifi"), interpret_any("open wifi settings"))

    def test_questions_do_not_authorize_actions(self):
        self.assertIsNone(interpret_any("how do I open Notepad"))
        self.assertIsNone(interpret_any("can you send messages"))


class ResolverTests(unittest.TestCase):
    APPS = [InstalledApp("Notepad", "n1"), InstalledApp("Spotify", "s1"),
            InstalledApp("WhatsApp", "w1"), InstalledApp("Google Chrome", "c1")]

    def test_exact_alias_and_words(self):
        self.assertEqual("Notepad", resolve_app("notepad", self.APPS).app.label)
        self.assertEqual("WhatsApp", resolve_app("whats app", self.APPS).app.label)
        self.assertEqual("Google Chrome", resolve_app("chrome", self.APPS).app.label)

    def test_no_match(self):
        self.assertIsNone(resolve_app("zzz unknown", self.APPS))


class ExecutionTests(unittest.TestCase):
    def test_unverified_prerequisite_stops_dependents(self):
        called = []
        def execute(command, alive):
            called.append(command)
            return Result(Status.REQUESTED, "Launch not observed")
        results = run_plan([OpenApp("Notepad"), TypeText("hi")], execute,
                           lambda c: True, lambda: True)
        self.assertEqual(1, len(called))
        self.assertEqual(Status.REQUESTED, results[0].status)

    def test_consequential_steps_need_confirmation(self):
        def forbidden(*args):
            self.fail("must not dispatch")
        results = run_plan([ClickControl("Send")], forbidden, lambda c: False, lambda: True)
        self.assertEqual(Status.BLOCKED, results[0].status)

    def test_cancellation_before_dispatch(self):
        state = [True]
        def confirm(_):
            state[0] = False
            return True
        results = run_plan([ClickControl("Send")], lambda *a: self.fail("no dispatch"),
                           confirm, lambda: state[0])
        self.assertEqual(Status.CANCELLED, results[0].status)

    def test_verified_steps_continue(self):
        results = run_plan([Help(), Help()], lambda *a: Result(Status.VERIFIED, "ok"),
                           lambda c: True, lambda: True)
        self.assertEqual(2, len(results))

    def test_session_never_revives_abandoned_work(self):
        session = Session()
        old = session.begin()
        new = session.begin()
        self.assertFalse(session.current(old))
        self.assertTrue(session.current(new))


if __name__ == "__main__":
    unittest.main()
