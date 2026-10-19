# How EMA understands and carries out requests

EMA works fully offline. No language model is bundled. Understanding and
execution are deterministic logic in the app. The optional online AI
(`CentralHubClient`) is never needed for device actions.

## Pipeline

| Stage | Component | What it does |
|---|---|---|
| Normalisation | `EmaSemanticParser.normalise` | Strips wake words, fillers and politeness. Applies spoken corrections ("Daniel, no, David"). |
| Segmentation | `EmaSemanticParser` | Splits at `, ; . and then`, but only where a new clause verb starts. "salt and pepper" stays one query. |
| Semantic roles | `EmaSemanticParser` | Fills frames: act (message / search / select / play / open / find / topic), app, recipient, body, query, selection, play/open. |
| Grounding | `EmaAppResolver` | Resolves an app phrase against the apps **installed on this phone**, including private and unknown apps. Ambiguity produces a question, never a guess. |
| References | `EmaSelection`, `EmaConversationContext` | Handles "the third result", "the longest one instead", "another one" and "him". |
| Composition | `EmaSemanticParser.compose` | Merges clauses into one goal (open + find + tell = one message) and propagates the app across steps. Unrelated goals stay separate. |
| Memory | `EmaMemory`, `EmaMemoryStore` | Explicit facts, name aliases ("my wife" → a contact), default apps and **learned shortcuts** (trigger → app or command). Stored atomically with a schema version. |
| Knowledge | `EmaKnowledge` | Arithmetic, percentages, unit conversion and dates. Answers are computed, never invented. |
| Capability check | `EmaCapabilityRegistry` | Checks prerequisites before anything is done ("I haven't done anything yet"). |
| Execution | `EmaPlanExecutor` | One sequencer for semantic plans and legacy text sequences. Steps run in order with per-step timeouts, a plan deadline, bounded retries, a duplicate guard, cancellation and optional per-step confirmation. |
| Observation | `EmaResultExtractor`, `EmaNodeSnapshot` | Turns the accessibility tree into result rows, including durations, views, age and ads. |
| Verification | `EmaResultSelectionFlow`, `EmaActionEvidence`, `EmaExecutionVerifier`, `EmaConditionWaiter` | Requires evidence: the target package is in the foreground and the screen has stopped changing, the media session is playing the chosen title, the screen changed, the recipient header matches, the composer holds the exact text, brightness/DND were read back. Fixed sleeps are not evidence. |
| Recovery | `EmaResultSelectionFlow` | Bounded back navigation to the list, bounded scrolling, re-running the search. Otherwise it explains what happened. |
| Response | `EmaPlanExecutor.summarize`, `EmaOutcomeClassifier` | Gives one honest summary: what was done, what was not, and why. |
| UI | `EmaProgressListener`, `EmaConversationView` | Shows the real plan, per-step state and the final classified outcome. |

Single simple commands ("open YouTube", "set an alarm for 7") return
`NotHandled` from the semantic layer. They go to the established grammar and
planner, which already handle them, so existing behaviour is preserved.

## The meaning frame

All of these produce `MSG[WhatsApp | Daniel | I am coming home]`:

- Message Daniel on WhatsApp that I am coming home.
- Open WhatsApp, find Daniel, and tell him I am coming home.
- On WhatsApp, send Daniel a message saying I am coming home.
- Daniel on WhatsApp, message him that I am coming home.
- I need to tell Daniel on WhatsApp that I am coming home.

The body is copied verbatim from the original text, never rebuilt from tokens.
Words inside the body such as "play", "call", "open YouTube" or other app names
are content, not instructions. A typed or speech-recogniser full stop at the end
is removed, but "!", "?" and quoted text are kept exactly.

## Safety rules for consequential actions (send, call)

- **Validated locally before running:** missing permissions stop the plan
  before anything is opened.
- **Unclear details:** an ambiguous app, an unresolved "him", or several
  matching contacts each produce a question.
- **No stale guesses:** no app is guessed for a message. EMA uses the user's
  default messaging app or SMS, never "the last app used".
- **Never retried, and never repeated within 60 s:** `EmaDuplicateGuard` stores
  only a hash of the action.
- **Recipient and composer are checked before Send, and the sent bubble after.**
  This is the existing `EmaActionEvidence` and `EmaSendControlResolver` logic.
- **Prerequisites must be confirmed:** a step that depends on an earlier one
  runs only after that step reached SUCCESS. An UNVERIFIED step is not enough
  for a consequential follow-up.
- **No code from natural language:** nothing in natural language is executed as
  code or shell.
- **Private logging:** logs record the shape of a request (word and character
  counts, `EmaPrivacy`), not its content.

## Choosing results without guessing

