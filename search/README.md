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

The screen shares EVMA's bundled wallpaper with a dark overlay, a translucent
search field, and a responsive poster grid. Remote selection highlights each
card; titles, release years, and service badges stay visible. A `+N` badge means
additional services are available in the title's provider picker. Cards with no
matching offer remain selectable and explain the scoped availability result.
Loading, no matches, and network/catalog errors have separate screen states.

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
