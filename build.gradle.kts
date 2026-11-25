// Top-level build file — MH Player
// Versions rule (coach, 2026-11-25 09:55): EVERY library on its latest stable version.
// Verified 2026-11-25 against Google Maven + Maven Central:
//   AGP 9.5.2, Kotlin 2.4.21 (2.5.0 still RC), Gradle 9.9.2 (see gradle-wrapper.properties).
plugins {
    id("com.android.application") version "9.5.2" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("org.jetbrains.kotlin.kapt") version "2.4.21" apply false
}
