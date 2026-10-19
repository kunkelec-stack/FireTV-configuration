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
select **Search movies & shows**, then **Search setup**. Create a personal
developer account at https://developers.movieofthenight.com/ and enter that
account's API key. Use a key issued directly by Movie of the Night, not RapidAPI.
There are request quotas; check the provider's current plan details before signup.
The key is stored in the companion's private SharedPreferences. It is never
embedded in source, sent to streaming services, or placed in `home.conf`.
Android backup is disabled for this component.

Search titles are sent over HTTPS to `api.movieofthenight.com`. No streaming
account password is needed. No analytics SDK or notification permission is used.
Service choices can be changed using **Services**. Rental/purchase filtering
cannot be turned off in this version.

## Results and limitations

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
