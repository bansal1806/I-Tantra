plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.itantra.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.itantra.app"
        // minSdk 24 (Android 7.0) -- covers low/mid-range phones per the PS's target hardware,
        // while still getting a modern-enough audio/permissions API surface.
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-m1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The sherpa-onnx AAR ships native .so for 4 ABIs; every real device we've tested
        // on (and the overwhelming majority of Android phones in the field today) is
        // arm64-v8a. Bundling armeabi-v7a/x86/x86_64 too was pure dead weight -- roughly
        // doubled the APK for architectures nothing we're targeting actually uses. M4
        // efficiency pass: restrict to what's real.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    // Model weights don't compress well (already dense/quantized) and there's a lot of
    // them -- skip deflate for these asset extensions to keep build times sane and avoid
    // needless CPU/battery cost unpacking them from the APK at install time.
    androidResources {
        noCompress += listOf("onnx", "onnx_data")
    }

    packaging {
        resources {
            excludes += "META-INF/*"
        }
    }
}

dependencies {
    // sherpa-onnx: no Maven/JitPack artifact, so the prebuilt AAR (Kotlin API + JNI .so per
    // ABI) from https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.7 lives in libs/ --
    // same version as desktop-spike's sherpa-onnx pip package, for consistency.
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // For extracting downloaded TTS voice archives (ModelManager) -- the sherpa-onnx
    // tts-models release only ships them as .tar.bz2, and there's no bzip2 support in the
    // Android/JVM standard library.
    implementation("org.apache.commons:commons-compress:1.26.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
