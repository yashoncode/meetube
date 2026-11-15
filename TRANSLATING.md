# Translating MeeTube

MeeTube is translated through pull requests on GitHub: edit the string files for your language and
open a pull request in this repository. Merged translations ship in the next release.

## What to translate

The phone app and its shared libraries use separate resource files:

| Part | What it covers | Where it lives | Current coverage |
|:--|:--|:--|:--|
| **Phone app** | Home feed, player, comments, downloads, casting, sign-in, updates and mobile-only features | `mobile/src/stmobile/res/values-*/strings_*.xml` | English source; Spanish, Polish and Russian translations exist, with different coverage by feature |
| **Shared settings and messages** | Settings screens, player menus and messages inherited from [SmartTube](https://github.com/yuliskov/SmartTube) | `common/src/main/res/values-*/strings.xml` | Many languages; some are incomplete |
| **File picker** | Labels used by the file-picker library | `filepicker-lib/src/main/res/values-*/strings.xml` | Separate library resources |

The phone app has several string files. Translate the matching file rather than putting every
string into `strings_mobile.xml`:

- `strings_mobile.xml`: the main phone interface and player.
- `strings_settings.xml` and `strings_settings_search.xml`: settings pages and settings search.
- `strings_web_signin.xml`: the separate web sign-in flow.
- `strings_community.xml`: community-related labels.
- `strings_playlist.xml`: playlist page and playlist actions.
- `strings_meetune.xml`: MeeTune and its equalizer.

A locale folder may contain only some of these files. Coverage differs by feature, so the presence
of a `values-<language>` folder does not mean the whole app is translated. Update existing files
for that locale; when a file is not present yet, use the matching English file as the template and
keep its filename.

Some shared strings belong to SmartTube's TV interface and never appear on a phone. You can skip
them when translating the phone app.

## Style

- Match the tone of YouTube's own app in your language. If YouTube calls it "Watch later", use its
  translation of "Watch later". People recognise those words.
- Keep it short. Most strings are buttons, menu items and one-line messages on a phone screen.
- Keep **MeeTube**, **MeeTune**, **SmartTube**, **YouTube**, **SponsorBlock**, **DeArrow** and
  **Return YouTube Dislike** as they are.
- Keep placeholders such as `%1$s` and `%2$d` exactly as written. You can move them within the
  sentence.
- `%%` is a literal percent sign. Keep both characters.
- For counts ("1 reply", "5 replies"), fill in every plural form your language uses. Russian uses
  `one`, `few`, `many` and `other`.

## Trying your translation

Merged translations ship in the next release, and every release is on the
[releases page](https://github.com/yashoncode/meetube/releases).

To have your language added, copy the matching English file into a new `values-<language>` folder
and translate it. If you think a string is unclear or wrong in English,
[open an issue](https://github.com/yashoncode/meetube/issues/new/choose).

## For developers

- Add new user-visible text to the matching English resource file under
  `mobile/src/stmobile/res/values/`. Use `strings_mobile.xml` for the main phone interface and
  the corresponding `strings_*.xml` file for settings, playlists, MeeTune or another feature.
- Update the Spanish resource when maintaining the Spanish translation. Other locale files are
  maintained separately; do not assume that every locale has every resource file.
- Anything the user should not translate (formats such as `0:00`, brand names, glyph paths) gets
  `translatable="false"`.
- Use `<plurals>` and `getQuantityString` for counts, and one format string with numbered
  placeholders rather than joining pieces of a sentence in code.
- To check a translation locally, run
  `./gradlew :mobile:lintStmobileDebug -PtranslationLint`. This enables the translation-focused
  lint checks configured in `mobile/build.gradle`: string format, plural quantity, extra
  translation keys and string escaping.
- There is currently no translation-specific GitHub Actions workflow in this repository. Run the
  command above locally; do not rely on `.github/workflows/translations.yml`.

