# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## Project Overview

Sashimi for Android — a Jellyfin client in Kotlin/Compose, `minSdk 26` / `targetSdk 36` / `compileSdk 37`
(AGP 9.4.1, Gradle 9.8.1, Kotlin 2.4.21).
Sibling clients: `sashimi-apple` (tvOS/iOS) and `sashimi-roku`. Behaviour is
deliberately ported between them, so a bug found in one is worth looking for in
the other two — several have been found in all three.

## Modules

- **`:core`** — networking (`JellyfinClient`, Retrofit + OkHttp), session/token
  storage, playback negotiation (`PlaybackEngine`, `DeviceProfile`), downloads
  (`DownloadManager`, Room). Pure Kotlin/Android-library, no Compose. **Almost all
  tests live here.**
- **`:app`** — Compose UI, ViewModels, navigation, the Media3 player and its
  MediaSession/PiP glue. A small JVM test set lives in `app/src/test/` for pure
  logic pulled out of the player (`NowPlaying`, `PipActions`); keep anything
  touching `android.net.Uri` or Media3 builders out of it, since unit tests run
  against the stubbed android.jar.

`:core` must stay TV-ready: no phone-only assumptions, since a TV client would
reuse it. No Android TV layout ships today (no Leanback declaration, touch-first
UI), so do not describe the app as running on TV.

## Build Commands

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME=~/Library/Android/sdk

