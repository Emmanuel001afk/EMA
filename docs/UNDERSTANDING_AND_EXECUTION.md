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
| Memory | `EmaMemory`, `EmaMemoryStore` | Explicit facts, name aliases ("my wife" → a contact) and default apps. |
| Knowledge | `EmaKnowledge` | Arithmetic, percentages, unit conversion and dates. Answers are computed, never invented. |
| Capability check | `EmaCapabilityRegistry` | Checks prerequisites before anything is done ("I haven't done anything yet"). |
| Execution | `EmaPlanExecutor` | Runs steps in order with per-step timeouts, a plan deadline, bounded retries, a duplicate guard and cancellation. |
| Observation | `EmaResultExtractor`, `EmaNodeSnapshot` | Turns the accessibility tree into result rows, including durations, views, age and ads. |
| Verification | `EmaResultSelectionFlow`, `EmaActionEvidence`, `EmaExecutionVerifier` | Requires evidence: the media session is playing the chosen title, the screen changed, the recipient header matches, the composer holds the exact text. |
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
  50 facts and 50 aliases, and can be listed and erased by voice ("what do you
  remember", "forget everything") or in Settings.

## Improving understanding: the development-time loop

EMA does **not** learn or retrain itself on the phone. Improvement is a
deliberate, testable process:

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
