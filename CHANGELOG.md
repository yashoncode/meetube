<div align="center">

# MeeTube TV Changelog

Every MeeTube TV release, newest first. The phone app's changelog is on the [main branch](https://github.com/yashoncode/meetube/blob/main/CHANGELOG.md).

</div>


| Version | Released | Highlights |
| :-- | :-- | :-- |
| [**1.0.0**](#100) | 2026-11-05 | First stable: in-app updates, Shorts player, faster starts |
| [**0.0.1** Beta](#001-beta) | 2026-11-03 | First beta |


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