./gradlew ktlintFormat            # autofix formatting
./gradlew ktlintCheck             # CI runs this
./gradlew :core:test :app:testDebugUnitTest   # the whole test suite
./gradlew :app:lintRelease        # CI runs this — see below
./gradlew :app:assembleRelease    # minified (R8) — CI runs this
./gradlew :app:bundleRelease      # the AAB Play actually ships
```

`JAVA_HOME` must be Android Studio's bundled JBR; the system JDK will not do.
Docs-only changes need no build, but CI still runs on the PR.

## Things that have actually bitten us

### Verify the merged manifest, not the source manifest

`app/src/main/AndroidManifest.xml` is only an input. What the platform reads is
the merged output, and for anything merged onto a *library's* component the two
differ. Check the real thing:

```bash
AAPT=$(ls $ANDROID_HOME/build-tools/*/aapt2 | tail -1)
$AAPT dump xmltree app/build/outputs/apk/release/app-release*.apk \
  --file AndroidManifest.xml | grep -A6 SystemForegroundService
```

Note there are four merged manifests under `app/build/intermediates/`; the
`bundle_manifest` one is what Play ships.

### XML comments cannot contain `--`

Two separate build failures in one session came from writing an em-dash-style
`--` inside an XML comment. `ManifestMerger2$MergeFailureException: Error
parsing …` and `Resource compilation failed … XMLStreamException` both mean
this. Use a colon or "and" instead. Quick check:

```bash
python3 -c "import xml.dom.minidom as m,glob;[m.parse(f) for f in
  glob.glob('app/src/main/res/xml/*.xml')+['app/src/main/AndroidManifest.xml']]"
```

### WorkManager foreground services

`DownloadWorker` returns `ForegroundInfo(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)`,
but the `startForeground()` call happens inside **WorkManager's own**
`SystemForegroundService`. Consequences:

- The manifest must declare the type via `tools:node="merge"` onto that service,
  not onto one of ours.
- `runCatching` around `setForeground()` cannot catch the failure — it is thrown
  in another component.
- Android Lint's `ForegroundServiceType` check **cannot see this**; it only
  inspects `startForeground()` in your own `Service` subclasses.

Missing it crashes every download on Android 14+ with
`IllegalArgumentException: foregroundServiceType 0x… is not a subset of …0x0`.

### Jellyfin: `MaxStreamingBitrate` is a ceiling, not a resolution

Capping resolution requires a device-profile `CodecProfile` with a `Width`
`LessThanEqual` condition. Spell it exactly that way: Jellyfin's
`ProfileConditionType` has no `LessThanOrEqual`, and an unknown enum name makes
the whole PlaybackInfo POST fail with 400 (fixed in #94). A "720p" option that
only lowers the bitrate delivers 1080p at a lower bitrate. Also,
`AllowVideoStreamCopy: true` lets the server satisfy a requested transcode by
remuxing untouched, so a quality change can be a literal no-op. (Open: #19.)

### Jellyfin: HDR stream copy brings same-bandwidth SDR fallback variants

The HLS transcoding profile lists `h264,hevc` (h264 first: that is what the
server encodes to) so an HEVC source can be copied when only its audio or
container needs converting. For an HDR copy, Jellyfin's master playlist then
adds `AllowVideoStreamCopy=false` SDR re-encode variants at the same
bandwidth; a variant switch restarts ffmpeg and stalls. `PlaybackEngine` pins
the primary variant's `main.m3u8` (`HlsMultivariant`, #155). Check the master's
variant list first for any HLS/HDR playback bug.

### Kotlin `by` delegation skips Java default methods

`class X(d: LoadControl) : LoadControl by d` forwards only the abstract
members; every Java `default` method (`shouldStartPlayback`, `onPrepared`,
...) runs the interface stub. Subclass the Media3 default instead, or
override each default explicitly and check with `javap`.

### Jellyfin: never leave `SubtitleStreamIndex` null

A null lets the server apply the user's own subtitle preference, and if that
picks an image track (PGS) it is burned into the transcode while the app shows
"Off". `PlaybackEngine` always sends `-1` unless the user picked an image track
on a transcode.

### Jellyfin: a transcode timeline starts at zero

When the server transcodes with `startTimeTicks`, the returned HLS timeline is
0-based **at that offset**. So `player.currentPosition` is relative, and every
consumer that treats it as absolute — progress reporting, re-negotiation,
intro/credit segments, the scrubber — is wrong by the resume offset. (Open: #18.)

### Lifecycle: the player is not released by backgrounding

The ExoPlayer is owned by `PlayerViewModel` and released in `onCleared()`, which
back-navigation triggers and backgrounding does not. Anything that must happen
when the app leaves the foreground needs an explicit `LifecycleEventObserver`.
Use `ON_STOP`, not `ON_PAUSE`: a PiP window keeps the activity STARTED.

### Edge-to-edge is mandatory at targetSdk 36

`MainActivity` calls `enableEdgeToEdge()`, and the player route deliberately
bypasses `MainScreen`'s `Scaffold` — so it receives no `innerPadding` and must
apply its own insets. Keep full-bleed backgrounds full-bleed; inset only the
controls.

### Silent-failure smell

This codebase leans hard on `runCatching{}.getOrNull()` / `.getOrDefault(...)`.
That has repeatedly converted a real error into wrong data rather than a visible
failure — a swallowed `NoSuchMethodError` showed version "?" in Settings, and
Home still renders "Start watching something to see it here" when every server
call fails (#30). When adding one, ask what the user sees when it fires.

There is no logging anywhere in the app (no `Log.*`, no `println`, no
`HttpLoggingInterceptor`) and no crash-reporting SDK. So a swallowed exception is
genuinely invisible — there is no second chance to notice it.

## Fix what you find

If you come across a problem while working (a missing test, a red or flaky CI job, a
bug, dead code, a broken workflow), fix it as part of the work. Don't just mention
it in a summary and move on. If the fix doesn't belong in the current PR, open a
follow-up issue and do it next. Only hand something back when it truly needs the
user: credentials, a paid account, a product decision, or physical hardware. Say
exactly what's needed.

Behaviour that depends on server responses is testable without a server:
`JellyfinClient` takes an `OkHttpClient`, so give it an interceptor that answers
from a table (see `core/src/test/.../home/ContinueWatchingSourceTest.kt`). Put
the logic under test in `:core`, not in a ViewModel.

## Git Workflow & CI

### Issue first

Create a GitHub issue before starting work, then branch and PR referencing it.
GitHub only auto-closes the **first** issue in a comma-separated `Closes` list —
close the rest by hand.

### Branch protection

`main` is protected; all changes go through a PR. Required checks:
`Build, lint & test`.

### CI shape

- `build` — ktlint, `:core:test`, `lintRelease`, `assembleDebug`, `assembleRelease`
- `release` — tags only (`v*`), `needs: build`. Builds the AAB + signed APK,
  publishes a GitHub Release, deploys to the Play internal track via fastlane
  (skipped if `PLAY_STORE_JSON_KEY` is unset). A release is a version-bump PR
  (`versionCode` + `versionName` in `app/build.gradle.kts`), then a tag on the
  merge commit.

Lint and the minified release build are both in CI on purpose: `assembleDebug`
can never catch an R8 keep-rule problem, and kotlinx.serialization, Retrofit,
Room and Media3 all depend on keep rules.

**The merge is not the ship gate — the tag is.** A merge is recoverable; a build
on the Play internal track is less so.

### Verification honesty

State in the PR what was and was not verified. "Builds and tests pass" is a
different claim from "exercised on a device". Anything only judgeable on real
hardware — playback, focus, PiP, downloads, offline behaviour — should say so.

## Testing on a real device

```bash
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- A locally built debug APK **will not** install over a release build signed with
  the upload key (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Uninstalling to force it
  destroys the user's session and downloads — do not do that without asking.
- On a foldable, `adb exec-out screencap -p` prepends a `[Warning] Multiple
  displays…` line that corrupts the PNG. Strip everything before the `\x89PNG`
  magic.
- Downloads need the notification permission; on a fresh install the runtime
  prompt appears and silently gates the worker until answered.
