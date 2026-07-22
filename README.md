# Sashimi for Android

A native Kotlin / Jetpack Compose (Material 3) client for [Jellyfin](https://jellyfin.org),
the Android sibling of the Sashimi iOS/tvOS apps. Phones and tablets first, with a
TV-ready architecture.

This repo is at **Milestone 2** (Browse). Playback and downloads land in later
milestones — see the spec.

## Features

- **Auth / multi-server** (M1): two-step connect (URL → credentials), servers
  list with switch / remove / add, per-server tokens, prefilled re-auth on
  expiry, 401-only-on-active-server session handling.
- **Home** (M2): configurable rows (Continue Watching + per-library Recently
  Added; order + visibility persisted). Continue Watching cards show remaining
  time, progress, and S#:E#; Recently Added rows dedupe by series, cap at 20,
  show "X new" / watched / quality badges (quality gated by the
  `showQualityBadges` setting), a See All grid past 6 items, and circular
  YouTube-channel styling. Pull-to-refresh; logo opens the server switcher.
- **Libraries** (M2): library list → adaptive poster grid with sort
  (Name / Date Added / Release Date / Rating / Runtime ± direction), filter
  (All / Unwatched / Watched / Favorites), shuffle, and in-library client-side
  search over eagerly-loaded pages, plus empty states.
- **Search** (M2): poster-grid results with a count and year/type captions,
  300 ms-debounced query, and recent-search chips (last 10, committed 1.5 s
  after a settled query with results; Clear).
- **Detail** (M2): one adaptive screen (compact = stacked, expanded = tablet
  two-column) with Play / Resume / Start Over / Trailer (M3 playback stubs),
  Shuffle (random episode), optimistic watched toggle, favorite + admin menu
  (File Info / Refresh Metadata / Delete), season tabs + episode list with
  current-episode highlight and progress, cast (from the refreshed full item),
  ratings, media badges, genres/cert, "Ends at", and overview expand/collapse.
- **Navigation** (M2): type-safe routes; detail pushes from every surface;
  `sashimi://item/{id}` and `sashimi://play/{id}` deep links (play → detail
  until M3).

## Module layout

| Module   | Contents |
|----------|----------|
| `:core`  | Jellyfin API client (`JellyfinClient`), models, and the multi-server `SessionManager`. **Zero Compose/UI imports** — this is the TV-readiness discipline: a future `:tv` module reuses `:core` wholesale. Retrofit + OkHttp + kotlinx.serialization; tokens in EncryptedSharedPreferences. |
| `:app`   | Phone/tablet Compose UI. Layout adapts by `WindowSizeClass`: compact = bottom navigation bar, expanded = navigation rail. Theme, auth flow, app shell, settings. |

Package base: `dev.bitstorm.sashimi` (application id `dev.bitstorm.sashimi`).
Min SDK 26, compile/target SDK 36.

## Building

No system Gradle or JDK is required — the project ships a Gradle wrapper. You do
need a JDK 21 and the Android SDK.

```bash
# Android Studio bundles a JDK 21; point JAVA_HOME at it (or any JDK 21):
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

# local.properties must contain sdk.dir (git-ignored); create it if missing:
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :core:test          # unit tests (URL normalization, session logic, model decoding)
./gradlew ktlintCheck         # lint
./gradlew :app:assembleDebug  # produces app/build/outputs/apk/debug/app-debug.apk
```

## CI

`.github/workflows/ci.yml` runs on every PR and push to `main`: JDK 21 (temurin),
ktlint, `:core` unit tests, and an `assembleDebug` APK uploaded as an artifact.

## Spec

The full design spec and milestone plan live in
`~/Documents/git/plans/sashimi-android/spec.md`.
