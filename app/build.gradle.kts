import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.mhplayer.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mhplayer.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        abiFilters("arm64-v8a")
    }

    signingConfigs {
        // Release keystore lives in android/keystore/ (generated locally, never committed).
        // If it does not exist yet, the release build falls back to unsigned until it is created.
        create("release") {
            val ks = rootProject.file("keystore/mhplayer-release.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = keystoreProps.getProperty("keystore.storePassword")
                keyAlias = keystoreProps.getProperty("keystore.keyAlias", "mhplayer")
                keyPassword = keystoreProps.getProperty("keystore.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures { viewBinding = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // All versions = latest stable, verified 2026-11-25 (Google Maven / Maven Central).
    // Coach rule (2026-11-25 09:55): every library on its latest stable version.
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    // OkHttp 5.5.1 (latest stable). NOTE: Media3 1.11.1's datasource-okhttp was built
    // against OkHttp 4.12.0 — first build + runtime test must confirm 5.x compat;
    // if it breaks, pin okhttp back to 4.12.0 as a Media3-compat exception.
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")
    // Room 2.8.5 bundles kotlin-metadata-jvm 2.3.x, which cannot read Kotlin 2.4.0
    // metadata (kapt fails). Pin the 2.4.21 library on the kapt classpath.
    kapt("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21")
    implementation("androidx.security:security-crypto:1.1.0")

    // Media3 1.11.1 — ALL pinned to the same version (primary player: ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")

    // libVLC 3.7.8 — fallback player. Pin exactly; do NOT use 4.0.0-eap29 (preview).
    implementation("org.videolan.android:libvlc-all:3.7.8")
}
