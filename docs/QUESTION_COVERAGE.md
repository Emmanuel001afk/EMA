# Question coverage and routing

This is a starting evaluation corpus, not a claim of general question-answering
accuracy. It was informed by the information categories in [Amazon's Alexa
examples](https://www.amazon.com/alexa-information/b?ie=UTF8&node=21588420011)
and the ambiguity and follow-up issues described in [Tanjim et al.](https://arxiv.org/html/2502.00537v1).
The comparisons below are code-review expectations, not measured device results.

| Category | Sample question | Intended behavior / limitation |
|---|---|---|
| Computation | What is 15% of 2400? | Local computed answer for supported expressions. |
| Conversion | How many minutes in 1 hours? | Local conversion for supported units and phrasings. |
| Dates/time | What day is 1 January 2027?; time in Lagos | Local date calculation; city time zones come from a limited table. |
| Native instructions | How do I send a message? | Explain the supported EMA workflow; don't send it. |
| General how-to | How do I cook rice?; how do I send a parcel? | Do not mistake shared words for an EMA workflow. Optional configured AI may answer; otherwise say offline answer unavailable. |
| Word meanings | What does photosynthesis mean?; define resilience | Offline WordNet 3.0 entries, with clearly labelled candidate base forms; no general sentence understanding, current slang or guarantee of a particular sense. |
| Letters | Spell resilience; how many letters in café?; alphabet | Spell the recognized word or count Unicode letters locally; this cannot verify what the user intended the speech recognizer to hear. |
| Other facts | What is the capital of Ghana? | Not a general offline encyclopedia. Optional AI answers may be wrong and are not necessarily sourced. |
| Live information | What's the weather in Lagos?; weather in Lagos tomorrow; who won the match? | Open-Meteo provides current conditions and daily forecasts for today/tomorrow when explicitly requested and online; ambiguous cities require clarification. Sports need another source. Data may be delayed or forecasts change. |
| Personal/device | Is Bluetooth on?; what is my battery level? | Existing device handlers rely on Android state and permissions; not all states are observable. |
| Grounded source | What does this screen say about the deadline? | Extractive retrieval from the explicitly named screen; no general inference. |
| Follow-up | What does it say about Friday?; double that | Uses only supported short-lived document or numeric context. Missing/expired context must not be guessed. |
| Action boundaries | How do I send a message? vs send Ada a message saying hello | Questions about instructions must not execute actions. Explicit commands go through existing safety and verification. |

`EmaQuestionPolicyTest` exercises a small routing corpus across modes; existing
knowledge, language and grounded-question tests cover other examples. Tests do
not establish whole-pipeline or real-device success. Expand the corpus with
independent paraphrases, misleading keywords, missing context, privacy-sensitive
requests and negative instructions before reporting any success rate.

## Internet and privacy

Unknown instruction questions can reach the optional AI only when the selected
mode allows AI and it is configured. AI responses to this question path are
answer-only: proposed actions are not executed. In native-only or unconfigured
mode EMA offers the user an explicit `search the web for ...` command; it does
not launch a search by itself. Explicit searches use the configured default:
EMA's SearXNG search, Google, or both. EMA search is blocked in native-only
mode; Google can still open a browser and use internet. Opening a results page
is not a sourced answer.

For a deliberately requested online lookup, say **“Wikipedia lookup Lagos”** or
**“look up Ada Lovelace on Wikipedia.”** EMA sends the specified article title to
English Wikipedia's API and returns an extract, a Wikipedia source link, and the
retrieval time. This is an exact-title lookup with redirects, not general web
search or free-form question answering. Missing and disambiguation pages prompt
for a more specific title; Wikipedia errors and connectivity failures are
reported without inventing an answer. The source may be incomplete, wrong or
out of date; the retrieval timestamp is not the article's last-update timestamp.

The lookup is read-only, available in all assistant modes only when explicitly
requested, and does not call AI. It is a network request even in native-only
mode: the title and network metadata reach Wikipedia. Avoid entering secrets or
sensitive personal information. EMA does not automatically look up unanswered
questions. Network availability and the live API must be verified on a device;
this sandbox cannot reach Wikipedia. Direct current weather uses Open-Meteo;
there is no independent source verification or built-in live news provider.

## Offline words and letters

EMA bundles a compact English dictionary generated from **WordNet
3.0**, with 155,287 lemma/part-of-speech entries across 27 compressed buckets
(about 6.2 MiB). The source and full license are included in
`app/src/main/assets/wordnet/LICENSE.txt`. The source archive is
`nltk/nltk_data` Git blob `3c68cd207844f071a5206f7fbed23e5c607ec02f`;
`tools/build_wordnet_dictionary.py` checks its SHA-256 and builds reproducible
assets. Examples: “define bank”, “what does resilience mean?”, “spell café”,
“how many letters in EMA?” and “alphabet”. Unknown entries are reported as
unknown or accompanied by explicitly labelled possible base forms, not invented. Dictionary requests run off the UI thread; no word is
sent over the network by this feature.

Several meanings may be returned because a word can have different senses;
EMA shows at most two per part of speech and six overall and reports if more
exist. For inflections, WordNet's exception list and bounded suffix rules propose
up to four possible base forms, labelled as possibilities, not certain meanings.
WordNet is a 2006 lexical dataset and does not cover every word, name,
inflected form, current usage, language, or sentence. Definitions and examples
may be incomplete or outdated. To try a narrow context match, say “meaning of
bank in sentence: the bank is beside the river and water.” EMA compares words in
the sentence with dictionary definitions and suggests a sense only with distinct
lexical evidence; otherwise it abstains. This is not general sentence understanding
and is not a certain interpretation. Spelling reflects the recognized input, not a
guarantee of correct speech recognition. The alphabet
answer is explicitly the English alphabet; letter counts use Unicode letters.

### Explicit offline reasoning

Use `reason: all cats are mammals; all mammals are animals; Luna is a cat; is Luna an animal?` for a small, offline, hypothetical syllogism. Separate premises and the final yes/no question with semicolons. Supported premises are `all X are Y`, `no X are Y`, `Name is a Y`, and `Name is not a Y`; the final question is `is Name a Y?` or `is Name not a Y?`. Names and categories must be single words. EMA explains a supporting chain, reports when both answers are supported, and says unknown when neither is established. Premises are treated as data, not device commands or verified facts.

This is not general language understanding or a truth checker. Plural matching uses a small heuristic and can misinterpret unusual words; use simple regular nouns and avoid ambiguous forms. Irregular plurals, pronouns, multiword categories, most quantifiers and other sentence forms are not supported. The input is limited to 24 premises and 3,000 characters. Contradiction reporting concerns the asked question, not a complete consistency check of all premises.

### Offline sentence analysis

`analyze this sentence: Could you open the door?` inspects the text without executing it. EMA lists up to 30 recognized Unicode word tokens (counting up to 200), offers a **tentative English-oriented surface classification**, and flags some explicit negation, conditional, quotation and sarcasm-marker cues. The quoted sentence remains data and is not sent to an action planner. These cues do not establish the speaker's intent, the scope of negation, or the meaning of a sentence; in particular, sarcasm generally cannot be reliably inferred from text alone. Word tokenization is not language identification or translation, and the simple rules are not a multilingual grammar. Maximum input: 4,000 characters and 200 recognized tokens. For word definitions, use EMA's separate offline WordNet reference, which covers English rather than all languages.

### Action intent and conditions

Before planning, EMA recognizes additional English prohibitions such as “I don't want you to call Ada,” including curly apostrophes, and withholds conditional or hypothetical action-shaped requests such as “if it rains, open Spotify” or “what if you open Spotify?” It asks for an unconditional action rather than trying to evaluate or schedule the condition. These are bounded patterns, not comprehensive intent or sarcasm detection; other phrasings can still be misunderstood. Explicit message bodies, search queries and proofreading text remain data and are not scanned as action constraints by these new checks.

### Comparing word placement

`compare sentences: Only Ada called Bob | Ada only called Bob` performs a read-only, offline comparison. EMA identifies when the recognized words are the same but ordered differently and reports positions of selected English markers, including `only`, `not`, quantifiers and conditionals. It can also report wording changes; it does **not** infer what the changed sentence means. Equal token sequences ignore punctuation and casing, so they do not establish equivalent meaning. Quoted content is included as visible wording, not treated as an instruction. Use exactly one `|` separator; the input is bounded to 4,000 characters and 200 recognized tokens per sentence. Tokenization is Unicode-aware but is not reliable word segmentation in every language.

Conditional-action checks also run after supported polite or indirect wrappers are removed, so “could you open Spotify if I ask” is not converted into an unconditional request. The checks are conservative and can require clarification for names containing condition words.
