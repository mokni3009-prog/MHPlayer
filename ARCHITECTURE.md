# MH Player — Architecture & Contracts (v1)

**App:** MH Player — simple Xtream IPTV player. Native 100% Kotlin. Package `com.mhplayer.app`.
**Stack:** Kotlin 2.4.21, AGP 9.5.2, Gradle 9.9.2, compileSdk 35, targetSdk 35, minSdk 24, JDK 17.
> Versions rule (coach, 2026-11-25 09:55): EVERY library on its latest stable version, verified 2026-11-25 against Google Maven / Maven Central. AGP 8.7.3→9.5.2 and Gradle 8.11.1→9.9.2 are unvalidated jumps — the first successful build must confirm them (AGP 9.x may demand compileSdk 36; if so, download platform android-36 and bump compileSdk/targetSdk).
**Devices:** Android phones + X96 Mate TV box (Allwinner H616). ABIs: `arm64-v8a`, `armeabi-v7a`. One APK.
**UI:** classic Android Views + ViewBinding + RecyclerView + Fragments (NO Compose — avoids compiler-version risk on this toolchain). French UI. DPAD (remote) + touch on every screen.
**Design tokens:** `DESIGN_TOKENS.md` in this directory (exact hex colors, sizes, texts from the 9 approved mockups). The UI must match the mockups 100% (exception: the duplicated "Films" in the movies mockup sidebar appears only ONCE in the real app).

