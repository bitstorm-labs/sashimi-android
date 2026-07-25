# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## Project Overview

Sashimi for Android — a Jellyfin client in Kotlin/Compose, `minSdk 26` / `targetSdk 36`.
Sibling clients: `sashimi-apple` (tvOS/iOS) and `sashimi-roku`. Behaviour is
deliberately ported between them, so a bug found in one is worth looking for in
the other two — several have been found in all three.

## Modules

- **`:core`** — networking (`JellyfinClient`, Retrofit + OkHttp), session/token
  storage, playback negotiation (`PlaybackEngine`, `DeviceProfile`), downloads
  (`DownloadManager`, Room). Pure Kotlin/Android-library, no Compose. **All 234
  tests live here.**
- **`:app`** — Compose UI, ViewModels, navigation, the Media3 player.
  **Has no test source set at all** — `app/src/` contains only `main/`.

`:core` must stay TV-ready: no phone-only assumptions, since a TV client would
reuse it.

## Build Commands

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME=~/Library/Android/sdk

./gradlew ktlintFormat            # autofix formatting
./gradlew ktlintCheck             # CI runs this
./gradlew :core:test              # the whole test suite
./gradlew :app:lintRelease        # CI runs this — see below
./gradlew :app:assembleRelease    # minified (R8) — CI runs this
./gradlew :app:bundleRelease      # the AAB Play actually ships
```

`JAVA_HOME` must be Android Studio's bundled JBR; the system JDK will not do.

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
`LessThanOrEqual` condition. A "720p" option that only lowers the bitrate
delivers 1080p at a lower bitrate. Also, `AllowVideoStreamCopy: true` lets the
server satisfy a requested transcode by remuxing untouched, so a quality change
can be a literal no-op. (Open: #19.)

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
  publishes a GitHub Release, deploys to the Play internal track via fastlane.

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
