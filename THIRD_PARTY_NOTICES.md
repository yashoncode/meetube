# Third-party notices & attributions

MeeTube bundles or depends on third-party software and data. This file collects
the required attributions and license notices.

MeeTube is Copyright (C) 2026-present yashoncode, licensed under the GNU General
Public License, version 3 (see [`LICENSE`](LICENSE)).

Additional permission under GNU GPL version 3 section 7: if you modify this
program, or any covered work, by linking or combining it with j2v8 (or a
modified version of it), containing parts covered by the terms of the Eclipse
Public License 1.0, the licensors of this program grant you additional
permission to convey the resulting work.

## Community data services

MeeTube can query the following community services. Their **data** is licensed
separately from their code, and MeeTube uses it under those terms:

- **SponsorBlock** — sponsor-segment data © SponsorBlock and its contributors,
  licensed under **CC BY-NC-SA 4.0**
  (https://creativecommons.org/licenses/by-nc-sa/4.0/).
  API: https://sponsor.ajay.app · Project: https://github.com/ajayyy/SponsorBlock
  MeeTube uses this data unmodified to skip segments; it does not redistribute
  the database. MeeTube is a non-commercial, free, open-source project.
- **DeArrow** — title/thumbnail branding data © DeArrow and its contributors,
  licensed under **CC BY-NC-SA 4.0**.
  API: https://sponsor.ajay.app/api/branding · Project: https://dearrow.ajay.app
  Used unmodified and non-commercially.
- **Return YouTube Dislike** — dislike-count data via
  https://returnyoutubedislikeapi.com (https://returnyoutubedislike.com).
  Used read-only, per the project's public API.

> These services are operated by their respective projects and are **not**
> affiliated with MeeTube. MeeTube's non-commercial status is a condition of
> using the SponsorBlock/DeArrow data (the NC term); do not add advertising,
> paid tiers, or other commercial monetization without re-evaluating these
> licenses.

## Bundled / vendored code

- **DoubleTapPlayerView** (`doubletapplayerview-media3/`) — © Vincent Kammerer,
  **MIT** (https://github.com/vkay94/DoubleTapPlayerView). MeeTube vendors a
  media3-rebased fork. See [`doubletapplayerview-media3/LICENSE`](doubletapplayerview-media3/LICENSE).
- **Slidr / SlidableActivity** (`slidableactivity/`) — © r0adkll, **Apache-2.0**.
  See [`slidableactivity/LICENSE.md`](slidableactivity/LICENSE.md).
- **filepicker-lib** (`filepicker-lib/`) — **Apache-2.0**.
  See [`filepicker-lib/LICENSE`](filepicker-lib/LICENSE).

## Submodules

- **MediaServiceCore** and **SharedModules** — © yuliskov, **MIT** (same license
  as SmartTube). MeeTube uses its own forks of these repositories.
  - SharedModules bundles **j2v8** (© EclipseSource, **Eclipse Public License
    v1.0**) and **Apache Commons IO** (**Apache-2.0**); their notices are
    preserved inside those directories.

## MeeTube

- **Kyant backdrop** (github.com/Kyant0/backdrop v2.0.0) — © 2025 Kyant,
  **Apache-2.0**. The rounded-rect refraction-with-dispersion and highlight AGSL
  shaders in `mobile/src/stmobile/java/com/newtube/mobile/ui/common/GlassView.java`
  are ported from it (as vendored by BitChord, github.com/kushagrasinghx/BitChord).
- **MIT KEMAR HRTF measurements** (compact set): © 1994 MIT Media Laboratory,
  Bill Gardner and Keith Martin; free to use with credit. MeeTune's Spatial sound
  head model (`MeeTuneHrtf.java`, made by `tools/kemar_hrtf.py`) is derived from it.

## Upstream

MeeTube started from code by **SmartTube** (https://github.com/yuliskov/SmartTube)
and **NewTube** (https://github.com/aleixrodriala/newtube). That code is under the
MIT License below, and its notice is kept as the license requires. MeeTube is not
affiliated with or endorsed by SmartTube's or NewTube's developers, Google, or
YouTube. "YouTube" and "Android" are trademarks of Google LLC.

```
MIT License

Copyright (c) 2020-present yuliskov

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```
