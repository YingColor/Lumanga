plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.yingcolor.comicviewer"
    compileSdk = 36

    defaultConfig {
        // The public identity of the app. The Kotlin namespace stays put so the
        // source tree and resource references do not need to move.
        applicationId = "com.yingcolor.lumanga"
        minSdk = 28
        targetSdk = 36
        versionCode = 11
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ""
        }
        release {
            // A debug build runs Compose through an unoptimised, un-AOT-compiled
            // runtime and is measurably slower on a phone. R8 + resource shrinking
            // plus the AndroidX baseline profiles are what make a real release
            // build feel like a native viewer instead of a prototype.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same signing key as the debug build, so the optimised APK installs
            // straight over the previous one with no uninstall / data loss.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    lint {
        // Lint's automatic "vital" pass on assembleRelease only produces static
        // warnings and forces an extra tool download. The full checks are still
        // available via ./gradlew lint, and CI runs them explicitly when desired.
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Installs the baseline profiles shipped inside the AndroidX / Compose AARs,
    // so the system can ahead-of-time compile the scroll and layout hot paths.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("org.apache.commons:commons-compress:1.27.1")
    // 7z needs these two at runtime: commons-io for its stream helpers and XZ for
    // the LZMA/LZMA2 coders every .7z uses.
    implementation("commons-io:commons-io:2.16.1")
    implementation(files("libs/xz-1.10.jar"))

    // RAR support (junrar). Bundled as jars instead of a Maven coordinate so the
    // build stays reproducible offline; junrar only needs slf4j's API, and the
    // NOP logger is enough for a reader.
    implementation(files("libs/junrar-8.1.2.jar"))
    implementation(files("libs/slf4j-api-2.0.17.jar"))

    debugImplementation("androidx.compose.ui:ui-tooling")
}
