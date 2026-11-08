<div align=center>
<h1>Lumanga</h1>
<img src="assets/images/logo/logo.png" width=200>
</div>

An offline, zero-permission comic reader for Android. It reads `.zip` / `.cbz`
archives straight from a folder on the device and displays pages **without
extracting any image to disk**.

- Android 9 (API 28) and newer
- Multi-gigabyte archives (ZIP64) are read in place through a seekable file
  descriptor — no copy, no extra storage
- UTF-8 / GBK / Shift_JIS entry names, natural page ordering
- Right-to-left manga mode, single/double page, **rapid consecutive** edge taps or swipes to turn pages, pinch zoom, reading progress
- Reader control bar: scrollable page-thumbnail preview (tap to jump), mirrored page seek bar with page numbers in manga mode, first/previous/next/last shortcuts, single-vs-double page and direction switches, collapse button; thumbnails use a dedicated small-bitmap cache
- Reading state follows the page you are on: turning back to the first page clears all progress and marks the book unread; merely opening a cover leaves no record
- Material 3 bottom navigation (icon + label, one travelling active indicator), swipe-between-pages; a pinned collapsing large-title app bar whose size/weight interpolate with scroll, plus a "back to top" FAB
- "Shelf / Currently reading" tabs, 2–5 covers per row, 7 sort modes, live search
- Persistent shelf index: a private JSON file of paths/metadata restores the shelf instantly on cold start, then a quiet background pass applies only real additions/removals. A "Refresh" chip forces an immediate re-check; up to 100,000 books are supported
- Material 3 Expressive styling: continuous gesture-driven indicators, shape morphing and horizontal page pushes
- Cover thumbnails cached in the app's private cache for instant re-opening; comic pages themselves are decoded in memory only
- No permissions declared in the manifest, no network access

Full documentation is in [README.md](README.md) (Chinese).
Installation steps for non-technical users: [使用说明.md](使用说明.md).

## Performance

Previous builds shipped an unoptimised **debug** APK, which is noticeably slower
for Jetpack Compose. The published APK is a **release** build: R8 code optimisation,
resource shrinking and the AndroidX baseline profiles shipped inside the AARs
(`assets/dexopt/baseline.prof`). The APK dropped from 27.6 MB to 2.6 MB and uses
the same signing key, so it installs over the old build without losing data.

On the code side, all archive reads now go through one worker with three priority
queues (visible page > neighbours > preview strip) instead of dozens of coroutines
fighting over a single ZIP channel; previews use much tighter sampling; the
collapsing header no longer recomposes the whole grid on every scroll frame;
covers return synchronously on a memory hit; and search no longer re-sorts the
whole library on every keystroke.

## Long Webtoon strips

A new **scroll** reading mode joins all pages into one continuous vertical list,
with no page breaks. Very tall strips are decoded in ~1000 px bands via
`BitmapRegionDecoder`, so an 800 x 20,000 px image never becomes a single
64 MB bitmap; page sizes come from only the first 64 KB of each entry. Fit
mode, alignment, screen orientation, image smoothing and an app-only
brightness override were added as general reader settings, implemented
independently from scratch.

The launcher icon is an AI-generated portrait of the same silver-lilac character,
shipped at full frame as a proper adaptive icon (separate foreground layers at
every density plus a legacy rounded-square fallback). The final artwork carries
no watermark and is used without cropping or retouching. Details in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Build with `./gradlew assembleRelease` (or `assembleDebug` for debugging only).
