<div align=center>
<h1>Lumanga</h1>
<img src="assets/images/logo/logo.png" width=200>
</div>

An offline-first comic reader for Android. It reads archives straight from a
folder on the device and displays pages **without extracting any image to disk**.
The only network request is the optional "check for updates" button in About.

- Android 8.0 (API 26) and newer
- Multi-gigabyte archives (ZIP64) are read in place through a seekable file
  descriptor — no copy, no extra storage
- ZIP / CBZ, RAR (4 and 5, solid included), 7z and plain **folders of images**;
  all four can live in the same library folder, and a folder book is read in
  place without copying or rearranging anything
- **Several library folders** (settings → Library folders → add), combined into
  one shelf, plus a **category filter** built from each root's first-level
  folders (author, genre, series)
- UTF-8 / GBK / Shift_JIS entry names, natural page ordering
- **Locked portrait by default**: the app fixes its own orientation instead of following the phone's gravity sensor, so lying on your side no longer flips the whole UI into landscape. Tap "Landscape" in the reader's top bar for a two-page spread, then "Portrait" to switch back; "Follow system" is still available in the reading settings
- **Favourites**: long-press a cover on the shelf to add or remove a favourite; a pink heart appears in the cover's top-right corner. Favourites are stored separately and survive a "clear reading progress"
- **Shelf filter**: a segmented control above the grid switches between all / finished / currently reading, and combines with search, category and sorting
- **Guarded reset**: clearing all reading progress needs three consecutive taps (a pause over 2 s restarts the count) followed by a warning dialog whose confirm button unlocks only after a 3-second countdown
- Right-to-left manga mode, single/double page, **rapid consecutive** edge taps or swipes to turn pages, pinch zoom, reading progress
- Reader control bar: scrollable page-thumbnail preview (tap to jump), mirrored page seek bar with page numbers in manga mode, first/previous/next/last shortcuts, single-vs-double page and direction switches, collapse button; thumbnails use a dedicated small-bitmap cache
- Reading state follows the page you are on: turning back to the first page clears all progress and marks the book unread; merely opening a cover leaves no record
- Material 3 bottom navigation (icon + label, one travelling active indicator), swipe-between-pages; a pinned collapsing large-title app bar whose size/weight interpolate with scroll, plus a "back to top" FAB
- "Shelf / Currently reading" tabs, 2–5 covers per row, 7 sort modes, live search
- Persistent shelf index: a private JSON file of paths/metadata restores the shelf instantly on cold start, then a quiet background pass applies only real additions/removals. A "Refresh" chip forces an immediate re-check; up to 100,000 books are supported
- Material 3 Expressive styling: continuous gesture-driven indicators, shape morphing and horizontal page pushes
- **Six fresh accent colours** (mint, lake cyan, sky blue, lavender, sakura pink,
  apricot) in a "Personalise" settings page — near-white, low-saturation surfaces
  with a clean bright accent; each has its own light and dark palette with an
  animated transition between them
- **Four page-turn animations** (in the reading settings): slide, cross-fade,
  3D book flip and zoom-in, all applied to tap, swipe and seek-bar turns. The
  book flip keeps its spine on one fixed edge and reverses the fold when going
  back, instead of mirroring the animation to the other side. Switching to
  right-to-left moves the spine to the right but keeps the same visual rule in
  both directions: turning forward rises from the bottom, turning back settles
  from the top. The settled page
  stays as an opaque underlay, so a turn never flashes a black frame (scroll
  mode is continuous and has no page turns)
- Edge taps are handled one down/up pair at a time, so rapid consecutive taps
  always turn one page each instead of being folded into a single gesture;
  a burst snaps to the target page while a single tap still plays the animation
- Cover thumbnails cached in the app's private cache for instant re-opening; comic pages themselves are decoded in memory only

Full documentation is in [README.md](README.md) (Chinese).
Installation steps for non-technical users: [使用说明.md](使用说明.md).

Full documentation: [README.md](README.md) (Chinese).
Installation steps: [使用说明.md](使用说明.md).
Latest APK: [Releases](https://github.com/YingColor/Lumanga/releases/latest).

Source is provided under the [LICENSE](LICENSE) (MIT).
