<div align="center">

# MeTube Changelog

Every MeTube release, newest first.<br>
✨ New &nbsp;·&nbsp; 🛠️ Changed &nbsp;·&nbsp; 🐛 Fixed

</div>


| Version | Released | Highlights |
| :-- | :-- | :-- |
| [**3.1.7** Clozone](#317-clozone) | 2026-10-26 | Your bars your way, live chat beside the video, share from the second |
| [**3.1.5** Orizon](#315-orizon) | 2026-10-25 | A new You page, More videos in fullscreen, HDR video |
| [**3.1.1** Spectre](#311-spectre) | 2026-10-22 | MeTube on Android TV, Shorts quality, tablet fixes |
| [**3.1.0** DoomsSlay](#310-doomsslay) | 2026-10-22 | Actions in fullscreen, a video box that fits, quicker Shorts |
| [**3.0.5**](#305) | 2026-10-21 | MeTube in Traditional Chinese, Shorts on channels, search chips |
| [**3.0.1**](#301) | 2026-10-19 | YouTube's search filters, Shorts in search, a real channel page |
| [**3.0.0**](#300) | 2026-10-19 | Your own bottom bar, three app icons, a truthful quality label |
| [**2.9.9**](#299) | 2026-10-18 | Shorts in picture-in-picture, channel pictures, a new icon |
| [**2.9.8**](#298) | 2026-10-17 | Home filters, sleep timer, steadier background play |
| [**2.9.6**](#296) | 2026-10-15 | Hotfix |
| [**2.9.5**](#295) | 2026-10-15 | Headphones on the mini player, iOS-style switches |
| [**2.9.3**](#293) | 2026-10-15 | Lyrics providers, smoother comments and fullscreen |
| [**2.9.2**](#292) | 2026-10-14 | Lyrics, liquid glass player, Description panel |
| [**2.8.0**](#280) | 2026-10-13 | Search tab and filters, Shorts edge to edge, floating mini player |
| [**2.5.7**](#257) | 2026-10-09 | Music button opens your music app |
| [**2.5.6**](#256) | 2026-10-09 | Picture-in-picture every time, and a Music button |
| [**2.5.1**](#251) | 2026-10-09 | Audio only, new icon and fixes |
| [**2.5.0**](#250) | 2026-10-09 | Glass and quicker Shorts |
| [**2.0.2**](#202) | 2026-10-08 | Vertical fullscreen |
| [**2.0.1**](#201) | 2026-10-07 | Feel |
| [**2.0.0**](#200) | 2026-10-07 | Liquid Glass |
| [**1.5.0**](#150) | 2026-10-07 | Shorts |
| [**1.0.0**](#100) | 2026-10-07 | Floating Glass |


## 3.1.7 Clozone

> **Your bars your way, live chat beside the video, share from the second**<br>
> 📅 2026-10-26 · closes #31, #32, #33, #37, #42, #54, #63, #65, #66, #67 and #68

### 💌 Love MeTube? Your friends might too

- **We would never ask you to share MeTube.** Okay, we are asking, a little. If it saved you from one unskippable ad this week, there is probably a friend, a cousin or a mum out there still sitting through all of them like it is 2015. Send them [the download link](https://github.com/yashoncode/metube/releases/latest). Or don't, and keep the ad free life to yourself. We will just be here, refreshing the download count every five minutes, totally fine about it.

### ✨ New

- **Arrange your bars by dragging.** Settings > Customization > Tabs now shows your bars as a stack of tiles with a small preview above them. Hold a tile, or grab its grip, and slide it: Search and your two tabs move between Home and You, and the preview follows along. Drag Search up and it moves to the top bar next to Cast, like on YouTube. Tap a tab to change what it shows (#31).
- **Live chat beside the video.** Upright, the chat sits under the video instead of covering it. Sideways, a chat button on the fullscreen pill opens it as a card on the right, and the video slides over to make room. Comments in landscape open in the same card (#32).
- **Share from the second you are on.** A tap on Share sends the link as before. Hold it to send the link from where you are now, so your friend lands right on the good part (#68).
- **Tap the time to see what is left.** The time on the player flips to the time remaining and back, and MeTube remembers your pick (#67).
- **Hold a video in Up next for its menu.** The same menu as on Home: queue, save to a playlist, share and the rest (#66).
- **Swipe a video out of your own playlists.** Watch later too. Changed your mind? Undo puts it back (#65).
- **Mute, search and download on Shorts,** small at the top right like YouTube's. Mute stays on from short to short until you tap it again.

### 🛠️ Changed

- **The bottom bar gets out of the way.** It hides while you scroll down and comes back when you scroll up, and Search results get the same floating bar (#37).
- **Shorts show your own picks first.** The Shorts tab opens on the Shorts from your account's Home, not just your subscriptions' (#42).
- **A short's description is one tap away.** Tap its title (#33).
- **Only tall Shorts fill the screen.** A square or wide short now shows whole, with the glow around it, instead of losing its sides.
- **The mini player goes where you put it.** Drag it anywhere and it springs to the nearest corner. Fling it sideways to close it.
- **The status bar glows with the video,** and Home blurs behind the player as you swipe it down to the mini player (Android 12 and newer).
- **HDR says so.** With HDR on, the Quality sheet labels HDR streams, like "2160p60 HDR" (#63).
- **Glass Accounts and Settings buttons** on the You page, with the colour light showing through.
- **Channel pictures on Shorts fade in** like the ones on Home, instead of popping in.
- **The Explore compass on Home is gone.** Music, Live, Gaming, News and Sports are still under Explore on the You page.

### 🐛 Fixed

- **Your History shows the Shorts you watched.** If you mostly watched Shorts lately, History looked empty.
- **A tab you put on the bar shows up.** Turning it off under You used to hide it from the bar too. Putting it on the bar now turns it back on (#54).
- **One decoder hiccup no longer lowers your quality for good.** When the phone could not start a video, MeTube dropped to 1080p and kept it for every video after. Now only that one video falls back. Thanks to NewTube for this fix.
- **Swiping the player down from Search** no longer slips under the bottom bar and jumps.

### ✅ Issues resolved

| Issue | Asked for | In 3.1.7 |
| :-- | :-- | :-- |
| [#31](https://github.com/yashoncode/metube/issues/31) | Search button in the top bar | Drag Search up into the top bar |
| [#32](https://github.com/yashoncode/metube/issues/32) | Live chat is hard to see, and missing in landscape | Chat under the video, or a card beside it |
| [#33](https://github.com/yashoncode/metube/issues/33) | A description for Shorts | Tap a short's title |
| [#37](https://github.com/yashoncode/metube/issues/37) | A bottom bar that hides like YouTube's | It hides as you scroll down |
| [#42](https://github.com/yashoncode/metube/issues/42) | Shorts don't match the YouTube app | Your account's Shorts come first |
| [#54](https://github.com/yashoncode/metube/issues/54) | Subscriptions missing from the bar | A tab on the bar always shows |
| [#63](https://github.com/yashoncode/metube/issues/63) | HDR next to the quality | HDR streams say HDR |
| [#65](https://github.com/yashoncode/metube/issues/65) | Swipe to remove from a playlist | Swipe it out, with Undo |
| [#66](https://github.com/yashoncode/metube/issues/66) | Hold a video for its menu | Hold any video in Up next |
| [#67](https://github.com/yashoncode/metube/issues/67) | Show the time left | Tap the time to flip it |
| [#68](https://github.com/yashoncode/metube/issues/68) | Share with a timestamp | Hold Share |

## 3.1.5 Orizon

> **A new You page, More videos in fullscreen, HDR video**<br>
> 📅 2026-10-25 · closes #24, #34, #35, #36, #38, #41, #45, #50, #56, #57 and #58

### 📣 A new name is coming

- **MeTube will get a new name soon.** The app, its features and your settings stay the same, and updates keep arriving as they do now. We want to pick the name with you: share your ideas and vote in [GitHub Discussions](https://github.com/yashoncode/metube/discussions).

### ✨ New

- **A You page like YouTube's.** Your picture, name and email up top with Accounts and Settings buttons, your latest History in a row that scrolls sideways, then your Library and Explore. A soft light in the corner slowly drifts through the colours and fades into true black, so it stays easy on AMOLED screens.
- **Swipe up in fullscreen for More videos.** In landscape, a swipe up on the middle of the video raises a row of Up next videos while the video keeps playing. Pick one to play it, or swipe down, tap the video or press Back to put the row away. A small arrow at the bottom shows it is there (#24).
- **An Explore menu on Home.** A compass before the topic chips opens a side menu with Music, Live, Gaming, News and Sports, frosted over the page, with the same colour light and rounded corners.
- **HDR video.** Turn on HDR video in Settings > Player and HDR videos play in HDR on a screen that can show it. It is off by default (#35).

### 🛠️ Changed

- **The mini player opens on the first tap.** Its buttons fade away three seconds after it shows and come back when you touch it, and a tap on the video always opens it again (#34).
- **Under a tall video, swiping up shows the details.** The video box first shrinks to the usual size, as on YouTube, then the page scrolls. Pull down at the top to grow it back (#36).
- **Shorts stay Shorts in landscape.** Turn the phone or tablet sideways and you still swipe from short to short, see the whole short, and get comments beside it (#57).
- **A like moves the like count.** Liking a video or a short now adds one to the count, and taking the like back takes one off (#45).
- **Each channel tab keeps its place.** Switching between Videos, Shorts, Popular and the rest returns you to where you left each one (#50).
- **See-through glass everywhere.** The buttons over the video, the mini player's buttons, the Search filter chips and the Search filters dialog all use the same clear glass, so the video and results show through.

### 🐛 Fixed

- **The quality you pick is the quality you get.** On some videos, choosing 1080p played 240p while the button still said 1080p. Now the closest quality at or below your pick plays (#58).
- **Video titles no longer go missing.** A card that arrived with only a picture now fills in its title and channel by itself (#41).
- **Home no longer stays stuck on the loading placeholders** on some phones, such as the Galaxy A03 Core, after the videos have loaded (#56).
- **Leaving picture-in-picture no longer crashes,** and picture-in-picture no longer shows the whole app instead of the video (#38).

### ✅ Issues resolved

| Issue | Asked for | In 3.1.5 |
| :-- | :-- | :-- |
| [#24](https://github.com/yashoncode/metube/issues/24) | A swipe-up More videos panel in fullscreen | Swipe up in landscape for a row of Up next videos |
| [#34](https://github.com/yashoncode/metube/issues/34) | Fix the mini player | One tap opens it, its buttons fade away |
| [#35](https://github.com/yashoncode/metube/issues/35) | HDR video | An HDR video switch in Settings > Player |
| [#36](https://github.com/yashoncode/metube/issues/36) | Swipe up to see a tall video's details | The box shrinks first, then the page scrolls |
| [#38](https://github.com/yashoncode/metube/issues/38) | Picture-in-picture shows the whole app | Only the video floats, and leaving it no longer crashes |
| [#41](https://github.com/yashoncode/metube/issues/41) | Video titles sometimes missing | Missing titles fill themselves in |
| [#45](https://github.com/yashoncode/metube/issues/45) | Liking Shorts doesn't update the like | The like count moves with your like |
| [#50](https://github.com/yashoncode/metube/issues/50) | Channel tabs jump back to the top | Each tab keeps its scroll position |
| [#56](https://github.com/yashoncode/metube/issues/56) | Home stuck on loading | The placeholders always make way for the videos |
| [#57](https://github.com/yashoncode/metube/issues/57) | Shorts turn into a normal video in landscape | Shorts mode holds in any orientation |
| [#58](https://github.com/yashoncode/metube/issues/58) | 1080p picked, 240p played | The nearest quality to your pick plays |

## 3.1.1 Spectre

> **MeTube on Android TV, Shorts quality, tablet fixes**<br>
> 📅 2026-10-22 · closes #21, #25, #26, #27, #28, #29 and #30

### ✨ New

- **MeTube on Android TV and TV boxes.** Install it on a TV, open it from the TV home screen and use it with just the remote. A white ring shows what you are on, everywhere. On Home, press Up from the first row for the topic chips and Back for the bottom bar. In the player, OK plays or pauses, Left and Right seek, Up and Down bring up the controls, and Back hides them before it leaves the video. In Shorts, Up and Down move between shorts. It is the same MeTube you know from your phone, not SmartTube's TV look (#21).
- **Shorts have their own default quality.** Settings > Video quality has a Shorts row next to Wi-Fi and mobile data, so you can save data on Shorts and keep other videos sharp, or the other way round (#26).

### 🛠️ Changed

- **Shorts comments work like YouTube's.** They take two thirds of the screen with the whole short above them, and the short grows and shrinks with the comments as you drag them (#27).
- **The floating fullscreen button is see-through**, with a dark ring, so it no longer hides the comments under it (#29).
- **Tablets feel more at home.** A short shows whole when the zoom setting is Default, and its sides blend into the glow. The mini player is as big as picture-in-picture, sheets span an upright tablet's width, and the bottom bar is only as wide as its items (#25).

### 🐛 Fixed

- **The lyrics panel no longer follows you home.** Going Home with lyrics open used to carry the panel into the picture-in-picture window, and minimizing left it over the mini player. Lyrics and other open panels now close when the player shrinks (#30).
- **The video stays smooth under the settings sheet.** It stuttered for a moment when the sheet opened (#28).
- A long channel name on a short no longer splits the Subscribe button over two lines.

### ✅ Issues resolved

| Issue | Asked for | In 3.1.1 |
| :-- | :-- | :-- |
| [#21](https://github.com/yashoncode/metube/issues/21) | Use MeTube with a TV remote | A TV home screen entry and remote control on every screen |
| [#25](https://github.com/yashoncode/metube/issues/25) | Shorts stretched on tablets | Tablets show the whole short when zoom is Default |
| [#26](https://github.com/yashoncode/metube/issues/26) | A separate quality for Shorts | A Shorts row in Settings > Video quality |
| [#27](https://github.com/yashoncode/metube/issues/27) | Shorts comments don't open fully | Comments take two thirds of the screen |
| [#28](https://github.com/yashoncode/metube/issues/28) | Stutter when opening the video options | The video keeps playing smoothly |
| [#29](https://github.com/yashoncode/metube/issues/29) | A lighter fullscreen button | A see-through button with a dark ring |
| [#30](https://github.com/yashoncode/metube/issues/30) | Lyrics panel over the mini player | Open panels close when the player shrinks |

## 3.1.0 DoomsSlay

> **Actions in fullscreen, a video box that fits, quicker Shorts**<br>
> 📅 2026-10-22 · closes #9, #13, #17, #19 and #20

### ✨ New

- **Like, comment and share in fullscreen.** A small frosted pill under the seek bar, in landscape and vertical fullscreen, has like, dislike, comments, download and share, with the same state as the watch page. The arrow beside the fullscreen button hides it, and MeTube remembers your choice (#9).
- **The video box takes the video's shape.** A square or vertical video no longer sits in a 16:9 box with black bars at its sides. The box follows the video, up to two thirds of the screen, so the title and buttons stay in sight (#13).
- **Live frosted glass over the player.** The settings sheet and the pickers opened over a video (download quality, repeat, zoom, save to playlist) blur the video as it keeps playing, instead of a frozen copy of it. Android 12 and newer (#17).
- **Shorts swipe faster.** The next short starts opening while the old one slides away, and stays preloaded while the current one loops. A swipe now plays the next short about a third sooner, with no red spinner over it and no jump from its picture to its video.
- **Comments beside the landscape video.** Comments opened in landscape fullscreen take a column on the right while the video plays on the left, like YouTube.
- **YouTube's vertical fullscreen.** Minimize and headphones at the top left, the title and channel under them, the seek bar and the actions just above the gesture bar.
- **Vertical videos go fullscreen upright.** The fullscreen button and the swipe up open a vertical video in vertical fullscreen, not turned to landscape with black bars.

### 🛠️ Changed

- **The mini player's buttons show on a tap**, like a picture-in-picture window. Tap the card for headphones, play/pause and close; tap the video while they show to open the player.
- **Vertical videos play in 1080p.** They were capped at 480p. Full quality also arrives sooner: the low quality start is replaced once the connection allows it, in about 5 s on Wi-Fi instead of about 10.
- **A quick double tap on play/pause plays or pauses**, like YouTube. With the controls hidden, the second tap used to hide them again.
- **Player sheets in landscape stay still and scroll.** Every row is in reach, and the last row on screen is cut in half and fades out, a hint that the list goes on.
- Shorts action buttons are a little smaller, and the second channel picture under them is gone.
- Romanian, Russian and Simplified Chinese cover more of the app, thanks to NewTube's translators on [Weblate](https://hosted.weblate.org/projects/newtube/).

### 🐛 Fixed

- **Home's grey placeholder stayed over the loaded videos** for seconds on slower phones, and could flash back when leaving Shorts. It now fades once (#20).
- **My videos looked broken with private or unlisted uploads.** YouTube never lists those to MeTube's sign-in, only public ones; an empty My videos now says so instead of "Nothing to show here yet" (#19).
- **No pause and rewind at the one minute mark.** When YouTube refuses a session at 60 seconds, the buffered video keeps playing while MeTube fetches a working link for the same stream.
- **Previous goes back even with SponsorBlock.** A second tap returns to the previous video even when SponsorBlock skipped an intro, and that video resumes where you left it.
- **The app opens on Android 7.0 again.**
- **Fullscreen on Android 11:** the gesture bar no longer stays on screen.
- Audio tracks are labelled better: original, dubbed and audio description tracks are told apart.
- Home no longer looks like it is loading again after you leave the fullscreen player.
- The app icon you picked shows on every launch screen and in recent apps, also after Android restarted MeTube in the background.
- Videos open a little lighter: the remote control service is built only when it is used.

### ✅ Issues resolved

| Issue | Asked for | In 3.1.0 |
| :-- | :-- | :-- |
| [#9](https://github.com/yashoncode/metube/issues/9) | Like, comment and share buttons in the player | A frosted action pill under the fullscreen seek bar |
| [#13](https://github.com/yashoncode/metube/issues/13) | Fit the video frame to the video | The video box takes the video's own shape |
| [#17](https://github.com/yashoncode/metube/issues/17) | Frosted glass on the player's settings pop-up | Live frosted glass on every sheet over the player |
| [#19](https://github.com/yashoncode/metube/issues/19) | My videos doesn't show my uploads | My videos explains why private and unlisted uploads are missing |
| [#20](https://github.com/yashoncode/metube/issues/20) | Placeholder stuck over Home on open | The placeholder fades once the videos load |

## 3.0.5

> **MeTube in Traditional Chinese, Shorts on channels, search chips**<br>
> 📅 2026-10-21

### ✨ New

- **MeTube in Traditional Chinese (繁體中文).** Every phone screen is translated for Taiwan, thanks to [@Capricornus007](https://github.com/Capricornus007) (#14). Channel pages now also show their stats in your language's own script.
- **A Shorts tab on channel pages.** Like YouTube's: tall tiles three to a row with the view count on each. Tap one to open the Shorts player and swipe through the channel's Shorts. Channels that only post Shorts no longer show an empty page.
- **Subscribe on the channel page.** Signed in, the channel header has a Subscribe button with the same splash as the watch page, and Undo.
- **Search chips.** All, Videos, Shorts, Live and Recently uploaded sit under the search bar, like YouTube's. One tap filters the results.
- **A frosted glass bar on channel pages.** The top bar and tabs frost the page scrolling under them.

### 🐛 Fixed

- Tapping the channel name or picture on a Short opened nothing, and a double tap there liked the video. Both now open the channel (#12).
- Tapping a channel picture on Home opened the channel after about a second; the page now opens at once and fills in.
- A channel opened from a youtube.com/@handle link now shows its header, Subscribe button and Shorts view counts.
- On a channel whose latest video is a collab, the header could show the other channel.

## 3.0.1

> **YouTube's search filters, Shorts in search, a real channel page**<br>
> 📅 2026-10-19

### ✨ New

- **YouTube's search filters.** The filter button opens the same "Search filters" YouTube has: Type, Duration, Upload date and Prioritize, plus Features chips: Live, 4K, HD, Subtitles/CC, Creative Commons, 360°, VR180, 3D, HDR, Location and Purchased. Any combination works now; before, many combinations quietly searched without a filter (#11).
- **Shorts in search.** Search results show Shorts the way YouTube does: a block of tall cards, two to a row. Tap one to open the Shorts player and swipe through the search's Shorts. Type › Shorts shows only Shorts (#11).
- **A channel page with a header.** The channel's banner, picture, name, @handle, subscribers and video count and its description (tap to read it all), over a soft blur of the banner. The header scrolls away; the tabs stay (#10).
- **Both channels on a collab.** A video made by two channels shows both channel pictures, like YouTube.
- **Hide the tab names.** Settings › Customization › Tab names: off leaves only the icons in the bottom bar.

### 🛠️ Changed

- Channel pictures on cards load sooner while you scroll and fade in instead of popping in.
- The search filters YouTube no longer has (Last hour, sort by upload date or rating) are gone.

## 3.0.0

> **Your own bottom bar, three app icons, a truthful quality label**<br>
> 📅 2026-10-19

### ✨ New

- **Choose your bottom bar.** Home, Search and You always stay; the tab before Search and the tab after it are yours: Shorts, Subscriptions, Downloads, History or none. Settings › Customization › Tabs. Picking what the other tab shows swaps the two. Subscriptions can be on the bar again (#7).
- **A third icon: MeTube (Fantasy).** The red heart with a white play button on a white tile, beside MeTube (default) and MeTube (Classic). The icon list shows each one.
- **The icon is everywhere.** The icon you pick now also shows in the Home pill, the launch screen and the update sheet, not only on the home screen. Picking one offers Restart to switch right away.

### 🛠️ Changed

- Settings choices (theme, app icon, interface size, ...) open in a bottom sheet instead of a pop-up in the middle of the screen.
- Switches and the picked option are red, the app's accent colour.
- Trending is gone from Tabs and You: YouTube removed it and its feed came back empty.

### 🐛 Fixed

- **"Auto (1080p60)" on videos without 60 fps.** In Auto the Quality row showed the Auto ceiling instead of the quality playing. It now shows the real one, for example "Auto (720p)" in the small player and "Auto (1080p)" in fullscreen (#8).
- **Subscriptions never reached the bottom bar** when turned on in Settings › Tabs (#7).
- Long tab names such as Subscriptions fit their tab instead of ending in "...".

## 2.9.9

> **Shorts in picture-in-picture, channel pictures, a new icon**<br>
> 📅 2026-10-18

### ✨ New

- **Shorts in picture-in-picture.** Press Home while a short plays and it keeps playing in a portrait window with previous, play/pause and next, like YouTube.
- **Channel pictures on cards.** Home, Subscriptions and Search show each channel's picture beside the video title. Tap the picture or the channel name to open the channel.
- **Sort a channel.** The new button on a channel page sorts its videos by Latest, Popular or Oldest.
- **Choose your double-tap.** Settings › Playback › Double-tap to seek: 3, 5, 10, 15 or 30 seconds.
- **A new icon.** A glossy heart with a play button, on the home screen, the launch animation and the Home pill. Prefer the old one? Settings › Customization › App icon › Classic.
- **Customization page.** Settings › Customization holds the theme, app icon, glass, interface size, start screen, Home layout, tabs, video menu, thumbnails and playlist style. General keeps language, country, voice search and number formats.
- **Update pop-up.** When a new version is out, MeTube asks once with Update now or Later. Later asks again a day later; Don't ask again turns it off (Settings › About › Update pop-up).

### 🛠️ Changed

- Group titles in Settings and pop-ups are smaller and red, so they no longer look like options.
- The account and Buy me a coffee rows left the top of Settings. Switch accounts from the You tab; the thank-you stays at the bottom of About.
- Updating to 2.9.9 removes MeTube from your home screen once (the launcher entry was renamed for the icon choice). It is still in the app drawer; pin it again from there.

### 🐛 Fixed

- **Ads in the Shorts feed.** Promoted shorts are now dropped.
- **Shorts wouldn't swipe right after opening.** The Shorts tab could open on a single subscription short before the rest of the feed arrived, and a swipe did nothing. It now waits for the next short.
- **Shorts picture-in-picture turned landscape on next.** The window keeps the short's shape while the next one loads.
- **Home could crash** reattaching the mini player's video.
- Searches that failed once are retried properly, and captions are no longer offered from auto-dubbed audio (from SmartTube).

## 2.9.8

> **Home filters, sleep timer, steadier background play**<br>
> 📅 2026-10-17

### ✨ New

- **Filters on Home.** Liquid-glass chips at the top of Home: All, Music, Gaming, News, Sports and Live. They slide away as you scroll down; scrolling back up shows a small arrow beside the MeTube pill that brings them back.
- **Home as rows.** Settings › General › Home layout › Rows shows Home like SmartTube: one titled row per topic that you swipe sideways. Feed stays the default.
- **Channels on Subscriptions.** A row of your subscribed channels heads Subscriptions. Tap one to see only that channel's videos; tap All for everything again.
- **Sleep timer.** In the player's settings: pause after 10 to 90 minutes, or at the end of the video.
- **Autoplay switch.** In the player's settings, one tap turns playing the next video on or off.
- **Video quality for Wi-Fi and mobile data.** Settings › Video quality has a default for each. Mobile data starts as "Same as Wi-Fi".

### 🐛 Fixed

- **Music stopped, and the app was closed, with the screen off.** MeTube now keeps the phone awake and connected while it plays, and asks once to be allowed to run in the background, which battery savers on phones like realme respect. Settings › Playback › "Keep playing with the screen off" shows whether it's allowed.

## 2.9.6

> **Hotfix**<br>
> 📅 2026-10-15

### 🐛 Fixed

- **The player could freeze half-shrunk.** A back swipe in the vertical fullscreen left the video stuck part-way to the mini player, with the page dimmed and no button or gesture working. Back now just leaves the vertical fullscreen, and if the player is ever caught half-way, Back puts it right.

## 2.9.5

> **Headphones on the mini player, iOS-style switches**<br>
> 📅 2026-10-15

### ✨ New

- **Headphones on the mini player.** The in-app mini player has a headphones button to switch to audio only, and so does its floating picture-in-picture window.
- **iOS-style switches.** Settings switches are compact capsules with a white pill knob that turns into clear glass as it slides.
- **Frosted search in Settings.** The "Search settings" bar is liquid glass, and the list scrolls under it. It follows the Liquid glass setting.
- **Buy me a coffee.** If you love MeTube and want to support it, there's a link in Settings and a small thank-you at the bottom of About.

### 🛠️ Changed

- **Pulling down into the vertical fullscreen follows your finger,** and springs back if you let go early. Going in and coming back out are smoother.
- **Smoother picture-in-picture from the mini player.** Its buttons hide before the window shrinks, and the card eases back into its corner when you return.
- **In the vertical fullscreen, the minimize and headphones buttons sit under the seek bar,** within reach of your thumb, and the top buttons stay clear of the camera notch.

## 2.9.3

> **Lyrics providers, smoother comments and fullscreen**<br>
> 📅 2026-10-15

### ✨ New

- **Choose your lyrics provider.** Like BitChord, the lyrics panel lists every source that has the song's words, and you can switch between them.
- **Glass buttons on Shorts.** Like, comments, share and the other buttons sit on the same frosted liquid glass as the player's buttons.
- **Remove from playlist and Watch later, from the card menu,** on your own playlists (from NewTube).
- **Channel links open,** including @handle links, and the channel page shows its name (from NewTube).

### 🛠️ Changed

- **Pulling Home to refresh shows videos you haven't seen.** The videos already on screen make room for the rest of YouTube's picks, with no extra wait.
- **Writing a comment shows a single bar,** with your picture and a rounded glass pill, and it rises smoothly with the keyboard.
- **The vertical fullscreen opens and closes smoothly,** without the page vanishing or the video jumping.
- **The Liquid glass setting covers every glass surface,** the player's and Shorts' buttons included.

### 🐛 Fixed

- **The lyrics panel fades away with the player** when you swipe the video down to the mini player, like comments and the description.
- **No more stray Shorts tiles in a channel's navigation row,** and the untitled row of channel tabs is called "More" (from NewTube).
- **The screen can sleep when the mini player is paused under a menu,** and a dubbed video's download keeps the right audio if the app closes mid-download (from NewTube).

## 2.9.2

> **Lyrics, liquid glass player, Description panel**<br>
> 📅 2026-10-14

### ✨ New

- **Lyrics for every song.** The Music button is now Lyrics: a panel under the video with the song's words, found across BitChord's lyrics sources (lrc.red, LyricsPlus, KuGou, LRCLIB, YouTube Music, Genius and more). Timed lyrics light up line by line with the song; tapping the video still works the player.
- **Liquid glass player buttons.** Every button over the video sits on frosted glass that blurs the picture behind it, in portrait, landscape and the vertical fullscreen.
- **A YouTube-style Description panel.** Tapping the title opens the description in a frosted panel under the video, with likes, views and the date, and text you can copy. The video keeps playing, undimmed.
- **Swipe between a channel's tabs.** Slide left or right on a channel's videos to change tabs.
- **Frosted comments.** The comments panel, the "Add a comment" pill and the sort and comment menus are frosted glass.

### 🛠️ Changed

- **Swipe up to leave the vertical fullscreen,** as well as down, and it settles smoothly.

### 🐛 Fixed

- **Comments by people with Arabic or Hebrew names read properly.** The name line no longer jumbles the date ("days ago 4 •"), and replies that are just a timestamp line up with the rest.

## 2.8.0

> **Search tab and filters, Shorts edge to edge, floating mini player**<br>
> 📅 2026-10-13

### ✨ New

- **Search has its own tab.** Search now sits in the bottom bar where Subscriptions was (Subscriptions is in the You panel), and the search icon left the top bar.
- **Search filters are back.** The button next to the search bar narrows results by upload date (last hour to this year), length, type (videos, channels, playlists, movies) and features (live, 4K, HDR), and sorts them by date, views or rating.
- **The mini player floats when you leave the app.** Going home while the mini player plays on Home now opens a picture-in-picture window. Expanding it brings you back; closing it stops the video.
- **Shorts fill the screen, edge to edge.** Ambient blur takes the strips under the status and navigation bars, and the video's edges fade into it.
- **A frosted glass search bar that glows as you type.** Results scroll under a frosted search bar with clear and voice search inside it. What you type glows in Siri's colours, and opening Search lights up the screen's edges.
- **A beating heart on launch.** The heart beats once on the start screen (Android 12 and later) and in the top bar.

### 🛠️ Changed

- **Shorts pause when you leave the app.** Shorts no longer open picture-in-picture, play in the background or shrink to the mini player; coming back plays them again.

### 🐛 Fixed

- **Shorts no longer turn into normal videos at random.** A Short could lose track of the Shorts feed and open on the watch page, and the end of the loaded Shorts fell back to an ordinary next video.
- **Picture-in-picture fills its window for vertical and square videos.** It showed the video in the top half over black, with the fullscreen button below.
- **The screen sleeps when a video ends or is paused.** It used to stay on until the battery ran out.
- **An audio track you pick applies to that video only.** A dub chosen once no longer follows you to every dubbed video, and "en" no longer matches "ben".
- **Downloads of dubbed videos keep the original audio,** also after a retry.
- **Original titles work, with or without DeArrow.**
- **Backups leave your sign-in tokens out.** Android's backup and the app's backup file restore signed out.
- **Fullscreen works on tablets,** where Android 16 ignores rotation requests.
- **No blank Shorts tile on channel pages, and no channel actions on playlist cards.**

The last seven fixes come from NewTube 1.15.1.

## 2.5.7

> **Music button opens your music app**<br>
> 📅 2026-10-09

### 🐛 Fixed

- **Music button opens whichever app you chose for YouTube Music links.** It used to open only the YouTube Music app. Now it follows Android's "Open by default" setting, so apps like BitChord work, and without a music app it opens in the browser.

## 2.5.6

> **Picture-in-picture every time, and a Music button**<br>
> 📅 2026-10-09

### ✨ New

- **Picture-in-picture whenever you leave a playing video.** Going home or switching apps now always shrinks the video into a floating window. The old "Play in background" setting is gone; the headphones button is how you ask for audio only.
- **Headphones in the picture-in-picture window.** Tap the window, then the headphones: the window closes and the sound keeps playing.
- **Music button.** For songs and music videos, a Music button next to Share opens the video in YouTube Music. It only shows for videos that YouTube Music has.

### 🐛 Fixed

- **Smoother picture-in-picture.** MeTube no longer resends the window's buttons every time the video buffers, which stalled the window's animation.

## 2.5.1

> **Audio only, new icon and fixes**<br>
> 📅 2026-10-09

### ✨ New

- **Audio only.** Tap the headphones at the top left of the player to listen without the video: no video is downloaded, the thumbnail stays on screen, and pressing Home keeps the sound playing instead of opening a picture-in-picture window. Tap again for the video.
- **A new icon.** A red heart with a play button, on black, on the launcher and in the top bar.

### 🐛 Fixed

- **No more crash after picture-in-picture at high quality.** At 1440p and above, MeTube kept up to two minutes of already-watched video in memory, and coming back from picture-in-picture could run out of memory. Video memory now has a hard limit tied to what the app may use; seeking back more than 20 seconds reloads from the disk cache.
- **Settings in fullscreen.** The settings and More menus no longer bring back the status and navigation bars, and no longer show a second plain panel beside the frosted one.
- **Diagnostic logs are named metube-diagnostics.**

## 2.5.0

> **Glass and quicker Shorts**<br>
> 📅 2026-10-09

### ✨ New

- **Up next countdown.** When a video ends, a small card in the player counts down ten seconds to the next one. Tap Play now to go at once, Replay to watch it again, or Cancel to stay put. Seeking back cancels it too.
- **A splash when you like or subscribe.** A ring and a burst of colour come off the button.
- **Floating glass top bar.** The full-width bar is gone: the MeTube logo and the cast and search buttons float as two liquid glass pills, like the navigation bar.
- **A quote when you tap the MeTube logo.** It shows for three seconds, and every tap brings a new one: no quote comes back until you have seen them all.
- **Frosted menus.** The player's settings, More and card menus are frosted glass over a blurred copy of what's behind them, while the page around them stays sharp.
- **Liquid glass setting.** Turn it off in Settings → General for a plain frosted blur that is easier on the battery.
- **Counts in K and M.** Views and likes read 250K and 12M even when your phone is set to English (India). Prefer lakh and crore? Turn on Lakh and crore counts in Settings → General.

### 🛠️ Changed

- **Shorts swipe faster.** MeTube now looks up the short after the next one in advance, and a short that was still preloading when you swiped to it plays from what it already has instead of starting over. Neighbouring thumbnails load at once.
- **Subscribed is a glass pill**, like the buttons above it.
- **The title and view count of a video are one button** that opens the description.
- **A small bounce on the navigation bar** when you switch tabs.
- **Downloads go to Movies/MeTube and Music/MeTube.** Earlier downloads stay where they are.

## 2.0.2

> **Vertical fullscreen**<br>
> 📅 2026-10-08

### ✨ New

- **Vertical fullscreen.** Pull the watch page down from its title and the video fills the upright screen, like YouTube's. The whole picture stays in view, the system bars hide, and the seek bar sits higher, within reach of your thumb. Swipe down on the video or press Back to leave it, or turn the phone sideways for landscape.
- **Ambient colours in vertical fullscreen.** The black bands above and below the video glow with its colours. Ambient mode is on by default; turn it off in Settings → Player → Watch page, or from the player's settings sheet.
- **A floating glass fullscreen button** at the bottom right of the watch page.
- **A new loading animation.** The coral cookie from the icon turns as it morphs into a four-leaf clover and back. It replaces the spinner app-wide.

### 🛠️ Changed

- **Shorts start faster.** While you watch a short, MeTube now preloads the first seconds of the next one: it asks for the next short 0.3 seconds in (it used to wait 5 seconds) and loads it once the current one has 3 seconds buffered. In testing, a short you swipe to after about 3 seconds started in about half a second.
- **A fatter seek bar while you drag it**, with a bigger dot, like YouTube's.
- **The minimize button in fullscreen** is a small round button, easy to spot over any picture.
- **No page titles in the top bar.** Just the MeTube logo, cast and search.
- **Shorts comments:** dragging them down no longer shows the short as an ordinary video underneath, and the short grows back to full screen as the comments slide away.

## 2.0.1

> **Feel**<br>
> 📅 2026-10-07

### ✨ New

- **Haptics everywhere.** Every button, card, chip, switch, menu row and bottom-sheet option answers your tap with a light click, and each double-tap seek forward or back clicks too. It follows your phone's touch-vibration setting.
- **True black for AMOLED.** In dark mode the pages, top bar and navigation area are pure black, so an AMOLED screen switches those pixels off and saves battery.
- **Shorts swipe like YouTube.** The next (or previous) short's picture slides in under your finger as you swipe, and stays on screen until the video starts, with no black flash. When nothing is queued yet, the swipe just springs back.
- **Scrub Shorts.** Drag the line at the bottom to jump around a short. A big time readout shows where you are, and the text gets out of the way while you drag.
- **More on Shorts.** A Shorts header with a back arrow; a "more" button for quality, captions and speed; and YouTube's spinning disc with the channel picture. Tap a title to read all of it.

### 🛠️ Changed

- **Shorts you've seen stay seen.** MeTube remembers the last 3,000 shorts for 7 days, across restarts, so the Shorts tab keeps serving new ones. If a feed has nothing new for three pages, it plays what it has instead of loading forever.
- **The Shorts progress line moves now.** In 2.0.0 it stayed empty while a short played.

## 2.0.0

> **Liquid Glass**<br>
> 📅 2026-10-07

### ✨ New

- **Shorts like YouTube.** The Shorts tab opens straight into the full-screen player, with no grid. The channel, a Subscribe button, the title and views sit at the bottom; like, dislike, comments and share on the right, with their counts; a thin progress line runs along the bottom. Tap to pause, double-tap to like, hold for 2x. Back returns to the tab you came from.
- **Endless, fresh Shorts.** The next page of Shorts loads before you reach the end, a short you have already seen this session is never served again, and the next one is buffered as soon as the current one starts.
- **Comments on Shorts.** The comments open under the short, which shrinks to the top of the screen, like YouTube.
- **Liquid glass.** The floating bar now bends what is under it like a lens, with a soft rainbow edge and a bright rim, and a lighter frost so you can see the page through it. The top bar is frosted glass too: the feed scrolls up under it.
- **A new icon.** A minimal coral "cookie" with a white M, in the Material 3 expressive style, with a themed version for Android 13+.

### 🛠️ Changed

- **Sharper Shorts thumbnails** (YouTube's tall 720×1280 images).
- **A bigger mini player** (216×122dp), landing exactly where the minimize animation ends.
- **Modern switches** in Settings: a rounded capsule, red when on.
- Like counts YouTube does not share no longer show "N/A".

## 1.5.0

> **Shorts**<br>
> 📅 2026-10-07

### ✨ New

- **Shorts.** A Shorts tab with a grid of tall cards. Tap one and it plays full screen; swipe up for the next short and down for the previous one. Each short loops until you swipe, and the next one loads while you watch. Like, dislike and share sit on the right.
- **Ambient glow.** On the watch page the colours of the playing video spread softly under the player and fade into the page, and follow the scene as it changes.
- **A new MeTube icon.** A rounded M on a coral-to-magenta tile, with a little glass bubble.

### 🛠️ Changed

- **Tap the title to read more.** The arrow is gone: tap a video's title or its views line to open the description, and the title shows in full.
- **Rounded everywhere.** Menus, sheets, dialogs, snackbars and chips have rounded corners, and the Comments panel slides up with a rounded top.
- **History moved to You.** The bottom bar is now Home, Shorts, Subscriptions, Downloads and You.

## 1.0.0

> **Floating Glass**<br>
> 📅 2026-10-07

### ✨ New

- **Floating glass.** The bottom navigation is now a frosted glass capsule floating over the page, with a live blur of what scrolls underneath. On Android 13 and later its edge bends the content like a lens.
- **A new look.** A darker page, a red accent, large tab titles, rounded thumbnails, and glass buttons and cards on the watch page.
- **MeTube.** A new name and app id, so it installs alongside NewTube. Updates come from github.com/yashoncode/metube.

### 🛠️ Changed

- **Dark by default.** Light theme is still in Settings → User interface → Theme.