## Dependencies (pin exact versions)

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application") version "9.5.2"
    id("org.jetbrains.kotlin.android") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
    id("org.jetbrains.kotlin.kapt") version "2.4.21"
}
dependencies {
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
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")
    implementation("androidx.security:security-crypto:1.1.0")
    // Media3 1.11.1 — ALL pinned to the same version
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    // libVLC 3.7.8 — fallback engine (88 MB AAR, LGPL 2.1). Pin exactly; do NOT use 4.0.0-eap29.
    implementation("org.videolan.android:libvlc-all:3.7.8")
}
android {
    namespace = "com.mhplayer.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mhplayer.app"
        minSdk = 24; targetSdk = 35
        versionCode = 1; versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("release") }
    }
    signingConfigs {
        create("release") {
            storeFile = file("keystore/mhplayer-release.keystore")
            storePassword = <from local.properties>; keyAlias = "mhplayer"
            keyPassword = <from local.properties>
        }
    }
    buildFeatures { viewBinding = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
```

## Package layout

```
com.mhplayer.app/
  App.kt                    // Application: lazy singletons (OkHttp client, Room, DualEnginePlayer)
  MainActivity.kt           // Single activity, hosts fragments, global DPAD/back handling
  data/
    model/Models.kt         // Credentials, Category, LiveChannel, VodItem, SeriesItem, Episode, PlayItem
    api/XtreamApi.kt        // Retrofit-free OkHttp + kotlinx.serialization calls to player_api.php
    api/M3uParser.kt        // Line-by-line M3U parser (BufferedReader, Dispatchers.IO)
    api/UrlBuilder.kt       // buildLiveUrl(streamId, output), buildVodUrl, buildSeriesUrl
    db/AppDatabase.kt       // Room: categories, channels, vod, series, episodes, channel_playback
    repo/XtreamRepository.kt// Auth, fetch+paged cache (TTL 24h), M3U import
    session/SessionManager.kt // EncryptedSharedPreferences: credentials, engine setting, quality lock
  player/
    LivePlayer.kt           // Interface + shared types (below)
    ExoLivePlayer.kt        // ExoPlayer 1.11.1 wrapper (stability config, see §Player)
    VlcLivePlayer.kt        // libVLC 3.7.8 wrapper (see §Player)
    DualEnginePlayer.kt     // Fallback state machine + watchdog + surface swap + per-channel memory
    AdaptiveLoadControl.kt  // DefaultLoadControl wrapper: grow minBuffer +5s after stall (cap 30s), decay after 10 min clean
  ui/
    theme/Theme.kt          // Colors/dimens from DESIGN_TOKENS.md (colors.xml, dimens.xml, styles.xml)
    SidebarView.kt          // Shared narrow sidebar (Accueil, Chaînes, Films, Séries, Ma playlist)
    LoginFragment.kt  LoadingFragment.kt  HomeFragment.kt  ChannelsFragment.kt
    MoviesFragment.kt  SeriesFragment.kt  PlaylistFragment.kt
    DetailsFragment.kt  PlayerFragment.kt
    adapters/               // RecyclerView adapters (rows, grids, bouquets, channels, episodes, chips)
  util/
    NetworkMonitor.kt       // ConnectivityManager.NetworkCallback -> player reconnect
    FocusHelper.kt          // DPAD focus helpers (request focus, focus highlight)
```

## Data contracts (exact)

```kotlin
// data/model/Models.kt
@Serializable data class Credentials(val serverUrl: String, val username: String, val password: String, val playlistName: String)
data class Category(val id: String, val name: String)
data class LiveChannel(val streamId: Int, val name: String, val icon: String?, val categoryId: String, val epgChannelId: String?, val isHbbtv: Boolean = false)
data class VodItem(val streamId: Int, val name: String, val icon: String?, val categoryId: String, val rating: Double?, val year: Int?, val genre: String?, val plot: String?, val durationSecs: Int?, val containerExtension: String?)
data class SeriesItem(val seriesId: Int, val name: String, val cover: String?, val categoryId: String, val rating: Double?, val year: Int?, val genre: String?, val plot: String?)
data class Episode(val id: Int, val episodeNum: Int, val title: String, val containerExtension: String, val season: Int, val info: String?)
data class Season(val seasonNumber: Int, val episodes: List<Episode>)

// Xtream API (player_api.php), all suspend, Dispatchers.IO, 5s connect / 10s read timeouts:
interface XtreamApi {
    suspend fun authenticate(creds: Credentials): AuthResult // user_info.status, exp_date, max_connections, allowed_output_formats; server_info.url/port
    suspend fun getLiveCategories(): List<Category>
    suspend fun getLiveStreams(categoryId: String): List<LiveChannel>
    suspend fun getVodCategories(): List<Category>
    suspend fun getVodStreams(categoryId: String): List<VodItem>
    suspend fun getSeriesCategories(): List<Category>
    suspend fun getSeries(categoryId: String): List<SeriesItem>
    suspend fun getSeriesInfo(seriesId: Int): List<Season>
}
// URL builders (server_info.url + port, credentials URL-encoded, never hard-coded host):
object UrlBuilder {
    fun liveUrl(creds: Credentials, streamId: Int, output: String): String // /live/<user>/<pass>/<id>.m3u8 | .ts
    fun vodUrl(creds: Credentials, streamId: Int, ext: String): String     // /movie/<user>/<pass>/<id>.<ext>
    fun seriesUrl(creds: Credentials, streamId: Int, ext: String): String  // /series/<user>/<pass>/<id>.<ext>
}
// M3U: parseM3u(inputStream): List<LiveChannel> + categories from group-title. Line-by-line, batch insert 500.
// Room: AppDatabase entities: CategoryEntity, LiveChannelEntity, VodEntity, SeriesEntity, EpisodeEntity,
//       ChannelPlaybackEntity(channelId TEXT PK, preferredEngine TEXT, preferredContainer TEXT,
//                               hwDecoder TEXT, lastError TEXT?, lastSuccessAt LONG)
// Repository: XtreamRepository — login(), loadAll(progress callback for Loading screen steps),
//             getLiveCategories()/getLiveStreams(categoryId) (Room first, network if stale >24h),
//             getVodCategories()/getVodStreams(categoryId), getSeriesCategories()/getSeries(categoryId),
//             getSeriesInfo(seriesId), importM3u(url), logout().
// Session: SessionManager — credentials (EncryptedSharedPreferences), engineSetting (AUTO|EXO|VLC, default AUTO),
//          qualityLock (AUTO|1080|720|480, default AUTO), hbbtvNoteSeen.
```

## Player contracts (exact) — implements research report.md §§1–14 + addendum §§15–22

```kotlin
// player/LivePlayer.kt
enum class Engine { EXO, VLC }
enum class Container { M3U8, TS }
enum class PlaybackErrorKind { Network, NotFound, Decoder, Parse, Drm, Unknown, BehindLiveWindow }
data class PlaybackError(val kind: PlaybackErrorKind, val message: String, val engine: Engine)
sealed class PlaybackState {
    object Idle : PlaybackState()
    object Opening : PlaybackState()
    data class Buffering(val percent: Int = -1) : PlaybackState()
    object Playing : PlaybackState()
    object Paused : PlaybackState()
    data class Error(val error: PlaybackError) : PlaybackState()
}
data class PlayItem(
    val url: String, val title: String, val isLive: Boolean, val container: Container,
    val channelId: String? = null, val engineHint: Engine? = null, val isVod: Boolean = false
)
interface LivePlayer {
    val state: StateFlow<PlaybackState>
    val bufferingPercent: StateFlow<Int>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val isLive: Boolean
    val currentEngine: StateFlow<Engine>
    fun play(item: PlayItem)
    fun stop()
    fun pause()
    fun resume()
    fun seekToLive()
    fun setVolume(volume: Float) // 0..1 (VLC internally allows 200% — clamp UI to 100%)
    fun release()
}
```

### ExoLivePlayer — Media3 1.11.1 (primary). Apply ALL of:
- ONE shared `OkHttpClient` (app singleton) + `OkHttpDataSource.Factory`, connect 8s / read 15s, UA `MHPlayer/1.0 (Android)`. Same client for Xtream API + images.
- ONE shared `DefaultBandwidthMeter` singleton, `setResetOnNetworkTypeChange(true)`.
- `AdaptiveTrackSelection.Factory(bandwidthMeter)`: bandwidthFraction 0.75, minDurationForQualityIncreaseMs 5000 (rise fast to highest quality — user rule: always highest possible quality), maxDurationForQualityDecreaseMs 25000.
- Track caps: maxVideoSize = device max — TV/H616 profile: 1920×1080; phones: display size. NO SD cap. AV1 excluded on H616 (`setPreferredVideoMimeTypes([HEVC, AVC])` on TV profile). `setTunnelingEnabled(true)` ONLY on TV profile AND only if `PackageManager.FEATURE_TUNNELED_PLAYBACK` present (phones: off).
- `DefaultLoadControl` live profile: `setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)`, `setPrioritizeTimeOverSizeThresholds(true)`, backBuffer 0. VOD profile: `30_000/120_000/2_500/5_000` + backBuffer 60_000. Wrapped in `AdaptiveLoadControl` (grow minBuffer +5s after each stall, cap 30s; decay one step after 10 min clean).
- `DefaultRenderersFactory`: `setEnableDecoderFallback(true)`, `EXTENSION_RENDERER_MODE_ON`. SurfaceView (`PlayerView` surface_type surface_view).
- `DefaultMediaSourceFactory`: `setLiveTargetOffsetMs(6_000)` (+ min 2_000 / max 30_000), `setLoadErrorHandlingPolicy(CustomRetryPolicy())`, `setCmcdConfigurationFactory(CmcdConfiguration.Factory())`, `CacheDataSource` (SimpleCache 256 MB, `FLAG_IGNORE_CACHE_ON_ERROR`) — cache for VOD; live segments pass through. `HlsMediaSource` chunkless preparation ON (default).
- `DefaultLivePlaybackSpeedControl`: fallback 0.97/1.03, `setTargetLiveOffsetIncrementOnRebufferMs(2_000)`.
- `CustomRetryPolicy : DefaultLoadErrorHandlingPolicy`: `getMinimumLoadableRetryCount = 8`; retry delay = errorCount×1000ms capped 5000ms, only for transient errors (SocketTimeout, ConnectException, UnknownHostException, 5xx/429); fail fast on 4xx/parser errors. Never pin `playbackSpeed=1.0f` on live.
- Live `MediaItem`: `LiveConfiguration(target 6000, min 2000, max 30000, speed 0.97–1.03)`. Raw `.ts`: `ProgressiveMediaSource` + `LiveConfiguration` (progressive-live), NO seek bar in UI (seeking unsupported in TS).
- `onPlayerError` dispatch (error matrix, report §9): BEHIND_LIVE_WINDOW → `seekToDefaultPosition(); prepare()` (NEVER engine-fallback); IO_NETWORK/TIMEOUT/5xx/429 → retry policy → app reconnect loop; 404/410 → container failover; PARSING_MANIFEST_* → container failover; DECODING_* → decoder fallback → software → downshift → other container; DRM_* → licence retry → SD fallback → DRM error UI (no VLC fallback); AUDIO/VIDEO_TRACK_INIT_FAILED → recreate sink / software audio.
- DRM: attach `DrmSessionManager` ONLY when the manifest/item carries `drmConfiguration`. Widevine L3 on H616: cap DRM renditions to SD.
- Connectivity: `NetworkCallback` — on loss show "retrying", keep player prepared; on regain, if IDLE after error, re-prepare same item at live edge. Do NOT recreate the player on network flap.
- Live offset monitor: poll `currentLiveOffset` every 1s; if > maxOffset for >10s → `seekToDefaultPosition()`.
- `EventLogger` in debug builds; `AnalyticsListener` records state/errors/format changes/dropped frames (no credentials in logs).
- ONE ExoPlayer instance for the process lifetime (in `App` / a lifecycle-aware holder); reuse across zaps (`setMediaItem + prepare`); `release()` only on app exit / leaving playback entirely. Never preload segments for live; manifest-only prefetch of the NEXT channel via the shared OkHttp client (HEAD/GET, no second player, never while it would be a 2nd Xtream connection — manifest HEAD does not hold a stream open).
- Container choice per channel: `.m3u8` first (healthy), `.ts` if `.m3u8` fails (persist winner per channel in `ChannelPlaybackEntity`).

### VlcLivePlayer — libVLC 3.7.8 (fallback). Apply ALL of:
- LAZY init (do NOT create LibVLC at app start — 88 MB native). One `LibVLC` per process, one `MediaPlayer` per playback.
- Init options: `--network-caching=3000 --live-caching=3000 --file-caching=1000 --clock-jitter=0 --drop-late-frames --skip-frames --vout=android-display --mediacodec-dr --http-user-agent=MHPlayer/1.0 (Android) --no-video-title-show --audio-time-stretch --adaptive-logic=predictive --adaptive-maxwidth=1920 --adaptive-maxheight=1080`. (clock-jitter: leave at default for Xtream TS stability — do NOT force 0 if TS PCR is jittery; the init value above is the default-equivalent for live; see note in code.)
- Per-media options: `:network-caching=3000`, `:live-caching=3000`, `:http-user-agent=MHPlayer/1.0 (Android)`. Flaky link profile: 5000/5000. Fast-zap profile: 1000/1000.
- `Media(libVlc, Uri.parse(url))` — NEVER a raw String. `media.setHWDecoderEnabled(true, false)` (force=false default; H616 allowlist may return UNKNOWN — if HW fails, VLC falls back to FFmpeg SW automatically). Per-channel HW override persisted.
- `VLCVideoLayout` (own SurfaceView) — separate from ExoPlayer's `PlayerView`; both in one FrameLayout, one VISIBLE at a time.
- Events → same `PlaybackState`/`PlaybackError` taxonomy (EncounteredError → Unknown + correlated HTTP HEAD probe: HEAD fails = Network, HEAD ok = Decoder/Parse). Live detection: `duration==0 && !isSeekable && state in (Playing,Paused)` — do NOT count Buffering as live.
- App-side retry: EncounteredError → backoff 1s,2s,4s,8s (cap 15s + jitter), max 5 attempts → other container → ExoPlayer → error UI. Buffering >15s or time frozen >10s → stop()+play() (re-open = seek-to-live in VLC).
- Release order: stop() → detachViews() → release() player → release media; libVlc.release() only at app exit. NEVER call libVLC from inside an event callback — post to Main handler. Release VLC player when zapping back to an ExoPlayer channel (do not keep two native cores alive).
- Volume clamp 0..1 (VLC max is 200% internally — never expose >100%).
- No DRM in VLC; no CMCD/Content Steering; no MediaSession bridge needed for v1 (lockscreen controls only on Exo path — document as a gap).
- Deinterlace: `--deinterlace=1 --deinterlace-mode=blend` only for interlaced channels flagged in playback memory (VLC advantage for 1080i).

### DualEnginePlayer — the fallback state machine (addendum §16)
- Owns ONE `LivePlayer` interface to the UI; UI never knows the active engine.
- Auto fallback (default AUTO): after Exo's own retry policy exhausts AND app reconnect loop (≥3 attempts) → decision tree:
  - DECODING_* → VLC immediately. PARSING_MANIFEST_* or 404/410 after .ts↔.m3u8 tried → VLC immediately. IO_NETWORK_* after reconnect loop → VLC once (if VLC fails within 10s → error UI). BEHIND_LIVE_WINDOW → NO fallback (seekToDefaultPosition in Exo). DRM_* → NO fallback.
  - Stall watchdog (coroutine, 1s tick): Exo BUFFERING >15s or position frozen >10s → 1st seekToDefaultPosition, 2nd re-prepare, 3rd switch to VLC. VLC Buffering >15s or time frozen >10s → stop+play, then switch to Exo.
  - **1 engine switch per channel selection, NEVER ping-pong.** After VLC fails → error UI with [Retry] [Try other format] [Switch engine] manual actions.
- Reverse fallback: VLC fails → Exo once if it looks like a VLC weakness (adaptive DASH/HLS ladder, LL-HLS, DRM, SW decode drops). Both engines fail same URL+container → other container in other engine → error UI.
- Surface swap: stop old engine BEFORE play on new one (never overlap network fetch); show "Switching player…" overlay (200–600ms black is expected).
- Per-channel memory (`ChannelPlaybackEntity`): record preferredEngine/preferredContainer/hwDecoder/lastError/lastSuccessAt on every fallback success/failure; next zap starts on the recorded winner. Per-channel capability table (§17.4): HLS/DASH ladder/LL/DRM → Exo only; raw TS clean → either (Exo first); TS corrupt/multi-program/DVB subs → VLC first; legacy codec / AC-3 → VLC first; RTSP/RTMP/UDP → VLC only.
- Manual: global setting AUTO/EXO/VLC (default AUTO); per-channel override (long-press → "Play with VLC"/"Play with ExoPlayer"); in-player "Try VLC player"/"Try ExoPlayer" buttons. Manual VLC choice skips the Exo attempt entirely; manual Exo choice does NOT auto-fallback without asking.
- HbbTV: channels play as normal live A/V in either engine. Interactive HbbTV is NOT available (no native HbbTV in either engine — addendum §21). If a channel is flagged HbbTV (name/category contains "HbbTV"), the Details screen shows: "HbbTV interactif non disponible sur cet appareil". NO WebView overlay in v1 (Xtream API supplies no HbbTV app URL — document as a known gap).
- "Zero buffering" target (addendum §22): zero VISIBLE interruptions — rebuffer ratio <0.5%, VST <2s, stall recovery <3s without user action. NOT literal zero. Checklist §22.8 must be verified on the real box (cannot be verified in this VM — report as a gap).

## UI contracts (exact screens)

- **LoginFragment:** tabs [Xtream Codes (active) | Playlist M3U]. Xtream: Serveur URL, Nom d'utilisateur, Mot de passe, Nom de la playlist + red "Se connecter". M3U: URL de la playlist, Nom de la playlist + "Se connecter". Red play-triangle + "MH Player" title. Auth via `player_api.php`; M3U via fetch+parse. On success → LoadingFragment. Store credentials.
- **LoadingFragment:** logo top-center, spinner, red progress bar (%), "Chargement des films…" status text, steps row (Chaînes ✓ / Films in progress / Séries gray), "Ma playlist" at bottom. Steps: fetch categories → live → VOD → series → done → Home.
- **HomeFragment:** narrow sidebar (Accueil active red), hero "Film à la une" + red Lecture button, rows "Films populaires" and "Séries" (6 posters each). Click poster → DetailsFragment; Lecture → PlayerFragment.
- **ChannelsFragment:** sidebar (Chaînes active), Bouquets list (Sports selected red bg, Actualités, Cinéma, Jeunesse, Documentaires, Musique), Chaînes list (selected channel FULL red background), embedded player window (channel name + red EN DIRECT badge). DPAD: left/right between columns, up/down in lists, OK selects/plays. OK on player window → PlayerFragment fullscreen.
- **MoviesFragment:** title "Films", chips (Tous red, Action, Comédie, Drame, Science-fiction, Thriller), 12 posters 2×6. "Films" appears ONCE in the sidebar (do NOT reproduce the mockup's duplicate).
- **SeriesFragment:** title "Séries", chips (Toutes red, Drame, Policier, Comédie, Science-fiction, Aventure), 12 posters 2×6.
- **PlaylistFragment:** info card (playlist name, "Serveur: … • Premium", counts: X chaînes / Y films / Z séries, "Dernière mise à jour: aujourd'hui"), 3 rows: Chaînes tiles, Films posters, Séries posters.
- **PlayerFragment (fullscreen):** video fills screen; top bar (back arrow + channel title + red EN DIRECT badge); bottom bar (play/pause, progress bar + times, volume, settings gear, fullscreen). Controls auto-hide after 3s; OK toggles play/pause; BACK exits to previous screen. Settings menu: engine (Auto/ExoPlayer/VLC), quality lock (Auto/1080p/720p/480p), container (Auto/HLS/TS). VOD: seek bar enabled; live: seek bar hidden (TS) or "Go Live" only.
- **DetailsFragment:** sidebar (Séries active for series), large poster, title, meta line (year • genre • seasons), description, red "Lecture" + gray "Plus d'infos", "Saison 1" row with 6 episode cards (Épisode 1 selected red). Movies: same layout without episodes. HbbTV-flagged channel: show "HbbTV interactif non disponible sur cet appareil" note.
- **Sidebar (all screens):** logo + menu (Accueil, Chaînes, Films, Séries, Ma playlist); active item red; focus highlight = red border/background; DPAD navigates; touch taps.
- **Landscape (TV):** sidebar + wide rows (as mockups). **Portrait (phone):** sidebar becomes a top bar with horizontal menu; lists vertical. Same fragments, `layout-land/` + `layout-port/` variants.
- Posters: Coil, placeholder = dark card with red MH mark; error = same placeholder. No real poster URLs in v1 without a subscription — use `stream_icon` from Xtream when present, placeholder otherwise.
- NO favorites, search, EPG, recording, parental controls, advanced settings. Only the settings listed above (engine, quality lock, container) inside the player.

## Build & verify

- Build: `./gradlew assembleRelease assembleDebug` (release signed with `keystore/mhplayer-release.keystore`; generate with keytool if absent — RSA 2048, 25y, alias mhplayer; passwords in `local.properties`, never committed).
- Verify: `aapt2 dump badging` on both APKs (package, version, ABIs, min/target SDK, permissions, launcher activity), `zipalign -c`, `apksigner verify`. No emulator/device is available in this VM — runtime playback CANNOT be verified here; report that explicitly.
- If a build fails: read `~/workspace/custom-skills/android_build_failure_verification/SKILL.md` FIRST and verify against real compiler output before diagnosing.
- Final deliverable: `android/app/build/outputs/apk/release/app-release.apk` (+ debug). Report path, size, verification evidence, and gaps vs this contract.
