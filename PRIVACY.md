# Privacy Policy for Sashimi

**Last updated: September 2026**

## Overview

Sashimi is an Android app for [Jellyfin](https://jellyfin.org) media servers. This policy explains exactly what Sashimi does with your data.

## Data Collection

**Sashimi does not collect, store, or transmit any personal data to the developer or to any third party.** There is no analytics, no telemetry, no crash reporting, and no advertising in this app.

### What Sashimi stores on your device

All of this stays in the app's private storage, which other apps cannot read:

- **Your server list**: each server's address, name, and your Jellyfin user name and user ID.
- **Your access token** for each server, kept in encrypted preferences whose key lives in the Android Keystore. Sashimi does **not** store your password; it is sent once, at sign-in, to your server.
- **Your settings**, such as playback and home screen preferences.
- **Recent searches**, so you can repeat them.
- **Downloads**: videos, subtitles, and artwork you choose to download, and the playback progress recorded while you watch them offline. These are saved in the app's own storage, never in shared storage.

Signing out removes that server's token from the device. Uninstalling the app removes everything above.

### Data transmission

Sashimi talks only to **your Jellyfin server**: the address you enter. Browsing, streaming, downloads, and playback progress go directly between your device and that server, and nowhere else.

If you connect over `http://` rather than `https://`, that traffic, including your password at sign-in, is unencrypted on your network. Use HTTPS if your server is reachable from outside your home.

### Permissions

- **Internet and network state**: to reach your server and to know when you are offline.
- **Notifications and foreground service (data sync)**: to show download progress while a download you started runs in the background.

### Third-party content

Artwork, ratings, and descriptions all come from your Jellyfin server. Sashimi makes **no network requests to any third party.**

## Your Jellyfin server

Your Jellyfin server has its own privacy practices, which Sashimi does not control. Refer to the Jellyfin documentation and your server administrator for how data is handled server-side.

## Children's privacy

Sashimi does not knowingly collect information from anyone, including children. Using the app requires a Jellyfin server, which is typically set up by an adult.

## Changes to this policy

This policy may be updated from time to time. Changes will be posted to this page.

## Contact

Questions or concerns: please open an issue on GitHub.

https://github.com/bitstorm-labs/sashimi-android/issues

## Open Source

Sashimi is open source. You can read exactly what it does:

https://github.com/bitstorm-labs/sashimi-android