`EmaResultExtractor` reads result rows from the screen generically, by
structure: the clickable row container, the text children, size and position
relative to the screen, and the search box and toolbar to exclude. There are no
coordinates and no per-app IDs, so it works for private apps as well as YouTube.

- **"first / third / last":** counted among non-sponsored rows. EMA scrolls (up
  to 3 times) when the position is not visible yet. "Last" is honestly "the last
  one loaded".
- **"longest / shortest / newest / most viewed":** EMA decides only when at
  least two rows show the metadata. Otherwise it refuses with "I won't guess"
  and taps nothing.
- **Playback** is verified through the app's media session title. If
  notification access is off, or something else is playing (possibly an ad), the
  outcome is UNVERIFIED with the reason, not a claimed success.

## Conversation context and memory

- **Short-term:** `EmaConversationContext` keeps the active results list: app,
  query and titles already chosen. It expires after 10 minutes and is cleared
  when another app is opened or played. It never stores recipients or message
  bodies.
- **Long-term:** `EmaMemory` holds only what the user explicitly asks it to
  remember. It refuses passwords, PINs and card or bank details, holds at most
  50 facts, 50 aliases and 50 shortcuts, and can be listed and erased by voice
  ("what do you remember", "forget everything"). The visible list was removed
  from Setup & preferences at the owner's request; the erase control remains.

## Learned shortcuts (taught by voice, AI off)

A shortcut maps a spoken phrase to either an installed app or a whole command:

- "Remember that whenever I say for me or Phormi browser it means open the
  Phormi app, package spelling P H O R M I"
- "Remember that offline backup means open Spotify and play the Offline Backup
  playlist"
- "When I say X, open Z" is accepted as explicit teaching too.

Resolution is against the packages actually installed. A spelled package name
("P H O R M I") is joined and matched to package segments, labels and package
leaves; several matches produce "Which app do you mean?" rather than a guess.
Shortcuts are consulted by `EmaAppActionEngine.findApp`, `EmaSemanticParser`
grounding and the router's whole-utterance expansion. Expansion is one level
and cannot recurse, and it is never applied inside a message body, search query
or reminder text. The store is written synchronously (`commit()`) after each
change, and reopens with a schema version.

## The local learning layer (on the phone, bounded, explainable)

The shortcuts above are what the owner asks EMA to remember. On top of that
there is a small learning layer (`EmaLearning`, `EmaLearningStore`) that
changes what EMA does with what the owner says:

- a **correction** ("no, I meant X") links what was said to what was meant;
- a **rephrase** after a failure links the failed request to the way the owner
  said it next;
- a **mishearing** links a phrase the recogniser got wrong to the wording it
  offered instead;
- **usage preferences** remember which app the owner really uses for media,
  messages, calls and searches, so "play my playlist" points at that app.

The rules that keep this honest:

- a link is used only after the owner confirmed it **once**, or after it was
  seen **twice**. One guess never changes what EMA does.
- a link is never used inside the content of a message, a search or a
  reminder: what the owner wrote is content, not a command.
- when two candidates are close, EMA **asks** ("Did you mean X or Y?") instead
  of choosing.
- nothing that looks like a secret (password, code, card number) is stored.
- "Forget what you learned" clears these links and leaves the "remember"
  shortcuts alone.
- "What have you learned?" answers with a spoken summary.
- the list is bounded (30 links) and everything stays on the phone.

The order of the layers is fixed: **remember** (the taught shortcuts) first,
then the **learned** links, then the **lexicon** (the built-in dictionary),
then the **fuzzy** fallback that grounds a request against the apps that are
really installed.

## The run trace (what happened, on the phone)

Every request leaves a short record (`EmaRunTrace`, `EmaRunTraceStore`): the
text EMA heard and the other guesses the recogniser offered, which layer
answered, the plan steps, the outcome and the message of every step, the
timings, the foreground package samples and whether screen control was on.

- At most 20 runs are kept, on the phone, in plain text.
- The recognised text and every message are cut to 40 characters, and
  anything that looks like a secret is withheld.
- "Copy last runs" (Setup & preferences, next to the learning log) puts them
  on the clipboard; "EMA, why did that fail?" reads the last one out loud.

## Multi-step requests and instruction clauses

Instruction clauses are plan options, not steps:

- "make sure one action does not hinder another" / "one action at a time"
  is removed and the plan stays strictly sequential (already the default).
- "then go home" / "go home when you're done" is removed and appended as a
  final **Go home** step, shown in the PLAN like every other step.
- "stop" / "cancel" / "never mind" during a plan cancels it and the summary
  names the steps that had finished and the ones that did not run.
- Optional per-step confirmation is a preference, **off by default**. When on,
  the plan pauses after each major step and waits for yes, no or stop.

