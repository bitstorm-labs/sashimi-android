# Sashimi for Android

<p align="center">
  <img src="sashimi-logo.png" alt="Sashimi Logo" width="160">
</p>

<p align="center">
  <strong>A native Jellyfin client for Android phones and tablets</strong>
</p>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#get-more-out-of-sashimi">Add-ons</a> •
  <a href="#requirements">Requirements</a> •
  <a href="#installation">Installation</a> •
  <a href="#development">Development</a> •
  <a href="#contributing">Contributing</a>
</p>

---

Sashimi is a Kotlin and Jetpack Compose (Material 3) client for
[Jellyfin](https://jellyfin.org/) media servers. It talks only to the Jellyfin
server you point it at. There is no Sashimi account, no telemetry and no
third-party network traffic.

It is the Android member of the Sashimi family. Sibling clients for
[Apple TV / iPhone / iPad / Mac](https://github.com/bitstorm-labs/sashimi-apple)
and [Roku](https://github.com/bitstorm-labs/sashimi-roku) live in their own
repositories. The shared `:core` module has no UI code so a TV layout could
reuse it, but **no Android TV layout ships yet**: the app does not declare
Leanback and the UI is touch-first.

## Screenshots

No screenshots yet.

## Features

### Browsing

- **Home.** **Continue Watching** plus a **Recently Added** row per library.
  Reorder or hide rows in *Settings → Home rows*, with drag-to-reorder.
  Continue Watching cards show remaining time, progress and S#:E#; Recently
  Added rows dedupe by series, show "X new", watched and quality badges, a See
  All grid past six items, and circular YouTube-channel styling.
  Pull-to-refresh.
- **Libraries.** Adaptive poster grid with sort (Name, Date Added, Release
  Date, Rating, Runtime — each with direction), filters (All, Unwatched,
  Watched, Favorites), shuffle, and in-library search.
- **Search** across your libraries, with a debounced query, result counts and
  year/type captions, and your last ten searches as chips with a Clear button.
- **Quality and rating badges** on artwork.

### Detail screens

- **Play**, **Resume** (with **Start Over**) and **Shuffle** for series, plus a
  **Trailer** button. One adaptive screen: stacked on phones, two columns on
  tablets.
- **Trailer button and hero trailer.** Appears when the title has a local
  trailer on the server, for example one downloaded by
  [Trailarr](https://github.com/nandyalu/trailarr). The trailer plays behind
  the detail page. Turn it off with *Settings → Play trailers on detail
  screens*; it never plays on mobile data.
- **Theme songs.** A show's theme music plays while you browse its page. Turn
  it off with *Settings → Play theme songs*. It stays quiet when other audio is
  playing.
- **Seasons and episodes.** Season tabs and an episode list, with next-up
  logic for series, cast, ratings and media badges.
- **Mark Watched** with an optimistic toggle, and the Favorite toggle.
- **Downloads.** Single downloads and bulk-season downloads with a quality
  picker. See [Offline](#offline).

### Playback

- **Direct play first.** PlaybackInfo negotiation goes direct play → direct
  stream → HLS transcode, with an Android DeviceProfile. 4K Dolby Vision /
  HDR10 HEVC is remuxed instead of re-encoded where the device allows.
- **Adaptive Auto quality.** Auto starts from the link you are on (Ethernet,
  Wi-Fi or metered mobile data) and steps up when throughput allows. It never
  steps up on a metered connection. A **quality menu** re-negotiates from your
  current position, and an OSD chip shows the streams in force.
- **Audio and subtitle tracks.** External VTT subtitles are side-loaded and
  rendered by the app.
- **Skip Intro / Skip Credits** pill with auto-skip (needs
  [Intro Skipper](https://github.com/intro-skipper/intro-skipper)).
- **Up Next.** A full-screen card at the end of an episode counts down to the
  next one, with auto-play and season rollover.
- **Resume threshold** and progress reporting to Jellyfin, so resume works
  across all your clients.
- **Picture-in-Picture**, and playback speed from 0.5× to 2×.

### Offline

Download movies and episodes and watch them without a connection:

- A download queue with a progress notification, and a downloads screen with
  active / completed / failed sections, retry, delete-all and a storage bar.
- Quality tiers (Original / High / Medium / Low); Original fails closed when
  the device cannot play the file.
- Subtitles are downloaded too, and side-loaded on offline playback.
- An offline Home and offline detail and playback, drawn from a local store,
  with position save and sync-on-reconnect.

### Server dashboard

- **Server Dashboard** (under Settings, Jellyfin administrators only):
  now-playing cards for every active stream with **Stop** and **Send a
  message** (shown on the viewer's screen), an Activity log, and a Health
  screen with library scan status, hardware transcoding, failed scheduled
  tasks and storage.
- Sashimi itself accepts Stop and message commands from the Jellyfin
  dashboard.

### Connecting

- **Multi-server.** Two-step connect (server URL → credentials), a servers
  list with switch / remove / add, per-server tokens, and prefilled re-auth
  when a session expires.
- **Deep links.** `sashimi://item/{id}` opens a detail screen and
  `sashimi://play/{id}` starts playback, including after sign-in on a cold
  start.

## Get more out of Sashimi

Sashimi works against a plain Jellyfin server. These server add-ons make it
better. None are required. The first three are made by Bitstorm Labs. The last
two are separate projects by other people; see
[Acknowledgments](#acknowledgments).

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

## Requirements

- An Android phone or tablet on Android 8.0 or newer (min SDK 26).
- A Jellyfin server, version 10.10 or newer.
- To build it yourself: JDK 21 and the Android SDK (platform 37).

## Installation

### Play internal-testing track

Release builds are deployed to the Play internal-testing track. Join the
testing program and install from the Play Store.

### GitHub Releases

Download the signed APK from the
[latest release](https://github.com/bitstorm-labs/sashimi-android/releases) and
install it directly. Android will ask you to allow installs from your browser
or file manager the first time.

## Development

### Module layout

| Module   | Contents |
|----------|----------|
| `:core`  | Jellyfin API client (`JellyfinClient`), models, multi-server `SessionManager`, playback negotiation, downloads engine (Room + WorkManager + OkHttp), settings, and the deep-link resolver. **Zero Compose/UI imports** — the TV-readiness discipline: a future `:tv` module reuses `:core` wholesale. |
| `:app`   | Phone/tablet Compose UI. Layout adapts by `WindowSizeClass`: compact = bottom navigation bar, expanded = navigation rail; grids and detail reflow to the wider width. |

Package base: `dev.bitstorm.sashimi`. Min SDK 26, target SDK 36, compile SDK
37. Built with AGP 9.4.1, Gradle 9.8.1 and Kotlin 2.4.21.

### Build Commands

The project ships a Gradle wrapper. You need a JDK 21 and the Android SDK
(platform 37).

```bash
# Use Android Studio's bundled JBR (JDK 21):
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

# local.properties must contain sdk.dir (git-ignored); create it if missing:
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :core:test            # unit tests
./gradlew ktlintCheck           # lint
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease  # minified (R8) release APK
```

### Release signing

Release builds are signed from an **upload keystore** supplied via environment
variables (CI) or Gradle properties (local). When none are present the release
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

The keystore and its passwords live outside the repo (for example in
`~/keystores/`) and are never committed. In CI, provide the keystore as a
base64 secret (`SASHIMI_UPLOAD_KEYSTORE_BASE64`) plus the password and alias
secrets.

### CI and releases

`.github/workflows/ci.yml` runs on every PR and push to `main`: JDK 21,
ktlint, `:core` and `:app` unit tests, `lintRelease`, `assembleDebug` and the
minified `assembleRelease`.

Pushing a `v*` tag runs the release job after the build: it builds a signed
AAB and APK, checks the APK signature, publishes a GitHub Release with the
APK, and deploys the AAB to the Play internal-testing track with fastlane
(`fastlane internal`). The Play step is skipped when the `PLAY_STORE_JSON_KEY`
secret is not set. Merging to `main` never releases; the tag does. To ship,
bump `versionCode` and `versionName` in `app/build.gradle.kts` in a PR, merge
it, then tag the merge commit.

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feat/my-feature`
3. Commit using conventional commits: `git commit -m "feat: add new feature"`
4. Push to your fork: `git push origin feat/my-feature`
5. Open a Pull Request

This project uses [Conventional Commits](https://www.conventionalcommits.org/):
`feat:`, `fix:`, `docs:`, `style:`, `refactor:`, `test:` and `chore:`. If you
plan something large, open an issue first.

## Security and privacy

Sashimi collects nothing: no analytics, telemetry, crash reporting or ads.
Your access token for each server is kept in encrypted preferences whose key
lives in the Android Keystore. Your password is never stored; it is sent once,
at sign-in, to your server.

- **[PRIVACY.md](PRIVACY.md)**: what is stored and what leaves the device.

## License

This project is licensed under the MIT License. See the
[LICENSE](LICENSE) file for details.

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
  Downloads trailers next to your media. Sashimi plays them behind the detail
  page.

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
