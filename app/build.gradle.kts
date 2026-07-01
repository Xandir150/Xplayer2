plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.teleteh.xplayer2"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.teleteh.xplayer2"
        minSdk = 29
        targetSdk = 36
        versionCode = 23
        versionName = "1.0.10b4"

        ndk {
            // ARM-only by policy: every real target (phones, XReal/RayNeo glasses, the XREAL Beam
            // box) is ARM, so we don't ship x86/x86_64 at all — no x86 prebuilts in the tree, and
            // x86 is emulator-only anyway (TFLite's x86_64 .so isn't even 16 KB-aligned). Run the app
            // on an ARM emulator image. Smaller APK, fully 16 KB-compliant.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("ANDROID_KEYSTORE_PATH") ?: "${rootDir}/keystore.jks"
            storeFile = file(ksPath)
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                ?: (project.findProperty("RELEASE_STORE_PASSWORD") as String?)
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                ?: (project.findProperty("RELEASE_KEY_ALIAS") as String?)
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                ?: (project.findProperty("RELEASE_KEY_PASSWORD") as String?)
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // VITURE's prebuilt libsdk.so is 4 KB-aligned and is the ONLY thing blocking Google Play's
    // 16 KB page-size requirement (our code, ffmpeg and TFLite are already 16 KB-aligned). Split it
    // by flavor: `play` ships WITHOUT VITURE → fully 16 KB-compliant, for Google Play; `full`
    // includes VITURE 2D/3D → for GitHub/sideload.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"   // no VITURE -> 16 KB compliant (Google Play)
            // YouTube is ON here too: the feature is invisible (no UI button, no youtube.com manifest
            // filter) — it only does anything when the user pastes/shares a YouTube link — and we
            // simply don't advertise it in the Play listing. This flag stays as a kill-switch we can
            // flip to false (rebuild) if Google ever objects.
            buildConfigField("boolean", "YOUTUBE_ENABLED_DEFAULT", "true")
        }
        create("full") {
            dimension = "distribution"   // + VITURE One SDK (libsdk.so) (GitHub sideload)
            buildConfigField("boolean", "YOUTUBE_ENABLED_DEFAULT", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    androidResources {
        localeFilters += listOf("en", "ru", "es", "zh")
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // VITURE SDK ships libsdk.so (arm64-v8a + armeabi-v7a). Legacy packaging extracts it
            // on install so System.loadLibrary finds it on older boxes (e.g. Amlogic S905Y4 / v7a).
            useLegacyPackaging = true
            // libffmpegJNI.so ships prebuilt in src/main/jniLibs (audio: AC-3/E-AC-3/DTS/…; see
            // BUILDING.md). If a local rebuild also produces one (via the optional jni/ffmpeg
            // symlink), pickFirst keeps the duplicate from failing packaging.
            pickFirsts += "**/libffmpegJNI.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.org.jetbrains.kotlinx.coroutines.core)
    implementation(libs.org.jetbrains.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(project(":media3-lib-exoplayer"))
    implementation(project(":media3-lib-exoplayer-hls"))
    implementation(project(":media3-lib-ui"))
    // MediaSession for foreground playback service
    implementation(project(":media3-lib-session"))
    // Image loading for device icons and thumbnails (Coil 3: network loading is a separate artifact)
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    // Local Media3 FFmpeg decoder module
    implementation(project(":media3-lib-decoder-ffmpeg"))
    // TensorFlow Lite for the Lazy-3D depth estimator (Depth-Anything-V2-Small).
    // NNAPI (NPU) delegate is built into the core runtime; GPU delegate is split out.
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.gpu)
    implementation(libs.tensorflow.lite.gpu.api)
    // VITURE One SDK (ArManager) for VITURE glasses 2D/3D switching. The .aar is NOT committed
    // (no redistribution): the release workflow downloads it into app/libs, and devs fetch it
    // locally. The fileTree include is empty-safe if the .aar is absent (build still succeeds).
    // VITURE One SDK: ONLY in the `full` flavor (its libsdk.so is 4 KB-aligned). The `play` flavor
    // omits it for 16 KB compliance and uses the no-op VitureController in src/play.
    "fullImplementation"(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}