<div align="center">

# MeeTube TV Changelog

Every MeeTube TV release, newest first. The phone app's changelog is on the [main branch](https://github.com/yashoncode/meetube/blob/main/CHANGELOG.md).

</div>


| Version | Released | Highlights |
| :-- | :-- | :-- |
| [**1.1.0**](#110) | 2026-11-13 | Videos open almost at once, glowing focus, AV1, lighter on memory |
| [**1.0.0**](#100) | 2026-11-05 | First stable: in-app updates, Shorts player, faster starts |
| [**0.0.1** Beta](#001-beta) | 2026-11-03 | First beta |


## 1.1.0

> **Faster and lighter**<br>
> 📅 2026-11-13

### New

- Videos open almost at once: half a second after you rest on a video card (Home, Search, Channel), MeeTube TV loads that video in the background, so OK only has to start the player. On a 1 GB TV a video now starts in about a second instead of three and a half.
- The focused card glows: a white ring around it and a lit title area, so you always see where you are.
- AV1 video on TVs that decode it in hardware: about 30% smaller than VP9 for the same picture, so videos start sooner and stall less. TVs on the old VP9 default move to AV1 once; a quality you pick later stays.
- SponsorBlock asks before skipping a segment instead of skipping on its own. Saved skips switch to asking once; a skip you pick after that stays.
- Home chips are one segmented track (pills in Settings), the heart sits beside the clock in the top bar, and videos start right after the sidebar.
- A simpler player: no panel behind the controls, and the quality is a pill under the clock.
- The mouse wheel scrolls like the D-pad.
- A new UPI QR code on the donation page.

### Faster and lighter

- About 50 MB less memory while a card preview or a video plays: the hidden browser that makes YouTube's security token closes as soon as the token is made.
- Card previews keep a small buffer of their own instead of the player's.
- Opens faster after an install or update: startup code is compiled ahead of time.

### Fixed

- No more stops with "PoToken attestation failed": while YouTube is still checking the token, the video keeps playing.
- Opening a link while the player closes no longer crashes the app.
- The clock pill on the signed-out Playlists and My videos pages no longer gets a second heart and an empty grey dot.
- The latest playback fixes from SmartTube, up to its 32.74 (2026-11-12): fewer retries on stream errors, live streams resume at the right position, empty rows are hidden, a crash fix on some Android 8 TVs (XGIMI), and new translations including Malay.

## 1.0.0

> **First stable**<br>
> 📅 2026-11-05

### New

- In-app updates: MeeTube TV finds its own new versions and installs them (About > Check for updates, or automatically). It reads only MeeTube TV releases, never the phone app's.
- Shorts: a Shorts player. The short plays in a rounded 9:16 card with its colours as ambient light on both sides, a progress line, and the title, channel and stats beside it. Up and down page through shorts, OK pauses.
- Shorts feed picked for your account: the Shorts tab and the next short after a row come from the Shorts on your Home, 16 at a time, loaded ahead while you watch. Signed out, it is the regular Shorts feed.
- Videos start sooner: playback begins once half a second is buffered and recovers from a stall at a second and a half. The video buffer is High by default (installs still on the old Medium move to High once).
- Cards: the focused video card is bigger, with wider gaps so it never covers the cards beside it. Settings cards keep their size and spacing, two rows as before.
- Home rows fade at both ends, starting at the card line, and the focused first card is never cut.
- Home: Kids, Sports, Live, Gaming, News and Music are glass pills at the top of Home. Home opens with focus on the first video.
- Subscriptions: channels are round avatars on glass pills, with an All item first for every channel's latest videos.
- Sidebar: a roomier floating glass panel; the focused item is a raised glass key that tilts in with a glint.
- Top bar: the clock, logo and buttons share one height, and an optional "Hey, name" greeting (User interface settings, off by default).
- Player: buttons in the order play, previous, channel, next, comments, like, dislike, repeat, quality, then the rest. Like and dislike counts sit on their buttons, and a switch turns off the glass outline on the controls.
- The glow ring spinner everywhere a spinner shows.
- About: Donation shows the international and India (UPI) QR codes side by side.

### Changed

- Settings: User interface drops colour schemes, the old channels look and a duplicate titles switch. Search and Subtitles are one page.


## 0.0.1 Beta

> **First beta**<br>
> 📅 2026-11-03

MeeTube TV brings MeeTube's glass design and its own tuning to Android TV and Google TV, and installs as its own app.

### New

- Home: a frosted glass sidebar that hugs its items, with a focus pill that glides between them and MeeTube icons for every section.
- Explore opens with a staggered slide.
- Cards: frosted rounded time pills, and Shorts show their length.
- Search: the screen's edges light up in Siri's colours when Search or voice search opens, and your words glow as you type.
- Player: a minimal glass look, with the title and channel at the top and quality and clock top right.
- One frosted panel at the bottom of the player: position and length, a white seek bar with a glowing knob, the time left, then one row of buttons.
- The buttons run play / pause, previous, next, comments, like, dislike and then the rest.
- Outlined like and dislike icons in the video description.
- Quicker control fades.

### Fixed

- Settings no longer spins forever.
- The suggestions row no longer peeks out under the player panel.

### Good to know

- This beta has no in-app updates: get new versions from Releases.
- Built on [SmartTube](https://github.com/yuliskov/SmartTube) by @yuliskov, as of 2026-11-03.
