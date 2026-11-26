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
        // "版本 <name>（构建 <code>）". The name is set by the user (1.1.0 as of build 125) and
        // only this counter has to keep rising. The release notes must repeat the same number on
        // their first line, or the in-app update check either misses a build or offers one that
        // is already installed.
        versionCode = 138
        versionName = "1.1.4"
        vectorDrawables { useSupportLibrary = true }
        // Both OCR backends ship native libs for four ABIs. Only real phones matter here;
        // dropping the emulator-only x86 ones keeps the APK from growing by another ~40 MB.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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

    // Three builds of the same app, one per way of using it. All three keep the same
    // applicationId and the same signing key, so they replace each other in place and the books,
    // progress and saved translations survive the swap.
    //
    //   full   - bundles the on-device recognisers (160 MB of assets), works offline
    //   lite   - no recognisers, recognises every page at the endpoint from OCR settings
    //   reader - no translation at all: no recognisers and no balloon segmenter (21 MB), the
    //            cheapest package for someone who only wants to read
    //
    // HAS_TRANSLATION is the switch the code reads: false removes every translate entry point
    // (book menu, drawer, reader toolbar, config dialog) and forces the stored toggle off, so
    // installing reader over full cannot leave it translating in the background.
    flavorDimensions += "ocr"
    productFlavors {
        create("full") {
            dimension = "ocr"
            buildConfigField("boolean", "HAS_LOCAL_OCR", "true")
            buildConfigField("boolean", "HAS_TRANSLATION", "true")
            resValue("string", "app_name", "光漫堂")
        }
        create("lite") {
            dimension = "ocr"
            buildConfigField("boolean", "HAS_LOCAL_OCR", "false")
            buildConfigField("boolean", "HAS_TRANSLATION", "true")
            resValue("string", "app_name", "光漫堂 精简版")
        }
        create("reader") {
            dimension = "ocr"
            buildConfigField("boolean", "HAS_LOCAL_OCR", "false")
            buildConfigField("boolean", "HAS_TRANSLATION", "false")
            resValue("string", "app_name", "光漫堂 阅读版")
        }
    }

    sourceSets {
        // assets/detect is the balloon segmenter. It exists only to give translated text the
        // shape of the bubble behind it, so it is dead weight in a build with no translation.
        // It lives outside every flavour directory and is added to the two translating ones
        // explicitly, which keeps a single 21 MB copy in git instead of one per flavour.
        getByName("full").assets.srcDir("src/shared-assets")
        getByName("lite").assets.srcDir("src/shared-assets")
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

// The reader build carries no recognisers, so the two inference runtimes and their JNI shims are
// dead weight there: 38 MB across the two ABIs, most of the package. Nothing in that build loads
// them — the OCR engine, the segmenter and the recogniser init are all behind HAS_TRANSLATION —
// so dropping the shared libraries only removes bytes. The classes stay in the dex (they cost a
// couple of hundred KB) because their keep rules are shared with the other flavours.
androidComponents {
    onVariants(selector().withFlavor("ocr" to "reader")) { variant ->
        variant.packaging.jniLibs.excludes.addAll(
            listOf(
                "**/libonnxruntime.so",
                "**/libonnxruntime4j_jni.so",
                "**/libtensorflowlite_jni.so",
                "**/libtensorflowlite_gpu_jni.so"
            )
        )
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
