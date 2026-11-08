# Compose / Kotlin metadata needed by the compiler-generated code.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-dontwarn kotlinx.**
-dontwarn org.apache.commons.compress.**

# Commons Compress resolves a few ZIP helpers by name and uses reflection for
# some optional codecs. Keep the ZIP package intact; it is small compared with
# the rest of the app and avoids subtle "works in debug, fails in release" bugs.
-keep class org.apache.commons.compress.archivers.zip.** { *; }

# 7z uses the same Commons Compress entry points as ZIP.
-keep class org.apache.commons.compress.archivers.sevenz.** { *; }

# junrar is a plain Java library with no reflection, but it is small enough that
# keeping it whole is cheaper than debugging a stripped class at runtime.
-keep class com.github.junrar.** { *; }
-dontwarn com.github.junrar.**
-dontwarn org.slf4j.**

# Baseline profiles reference app classes by name. Keep the profile installer's
# entry points so the AOT hints actually survive shrinking.
-keep class androidx.profileinstaller.** { *; }
