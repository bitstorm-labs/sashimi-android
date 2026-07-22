# Sashimi for Android

A native Kotlin / Jetpack Compose (Material 3) client for [Jellyfin](https://jellyfin.org),
the Android sibling of the Sashimi iOS/tvOS apps. Phones and tablets first, with a
TV-ready architecture. Full iPhone-parity feature set, including downloads and
offline playback.

**Status:** Milestone 5 — parity sweep + release prep. Version `0.5.0`, the first
full APK.

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
  local-first Trailer, optimistic watched toggle, favorite + admin menu (File Info
  / Refresh Metadata / Delete — hidden when offline), season tabs + episode list,
  cast, ratings, media badges, and single + bulk-season downloads with a quality
  picker.
- **Player (Media3/ExoPlayer):** PlaybackInfo negotiation (direct play → direct
  stream → HLS transcode) with an Android DeviceProfile, OSD stream-info chip,
  quality menu (re-negotiates preserving position), audio + subtitle track
  selection (external VTT side-loaded and app-rendered), speed 0.5–2×, skip
  intro/credits with auto-skip, auto-play next with season rollover, resume
  threshold, progress reporting, and Picture-in-Picture.
- **Downloads / offline:** WorkManager queue with a foreground-service progress
  notification, quality tiers (Original / High / Medium / Low), Original
  fail-closed on device compatibility, a downloads screen (active / completed /
  failed / retry / delete-all + storage bar), subtitle downloads (side-loaded on
  offline playback), an offline Home + offline detail/playback drawn from a Room
  store, with local position save and sync-on-reconnect.
- **Settings:** servers, Home row order editor, quality-badge toggle, playback
  toggles, max bitrate, resume threshold, audio/subtitle languages, downloads
  storage + delete-all, about (version/build), and sign out with confirmation.
- **Deep links:** `sashimi://item/{id}` → detail and `sashimi://play/{id}` →
  player, with cold-start stash-until-authenticated (a launch link opens after
  sign-in).

## Screenshots

_Phone and tablet screenshots to be added ahead of the Play internal-testing
release._

<!-- TODO: add screenshots to docs/screenshots/ and embed here. -->

## Module layout

| Module   | Contents |
|----------|----------|
| `:core`  | Jellyfin API client (`JellyfinClient`), models, multi-server `SessionManager`, playback negotiation, downloads engine (Room + WorkManager + OkHttp), settings, and the deep-link resolver. **Zero Compose/UI imports** — the TV-readiness discipline: a future `:tv` module reuses `:core` wholesale. |
| `:app`   | Phone/tablet Compose UI. Layout adapts by `WindowSizeClass`: compact = bottom navigation bar, expanded = navigation rail; grids and detail reflow to the wider width. |

Package base: `dev.bitstorm.sashimi`. Min SDK 26, compile/target SDK 36.

## Building

The project ships a Gradle wrapper. You need a JDK 21 and the Android SDK.

```bash
# Android Studio bundles a JDK 21; point JAVA_HOME at it (or any JDK 21):
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

## CI

`.github/workflows/ci.yml` runs on every PR and push to `main`: JDK 21 (temurin),
ktlint, `:core` unit tests, and an `assembleDebug` APK artifact. On `v*` tags a
release job additionally builds a signed AAB + APK (signing secrets
optional-guarded).

## Spec

The full design spec and milestone plan live in
`~/Documents/git/plans/sashimi-android/spec.md`.
