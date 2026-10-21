<p align="center"><img src=".github/assets/metube/icon.png" width="112" alt="MeTube logo"></p>

<h1 align="center">MeTube</h1>

**YouTube, the way it should feel.** No ads. No interruptions. A gorgeous liquid glass interface, synced lyrics, background play and one-tap downloads.

<details>
<summary><b>📸 Screenshots</b> (tap to open)</summary>
<br>
<p align="center">
  <img src=".github/assets/metube/home.jpg" width="160" alt="MeTube Home with topic chips and the floating glass tab bar">
  <img src=".github/assets/metube/search.jpg" width="160" alt="MeTube search results with YouTube's chips: All, Videos, Shorts, Live, Recently uploaded">
  <img src=".github/assets/metube/watch.jpg" width="160" alt="MeTube watch page with glass action pills">
  <img src=".github/assets/metube/shorts.jpg" width="160" alt="MeTube Shorts">
  <img src=".github/assets/metube/channel.jpg" width="160" alt="MeTube in Traditional Chinese: a Taiwanese channel page with its banner, Subscribe button and Shorts tab">
  <img src=".github/assets/metube/lyrics.jpg" width="160" alt="MeTube synced lyrics panel with provider picker">
  <img src=".github/assets/metube/downloads.jpg" width="160" alt="MeTube offline downloads">
</p>
</details>

<p align="center"><a href="https://github.com/yashoncode/metube/releases/latest"><b>⬇ Download the latest MeTube</b></a></p>

## Features

- 🚫 **Zero ads, zero sponsor segments.** Ads are gone, and SponsorBlock skips in-video sponsors, intros and "like and subscribe" reminders for you.
- 💎 **Liquid glass everywhere.** Frosted, lens-bending glass on the navigation bar, player buttons and Shorts, iOS 26 style, on Android.
- 🎤 **Synced lyrics.** Music videos get live, line-by-line lyrics that follow the song. Pick your favourite lyrics provider in one tap.
- 🎧 **Background play & picture-in-picture.** Lock your screen or switch apps, the music keeps going. Shorts float in their own portrait window, with previous and next.
- ⬇️ **Offline downloads up to 1080p.** Save videos to watch on a flight, a train or with no data.
- 📱 **Shorts done right.** A clean, full-screen Shorts player with glass like, comment and share buttons.
- 🧑‍🎤 **Channels at a glance.** Every card shows the channel's photo (both photos on a collab); tap it, or the channel name, to jump straight to the channel. Channel pages open with the banner, stats, description and a Subscribe button under a frosted glass bar, with a Shorts tab like YouTube's. Sort any channel's videos by Latest, Popular or Oldest.
- 🔎 **YouTube's search filters.** One-tap chips for All, Videos, Shorts, Live and Recently uploaded, plus the full filter dialog: type, duration, upload date, popularity and every feature filter.
- 🌏 **Speaks your language.** MeTube is in English, Español and 繁體中文 (Traditional Chinese), and channel pages, view counts and dates follow your language too. Want MeTube in yours? See [TRANSLATING.md](TRANSLATING.md).
- ⏩ **Your double-tap.** Double-tap to skip 3, 5, 10, 15 or 30 seconds, your pick.
- 🎨 **Make it yours.** One Customization page for the theme, the app icon (new, classic or fantasy), glass, Home layout, tabs and the video menu.
- 🔄 **Always something new.** Pull Home to refresh and get videos you haven't seen yet.
- 💬 **Smooth comments & fullscreen.** A rounded glass comment bar that rises with the keyboard, and buttery vertical fullscreen.
- 👎 **Dislikes are back.** Return YouTube Dislike shows the real count. DeArrow swaps clickbait titles and thumbnails.
- 🔐 **Your account, your subscriptions.** Sign in with a code, or use it without an account. Cast to your TV.

MeTube is a heavily tweaked fork of [NewTube](https://github.com/aleixrodriala/newtube) by [@aleixrodriala](https://github.com/aleixrodriala), reworked for performance, smoothness and a polished UI. NewTube is built on [SmartTube](https://github.com/yuliskov/SmartTube) by [@yuliskov](https://github.com/yuliskov). MeTube is an independent project and is not endorsed by either upstream developer.

## Download

Get the APK for your phone from [Releases](https://github.com/yashoncode/metube/releases/latest):

| Your device | File |
|:--|:--|
| Most phones (64-bit) | `MeTube_<version>_arm64-v8a.apk` |
| Older phones (32-bit) | `MeTube_<version>_armeabi-v7a.apk` |
| Not sure | `MeTube_<version>_universal.apk` |

Android 7.0 or later. MeTube has its own app id (`io.github.yashoncode.metube`), so it installs alongside NewTube or SmartTube.

## What MeTube adds on top of NewTube

- **Floating glass.** The bottom navigation is a frosted glass capsule floating over the page. It shows a live, saturated blur of the content behind it with fine grain and a bright top rim. On Android 13 and later the edge also bends the content like a lens, with slight colour fringing. On Android 7 to 11, which can't sample what's behind a view, it shows a solid tint instead. The code is in `smarttubetv/src/stmobile/java/com/newtube/mobile/ui/common/GlassView.java`.
- **New look.** A dark page (`#0B0B0E`), a red accent (`#FF453A`), large tab titles, a brand row with the actions in a glass pill, 20dp-rounded thumbnails, and glass action pills and cards on the watch page.
- **Dark theme by default.** Light theme is still available in Settings.
- **Lyrics.** Synced lyrics panel with a choice of providers.
- **Branding.** MeTube name, app id and APK names. The in-app updater checks this repository's releases.

## Build

```
./gradlew :smarttubetv:assembleStmobileDebug     # debug, for testing
./gradlew :smarttubetv:assembleStmobileRelease   # release, signed with keystore.properties if present
# APKs: smarttubetv/build/outputs/renamed_apks/stmobile<Type>/MeTube_<version>_<abi>.apk
```

Clone with `--recurse-submodules`. The backend lives in the `MediaServiceCore` and `SharedModules` submodules (NewTube's forks). Add `-PemulatorAbi` to run on an x86_64 emulator. For upstream documentation, see [NewTube's README](https://github.com/aleixrodriala/newtube#readme) and [CLAUDE.md](CLAUDE.md).

## Support MeTube

MeTube is free, has no ads and always will. I build it in my spare time because I love using it.
If it makes your YouTube a little nicer and you'd like to say thanks, you can buy me a coffee.
It's completely optional, and every coffee is truly appreciated. ☕

<p align="center"><a href="https://buymeacoffee.com/yashwynth"><img src="https://img.shields.io/badge/Buy%20me%20a%20coffee-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=000000" alt="Buy me a coffee"></a></p>

A star on GitHub or sharing MeTube with a friend helps just as much. Thank you for using it! ❤️

## License

MIT, see [LICENSE](LICENSE). The original NewTube and SmartTube copyright notices are kept. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
