plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.teddybear.aura"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.teddybear.aura"
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "beta - 0.7.0.a"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    // Нужно для FFmpeg — библиотека пока не поддерживает 16 KB page alignment
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
        }
    }
}

dependencies {
    // ── Compose BOM ──────────────────────────────────────────────────────────
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    // ── View-based UI (нужны если используешь XML-компоненты или темы) ───────
    implementation(libs.material)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)

    // ── Core / Lifecycle ─────────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.process)

    // ── Media3 (ExoPlayer) ───────────────────────────────────────────────────
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    // ── Room ─────────────────────────────────────────────────────────────────
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ── DataStore ────────────────────────────────────────────────────────────
    implementation(libs.androidx.datastore.preferences)

    // ── WorkManager ──────────────────────────────────────────────────────────
    implementation(libs.androidx.work.runtime.ktx)

    // ── Network ──────────────────────────────────────────────────────────────
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.gson)
    implementation(libs.gson)

    // ── FFmpeg ───────────────────────────────────────────────────────────────
    implementation(libs.ffmpeg.kit.audio)

    // ── NewPipe Extractor ────────────────────────────────────────────────────
    implementation(libs.newpipe.extractor)

    // ── QR / Barcode ─────────────────────────────────────────────────────────
    implementation(libs.zxing.android)
    implementation(libs.zxing.core)

    // ── Crypto ───────────────────────────────────────────────────────────────
    implementation(libs.bouncycastle)

    // ── Image loading ────────────────────────────────────────────────────────
    implementation(libs.coil.compose)

    // ── Utilities ────────────────────────────────────────────────────────────
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)

    // ── JAudioTagger ─────────────────────────────────────────────────────────
    implementation(libs.jaudiotagger) {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
    }

    // ── Testing ──────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
