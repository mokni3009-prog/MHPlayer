# MH Player

Simple Xtream IPTV player for Android — phones and TV boxes (X96 Mate, Allwinner H616, ARM64).

- Login with Xtream Codes or M3U playlist
- Live channels, movies and series
- Dual player engine: ExoPlayer (Media3) primary, VLC (libVLC) fallback
- Remote control (DPAD) + touch
- UI in French

## Build

Release APK is built by GitHub Actions (`.github/workflows/build.yml`) with Gradle 9.9.2,
Kotlin 2.4.21 and Android Gradle Plugin 9.5.2. The signed APK is published as a GitHub Release.

Package: `com.mhplayer.app` — single `arm64-v8a` APK.
