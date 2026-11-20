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

## Screen control and reasoning follow-up

The shared app-control path now resolves the top application window rather than accidentally reading the keyboard or assistant window. Search is a bounded observe/act loop: identify a search field by role/label/id, reveal one Search entry, allow the transition to render, set text, then read it back from a fresh snapshot. A Search navigation tab is not pressed as a submit button. Password text is excluded from screen snapshots. An arbitrary chat/login field is not treated as search.

Media selection groups title/artist labels into actual result rows. A title-only content row is no longer thrown away just because its title exactly matches the query. Selection allows a bounded wait and one forward scroll; it never substitutes an unrelated first result. Auto-start playback is checked before pressing Play. Delayed Play controls are polled, a Play action is issued at most once, and the requested app/title/artist must be verified through a media session before claiming success. Media plan steps receive a larger bounded budget to accommodate cold starts. Sign-in/network/subscription blockers are reported only when observed in the app.

Explicit screen questions/summaries now use the existing offline screenshot OCR when accessibility returns no text. OCR is read-only; it does not autonomously infer coordinates or click arbitrary visual content. Protected content and controls not exposed by the app may still be inaccessible.

New computed knowledge examples:
- `sin 30 degrees`, `cos 0`, `log2 8` (trigonometry defaults to degrees; explicit radians supported)
- `mean of 2,4,6`, `median of 4,1,3,2`, `sample standard deviation of 1,2,3`
- `is 97 a prime number`, `gcd of 48 and 18`, `lcm of 12 and 18`
- `split 200 between 4 people with 10 percent tip`
- `bmi 81 kg at 180 cm` (screening calculation, not medical advice)
- `age born 2000-11-16`
- `solve linear 2 x + 3 = 11`, `solve quadratic 1 x^2 + -5 x + 6 = 0`
- `time in Lagos`, `time in London` (31 city mappings with timezone database/DST rules)
- After a numeric calculation: `double that`, `halve that`, `split it 4 ways`

These are explicit, deterministic grammars, not arbitrary mathematical theorem proving. Missing prior numeric context and invalid domains produce explanations rather than guessed results. `EmaSearchPolicyTest` and `EmaReasoningKnowledgeTest` exercise safe search decisions, row semantics, domain errors, exact integer operations, DST and conversational numeric references. Real-device validation across app versions remains required.

For device troubleshooting, say `diagnose screen control` or `check screen access`. The response reports Accessibility connectivity, foreground package, visible/readable control counts and whether media-session verification is connected, without dumping screen text. EMA cannot silently grant these Android permissions. Real system dialogs block targeted automation rather than allowing actions through the dialog into the underlying app.

## Offline speech reliability and guarantees

New installations now default to **EMA offline only**, with no platform/cloud speech fallback. Existing explicitly selected modes (including legacy opt-outs) are preserved; existing users should select **EMA offline only** to enforce that policy. Strict-mode planning does not enumerate other recognizers or check network availability. This is a privacy/selection guarantee, not a guarantee that Android can always allocate the engine or grant microphone access.

The English speech model remains bundled in the APK. First-use preparation and cold reloads use local assets, not downloads. Empty required model files now fail readiness checks. If the installed native model cannot be opened with an I/O failure, the shared model manager performs **one** reinstall from the checksum-verified bundled archive and tries loading once more. It does not retry indefinitely or mistake an out-of-memory/permission error for model corruption. Existing warm-model leases, low-memory protections, idle unloading and one transient microphone retry remain in place. Failures include recovery guidance rather than claiming the engine is fully ready or silently using cloud speech.

`EmaOfflineModelRecoveryTest` covers successful repair, persistent failure bounds, failed reinstalls, memory/permission errors and strict-default backend selection. No word-error-rate benchmark or physical-device stress testing has established "best available" recognition quality. Reliable task completion also depends on app accessibility, network content, permissions and sign-in state; EMA must report those limits rather than pretend every task can be completed.

## Already-populated search and speech-error review

A search can be ready even when a custom app does not expose its query as an editable field. The search driver and media executor now recognize a visible query anchor plus an actionable matching result row, and continue to selection instead of insisting on typing again. A recommendation alone, an empty query, or a query echo without results is insufficient. Text and content-description duplication on a Search tab no longer prevents a focused search field from being recognized.

Command-only normalization recognizes the split brand variant `spots if i` as Spotify, while preserving message bodies and any undecoded trailing words. It does not invent `and play Arcade` from a badly recognized phrase. When a fuzzy direct-app match contains an unresolved suffix, EMA requests clarification instead of silently dropping that suffix and reporting that opening the app completed the whole request. Explicit structured app-section requests still follow the section-navigation path.

The motivating screenshots are evidence of visible text and UI state, not a recording of the original audio or an accessibility-node dump. These regression fixtures reproduce the visible screen shape; they do not establish word-error-rate improvements or confirm behavior on that specific Spotify build. Validate with `diagnose screen control`, then try a typed command and the same spoken command separately on the device.