## Patience instead of fixed delays

Cold-starting an app can take seconds, so EMA waits for observable conditions
(`EmaConditionWaiter`) instead of sleeping:

- opening an app: the target package is the foreground package **and** the
  screen text has been unchanged for about 600 ms, bounded at about 8 s;
- searching: the search field becomes reachable, then a non-empty result list
  is observed (bounded ~8 s);
- selecting a result: up to three attempts, re-checking the foreground app;
- playback: a play-from-search intent is given about 4 s to report the
  requested title as playing before the visible path is used. If the media
  session already reports playing, EMA never presses Play (that would pause
  it), and it presses at most once.

## Device settings without AI

`EmaSystemCommandVocabulary.parseDeviceSetting` recognises the subject and the
action independently, so new wording works without new phrases:

| Subject | Actions | Execution |
|---|---|---|
| Do Not Disturb / silent | on, off | `setInterruptionFilter`, then `currentInterruptionFilter` is read back |
| Brightness | on, off, increase, decrease, maximum, minimum, set N% | manual mode is set first, the value is written and read back |
| Airplane / Wi-Fi / Bluetooth | on, off | Quick Settings tile matched by label, tapped only when wrong, verified; Settings page as fallback; honest failure otherwise |

`ACCESS_NOTIFICATION_POLICY` is declared so EMA can appear in Android's DND
access list. Wi-Fi and Bluetooth are never written directly: a normal app
cannot, so EMA uses the tile path and verifies what it can observe.

## Alarms: nearest future, repeats and dates

- A bare hour is the **nearest future occurrence** and is stated explicitly:
  at 10:00, "wake me up at 7" is "today at 7:00 PM"; at 20:00 it is
  "tomorrow at 7:00 AM".
- AM/PM counts only when it is adjacent to the number. "7, I am going out"
  is not 7 AM because of the "am" in "I am".
- "6 and 6:30 am" becomes two separate `ACTION_SET_ALARM` requests with a gap,
  and each alarm is reported.
- "on weekdays", "on weekends", "every day" and named days are passed as
  `EXTRA_DAYS`, so the clock app repeats them.
- A specific calendar day ("tomorrow at 7") cannot be expressed by the clock
  app. EMA says so and schedules its own exact alarm (`setAlarmClock` plus an
  alarm notification, full-screen only when Android allows it). It also checks
  the exact-alarm permission and opens the grant screen when it is missing.

## Digital-assistant registration

The voice-interaction XML names a real `RecognitionService`; without one
Android rejects the registration silently. `EmaRecognitionService` delegates to
an **explicit** recogniser (on-device on API 31+, otherwise an installed
service that is not EMA). EMA never binds the default recogniser, because that
can resolve back to EMA now that it offers one. Setup shows the real state:
role held / service active / recognition service declared.

## What still needs checking on a real phone

These are implemented but cannot be proven in the repository's JVM tests:

- digital-assistant registration appearing in Android's assistant picker, and
  the recognition service receiving audio;
- Quick Settings label matching and tile state across OEM skins, and the
  Settings-page fallback on devices where the tile is not reachable;
- cold-start timing for Spotify/YouTube (the waits are bounded, but real device
  latency varies);
- speech hypotheses and API 33 biasing strings with the device's recogniser;
- EMA's own exact alarm notification, full-screen permission and lock-screen
  behaviour on Android 12–15;
- per-step voice confirmation in a live voice session.

## Improving understanding: the development-time loop

EMA does not retrain itself on the phone: it never rewrites its own
understanding rules. What it can do is remember what the owner told it (the
learning layer above), and the rules themselves are improved by a deliberate,
testable process:

1. **Collect.** In Settings, turn on "Keep requests EMA didn't understand". The
   list stays on the phone, holds at most 30 entries, is off by default and can
   be cleared. "Copy list" puts it on the clipboard for a bug report.
2. **Record the expectation.** Add each request to
   `app/src/test/resources/ema_understanding_corpus.tsv` with the frame it
   *should* produce.
3. **Watch it fail.** Run `EmaUnderstandingCorpusTest`. It reports every
   mismatch at once.
4. **Fix the logic, not the phrase.** Change normalisation, roles, grounding or
   composition so that the whole family of phrasings works. Avoid adding the
   literal sentence to a list.
5. **Keep it fixed.** The corpus line now guards the behaviour on every CI run.

## Offline behaviour

With no network, with AI switched off, or with the hub unreachable, all of the
above still works. In native-first mode the AI is used only for **questions**,
and only when the hub is configured. It is never a hidden fallback for device
commands that native understanding failed on. Those get an honest reply.
Questions that need live information ("weather", "who won", "news") get a clear
"that needs the internet" answer instead of an invented one.
