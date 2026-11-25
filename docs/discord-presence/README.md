# Discord status (add-on)

MeeTube can show what you are watching, and MeeTune what you are listening to, as your Discord
status: "Watching MeeTube" or "Listening to MeeTune", with the title, the channel or artist, the
thumbnail, a live progress bar and a button that opens the video. It is an optional add-on: the
code that talks to Discord is downloaded only when someone turns it on, and the APK carries none
of it.

This page is the study of BitChord's implementation it reproduces, the plan, and the security
notes. BitChord commit studied: `21356206` (2026-11-21).

## 1. How BitChord does it

| Part | BitChord | Files |
|---|---|---|
| Sign in | A WebView opens `discord.com/login`; the real page handles password, 2FA and passkeys. When the URL leaves `/login`, a script adds a fresh iframe, reads `localStorage.token` from it (Discord removes `localStorage` from the top window after boot) and hands it out through `alert()`, caught in `onJsAlert`. 12 tries, 500 ms apart. The WebView user agent loses `; wv` and `Version/4.0`, or Discord serves a blank page. | `auth/DiscordLoginScreen.kt` |
| Token storage | `EncryptedSharedPreferences` (Keystore wrapped), recreated when a restore leaves an unreadable keyset. | `auth/EncryptedPrefs.kt`, `auth/AuthStore.kt` |
| Talking to Discord | The user gateway, `wss://gateway.discord.gg/?v=9&encoding=json`, logged in with the user's own token and identifying as the Discord Android app (`os=Android`, `browser=Discord Android`, `X-Super-Properties` of client 314.13, user agent `Discord-Android/314013;RNA`). Heartbeats with an ACK watchdog (an unanswered heartbeat drops the socket), RESUME on the `resume_gateway_url`, reconnect backoff 1 s to 60 s, and the last presence replayed on every READY/RESUMED. | `shared/.../com/my/kizzy/gateway/DiscordWebSocket.kt`, `.../entities/*` (a modified copy of Kizzy) |
| Publishing | Opcode 3 PRESENCE_UPDATE with one activity: `name`, `type` (2 Listening, 3 Watching), `details` (title), `state` (artist), `timestamps.start/end`, `assets.large_image`, `buttons` + `metadata.button_urls`, `application_id`, `status_display_type`. Clearing is an activity-less presence. | `.../rpc/KizzyRPC.kt` |
| Artwork | `POST /api/v9/applications/{app id}/external-assets {"urls":[...]}` with the user token returns a Discord media proxy path, sent as `mp:<path>`. Needs a Discord application id (BitChord registered its own). | `.../rpc/ExternalAssets.kt`, `DiscordApplication.kt` |
| Playback | In `PlaybackService`: play and track change push; pause clears (Discord's bar runs on its own clock, so a paused card would keep counting); a seek pushes new timestamps; speed is divided out of both timestamps and shown as `[1.25x]`; a network change wakes the socket; service death clears and closes. | `playback/PlaybackService.kt` (`pushDiscordPresence`, `clearDiscordPresence`, `observeDiscord`) |
| Dependencies | Ktor client + WebSockets, kotlinx.serialization, androidx.security-crypto, Compose for the login screen. | `shared/build.gradle.kts`, `app/build.gradle.kts` |

The timestamps are absolute instants: start = now minus position/speed, end = start plus
duration/speed. A card published once stays right until something changes, so updates happen
only on play, pause, seek, speed change and track change, never on a timer.

## 2. What MeeTube keeps, and where it differs

Same user-facing behaviour, same mechanism (user token from a WebView login, user gateway as
Discord Android, external-assets for art, the same opcodes and fields). Differences, each forced
by something in this codebase:

1. **No Kizzy code is copied.** Kizzy's files in BitChord carry "can not be copied and/or
   distributed without the express permission of yzziK(Vaibhav)", which does not sit with
   GPL redistribution. The gateway client is written fresh for MeeTube against Discord's
   documented gateway protocol, with the same behaviour (watchdog, resume, backoff, replay).
2. **OkHttp instead of Ktor, org.json instead of kotlinx.serialization.** MeeTube already ships
   OkHttp (which has WebSockets) and Android has org.json, so the add-on brings no library of its
   own and stays a few tens of KB. Bundling Ktor in a downloaded dex would also clash with
   classes the app already loads.
3. **Java, not Kotlin.** The app module is Java, and a Kotlin add-on would need its own stdlib
   loaded next to the app's.
4. **A downloaded dex instead of an app module.** BitChord compiles Discord into the APK. Here
   the client lives in `discord-addon/`, is built to one `classes.dex`, and is published as the
   release asset `discord-status-addon-v1/discord-presence-1.dex`. The app pins its size and SHA-256, checks
   both on download and again before every load, and loads it with `DexClassLoader`, the way
   Karaoke loads its runtime (`KaraokePack`). The APK keeps only what has to exist before the
   add-on does: the settings page, the download, the login WebView and the player hook.
5. **Keystore AES-GCM instead of EncryptedSharedPreferences.** security-crypto is deprecated and
   not in the app; the platform Keystore does the same job with no new dependency.
6. **One player for both sides.** MeeTube and MeeTune share `Media3PlayerController`; the hook
   is in its listener, and `MeeTune.isMusic(video)` picks Listening (MeeTune) or Watching
   (MeeTube). Live videos get a start time only, so Discord shows time elapsed instead of a bar.
7. **Updates are debounced by 1 s** in the app. Scrubbing or a quick pause and play would
   otherwise send a burst, and Discord closes a gateway that updates presence too often.
8. **Discord application ids are MeeTube's own.** BitChord's id would show BitChord's name and
   icon. MeeTube is `1575206723822682123` and MeeTune is `1575206880567890100`
   (`DiscordPresence.MEETUBE_APP_ID` / `MEETUNE_APP_ID`); artwork and the button need them.
9. **Customisation BitChord offers and this does not (yet):** custom activity name, button
   texts, status (online/idle/dnd) and the audio quality line. Add when someone asks.
10. **Sign out revokes the token** (`POST /api/v9/auth/logout`) and the WebView's Discord
    storage is wiped right after the token is read. BitChord does neither.

## 3. Layout

| File | Role |
|---|---|
| `discord-addon/src/main/java/io/yash/meetube/discord/Presence.java` | The add-on: gateway client, presence publishing, external-assets, token script, logout. Built with `./gradlew :discord-addon:addonDex`. |
| `mobile/.../discord/DiscordAddon.java` | Download, verify, load; encrypted token; switches. |
| `mobile/.../discord/DiscordPresence.java` | Player state to a presence: Watching or Listening, timestamps, speed, debounce. |
| `mobile/.../ui/settings/DiscordPages.java` | Settings > Discord status, and the Discord login. MeeTune's Add-ons card opens the same page. |
| `Media3PlayerController` | One-line `MEETUBE(discord)` hooks: play/pause, seek, speed, new video, release. |

### Add-on contract (called by reflection)

Class `io.yash.meetube.discord.Presence`:

- `Presence(String token, String device)`; `publish(String activityJson)`; `clear()`; `close()`
- `static String tokenScript()`; `static String userName(String token)`; `static void logout(String token)`

`activityJson`: `{type: "watching"|"listening", appId, name, details, state, image, url, button, start, end}`.
A new add-on version keeps this contract, or bumps `VERSION` in `DiscordAddon` together with it.

### Publishing a new add-on build

1. `./gradlew :discord-addon:addonDex` writes `discord-addon/build/addon/discord-presence-1.dex`.
2. Put its size and SHA-256 into `DiscordAddon.SIZE` / `SHA256`.
3. Upload it to the GitHub release `discord-status-addon-v1` of yashoncode/meetube.

## 4. Security implications

**What the token is.** The value read from `localStorage.token` is the account's full user
token: anyone holding it can read and send messages, join and leave servers, change settings and
act as the user in every way the Discord app can, short of changing the password or email. It is
not scoped like an OAuth token. Discord offers no OAuth scope that lets a third-party app set a
user's presence, which is why BitChord, Kizzy and this add-on all need it.

**Terms of service.** Logging in to the gateway with a user token from anything that is not
Discord's own client is "self-botting" under Discord's terms, and the add-on identifies itself as
Discord Android. Discord has not been seen to act against presence-only clients like Kizzy, but
an account can be limited or banned for it. The settings page says this before the sign in.

**What the add-on does with it.** The token is sent only to `discord.com` and
`gateway.discord.gg`, over TLS: on the gateway IDENTIFY/RESUME, and as the `Authorization`
header of the external-assets, users/@me and logout calls. Thumbnail URLs (i.ytimg.com) are
given to Discord so it can proxy them, which tells Discord which video is playing; that is the
point of the feature. Nothing goes to MeeTube or any other server.

**At rest.** The token is encrypted with an AES-256-GCM key that lives in the Android Keystore
and never leaves the device; the ciphertext is in `shared_prefs/discord.xml`, excluded from cloud
backup and device transfer like the YouTube sign-in. Right after the token is read, the WebView's
cookies and `localStorage` for discord.com are deleted, so the WebView keeps no second copy.
Root access or a debugger on an unlocked debug build can still read it from memory; that is true
of every app that holds a login.

**Sign out.** Removing the account calls Discord's logout with the token, which invalidates it on
Discord's side, then deletes it locally. If the phone is offline at that moment the token stays
valid on Discord until the user signs that device out under Discord > Settings > Devices, or
changes the password.

**Downloaded code.** The add-on runs inside MeeTube's process with all of MeeTube's permissions.
It is fetched over HTTPS from this repo's GitHub release, and the app refuses any file whose size
or SHA-256 differs from the values compiled into the APK, both on download and before each load,
so a swapped release asset or a modified file on disk is never run. The file is kept read-only in
private storage. Changing the add-on therefore needs a new APK release, by design.

**The login page.** The WebView runs discord.com's own page with JavaScript on. The only script
the app adds runs after sign in, on discord.com only, and reads one value. No JavaScript bridge
is exposed to the page (`addJavascriptInterface` is not used), so the page cannot call into the
app.
