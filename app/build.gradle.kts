plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.yingcolor.lumanga"
    compileSdk = 36

    defaultConfig {
        
        
        
        applicationId = "com.yingcolor.lumanga"
        
        
        
        
        minSdk = 26
        targetSdk = 36
        // Bumped with every delivered build so an install can be told apart: 关于 dialog shows
        // "版本 <name>（构建 <code>）". Version *name* stays 1.5.0 on purpose — only this counter
        // moves. The release notes must repeat the same number on their first line, or the
        // in-app update check either misses a build or offers one that is already installed.
        versionCode = 124
        versionName = "1.5.0"
        vectorDrawables { useSupportLibrary = true }
        // Both OCR backends ship native libs; the ABIs are chosen by the splits block below
        // (ndk.abiFilters and splits.abi cannot both be set). x86 emulators are left out.
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ""
        }
        release {
            
            
            
            
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            
            
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        
        
        buildConfig = true
    }

    lint {
        
        
        
        checkReleaseBuilds = false
    }

    // One APK per ABI. The two native stacks (LiteRT + ONNX Runtime) cost 24 MB on arm64 and
    // 17 MB more for 32-bit devices that will never run this build: shipping both in one file
    // makes every install pay for the other one. The arm64 file is the one handed out; the
    // armeabi-v7a one is kept for old phones.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    androidResources {
        // The LiteRT library asks AGP to store every .tflite uncompressed so the models can be
        // memory-mapped. Nothing here maps them — every model is streamed into filesDir on first
        // use (PaddleOcrEngine.copyAssets) — so the files are deflated instead: ~18 MB smaller
        // APK for the same bytes on disk.
        noCompress.clear()
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
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    // The 21 MB fp16 balloon-segmentation model needs ~2 GB of CPU arena, which left the
    // process 4 MB of native headroom and made it trivially killable. The GPU delegate keeps
    // the working set in GPU memory instead; BubbleSegmenter falls back to plain CPU if the
    // delegate cannot be created.
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    // GpuDelegateFactory.Options lives in the -api artifact; R8 refuses to shrink without it.
    implementation("org.tensorflow:tensorflow-lite-api:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")
    // ONNX Runtime runs the bundled PP-OCRv6_medium FP16 models; the small model stays on LiteRT.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    
    
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("org.apache.commons:commons-compress:1.27.1")
    
    
    implementation("commons-io:commons-io:2.16.1")
    implementation(files("libs/xz-1.10.jar"))

    
    
    
    implementation(files("libs/junrar-8.1.2.jar"))
    implementation(files("libs/slf4j-api-2.0.17.jar"))

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// LiteRT pulled androidx.fragment 1.1.0 whose AAR is not in the offline cache. Not used any
// more, kept out of the dependency graph now that the GPU delegate replaced it.
