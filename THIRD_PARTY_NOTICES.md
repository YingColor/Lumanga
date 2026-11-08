# Third-party notices

This project is distributed under the MIT License (see `LICENSE`). The following
third-party libraries are used at build time.

## Apache Commons Compress (runtime dependency)

- Version: 1.27.1
- License: Apache License, Version 2.0
- Project: https://commons.apache.org/proper/commons-compress/
- Usage: reads ZIP/CBZ entries from a `SeekableByteChannel`, handles ZIP64 and
  non-UTF-8 entry names.

- Also used for `.7z`: `SevenZFile` reads the archive through the same seekable
  channel, so nothing is extracted to disk.

The full Apache-2.0 text is available at
https://www.apache.org/licenses/LICENSE-2.0 . No source files from Commons Compress
are vendored into this repository; the library is resolved at build time from Maven Central.

## AndroidX / Jetpack Compose / Kotlin (build dependencies)

- License: Apache License, Version 2.0
- Source: https://developer.android.com/jetpack and https://github.com/JetBrains/kotlin
- Resolved at build time from Google's Maven repository and Maven Central.
- Includes `androidx.profileinstaller:profileinstaller:1.3.1` (Apache-2.0), which
  installs the baseline profiles shipped inside the AndroidX AARs so the system
  can ahead-of-time compile the app's scroll / layout hot paths.

## Apache Commons IO (runtime dependency)

- Version: 2.16.1
- License: Apache License, Version 2.0
- Project: https://commons.apache.org/proper/commons-io/
- Usage: stream helpers that Commons Compress needs while decoding 7z blocks.

## XZ for Java (bundled jar, `app/libs/xz-1.10.jar`)

- Version: 1.10
- License: Public Domain
- Project: https://tukaani.org/xz/java.html
- Usage: LZMA / LZMA2 decoder used by every `.7z` archive.

## junrar (bundled jar, `app/libs/junrar-8.1.2.jar`)

- Version: 8.1.2
- License: the "unrar" license — free to use for reading/extracting RAR archives;
  it may not be used to build a RAR-compatible *archiver*. This project only reads.
- Project: https://github.com/junrar/junrar
- Usage: reads `.rar` (RAR 4 and RAR 5) entries without unpacking the archive first.

## SLF4J API (bundled jar, `app/libs/slf4j-api-2.0.17.jar`)

- Version: 2.0.17
- License: MIT
- Project: https://www.slf4j.org/
- Usage: logging facade required by junrar; no provider is shipped, so it stays a no-op.

## Launcher icon

The launcher icon is an AI-generated illustration containing no third-party
artwork, character design, logo or icon set. It is shipped as adaptive-icon
foreground layers for every density, plus legacy rounded-square fallbacks.