## Selection and message-continuation regressions

- A terminal perception phrase (`the first video you see`, `the second result that you can see`) qualifies an observed ordinal, not a song/video title. Titles with additional topic words remain queries. `search on 'how to build an operating system'` strips the search introducer and enclosing quote pair.
- Ambiguous recipient/body clarification retains the original app. A reply such as `Daniel Bro, am coming home` supplies an explicit recipient/body boundary; later commas remain in the message. Cancellation and new explicit commands can leave this clarification safely.
- Explicitly not typing/pressing Send/sending is a failed submission, not an unverified send. It must not poison duplicate suppression. Possible submissions remain guarded, with wording that describes uncertainty rather than claiming success.
- Chat-header verification tolerates up to 12 additional 400 ms observations while the target package remains accessible and the phone unlocked. Recipient/body matching stays exact; a larger bounded 1,200-node target snapshot improves coverage on long chat trees. A failed header check acknowledges that an intent may already have prefilled a draft; a draft is not evidence of sending.
- Device vocabulary accepts `on flashlight`, `off flashlight`, `on torch`, and `off torch` in addition to existing forms. Permissions and hardware availability still apply.

These fixes address reproduced parser/state bugs from device screenshots. They do not establish that every app exposes usable controls, that every slow screen eventually loads, or that an uncertain send is safe to repeat. Physical-device regression remains necessary for messaging and playback.

## Native request assessment and task meaning

The native path now assesses speech acts before semantic parsing and before pending-task
follow-ups: indirect action requests are canonicalized, instruction/capability questions
are answered without acting, and explicit negative or mixed-negative instructions do not
execute. Explicit message/search payloads are protected from prefix rewriting. This is a
bounded grammar, not general language understanding; unsupported paraphrases still need
clarification. Eight exact French/Spanish device phrases are text mappings only, not a
claim of multilingual offline acoustic recognition.

Installed app identity is separate from app purpose. Exact known package identities map
to task concepts across messaging, video, music, browsers, maps, email, files, camera,
calendar, clock, notes and calculators. Unknown packages remain unknown; purpose is not
proof that every listed workflow is implemented or accessible in that app version.

Messaging readiness has explicit wait/type/submit/blocked/timeout stages. Loading can
be observed for eight seconds; wrong packages, locks and conflicting drafts block action.
A visible recipient, unambiguous composer, exact body and safe Send control are required.
Submission still uses the existing final recheck and post-send verification. Unit tests
exercise these stages; real devices/app versions and slow networks require validation.

## Device knowledge and speech safety sweep

Device discovery uses a shared offline catalogue of 23 settings concepts: Wi-Fi,
Bluetooth, DND, airplane mode, location, hotspot, NFC, battery saver, display, sound,
notifications, apps/permissions, storage, accessibility, VPN, mobile data, date/time,
security, keyboards, accounts/sync, auto rotation, silent mode and vibrate mode. Try `search settings for hotspot`, `what is
Bluetooth?`, `is Wi-Fi on?`, or `open app permissions`. Explanations never toggle;
status queries never infer a connection from a radio being enabled.

Silent/vibrate modes change and verify the ringtone stream, not media volume. Auto
rotation uses Android write-settings permission and read-back. These changes require an
unlocked phone.

Explicit Wi-Fi/Bluetooth/airplane commands retain the existing observed toggle and
system-readback workflow. Location, battery saver and NFC can use an observed Settings
switch, with Accessibility access, an unlocked phone, bounded loading waits and state
verification. Unsupported OEM pages/labels fail safely. Other catalogue entries provide
navigation and guidance, not a claim of automatic control. Network passwords, Bluetooth
pairing consent, VPN consent, security authentication and account credentials stay under
the user's control. Connecting/pairing/forgetting requests open the appropriate controls
and explicitly report that the requested connection operation was not completed.

DND uses priority mode so the user's configured interruption exceptions are respected,
not total-silence mode. Android 15+ policy ownership can restrict global effects; EMA
reads back the reported filter and must not claim success without matching evidence.
Wi-Fi reads use WifiManager; an Internet quick-settings tile is deliberately not treated
as a Wi-Fi-only switch because it can contain cellular controls too.

Offline STT improvements here are decoder-policy, endpoint and rescoring changes, not a
new acoustic model: closed grammars are limited to confirmation turns; unfinished
setting targets and message-content slots get extra pause tolerance; rescoring cannot
reverse the top acoustic hypothesis's setting polarity/target/value or remove a negative
instruction. The recognizer still can mishear speech. English model accuracy, accents,
noise and microphone routes require real-recording evaluation; no measured WER improvement
is claimed. Dictation and web search remain open vocabulary and do not use command safety
filtering. No audio leaves the phone in offline-only mode.
