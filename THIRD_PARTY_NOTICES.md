# Third-party notices

This project is distributed under the MIT License (see `LICENSE`). The following
third-party libraries are used at build time.

## Apache Commons Compress (runtime dependency)

- Version: 1.27.1
- License: Apache License, Version 2.0
- Project: https://commons.apache.org/proper/commons-compress/
- Usage: reads ZIP/CBZ entries from a `SeekableByteChannel`, handles ZIP64 and
  non-UTF-8 entry names.

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

## Launcher icon

The launcher icon is an AI-generated illustration containing no third-party
artwork, character design, logo or icon set. It is shipped as adaptive-icon
foreground layers for every density, plus legacy rounded-square fallbacks.
