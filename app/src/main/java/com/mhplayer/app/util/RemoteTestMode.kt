package com.mhplayer.app.util

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Base64
import android.view.View
import android.widget.EditText
import com.mhplayer.app.BuildConfig
import com.mhplayer.app.MainActivity
import com.mhplayer.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Remote test mode (v1.4, DEV ONLY — removed in the final release).
 *
 * Lets Sahboush drive the app on the user's phone from afar, through our
 * own Cloudflare Worker + D1 (same server as the crash reports):
 *
 * - Polls GET /remote/commands?pending=1 every ~10s and executes the
 *   commands it finds (open_screen, fill_field, tap_button, screenshot,
 *   get_logs, go_back), then POSTs the result to /remote/results.
 * - POSTs a heartbeat to /remote/heartbeat every ~15s with the current
 *   screen + a small screenshot + recent logs, so the tester can see
 *   what is on the phone without any command.
 *
 * Data goes ONLY to the user's own Worker. The feature is fully isolated
 * in this file + the BuildConfig.REMOTE_TEST_ENABLED flag (true for dev
 * builds, false/absent in the final release). It must never crash the
 * app: every entry point is wrapped in try/catch.
 *
 * Commands the tester can send (payload is a JSON object):
 * - open_screen {screen}: login | loading | home | channels | movies |
 *   series | playlist (details/player need arguments -> error result).
 * - fill_field {field, text}: field = one of the whitelisted EditText ids.
 * - tap_button {button}: button = one of the whitelisted view ids.
 * - screenshot {}: returns a fresh screenshot in the result.
 * - get_logs {}: returns recent logcat lines in the result.
 * - go_back {}: presses back in the app.
 */
object RemoteTestMode {

    private const val BASE =
        "https://streammaster-crash-reporter.streammaster.workers.dev"
    private const val POLL_INTERVAL_MS = 10_000L
    private const val HEARTBEAT_INTERVAL_MS = 15_000L
    private const val TIMEOUT_SECONDS = 10L
    private const val MAX_SCREENSHOT_CHARS = 100_000
    private const val MAX_LOGS_CHARS = 3_000

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentActivity: MainActivity? = null

    /** Whitelisted fields a remote command may fill (EditText ids). */
    private val FIELD_IDS = mapOf(
        "inputServer" to R.id.inputServer,
        "inputUsername" to R.id.inputUsername,
        "inputPassword" to R.id.inputPassword,
        "inputPlaylistName" to R.id.inputPlaylistName,
        "inputM3uUrl" to R.id.inputM3uUrl,
        "inputM3uPlaylistName" to R.id.inputM3uPlaylistName,
    )

