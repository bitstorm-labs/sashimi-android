# Sashimi for Android

A native Kotlin / Jetpack Compose (Material 3) client for [Jellyfin](https://jellyfin.org),
the Android sibling of the Sashimi iOS/tvOS apps. Phones and tablets first, with a
TV-ready architecture.

This repo is at **Milestone 1** (scaffold + auth): API client core, multi-server
session manager, two-step connect/login flow, and an empty tabbed shell. Browse,
playback, and downloads land in later milestones — see the spec.

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
