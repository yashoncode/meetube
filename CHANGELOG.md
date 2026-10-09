# MeTube Changelog

All notable user-facing changes to MeTube. MeTube is a fork of NewTube; for NewTube's own history, see [CHANGELOG.newtube.md](CHANGELOG.newtube.md).

## 2.5.0 — 2026-10-09 — Glass and quicker Shorts

### New

- **Up next countdown.** When a video ends, a small card in the player counts down ten seconds to the next one. Tap Play now to go at once, Replay to watch it again, or Cancel to stay put. Seeking back cancels it too.
- **A splash when you like or subscribe.** A ring and a burst of colour come off the button.
- **Floating glass top bar.** The full-width bar is gone: the MeTube logo and the cast and search buttons float as two liquid glass pills, like the navigation bar.
- **A quote when you tap the MeTube logo.** It shows for three seconds, and every tap brings a new one: no quote comes back until you have seen them all.
- **Frosted menus.** The player's settings, More and card menus are frosted glass over a blurred copy of what's behind them, while the page around them stays sharp.
- **Liquid glass setting.** Turn it off in Settings → General for a plain frosted blur that is easier on the battery.
- **Counts in K and M.** Views and likes read 250K and 12M even when your phone is set to English (India). Prefer lakh and crore? Turn on Lakh and crore counts in Settings → General.

### Changed

- **Shorts swipe faster.** MeTube now looks up the short after the next one in advance, and a short that was still preloading when you swiped to it plays from what it already has instead of starting over. Neighbouring thumbnails load at once.
- **Subscribed is a glass pill**, like the buttons above it.
- **The title and view count of a video are one button** that opens the description.
- **A small bounce on the navigation bar** when you switch tabs.
- **Downloads go to Movies/MeTube and Music/MeTube.** Earlier downloads stay where they are.

## 2.0.2 — 2026-10-08 — Vertical fullscreen

### New

- **Vertical fullscreen.** Pull the watch page down from its title and the video fills the upright screen, like YouTube's. The whole picture stays in view, the system bars hide, and the seek bar sits higher, within reach of your thumb. Swipe down on the video or press Back to leave it, or turn the phone sideways for landscape.
- **Ambient colours in vertical fullscreen.** The black bands above and below the video glow with its colours. Ambient mode is on by default; turn it off in Settings → Player → Watch page, or from the player's settings sheet.
- **A floating glass fullscreen button** at the bottom right of the watch page.
- **A new loading animation.** The coral cookie from the icon turns as it morphs into a four-leaf clover and back. It replaces the spinner app-wide.

### Changed

- **Shorts start faster.** While you watch a short, MeTube now preloads the first seconds of the next one: it asks for the next short 0.3 seconds in (it used to wait 5 seconds) and loads it once the current one has 3 seconds buffered. In testing, a short you swipe to after about 3 seconds started in about half a second.
- **A fatter seek bar while you drag it**, with a bigger dot, like YouTube's.
- **The minimize button in fullscreen** is a small round button, easy to spot over any picture.
- **No page titles in the top bar.** Just the MeTube logo, cast and search.
- **Shorts comments:** dragging them down no longer shows the short as an ordinary video underneath, and the short grows back to full screen as the comments slide away.

## 2.0.1 — 2026-10-07 — Feel

### New

- **Haptics everywhere.** Every button, card, chip, switch, menu row and bottom-sheet option answers your tap with a light click, and each double-tap seek forward or back clicks too. It follows your phone's touch-vibration setting.
- **True black for AMOLED.** In dark mode the pages, top bar and navigation area are pure black, so an AMOLED screen switches those pixels off and saves battery.
- **Shorts swipe like YouTube.** The next (or previous) short's picture slides in under your finger as you swipe, and stays on screen until the video starts, with no black flash. When nothing is queued yet, the swipe just springs back.
- **Scrub Shorts.** Drag the line at the bottom to jump around a short. A big time readout shows where you are, and the text gets out of the way while you drag.
- **More on Shorts.** A Shorts header with a back arrow; a "more" button for quality, captions and speed; and YouTube's spinning disc with the channel picture. Tap a title to read all of it.

### Changed

- **Shorts you've seen stay seen.** MeTube remembers the last 3,000 shorts for 7 days, across restarts, so the Shorts tab keeps serving new ones. If a feed has nothing new for three pages, it plays what it has instead of loading forever.
- **The Shorts progress line moves now.** In 2.0.0 it stayed empty while a short played.

## 2.0.0 — 2026-10-07 — Liquid Glass

### New

- **Shorts like YouTube.** The Shorts tab opens straight into the full-screen player, with no grid. The channel, a Subscribe button, the title and views sit at the bottom; like, dislike, comments and share on the right, with their counts; a thin progress line runs along the bottom. Tap to pause, double-tap to like, hold for 2x. Back returns to the tab you came from.
- **Endless, fresh Shorts.** The next page of Shorts loads before you reach the end, a short you have already seen this session is never served again, and the next one is buffered as soon as the current one starts.
- **Comments on Shorts.** The comments open under the short, which shrinks to the top of the screen, like YouTube.
- **Liquid glass.** The floating bar now bends what is under it like a lens, with a soft rainbow edge and a bright rim, and a lighter frost so you can see the page through it. The top bar is frosted glass too: the feed scrolls up under it.
- **A new icon.** A minimal coral "cookie" with a white M, in the Material 3 expressive style, with a themed version for Android 13+.

### Changed

- **Sharper Shorts thumbnails** (YouTube's tall 720×1280 images).
- **A bigger mini player** (216×122dp), landing exactly where the minimize animation ends.
- **Modern switches** in Settings: a rounded capsule, red when on.
- Like counts YouTube does not share no longer show "N/A".

## 1.5.0 — 2026-10-07 — Shorts

### New

- **Shorts.** A Shorts tab with a grid of tall cards. Tap one and it plays full screen; swipe up for the next short and down for the previous one. Each short loops until you swipe, and the next one loads while you watch. Like, dislike and share sit on the right.
- **Ambient glow.** On the watch page the colours of the playing video spread softly under the player and fade into the page, and follow the scene as it changes.
- **A new MeTube icon.** A rounded M on a coral-to-magenta tile, with a little glass bubble.

### Changed

- **Tap the title to read more.** The arrow is gone: tap a video's title or its views line to open the description, and the title shows in full.
- **Rounded everywhere.** Menus, sheets, dialogs, snackbars and chips have rounded corners, and the Comments panel slides up with a rounded top.
- **History moved to You.** The bottom bar is now Home, Shorts, Subscriptions, Downloads and You.

## 1.0.0 — 2026-10-07 — Floating Glass

### New

- **Floating glass.** The bottom navigation is now a frosted glass capsule floating over the page, with a live blur of what scrolls underneath. On Android 13 and later its edge bends the content like a lens.
- **A new look.** A darker page, a red accent, large tab titles, rounded thumbnails, and glass buttons and cards on the watch page.
- **MeTube.** A new name and app id, so it installs alongside NewTube. Updates come from github.com/yashoncode/metube.

### Changed

- **Dark by default.** Light theme is still in Settings → User interface → Theme.