    /** Whitelisted views a remote command may tap. */
    private val BUTTON_IDS = mapOf(
        "loginButton" to R.id.loginButton,
        "retryButton" to R.id.retryButton,
        "tabXtream" to R.id.tabXtream,
        "tabM3u" to R.id.tabM3u,
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** Starts the poll + heartbeat loops. Safe to call twice. */
    fun start(app: Application) {
        try {
            if (!started.compareAndSet(false, true)) return
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (activity is MainActivity) currentActivity = activity
                }

                override fun onActivityPaused(activity: Activity) {
                    if (activity === currentActivity) currentActivity = null
                }

                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            })
            scope.launch { pollLoop() }
            scope.launch { heartbeatLoop() }
        } catch (_: Throwable) {
            // Remote test must never break the app.
        }
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            try {
                pollOnce()
            } catch (_: Throwable) {
                // Network down / server hiccup: retry on the next tick.
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun heartbeatLoop() {
        while (scope.isActive) {
            try {
                sendHeartbeat()
            } catch (_: Throwable) {
                // Ignore; next heartbeat retries.
            }
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    /** Fetches pending commands and executes each one. */
    private suspend fun pollOnce() {
        val body = get("/remote/commands?pending=1") ?: return
        val commands = runCatching {
            JSONObject(body).optJSONArray("commands") ?: JSONArray()
        }.getOrDefault(JSONArray())
        for (i in 0 until commands.length()) {
            val cmd = commands.optJSONObject(i) ?: continue
            val id = cmd.optInt("id", 0)
            if (id == 0) continue
            val action = cmd.optString("action", "")
            val payload = runCatching {
                JSONObject(cmd.optString("payload", "{}"))
            }.getOrDefault(JSONObject())
            val status = execute(action, payload)
            val result = JSONObject().apply {
                put("command_id", id)
                put("status", status)
                put("screen", currentScreen())
                put("screenshot", captureScreenshot())
                put("logs", readLogs())
            }
            post("/remote/results", result.toString())
        }
    }

    /** Current screen (visible fragment tag) or "unknown". */
    private suspend fun currentScreen(): String {
        return try {
            withContext(Dispatchers.Main) {
                val activity = currentActivity ?: return@withContext "unknown"
                activity.supportFragmentManager
                    .findFragmentById(R.id.fragmentContainer)?.tag ?: "unknown"
            }
        } catch (_: Throwable) {
            "unknown"
        }
    }

    private suspend fun sendHeartbeat() {
        val heartbeat = JSONObject().apply {
            put("device_model", Build.MODEL ?: "unknown")
            put("app_version", BuildConfig.VERSION_NAME)
            put("screen", currentScreen())
            put("screenshot", captureScreenshot())
            put("logs", readLogs())
        }
        post("/remote/heartbeat", heartbeat.toString())
    }

    /**
     * Executes one command. Returns a short status string ("done" or
     * "error: ...") that is stored with the result.
     */
    private suspend fun execute(action: String, payload: JSONObject): String {
        return try {
            when (action) {
                "open_screen" -> openScreen(payload.optString("screen", ""))
                "fill_field" -> fillField(
                    payload.optString("field", ""),
                    payload.optString("text", ""),
                )
                "tap_button" -> tapButton(payload.optString("button", ""))
                "screenshot" -> "done"
                "get_logs" -> "done"
                "go_back" -> {
                    withContext(Dispatchers.Main) {
                        currentActivity?.onBackPressedDispatcher?.onBackPressed()
                    }
                    "done"
                }
                else -> "error: unknown action '$action'"
            }
        } catch (e: Throwable) {
            "error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private suspend fun openScreen(screen: String): String {
        val activity = currentActivity ?: return "error: no activity"
        return try {
            withContext(Dispatchers.Main) {
                when (screen) {
                    "login" -> activity.showFragment(
                        com.mhplayer.app.ui.LoginFragment(),
                        MainActivity.TAG_LOGIN,
                        addToBackStack = false,
                    )
                    "loading" -> activity.openLoading()
                    "home" -> activity.navigateTo(MainActivity.Destination.HOME)
                    "channels" -> activity.navigateTo(MainActivity.Destination.CHANNELS)
                    "movies" -> activity.navigateTo(MainActivity.Destination.MOVIES)
                    "series" -> activity.navigateTo(MainActivity.Destination.SERIES)
                    "playlist" -> activity.navigateTo(MainActivity.Destination.PLAYLIST)
                    "details", "player" -> return@withContext "error: screen '$screen' needs arguments"
                    else -> return@withContext "error: unknown screen '$screen'"
                }
                "done"
            }
        } catch (e: Throwable) {
            "error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private suspend fun fillField(field: String, text: String): String {
        val resId = FIELD_IDS[field] ?: return "error: unknown field '$field'"
        val activity = currentActivity ?: return "error: no activity"
        return try {
            withContext(Dispatchers.Main) {
                val edit = activity.findViewById<EditText>(resId)
                    ?: return@withContext "error: field '$field' not on screen"
                edit.setText(text)
                "done"
            }
        } catch (e: Throwable) {
            "error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private suspend fun tapButton(button: String): String {
        val resId = BUTTON_IDS[button] ?: return "error: unknown button '$button'"
        val activity = currentActivity ?: return "error: no activity"
        return try {
            withContext(Dispatchers.Main) {
                val view = activity.findViewById<View>(resId)
                    ?: return@withContext "error: button '$button' not on screen"
                view.performClick()
                "done"
            }
        } catch (e: Throwable) {
            "error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Current window as a small JPEG (base64), capped in size. */
    private suspend fun captureScreenshot(): String {
        return try {
            withContext(Dispatchers.Main) {
                val activity = currentActivity ?: return@withContext ""
                val view = activity.window?.decorView?.rootView ?: return@withContext ""
                if (view.width <= 0 || view.height <= 0) return@withContext ""
                val full = Bitmap.createBitmap(
                    view.width, view.height, Bitmap.Config.ARGB_8888,
                )
                view.draw(Canvas(full))
                val scale = 480f / full.width.coerceAtLeast(1)
                val scaled = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        full,
                        480,
                        (full.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    full
                }
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 55, out)
                if (scaled !== full) scaled.recycle()
                full.recycle()
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                if (b64.length > MAX_SCREENSHOT_CHARS) "" else b64
            }
        } catch (_: Throwable) {
            ""
        }
    }

    /** Recent logcat lines of this process only (no permission needed). */
    private fun readLogs(): String {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "--pid=${Process.myPid()}", "-t", "100"),
            )
            val text = process.inputStream.bufferedReader().use { it.readText() }
            if (text.length > MAX_LOGS_CHARS) text.takeLast(MAX_LOGS_CHARS) else text
        } catch (_: Throwable) {
            ""
        }
    }

    private fun get(path: String): String? {
        return try {
            val request = Request.Builder().url(BASE + path).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun post(path: String, json: String): Boolean {
        return try {
            val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(BASE + path).post(body).build()
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful &&
                    (response.body?.string().orEmpty().contains("\"ok\":true"))
            }
        } catch (_: Throwable) {
            false
        }
    }
}
