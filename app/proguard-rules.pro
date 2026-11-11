-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-dontwarn kotlinx.**
-dontwarn org.apache.commons.compress.**

-keep class org.apache.commons.compress.archivers.zip.** { *; }

-keep class org.apache.commons.compress.archivers.sevenz.** { *; }

-keep class com.github.junrar.** { *; }
-dontwarn com.github.junrar.**
-dontwarn org.slf4j.**

-keep class androidx.profileinstaller.** { *; }

-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
