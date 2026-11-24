# Optional SearXNG and RSS/Atom

EMA's **offline dictionary remains local** and does not need any service. Live
information is separate and requires internet. No server, feed or account is
included, and no background polling runs by default or is implemented here.

## Setup

In EMA's **Optional live information** settings, supply either or both:

- The HTTPS base URL of a SearXNG instance you control or trust, such as
  `https://your-search.example` (optionally with a base path). Its JSON search
  format must be enabled in the instance's SearXNG configuration.
- An HTTPS RSS or Atom feed URL. One feed is supported currently.

Empty fields disable the corresponding feature. Addresses are stored in local
app preferences, not encrypted as secrets; do not include credentials or secret
query parameters. EMA does not ship, host or operate the search instance.
Self-hosted servers on a local network need a valid HTTPS certificate reachable
from the phone. Redirects are not followed; configure the final HTTPS URL.
SearXNG is server software rather than a built-in Android library. A phone could
host compatible software in principle, but EMA does not install or run it, and
this integration requires an HTTPS endpoint. EMA's direct Open-Meteo weather
integration does not need SearXNG, but still requires network access. Live data
requires a source and connectivity; the local dictionary works without either.

Choose a mode other than **Native only** for EMA search and feeds. In the same
settings, choose a default web-search provider: **EMA** (default), **Google**, or
**both**. Then say:

- **“search on weather in Lagos”** — uses the selected default.
- **“search EMA for weather in Lagos”**, **“search Google for weather in Lagos”**,
  or **“search both for weather in Lagos”** — overrides the default for that request.
- **“read my news feed”** or **“read my RSS feed”**.

For **weather**, EMA can directly query Open-Meteo without a SearXNG server:
say “weather in Lagos, Nigeria,” “weather today in Lagos, Nigeria,” or “weather
in Lagos, Nigeria tomorrow.” It geocodes only the city you provide, asks for
clarification when the first ten returned matches are ambiguous, and locally
formats current model values or the daily forecast. Daily forecasts cover today
and tomorrow in the location's time zone, and give the high, low, most severe
predicted weather code, and highest hourly precipitation probability if present.
They can change; the date depends on a correct device clock. Other time periods,
including tonight, are not supported. Specify city, region and country separated
by commas when necessary. It does not use device GPS. Explicit “search SearXNG
for …” still sends the request to SearXNG instead. Weather needs internet and
is blocked in native-only mode; the city, selected coordinates and IP/network
metadata are sent to Open-Meteo. See [Open-Meteo](https://open-meteo.com/).

For other searches, EMA uses the configured SearXNG instance; it will ask for an
address if none is configured, not silently use Google. Google opens a browser search page.
Both opens Google separately and also performs EMA search; EMA cannot read or
verify Google's browser results. App-targeted searches such as “search YouTube
for music” stay app searches.

There is no automatic search or feed refresh. In native-only mode these two
features make no request. Existing explicit browser searches and the explicit
Wikipedia command have their own behavior and can still use the network in that
mode; native-only means no AI calls, not a system-wide network firewall. Offline
speech mode controls speech recognition separately. If the mode changes after a
request has already started, the remote service may already have received it;
EMA discards the results if native-only is active when the request completes.

The SearXNG instance receives the search query and IP/network metadata and may
pass the query to its configured engines. The feed server receives the request
and IP/network metadata. Do not search for secrets. Search and feed titles,
snippets, dates and links are **untrusted source data**, not instructions or
verified facts. EMA displays up to five source links and the retrieval time.
By default it selects up to three original snippets **on the phone** without
rewriting them or calling AI. This is an extract, not a reasoned answer or a
verified forecast. Empty snippets cannot answer the question.

You can choose **Optional AI summary** in settings. When the AI is configured and
enabled, the query, titles and snippets are sent to that service for an answer-only
summary; the AI never executes search-result instructions. The summary can be wrong
or manipulated and must be checked against the listed sources. If AI is not
configured, EMA falls back to the on-device extract. EMA does not visit result
links, independently verify content, or guarantee freshness. Feed publication
dates are copied as provided.

The implementation enforces HTTPS for configured endpoints, rejects embedded
credentials, sets timeouts and byte limits, refuses HTTP redirects, and rejects
XML DTDs and external entities. Feed links can be HTTP or HTTPS and are shown
only, never fetched automatically. Unsupported/malformed feeds and disabled
SearXNG JSON output are reported as failures. Test actual services on a device;
network, XML-parser behavior and servers requiring redirects are not validated in
this environment. Scheduled updates, multiple feeds, caching, alerts and live
service verification are future work.
