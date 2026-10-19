# EVMA Search

Optional search companion for the EVMA home screen. The home launcher still
requests no permissions. This separate APK requests only `INTERNET` to query
the [Streaming Availability API](https://docs.movieofthenight.com/guide/shows).

Search defaults to United States availability and the user's selected services.
Only `subscription` and `free` offers are shown. Rental, purchase, and extra
channel/add-on offers are excluded. Spotify remains in the launcher app row;
it is not a movie/TV catalog provider in this search screen.

## Setup

Build and install the search APK alongside the launcher. In the home screen,
select **Search movies & shows**, then **Settings → Search setup**. Create a personal
developer account at https://developers.movieofthenight.com/ and enter that
account's API key. Use a key issued directly by Movie of the Night, not RapidAPI.
There are request quotas; check the provider's current plan details before signup.
The key is stored in the companion's private SharedPreferences. It is never
embedded in source, sent to streaming services, or placed in `home.conf`.
Android backup is disabled for this component.

Search titles are sent over HTTPS to `api.movieofthenight.com`. No streaming
account password is needed. No analytics SDK or notification permission is used.
Service choices can be changed using **Settings → Your streaming services**. Rental/purchase filtering
cannot be turned off in this version.

## Results and limitations

### Details, profiles, watchlists, and history

Select a poster to open a full-screen title page with synopsis, year/type,
runtime and genres when provided, critics score, and **Where to watch**.
Provider selection and unsupported-link fallback are retained behind that button.
Back or Close returns to the previous grid. Long descriptions scroll with the remote.

The profile button switches between **Together**, **Evan**, and **Emma**.
Each profile has its own watchlist, latest 20 unique recent searches, and service
filters. API keys and the ratings cache are shared privately on this TV. Existing
service selections are used as initial defaults. These are local discovery
profiles; switching does not change streaming-service accounts, launcher app
order, or appearance preferences and does not provide parental access control.

**Recent** displays only the active profile's submitted searches. Selecting one
submits it again. Clearing history asks for confirmation and affects that profile.
Browsing history makes no catalog request. No search history is uploaded for analytics.

**Save to watchlist** and **Remove from watchlist** work on the details page.
Up to 100 titles per profile are stored in private preferences with a bounded
metadata snapshot. Availability links are excluded from snapshots. Saved cards
say **Check availability**; opening a saved title makes one catalog request by
exact show/IMDb ID to refresh current US subscription/free offers before enabling
provider links. Refresh errors keep the saved metadata and offer Retry; stale
offers are never silently reused. Metadata remains readable offline. Unsupported
or removed IDs prompt a new search rather than guessing another title.

**Pick for us** opens choices for Movies/TV, genre, and runtime. It chooses only
unwatched titles from the active profile's watchlist and checks current availability.
**Pick again** on the details page reuses that profile's picker choices. It avoids
the previous pick when another eligible title exists. Picks do not automatically
play or purchase anything or promise that a title is still available. Each saved
title/pick opened triggers a catalog lookup, independent of the ratings caches.

**Mark watched / Mark unwatched** on details is local to the active profile and
persists even if a saved title is removed and later re-added. The watchlist's
**Filters** button filters by Movies/TV, genre, runtime, and All/Unwatched/Watched.
Watchlist and picker filters are saved independently per profile. Runtime caps
are strict (Under 2 hours means less than 120 minutes), and exclude missing
durations. There is no automatic viewing-progress tracking across streaming apps.

Closing details restores the underlying grid's saved scroll state and focused
title. When a title was removed or filtered out, selection falls back to the
nearest remaining position. Rerolling or retrying a details page preserves the
original return point. The header control is restored when that opened details.

When the search field is empty, up to five recent searches appear as remote-friendly
suggestions. Suggestions are hidden in watchlist mode and while a search is loading;
selecting one explicitly submits a search. Displaying them makes no request.
All search menus share the same rounded panel, text colors, buttons, and focus
highlights, including profiles, service selection, key setup, filters, and errors.

The screen shares EVMA's bundled wallpaper with a dark overlay, a translucent
search field, and a responsive poster grid. Remote selection highlights each
card; titles, release years, and service badges stay visible. A `+N` badge means
additional services are available in the title's provider picker. Cards with no
matching offer remain selectable and explain the scoped availability result.
Loading, no matches, and network/catalog errors have separate screen states.

### Rotten Tomatoes critics scores

Open **Settings → Rotten Tomatoes setup** to enter a separate, email-activated
[OMDb API key](https://www.omdbapi.com/apikey.aspx). This is independent of the
Movie of the Night key. The free OMDb signup currently lists 1,000 daily requests;
check the signup page for current limits. Scores are optional and use no new
Android permissions. Removing this key disables ratings without disabling search.

Visible poster cards request OMDb metadata by the exact IMDb ID supplied by the
catalog. Only `Ratings` entries whose `Source` is `Rotten Tomatoes` and whose
value is a valid percentage are shown. The returned IMDb ID must match. These
are critics scores supplied by OMDb, not audience scores or the catalog's own
rating. A show-level score must not be treated as a score for every season.
Many series and some movies have no returned RT score: the badge then says
`Critics —`. Missing IMDb IDs also show a dash with an explanatory description.
Key, quota, and lookup errors use distinct badge messages rather than a zero.

Two background workers load ratings without delaying search results or provider
links. Positive scores are cached privately for seven days; confirmed missing
scores for one day. The cache is capped at 256 IDs. Failed lookups are not cached.
Repeated cards for an ID share a pending request. A key/quota rejection pauses
further new requests until the next explicit search or settings change.

OMDb receives IMDb IDs over HTTPS. It requires its key in the URL query; the
app never logs that URL or raw lookup exceptions. The key is stored only in the
companion's private preferences and is not sent to image hosts, weather,
Movie of the Night, or streaming apps. No title guessing or website scraping is
used to fill missing scores. Live score validation requires the user's OMDb key.

### Audience scores and score icons

Open **Settings → Audience score setup** and enter a separate MDBList API key,
obtained from Preferences at mdblist.com. See the provider's
[API documentation and limits](https://docs.mdblist.com/docs/api).
This optional key is private on the TV and shared across local profiles. Removing
it disables audience lookups while preserving the movie-search and OMDb keys.

Audience lookups use the documented exact-ID route
`https://api.mdblist.com/imdb/{movie|show|any}/{imdb_id}/?apikey=...`.
The returned IMDb ID must match. Only `popcorn` (or legacy `tomatoesaudience`)
rating entries are accepted, with integer percentage values from 0 to 100.
Other platforms' user scores and MDBList's aggregate score are never substituted.
Key, quota, network/schema errors and missing ratings have distinct labels.
Audience verification status is not supplied by this integration, so it must
not be presented as a specifically Verified or All Audience score. Series-level
ratings must not be described as ratings for each individual season.

Critics and Audience have separate private bounded caches, pending-request
deduplication, timeouts, key setup, and cancellation. Each uses up to two workers.
Successful scores are cached for seven days, missing scores for one day. Request
URLs/keys and raw network exceptions are never logged. Live MDBList validation
requires the user's key. Audience requests are optional and do not gate search,
watchlist editing, or provider links.

The matching score icons are imported from Rotten Tomatoes' published web-component
vectors: red tomato at 60%+, green splat below 60%, full red popcorn bucket at
60%+, tipped green bucket below 60%, and gray variants for missing scores. Source
paths, colors, translations and mask clipping are preserved as Android vector
resources. Setup/error states have no score icon. A gray icon means data was not
provided here; it does not establish that a title is unreleased or lacks reviews.

**Certified Fresh and Verified Hot are never inferred from percentages.** These
feeds do not confirm the necessary award status, review counts, top-critic counts,
verified vote counts, release type or season-specific award. Award badges remain
hidden unless a future source explicitly confirms them.

Artwork source: Rotten Tomatoes score-icon-critics and score-icon-audience in
`/assets/pizza-pie/javascripts/bundles/roma/default.a3b33025165.js`, obtained from
the [official licensing page](https://www.rottentomatoes.com/help_desk/licensing).
Rotten Tomatoes® and its score marks belong to their respective owners. The
app displays brand/source attribution; imported resources grant no broader
trademark license. See the linked brand guidance for distribution requirements.

### Private library backup

The local Windows `Backup-EvmaTV-Watchlists.ps1` helper adds the export feature
through an in-place app update, preserving app data and first saving the previous
APK. Run it after finishing watchlist edits. It reads a shell-only, DUMP-protected
content endpoint at `content://tv.evma.search.provision/library-backup` directly
into a PC file without relying on Fire OS external-storage access.

The version-1 `evma-library-backup` JSON contains active profile and all three
profiles' watchlist metadata, recent searches, watched identities, service choices,
last pick and filters. Only those fields are exported. API keys, streaming logins,
weather/ratings caches and device identifiers are excluded. No TV preferences are
written by the export endpoint. Invalid stored library data fails the export
rather than silently replacing it with empty lists.

The helper checks the format and profile/title counts, refuses to declare success
for an unexpectedly empty library, verifies launcher settings remain unchanged,
and writes `watchlists.json` with a SHA-256 sidecar in local `outputs/EVMA-backups/`.
Only a reported successful export is a completed backup; an APK backup alone is
not a watchlist backup. Keep the JSON and checksum together. It is a data export
for library recovery, not a firmware image or a tested system recovery backup.

Ratings errors do not prevent library export. Quota messages in HTTP 401/403
error bodies are recognized as limits before falling back to the key/access error.

Poster URLs come from the search response (`imageSet.verticalPoster.w240`, with
`w360` fallback), so artwork needs no additional catalog lookup. Public image
hosts receive HTTPS image requests without the catalog key. Two workers, bounded
responses, an 8 MiB memory cache, and sampled decoding limit TV resource usage.
Missing, unsupported/SVG, and failed images show a title placeholder. Artwork is
cached only in memory. Searching is still explicit; typing does not send requests.

- A single matching service attempts to open its title link in its installed app.
- Multiple matching services show a picker before opening a link.
- No matching offer shows a scoped message: no listed US subscription/free option
  on the selected services. It does not claim the title is unavailable everywhere.
- Network, authentication, quota, and malformed-data errors are separate states.
- Installed packages are discovered from launchable activities and matched by
  display name. No guessed package names are used to open or modify streaming apps.
- A provider link is opened only in the matching installed app. If the app does
  not accept the supplied HTTPS link, the UI explains the limitation and offers
  to open the app for manual search. Apps may accept a link but display a generic
  page, so every provider needs real-device validation.
- Catalog coverage and freshness vary; seasons, subscription tiers, and catalog
  removals may differ from the results. Live searches cannot be validated without
  a working API key. API results are not proof of the user's subscription entitlement.

## Build and recovery

Use JDK 17, Android API 28 and build-tools as described in `../launcher/README.md`.
Run `bash search/build.sh` from the repository root; no Gradle is needed.
The companion APK is `search/build/evma-search.apk`. Keep its signing key for updates.
Install it with ADB separately; building never installs or changes HOME behavior.

The search manifest declares no HOME intent filter or accessibility service.
Back returns to EVMA. Amazon remains the preview's HOME target. Rolling back the
launcher APK does not require removing the search app or changing streaming apps.
Uninstalling the search app deletes its own API key and service settings.

## Fairfield weather and automatic key setup

The companion also provides Fairfield, Ohio weather to the home screen through
a bound Messenger service. Requests are accepted only from the installed
`tv.dumbtv.home` package when it has the same signing certificate. Build the
launcher first; both APKs must use the same private signing key.

Weather uses the [Open-Meteo API](https://open-meteo.com/en/docs), fixed Fairfield
city-center coordinates, Fahrenheit, and America/New_York time. It needs no API
key or location permission and does not consume movie-search requests. Successful
responses are cached for 20 minutes. Failures are rate-limited, and an older
reading is labeled cached rather than presented as freshly fetched weather.
The launcher binds only while visible; no always-running or foreground service
is added. Weather data attribution is displayed on the card.

Manual setup is the active Windows workflow. APK updates use `install -r` to
preserve the key and selected services; do not uninstall or clear app data.
The preview updater does not attempt automatic key transfer. The following
optional developer import endpoint remains available but is not used by updates.

The optional developer endpoint accepts ADB standard input through a content provider restricted to ADB
shell (UID 2000) and the system `DUMP` permission. The provider validates at most
4096 bytes and saves the key in private preferences. This avoids external-storage
access, which Fire OS can deny. Only a status value can be queried; the provider
never returns the key. Any existing PC staging file remains in the ignored
`report/private/` folder; the current update scripts do not read it.
Keys are never included in source, APKs, intent extras, command
arguments, logs, status output, or temporary files on the TV.
