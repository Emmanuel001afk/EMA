# Native productivity intelligence

EMA remains **native-first**. These features run as deterministic Kotlin code, before optional AI fallback, and make no network calls. `NATIVE_ONLY` does not disable them.

## Design reference and honest limits

Gemini's documented Android assistant features include screen-context questions, summaries, writing help and phone actions: https://support.google.com/gemini/answer/14579631 . Those workflows motivated this upgrade, not a claim of equivalent model intelligence.

EMA uses extractive text ranking (term frequency, salience, MMR and BM25), explicit entity patterns, rule-based writing edits, fixed draft templates and bounded local state. It cannot infer arbitrary visual meaning, translate arbitrary languages, research live facts or guarantee human-quality prose. Review all extracted details and rewritten text. Screen access depends on Android Accessibility; protected or inaccessible content may not be readable. Screen/document text is data, never an instruction to execute.

## Try it

| Feature | Examples |
| --- | --- |
| Screen summary | `summarize screen` |
| Grounded screen question | `ask screen: when is the meeting?` |
| Screen details | `find details on this screen` |
| Shared document | Share plain text to EMA, then `summarize it`, `word count`, `ask document: what is the deadline?` |
| Transcript follow-up | Transcribe an audio file with EMA, then `summarize it` |
| Proofreading | `proofread: teh meeting is tommorow` |
| Tone/format | `make this professional: pls send teh report`, `bullet points: <text>`, `title case: <text>` |
| Clipboard | `proofread my clipboard`, `word count of my clipboard` (Android clipboard restrictions apply) |
| Drafts | `draft a running late text`, `draft a polite email to Ada saying we need to reschedule` |
| Named checklist | `add milk, bread, and eggs to my shopping list`, `show my shopping list` |
| List follow-up | `add butter too`, `check off milk on my shopping list`, `remove bread from my shopping list` |
| Notes | `take a note: review the report`, `read my last note`, `pin my last note`, `search notes for report` |
| Parking | `remember I parked at Level 2 Zone B`, `where did I park?` |
| Undo | `undo that` restores the previous workspace snapshot, not arbitrary device actions |
| Stopwatch | `start stopwatch`, `stopwatch lap`, `pause stopwatch`, `resume stopwatch`, `reset stopwatch` |
| Briefing | `brief me`, `good morning` — local time/date, upcoming EMA reminders, open tasks, accessible notifications and saved parking |
| Custom routine | `create a routine called study: turn on DND; open Calculator`, `run study routine`, `delete routine study` |
| Notifications | `summarize notifications`, `priority notifications` (requires notification-listener access) |

Starter and contextual suggestion buttons are available in the main conversation UI. Suggestions are fixed commands; content from an app or document cannot supply a command chip.

## Execution and safety

- Productivity payloads are parsed before alias replacement or plan-instruction stripping so writing/note text stays intact.
- Custom routine children use the existing ordered plan executor and action verification. Execution stops on failed dependencies; permission and confirmation behavior stays with the underlying action. Routines cannot expand other custom routines. Use semicolons between steps. Commas and `then` are preserved in step payloads; do not embed semicolons in message bodies.
- Drafts are displayed only. They are not sent or copied automatically.
- Voice bulk deletion of all notes or an entire list is blocked. Individual deletes are reversible with workspace undo. Ambiguous item/note matches are refused.
- Workspace writes use one app-private SharedPreferences transaction and report success only when `commit()` succeeds. This is Android sandbox storage, **not an encrypted password vault**.
- Passwords, PINs, verification codes and common financial/security identifiers are refused by pattern-based guards. These are not a guarantee of detecting every secret: do not dictate sensitive credentials to EMA.
- Notification digests prioritize urgent categories/messages and redact recognized verification codes, including codes in notification titles. They describe active notifications, not a verified unread-message count. Inline replies and blanket dismissal are not enabled by this upgrade.
- Working documents/replies and undo snapshots are held in process memory with expiration on access. They do not survive process death. Workspace lists/notes/parking and custom routines persist locally. Normal conversation history remains governed by the existing app; secret-pattern text is excluded from new history writes.
- Storage bounds: 25 lists × 80 items, 100 notes, 30 custom routines × 8 steps; dialogue documents 12,000 characters, six undo snapshots. Full note storage refuses new notes rather than silently discarding old ones.

## Modules and validation

Pure engines: `EmaScreenIntelligence`, `EmaWritingTools`, `EmaWorkspace`, `EmaDialogueState`, `EmaNamedRoutines`, `EmaNotificationDigest`, `EmaFollowUpSuggestions`.

Android integration: `EmaProductivityEngine`, `EmaCommandRouter`, workspace/routine stores, notification listener, conversation UI, plain-text sharing and transcript intake.

`EmaProductivityTest` covers deterministic engines, round trips, TTLs, secret refusal, notification ambiguity/redaction and no-action draft behavior. Existing understanding-corpus and knowledge contracts are unchanged. Android CI compiles Kotlin, runs JVM unit tests and builds debug/release APKs with packaging verification. Device permissions, cross-app accessibility and OEM behavior still require physical-device testing.

Not included: timed focus-mode auto-restoration, general generative reasoning, arbitrary image Q&A, new cloud integrations, broad scientific/algebra/world-clock expansion, or notification inline sending.
