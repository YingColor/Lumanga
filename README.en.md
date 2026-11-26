<div align=center>
<h1>Lumanga</h1>
<img src="assets/images/logo/logo.png" width=200>
</div>

**English** | [简体中文](README.md)

A local-first Android manga reader: open `.zip` / `.cbz` archives straight from a folder on the
phone and read them without unpacking anything to disk. It never goes online except to check for
updates, or when you turn translation on yourself.

Android 8.0 (API 26) and newer. The interface follows Material 3 Expressive.

---

## This project is AI-generated

**Every line of code, every string in the interface and this document were written by an AI
(large language model).** The maintainer sets the requirements, checks each build on a real
phone, reports what looks wrong, and decides what ships.

- Nothing was typed by hand line by line: each piece was produced from a requirement and kept
  only after it ran correctly;
- Feature choices, visual style, icons and release cadence are the maintainer's;
- The commit history keeps release points only, not the development process.

This is stated plainly so you know what you are adopting. If code style, maintainability or
accountability matter to you, judge for yourself before using it. The source is provided as-is
under [LICENSE](LICENSE) (AGPL-3.0): use and modify it freely, but redistributing it means
releasing your version under AGPL-3.0 as well — it cannot become a closed-source product. The
balloon and text detection model comes from
[manga-translator-android](https://github.com/jedzqer/manga-translator-android) (AGPL-3.0); see
[NOTICE](NOTICE) for provenance and licensing.

## How it differs from a normal image viewer

The usual approach unpacks the archive and leaves a pile of image files on the phone, which costs
storage and time. This app does three things only:

1. reads the archive's **table of contents** to work out page order (no image data);
2. when a page is shown, expands and decodes **that page's** compressed bytes in memory;
3. releases it on the way out — the archive is all that ever exists on disk.

Because the archive is opened through a seekable file descriptor, even a multi-gigabyte book
**needs no copy** and takes no extra storage space.

> The one unavoidable step: a screen can only show a picture that has been decoded into a bitmap.
> That happens in memory, temporarily, and is never written to a file.

## Features

- Pick a manga folder once and it is remembered; the app opens on your shelf
- **Six interface languages plus "follow system"**: 简体中文, 繁體中文, English, 日本語, 한국어 and
  Русский, chosen in Personalize → Language. "Follow system" falls back to English for anything
  else. Translation itself is unaffected: the source and target languages of a translation are set
  separately in the translate settings
- **Reading status on top of the shelf**: All / Unread / Reading / Finished / Favorites as a full
  row of tabs, or as a dropdown in the toolbar (Personalize → where reading status shows). Tapping
  a tab switches and scrolls back to the top
- Shelf columns of 2 / 3 / 4 / 5 books, with cards tightening up automatically at higher counts
- 7 sort orders: name A→Z and Z→A, newest and oldest modified, largest, smallest, recently read
- Collapsible large title: size, weight and opacity interpolate continuously with the scroll, while
  the toolbar stays put (search / sort / columns / refresh / settings); a back-to-top button appears
  once you scroll
- Scans `.zip` / `.cbz` / `.rar` / `.7z` **and already-unpacked image folders**, subfolders
  included, mixed together in one directory if you like; covers, search and real progress (page x
  of y plus a bar, recorded once you turn a page)
- Folder-based manga are first-class citizens: images are listed and sorted by natural filename
  order, nothing is copied or generated, and page counts and progress are recorded exactly as for
  archives — the shelf shows "Folder" instead of a size
- **Several manga folders** (Settings → Manga folders → Add): internal storage, SD card, one folder
  per author or genre, all collected onto one shelf
- **One card per manga folder**: every folder you add becomes a card on the shelf — cover from the
  first page of the first book by name (it does not follow the sort order), badge counting the
  books. Tap it to see just that folder, back arrow to return; search looks across all folders and
  lists the matches directly
- Persistent shelf index: cold starts show the remembered shelf immediately and verify it quietly
  in the background, so thousands of books need no rescan; Refresh rechecks at once; scanning is
  capped at 100,000 books and 12 levels deep
- Cover cache across launches, downsampled per column count (160–360 px) with limited concurrency,
  so five books in a row stay smooth
- Motion follows one continuous state: the indicator tracks your drag with a shape squash, while
  colour, corner radius and type size come from the same progress value; pages slide in with
  parallax, and the system "reduce motion" setting is respected
- Reads archives in place, ZIP64 (over 4 GB) included
- Detects UTF-8 / GBK / Shift_JIS filenames so Japanese and Chinese names are not mojibake
- Natural page order (`page2` before `page10`), with readme files skipped
- jpg / png / webp / gif / bmp / heic / avif and friends
- Right-to-left (manga) or left-to-right paging, single page or spread, tap the edges to turn
  (rapid taps welcome, can be turned off), pinch to zoom, double-tap to reset
- **Two reading modes**: pages (single or spread, either direction) and **scroll** (continuous
  vertical scrolling, made for long webtoons). Scroll mode cuts a 20,000 px strip into ~1000 px
  tiles and decodes only what is on screen; opening a book reads just the image headers (about
  64 KB per page) to work out the total length
- In scroll mode: drag to read on, tap the sides to move one screen, jump with the page bar, or use
  first / previous / next / last; progress follows the page at the top of the screen
- **Seven scaling modes**, for pages and scrolling alike: original size, fit screen, fit width, fit
  height, fixed zoom (40–400%), stretch, cover; drag when the page is larger than the screen
- **Portrait is locked by default** so lying on your side does not suddenly rotate the app. The
  landscape / portrait button in the reader switches per book; the global default stays in
  settings, including "follow system"
- More reading options: origin (centre / left / right), screen orientation, image smoothing
  (smooth / sharp), backlight (follow the system, or adjust inside the app only)
- **Favorites**: long-press a cover, then Favorite — a pink heart appears on the cover. Clearing
  reading progress does not clear favorites
- **Mis-tap guards**: clearing reading progress needs three taps in a row (a 2 s pause restarts the
  count) and then three seconds of reading the warning; deleting stored translations works the same
  way
- **Two-level settings**: the shelf button opens the settings window (reading settings, manga
  folders, personalize, shelf columns, clear progress, about), shown as a frosted-glass card that
  blurs only what is behind it
- Back button steps back one layer at a time: close the settings window, hide the toolbar, then
  leave the book
- Reader bottom bar: a scrollable **page thumbnail strip** (tap to jump, current page outlined),
  mirrored page bar and page numbers in manga mode, first / previous / next / last, single-page and
  direction shortcuts, hide toolbar
- Everything applies immediately (one observable state source), the last page is remembered, the
  screen can stay awake, fully immersive
- **Six soft theme colours** (matcha, lake, sky, lavender, sakura, tangerine) with matching light
  and dark palettes; colours cross-fade when you switch
- **Four page animations**: slide, fade, curl (3D around the spine) and zoom; the spine stays on the
  correct side for the reading direction, and the reverse turn is the same animation played
  backwards
- **Live translation (optional, off by default)**: recognises the foreign text on the page, groups
  it by speech bubble, translates it and puts it back, erasing the original first. The 译 button in
  the reader toggles it. You supply the service (OpenAI-compatible or a few other protocols); the
  model list can be fetched from the endpoint. No translation service is bundled, images never
  leave the phone, and only recognised text is sent
- **Four built-in recognition models**: `PP-OCRv6_small` (default, fast and light),
  `PP-OCRv6_medium` (more accurate, noticeably slower), `PP-OCRv6_small + medium` (medium's
  detector with small's recogniser — better on busy artwork) and `MangaOCR_mobile` (whole bubble at
  once, Japanese only). All are bundled, nothing to download
- **Pre-translate a whole book**: long-press a cover → Pre-translate, pick pages, and the text is
  stored on the phone; later pages open without waiting and work offline. Only text and bubble
  positions are stored (a few hundred KB per book)
- **Batch pre-translate by folder**, with a resumable queue and a "Translated" badge on covers
- Light / dark / follow system
- Large images are downsampled to the screen with a pixel budget to avoid crashes; neighbouring
  pages are prefetched

## Permissions and privacy

The app requests `INTERNET` only, for two things, both when needed:

1. About → Check for updates reads the public GitHub releases page;
2. **after you turn live translation on yourself**, recognised text is sent to the translation
   endpoint you configured; fetching the model list reads that endpoint's list.

Translation is off by default and the service address is empty, so nothing is sent until you fill it
in. Only text leaves the phone; the manga images never do. No storage permission is requested and no
permission dialog is ever shown.

File access goes through the system folder picker (Storage Access Framework): you pick a folder
once, the app gets read access to that folder only, and keeps it with
`takePersistableUriPermission`. Archives are never modified.

**About RAR temp files**: the RAR library wants a real path, so the open file descriptor is passed as
`/proc/self/fd/<n>` and nothing is copied. Only cloud-storage directories that can only be read
sequentially get a temporary copy in the private cache `cacheDir/rar`, deleted when the book is
closed.

**About cover thumbnails**: manga pages are decoded in memory only and never written to disk. The one
exception is cover thumbnails — 320 px JPEGs in the app's private cache `cacheDir/covers`, so
returning to the shelf does not unpack covers again. They are not in your manga folder, not the
original artwork, removed with the app, and a hundred books cost a few MB. The system clears the
directory under storage pressure.

**About the shelf index**: `filesDir/shelf_index.json` stores each book's URI, name, byte count and
modification time — **no manga content** — and is never written into your folders. If a scan comes
back empty while the cache is not, that is treated as a permission hiccup and the shelf is never
wiped. Changing folders invalidates it; uninstalling removes it.

## Installing on a phone

See [使用说明.md](使用说明.md) (Chinese). In short: copy the APK to the phone, open it, allow
installing from unknown sources, then pick a folder once.

There are three packages on the release page. Reading is identical in all of them; translation is
what differs:

- `Lumanga-<version>.apk` (full, about 200 MB): all four recognisers inside, translates offline.
- `Lumanga-<version>-lite.apk` (about 62 MB): no recognisers — every page is read at the address
  configured in Translation settings → OCR settings, so it needs a network and a vision model.
- `Lumanga-<version>-reader.apk` (about 2 MB): **no translation at all**, just the reader. It ships
  no recognisers, no balloon segmenter and no translation font, and never goes online.

All three share the same applicationId and signing key, so they **replace each other in place**:
books, reading progress and stored translations survive the swap. Installing the reader build does
not delete translations you already made — they simply are not drawn, and come back if you
reinstall the full or lite build.

## Building from source

You need JDK 17+ and the Android SDK (Platform 36, Build Tools 36.0.0). `junrar` and `xz`, used for
RAR and 7z, are committed as jars in `app/libs`, so no download is needed.

```bash
./gradlew assembleDebug          # debug build (slower on a phone, for troubleshooting)
./gradlew assembleFullRelease    # full: the four recognisers are bundled
./gradlew assembleLiteRelease    # lite: no recognisers, about a third of the size
./gradlew assembleReaderRelease  # reader: no translation, no recognisers or fonts
```

Outputs land in `app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk` (debug in
`apk/debug/`). All flavours use the same debug signing key so they can be installed over one
another.

> If `maven.google.com` is unreachable, `settings.gradle.kts` lists
> `https://dl.google.com/dl/android/maven2` first as a reachable mirror.

Main versions: Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.12.01 / Commons Compress
1.27.1 / ProfileInstaller 1.3.1.

## Long webtoon strips

Choose **Scroll** in Reading settings → Reading mode (the reader's bottom bar has a shortcut). Then
drag up and down to read on, tap the sides to move one screen, tap the middle for the toolbar, and
use the page bar to jump.

Why long strips used to be painful: an 800 × 20,000 px strip needs a ~64 MB bitmap and often exceeds
the GPU texture limit, so it blurs, stutters or refuses to open. Now:

- opening the book decompresses only the first 64 KB of each page to read its dimensions, so the
  total length is known almost immediately;
- the strip is cut into ~1000 px tiles and `BitmapRegionDecoder` decodes **only the visible ones**;
- the decompressed page data is kept once and shared by all of its tiles;
- JPEG / PNG / WebP use region decoding; HEIF / AVIF are cropped through `ImageDecoder`, falling back
  to "shrink the whole image, then crop";
- full-page decoding is capped at 8 megapixels, so a stray long strip in page mode cannot eat tens of
  megabytes.

Throughout, pages stay **in memory**; the archive is all that is on disk.

## Supported formats and limits

| Item | Notes |
| --- | --- |
| Archives | `.zip`, `.cbz` (ZIP64 included) |
| RAR | `.rar` (RAR 4 / 5, solid archives too) |
| 7z | `.7z` (LZMA / LZMA2, solid too) |
| Image folders | Images directly in a folder, ordered by natural filename; subfolders with images are treated as one work per level |
| Images | jpg / png / webp / gif / bmp / heic / heif / avif / jxl / tiff (depends on the system decoder) |
| Not supported | Multi-volume archives, encrypted or password-protected archives, non-standard compression |
| Folder location | Ordinary folders in internal storage or on an SD card work best |
| Cloud directories | Some can only be read sequentially and are reported as such |
| Single page size | Images over 96 MB decoded are refused to avoid running out of memory |
| Long strips | Use scroll mode; HEIF / AVIF strips fall back to a lower-resolution path |

For a multi-gigabyte archive the first open only reads the table of contents, so the time depends on
the number of entries rather than the size.

## Project structure

```
app/src/main/java/com/yingcolor/lumanga/
├── MainActivity.kt        entry point and screen switching
├── UiStrings.kt           interface text, looked up per language
├── LangText.kt            Lang enum, Texts class and the Chinese table
├── LangText*.kt           one text table per language (en / ja / ko / tw / ru)
├── theme/Theme.kt         M3 Expressive shapes, motion, colour transitions
├── theme/Palettes.kt      six palettes (neutral skeleton + accent mixing)
├── data/
│   ├── PageSource.kt      zip / rar / 7z / folder page sources
│   ├── ArchiveReader.kt   zip reading core
│   ├── PageDecoder.kt     image decoding and memory budget
│   ├── ComicRepository.kt shelf scanning and cover cache
│   ├── ShelfIndex.kt      persistent shelf index
│   ├── Prefs.kt           settings and reading progress
│   ├── UiSettings.kt      observable settings (immediate refresh)
│   └── Models.kt
└── ui/
    ├── ShelfScreen.kt     shelf, sorting, columns
    ├── ReaderScreen.kt    reader and tap-to-turn layer
    ├── WebtoonScreen.kt   scroll mode (tiled long strips)
    ├── SettingsSheet.kt   settings window
    ├── Backdrop.kt        frosted-glass snapshot for the settings card
    ├── Motion.kt          motion curves and continuous selection helpers
    └── Components.kt      pill buttons, segmented choices and friends
```

## Credits

This project builds on models and fonts made by other people; the credit is theirs.

| Source | Licence | Used for |
|---|---|---|
| [jedzqer / manga-translator-android](https://github.com/jedzqer/manga-translator-android) | AGPL-3.0 | Balloon / free-text detection model `mixed-dual-s-e5_float16.tflite`; the idea of re-running detection per balloon also comes from it |
| [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) PP-OCRv6 Small | Apache-2.0 | Default text detection and recognition (LiteRT conversion by [LiteRT Community](https://huggingface.co/litert-community/PP-OCRv6-Small-LiteRT)) |
| [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) PP-OCRv6 Medium | Apache-2.0 | High-accuracy model for precise detection and re-reading (official ONNX export, converted to FP16 here) |
| [bluolightning / manga-ocr-mobile](https://huggingface.co/bluolightning/manga-ocr-mobile) | Apache-2.0 | Optional MangaOCR_mobile recogniser |
| [ZCOOL KuaiLe](https://fonts.google.com/specimen/ZCOOL+KuaiLe) | SIL OFL 1.1 | Bundled font used to set translated text |

Versions and licences of the runtime libraries (TensorFlow Lite, ONNX Runtime, Apache Commons
Compress / IO, junrar, XZ for Java, SLF4J, AndroidX and Jetpack Compose), together with the full file
list of the models above, are in [`NOTICE`](NOTICE) and
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Contributing

Issues and pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md).
