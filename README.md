<h1 align="center">MeeTube TV</h1>

<p align="center"><b>MeeTube for Android TV and Google TV, with its own glass design and tuning.</b></p>

This is the **tv** branch of [MeeTube](https://github.com/yashoncode/meetube): the MeeTube TV app. The phone app, the README with downloads and the issue tracker are on the [main branch](https://github.com/yashoncode/meetube).

- **Download:** [MeeTube TV 1.0.0](https://github.com/yashoncode/meetube/releases/tag/tv-v1.0.0) (armeabi-v7a for most TVs, arm64-v8a for newer 64-bit boxes, universal if not sure).
- **What's new:** [CHANGELOG.md](CHANGELOG.md).
- **Bugs and ideas:** [open an issue](https://github.com/yashoncode/meetube/issues/new/choose) and pick MeeTube TV.

## Build

JDK 17, then:

```
git clone -b tv --recurse-submodules https://github.com/yashoncode/meetube.git meetube-tv
./gradlew :smarttubetv:assembleStstableRelease   # signed with keystore.properties if present
# APKs: smarttubetv/build/outputs/apk/ststable/release/MeeTubeTV_<version>_<abi>.apk
```

Releases are made by pushing a `tv-vX.Y.Z` tag (`tv-vX.Y.Z-beta` for a beta) on this branch:
[release-tv.yml](.github/workflows/release-tv.yml) builds the signed APKs and publishes the release.
A stable release commit also bumps [meetube_tv.json](meetube_tv.json), which the app's in-app updater reads.

## Credits

MeeTube TV started from a clone of [SmartTube](https://github.com/yuliskov/SmartTube) by [@yuliskov](https://github.com/yuliskov)
(master `b8fbe00`, 2026-11-03) and is tuned its own way. This branch's history starts there; SmartTube's later changes
come in as a diff from that commit. MeeTube TV is an independent project and is not endorsed by SmartTube's developer.
It is not affiliated with, endorsed by, or connected to YouTube or Google in any way.

MIT, see [LICENSE](LICENSE) (SmartTube's copyright notice is kept).
