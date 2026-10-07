# MeTube

A YouTube client for Android phones with a "floating glass" interface: frosted, iOS-style glass bars over a dark page, large titles and rounded cards.

MeTube is a fork of [NewTube](https://github.com/aleixrodriala/newtube) by [@aleixrodriala](https://github.com/aleixrodriala), which is built on [SmartTube](https://github.com/yuliskov/SmartTube) by [@yuliskov](https://github.com/yuliskov). Under the hood it is NewTube unchanged: the same YouTube engine, account sign-in with a code, background playback, picture-in-picture, offline downloads, SponsorBlock, DeArrow, Return YouTube Dislike and casting. MeTube is an independent project and is not endorsed by either upstream developer.

## Download

Get the APK for your phone from [Releases](https://github.com/yashoncode/metube/releases/latest):

| Your device | File |
|:--|:--|
| Most phones (64-bit) | `MeTube_<version>_arm64-v8a.apk` |
| Older phones (32-bit) | `MeTube_<version>_armeabi-v7a.apk` |
| Not sure | `MeTube_<version>_universal.apk` |

Android 7.0 or later. MeTube has its own app id (`io.github.yashoncode.metube`), so it installs alongside NewTube or SmartTube.

## What MeTube changes

- **Floating glass.** The bottom navigation is a frosted glass capsule floating over the page. It shows a live, saturated blur of the content behind it with fine grain and a bright top rim. On Android 13 and later the edge also bends the content like a lens, with slight colour fringing. On Android 7 to 11, which can't sample what's behind a view, it shows a solid tint instead. The code is in `smarttubetv/src/stmobile/java/com/newtube/mobile/ui/common/GlassView.java`.
- **New look.** A dark page (`#0B0B0E`), a red accent (`#FF453A`), large tab titles, a brand row with the actions in a glass pill, 20dp-rounded thumbnails, and glass action pills and cards on the watch page.
- **Dark theme by default.** Light theme is still available in Settings.
- **Branding.** MeTube name, app id and APK names. The in-app updater checks this repository's releases.

## Build

```
./gradlew :smarttubetv:assembleStmobileDebug     # debug, for testing
./gradlew :smarttubetv:assembleStmobileRelease   # release, signed with keystore.properties if present
# APKs: smarttubetv/build/outputs/renamed_apks/stmobile<Type>/MeTube_<version>_<abi>.apk
```

Clone with `--recurse-submodules`. The backend lives in the `MediaServiceCore` and `SharedModules` submodules (NewTube's forks). Add `-PemulatorAbi` to run on an x86_64 emulator. For upstream documentation, see [README.newtube.md](README.newtube.md) and [CLAUDE.md](CLAUDE.md).

## License

MIT, see [LICENSE](LICENSE). The original NewTube and SmartTube copyright notices are kept. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
