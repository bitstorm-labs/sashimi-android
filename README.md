# Sashimi for Android

A native Kotlin / Jetpack Compose (Material 3) client for [Jellyfin](https://jellyfin.org),
the Android sibling of the Sashimi Apple and Roku apps. It runs on phones and
tablets. The shared `:core` module has no UI code so a TV layout can reuse it,
but **no Android TV layout ships yet**: the app does not declare Leanback, and
the UI is touch-first.

**Status:** version `0.12.0`. Release builds go to the Play internal-testing
track and to [GitHub Releases](https://github.com/bitstorm-labs/sashimi-android/releases)
as a signed APK.

## Features

- **Auth / multi-server:** two-step connect (URL → credentials), servers list
  with switch / remove / add, per-server tokens, prefilled re-auth on expiry,
  401-only-on-active-server session handling, and a "your session has expired"
  banner on the connect screen.
- **Home:** configurable rows (Continue Watching + per-library Recently Added;
  order + visibility persisted, editable in Settings with drag-to-reorder).
  Continue Watching cards show remaining time, progress, and S#:E#; Recently
  Added rows dedupe by series, show "X new" / watched / quality badges, a See All
  grid past 6 items, and circular YouTube-channel styling. Pull-to-refresh.
- **Libraries:** adaptive poster grid with sort (Name / Date Added / Release Date
  / Rating / Runtime ± direction), filter (All / Unwatched / Watched / Favorites),
  shuffle, and in-library search.
- **Search:** poster-grid results with a count and year/type captions, debounced
  query, and recent-search chips (last 10, Clear).
- **Detail:** one adaptive screen (compact = stacked, expanded = tablet
  two-column) with Play / Resume / Start Over, next-up logic for series, Shuffle,
  local-first Trailer, a trailer that plays behind the page, optimistic watched toggle, favorite + admin menu (File Info
  / Refresh Metadata / Delete — hidden when offline), season tabs + episode list,
  cast, ratings, media badges, and single + bulk-season downloads with a quality
  picker.
- **Player (Media3/ExoPlayer):** PlaybackInfo negotiation (direct play → direct
  stream → HLS transcode) with an Android DeviceProfile, OSD stream-info chip,
  quality menu (re-negotiates preserving position), audio + subtitle track
  selection (external VTT side-loaded and app-rendered), speed 0.5–2×, skip
  intro/credits pill with auto-skip, a full-screen Up Next screen at the end of
  an episode with auto-play and season rollover, resume threshold, progress
  reporting, and Picture-in-Picture. 4K Dolby Vision / HDR10 HEVC is remuxed
  instead of re-encoded where the device allows.
- **Adaptive Auto quality:** Auto starts from the link you are on (Ethernet,
  Wi-Fi or metered mobile data) and steps up when throughput allows. It never
  steps up on a metered connection.
- **Theme songs and trailers:** a show's theme music plays while you browse its
  page, and a title's local trailer plays behind the page. Both can be turned
  off in Settings. Theme songs stay quiet when other audio is playing, and
  trailers never play on mobile data.
- **Admin server dashboard** (Jellyfin administrators only, under Settings):
  Now playing cards for every active stream, with Stop and Send a message
  (shown on the viewer's screen), an Activity log, and a Health screen with
  library scan status, hardware transcoding, failed scheduled tasks and
  storage. Sashimi itself accepts Stop and message commands from that dashboard.
- **Downloads / offline:** WorkManager queue with a foreground-service progress
  notification, quality tiers (Original / High / Medium / Low), Original
  fail-closed on device compatibility, a downloads screen (active / completed /
  failed / retry / delete-all + storage bar), subtitle downloads (side-loaded on
  offline playback), an offline Home + offline detail/playback drawn from a Room
  store, with local position save and sync-on-reconnect.
- **Settings:** servers, Home row order editor, quality badges and ratings,
  theme songs, trailers, 24-hour time, max bitrate, always play original,
  resume threshold, audio/subtitle languages, downloads storage + delete-all,
  about (version/build), and sign out with confirmation.
- **Deep links:** `sashimi://item/{id}` → detail and `sashimi://play/{id}` →
  player, with cold-start stash-until-authenticated (a launch link opens after
  sign-in).

## Get more out of Sashimi

Sashimi works against a plain Jellyfin server. These server add-ons make it
better. None are required. The first three are made by Bitstorm Labs. The last two
are separate projects by other people; see [Acknowledgments](#acknowledgments).

| Add-on | What you get in Sashimi for Android | Without it |
|--------|-------------------------------------|------------|
| [Continue Watching plugin](https://github.com/bitstorm-labs/jellyfin-plugin-continue-watching) | The server merges Resume and Next Up into one ranked row (`GET /ContinueWatching`), and Home shows it as Continue Watching. | The app builds the same row itself from Resume, Next Up and your recent plays when the server answers 404. |
| [Channels plugin](https://github.com/bitstorm-labs/jellyfin-plugin-channels) | Not supported yet. The Android app has no channels row or guide. | Nothing changes. |
| [Theme Songs plugin](https://github.com/bitstorm-labs/jellyfin-theme-songs) (manifest: `https://raw.githubusercontent.com/bitstorm-labs/jellyfin-theme-songs/main/manifest.json`) | Theme music plays on series pages. Toggle: Settings, Play theme songs. | Series pages are silent. |
| [Intro Skipper](https://github.com/intro-skipper/intro-skipper), by the Intro Skipper team (not Bitstorm Labs) | Skip Intro and Skip Credits buttons in the player. Sashimi reads Jellyfin's native `/MediaSegments`, and falls back to the plugin's own endpoint on older servers. | No skip buttons. |
| [Trailarr](https://github.com/nandyalu/trailarr) by nandyalu (separate app, not a plugin, not Bitstorm Labs) | Downloads trailers next to your media. Sashimi plays them from the Trailer button and behind detail pages. Toggle: Settings, Play trailers on detail screens. | The Trailer button and hero trailers only appear for titles that have a local trailer. |

Hardware transcoding on the server also helps Auto quality. When Auto has to
transcode, a GPU keeps it fast, and the admin Health screen shows whether it is
on.

## Screenshots

No screenshots yet.

## Module layout

| Module   | Contents |
|----------|----------|
| `:core`  | Jellyfin API client (`JellyfinClient`), models, multi-server `SessionManager`, playback negotiation, downloads engine (Room + WorkManager + OkHttp), settings, and the deep-link resolver. **Zero Compose/UI imports** — the TV-readiness discipline: a future `:tv` module reuses `:core` wholesale. |
| `:app`   | Phone/tablet Compose UI. Layout adapts by `WindowSizeClass`: compact = bottom navigation bar, expanded = navigation rail; grids and detail reflow to the wider width. |

Package base: `dev.bitstorm.sashimi`. Min SDK 26, target SDK 36, compile SDK 37.
Built with AGP 9.4.1, Gradle 9.8.1 and Kotlin 2.4.21.

## Building

The project ships a Gradle wrapper. You need a JDK 21 and the Android SDK (platform 37).

```bash
# Use Android Studio's bundled JBR (JDK 21):
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

# local.properties must contain sdk.dir (git-ignored); create it if missing:
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :core:test          # unit tests
./gradlew ktlintCheck         # lint
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease  # minified (R8) release APK
```

## Release signing

Release builds are signed from an **upload keystore** supplied via environment
variables (CI) or gradle properties (local). When none are present the release
build falls back to unsigned so `assembleRelease` still succeeds.

| Variable / property             | Meaning                          |
|---------------------------------|----------------------------------|
| `SASHIMI_UPLOAD_STORE_FILE`     | Path to the `.jks` keystore      |
| `SASHIMI_UPLOAD_STORE_PASSWORD` | Keystore password                |
| `SASHIMI_UPLOAD_KEY_ALIAS`      | Key alias (default `sashimi`)    |
| `SASHIMI_UPLOAD_KEY_PASSWORD`   | Key password (defaults to store) |

```bash
export SASHIMI_UPLOAD_STORE_FILE="$HOME/keystores/sashimi-android-upload.jks"
export SASHIMI_UPLOAD_STORE_PASSWORD="…"
./gradlew :app:bundleRelease :app:assembleRelease
```

The keystore and its password live outside the repo (e.g. `~/keystores/`) and are
never committed. In CI, provide the keystore as a base64 secret
(`SASHIMI_UPLOAD_KEYSTORE_BASE64`) plus the password/alias secrets.

## CI and releases

`.github/workflows/ci.yml` runs on every PR and push to `main`: JDK 21,
ktlint, `:core` and `:app` unit tests, `lintRelease`, `assembleDebug` and the
minified `assembleRelease`.

Pushing a `v*` tag runs the release job after the build: it builds a signed AAB
and APK, checks the APK signature, publishes a GitHub Release with the APK, and
deploys the AAB to the Play internal-testing track with fastlane
(`fastlane internal`). The Play step is skipped when the `PLAY_STORE_JSON_KEY`
secret is not set. Merging to `main` never releases; the tag does. To ship,
bump `versionCode` and `versionName` in `app/build.gradle.kts` in a PR, merge
it, then tag the merge commit.

## Acknowledgments

Sashimi is built on, and works with, other people's projects. Each one is a
separate project under its own license. Sashimi is not affiliated with or
endorsed by any of them.

**The server**

- [Jellyfin](https://jellyfin.org/) (the Jellyfin project, GPL-2.0). The free
  software media system Sashimi is a client for.

**Server add-ons Sashimi works with** (not made by Bitstorm Labs)

- [Intro Skipper](https://github.com/intro-skipper/intro-skipper) (by the Intro
  Skipper team, GPL-3.0). Detects intros and credits. Sashimi reads the
  segments it writes to Jellyfin and shows the Skip buttons.
- [Trailarr](https://github.com/nandyalu/trailarr) (by nandyalu, GPL-3.0).
  Downloads trailers next to your media. Sashimi plays them behind the detail page.

**Libraries**

- [Media3 and ExoPlayer](https://github.com/androidx/media) (Google, Apache-2.0).
  Playback.
- [Jetpack Compose, Room and WorkManager](https://developer.android.com/jetpack)
  (Google, Apache-2.0). UI, local storage and background downloads.
- [Kotlin](https://github.com/JetBrains/kotlin) and
  [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)
  (JetBrains, Apache-2.0).
- [Retrofit](https://github.com/lysine-dev/retrofit) and
  [OkHttp](https://github.com/lysine-dev/okhttp) (Square, Apache-2.0). Talking
  to the Jellyfin API.
- [Coil](https://github.com/coil-kt/coil) (coil-kt, Apache-2.0). Image loading.

The TMDb logo next to ratings belongs to its owner. The ratings themselves come
from your Jellyfin server.
