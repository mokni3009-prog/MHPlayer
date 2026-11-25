package com.mhplayer.app.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.mhplayer.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-update (v1.2). The app is not on Google Play (distributed from
 * GitHub), so the Play In-App Updates API does not apply: this is a
 * small self-update of our own.
 *
 * Source of truth: releases/version.json in the repo
 * (versionName, versionCode, apkUrl, changelog).
 *
 * Flow: on launch (once per process) MainActivity calls [checkForUpdate].
 * If a newer versionCode exists, an English dialog offers
 * "Update" / "Later" (never forced). The APK downloads with
 * the system DownloadManager (notification + progress) and installs on
 * top of the current install (same package + signature, no uninstall,
 * user data kept) via ACTION_VIEW + FileProvider.
 *
 * If the user has not allowed "install unknown apps", the app shows a
 * clear message explaining to enable it once in Settings; when they
 * come back, [onResume] offers the pending update again.
 *
 * v1.4 FIX: the APK now downloads to the app's PRIVATE external files
 * dir (no storage permission needed on any API level). v1.2/v1.3 used the
 * public Downloads folder, which needs WRITE_EXTERNAL_STORAGE on
 * Android 9 — the app does not declare it, so enqueue() threw and the
 * update silently did nothing. Download failures are now visible
 * (Toast) and the dialog is re-offered; a DownloadManager failure
 * falls back to a plain OkHttp download.
 *
 * The updater must never crash the app: every entry point is wrapped
 * in try/catch, and a failed check/download simply does nothing.
 */
object AppUpdater {

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/mokni3009-prog/MHPlayer/main/releases/version.json"
    private const val TIMEOUT_SECONDS = 10L

    /** Checked once per process launch. */
    private val checked = AtomicBoolean(false)

    /** Update the user asked about but could not install yet
     * (unknown-sources permission missing). Cleared on cancel. */
    @Volatile
    private var pendingUpdate: VersionInfo? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Checks for a newer version once per launch. Safe to call from
     * MainActivity.onCreate. Never throws.
     */
    fun checkForUpdate(activity: AppCompatActivity) {
        try {
            if (!checked.compareAndSet(false, true)) return
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val info = fetchVersionInfo() ?: return@launch
                if (info.versionCode <= BuildConfig.VERSION_CODE) return@launch
                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        showUpdateDialog(activity, info)
                    }
                }
            }
        } catch (_: Throwable) {
            // Updater must never crash the app.
        }
    }

    /**
     * Manual check from Settings > Updates. Bypasses the once-per-launch
     * guard and tells the user when they are already up to date.
     * Never throws.
     */
    fun checkNow(activity: AppCompatActivity) {
        try {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val info = fetchVersionInfo()
                withContext(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@withContext
                    if (info == null || info.versionCode <= BuildConfig.VERSION_CODE) {
                        Toast.makeText(
                            activity,
                            "You already have the latest version",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@withContext
                    }
                    showUpdateDialog(activity, info)
                }
            }
        } catch (_: Throwable) {
            // Updater must never crash the app.
        }
    }

    /**
     * Called from MainActivity.onResume: if the user just enabled
     * "install unknown apps" in Settings, offer the pending update
     * again. Never throws.
     */
    fun onResume(activity: AppCompatActivity) {
        try {
            val info = pendingUpdate ?: return
            if (activity.isFinishing || activity.isDestroyed) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.packageManager.canRequestPackageInstalls()
            ) {
                return
            }
            pendingUpdate = null
            showUpdateDialog(activity, info)
        } catch (_: Throwable) {
            // Updater must never crash the app.
        }
    }

    private fun fetchVersionInfo(): VersionInfo? {
        return try {
            val request = Request.Builder().url(VERSION_URL).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                val json = JSONObject(body)
                VersionInfo(
                    versionName = json.optString("versionName", ""),
                    versionCode = json.optInt("versionCode", 0),
                    apkUrl = json.optString("apkUrl", ""),
                    changelog = json.optString("changelog", ""),
                ).takeIf { it.versionCode > 0 && it.apkUrl.isNotBlank() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun showUpdateDialog(activity: AppCompatActivity, info: VersionInfo) {
        // v1.4 redesign: modern custom dialog (logo badge + version chip +
        // changelog + two modern buttons) instead of the plain system dialog.
        try {
            val view = LayoutInflater.from(activity)
                .inflate(com.mhplayer.app.R.layout.dialog_update, null)
            val versionChip = view.findViewById<android.widget.TextView>(
                com.mhplayer.app.R.id.updateVersionChip,
            )
            versionChip.text = "v${info.versionName}"
            val changelogView = view.findViewById<android.widget.TextView>(
                com.mhplayer.app.R.id.updateChangelog,
            )
            changelogView.text = buildString {
                append("A new version is available.")
                if (info.changelog.isNotBlank()) {
                    append("\n\n")
                    append(info.changelog)
                }
            }
            val dialog = AlertDialog.Builder(activity)
                .setView(view)
                .setCancelable(true)
                .create()
            view.findViewById<android.widget.Button>(
                com.mhplayer.app.R.id.updateButton,
            ).setOnClickListener {
                dialog.dismiss()
                startUpdate(activity, info)
            }
            view.findViewById<android.widget.Button>(
                com.mhplayer.app.R.id.updateLaterButton,
            ).setOnClickListener { dialog.dismiss() }
            dialog.show()
            // Transparent window so the rounded dialog card shows through.
            dialog.window?.setBackgroundDrawable(
                ColorDrawable(android.graphics.Color.TRANSPARENT),
            )
        } catch (_: Throwable) {
            // Never crash the app.
        }
    }

    private fun startUpdate(activity: AppCompatActivity, info: VersionInfo) {
        try {
            // Unknown-sources permission (Android 8+): needed to install the APK.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.packageManager.canRequestPackageInstalls()
            ) {
                pendingUpdate = info
                showUnknownSourcesDialog(activity)
                return
            }
            downloadApk(activity, info)
        } catch (_: Throwable) {
            // Never crash the app.
        }
    }

    private fun showUnknownSourcesDialog(activity: AppCompatActivity) {
        try {
            AlertDialog.Builder(activity)
                .setTitle("Install permission")
                .setMessage(
                    "To install the update, allow installing unknown apps for Zeno Play once: " +
                        "turn on \"Allow installing unknown apps\" " +
                        "for Zeno Play, then come back here and tap \"Update\".",
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(intent)
                    } catch (_: Throwable) {
                        // Settings screen unavailable on this device.
                    }
                }
                .setNegativeButton("Cancel") { _, _ ->
                    pendingUpdate = null
                }
                .setCancelable(true)
                .show()
        } catch (_: Throwable) {
            // Never crash the app.
        }
    }

    private fun downloadApk(activity: AppCompatActivity, info: VersionInfo) {
        // v1.4 FIX (update button did nothing on v1.2/v1.3): the old code
        // downloaded to the PUBLIC Downloads folder
        // (setDestinationInExternalPublicDir), which on Android 9 (API 28,
        // the user's Infinix X650C) requires WRITE_EXTERNAL_STORAGE — a
        // permission the app does not declare. dm.enqueue() threw
        // SecurityException, swallowed by the catch-all, so the dialog
        // vanished and nothing happened. The fix downloads to the app's
        // PRIVATE external files dir: no storage permission is needed on
        // any API level. Failures are now VISIBLE (Toast) instead of silent.
        try {
            val updatesDir = File(activity.getExternalFilesDir(null), "updates")
                .apply { mkdirs() }
            val file = File(updatesDir, "Zeno-Play-v${info.versionName}.apk")
            if (file.exists()) file.delete()
            val request = DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("Zeno Play v${info.versionName}")
                .setDescription("Downloading the update...")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                .setDestinationUri(Uri.fromFile(file))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)
            registerCompletionReceiver(activity, downloadId, file, info)
        } catch (_: Throwable) {
            // DownloadManager unavailable/disabled on this device: fall back
            // to a plain OkHttp download to the same private file.
            downloadApkFallback(activity, info)
        }
    }

    /** Fallback when DownloadManager is missing/disabled: OkHttp download. */
    private fun downloadApkFallback(activity: AppCompatActivity, info: VersionInfo) {
        try {
            Toast.makeText(
                activity,
                "Downloading the update...",
                Toast.LENGTH_SHORT,
            ).show()
            activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val updatesDir = File(activity.getExternalFilesDir(null), "updates")
                        .apply { mkdirs() }
                    val file = File(updatesDir, "Zeno-Play-v${info.versionName}.apk")
                    val request = Request.Builder().url(info.apkUrl).get().build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                        val body = response.body ?: throw java.io.IOException("Empty body")
                        file.outputStream().use { out -> body.byteStream().copyTo(out) }
                    }
                    withContext(Dispatchers.Main) { installApk(activity, file, info) }
                } catch (_: Throwable) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            activity,
                            "Update download failed",
                            Toast.LENGTH_LONG,
                        ).show()
                        pendingUpdate = info
                        showUpdateDialog(activity, info)
                    }
                }
            }
        } catch (_: Throwable) {
            Toast.makeText(
                activity,
                "Update download failed",
                Toast.LENGTH_LONG,
            ).show()
            pendingUpdate = info
        }
    }

    private fun registerCompletionReceiver(
        activity: AppCompatActivity,
        downloadId: Long,
        file: File,
        info: VersionInfo,
    ) {
        try {
            val appContext = activity.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try {
                        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                        val id = intent.getLongExtra(
                            DownloadManager.EXTRA_DOWNLOAD_ID,
                            -1L,
                        )
                        if (id != downloadId) return
                        appContext.unregisterReceiver(this)
                        // v1.4: check the download actually SUCCEEDED before
                        // installing (a failed download used to be ignored).
                        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        val status = dm.query(
                            DownloadManager.Query().setFilterById(downloadId),
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                cursor.getInt(
                                    cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_STATUS,
                                    ),
                                )
                            } else DownloadManager.STATUS_FAILED
                        } ?: DownloadManager.STATUS_FAILED
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            installApk(appContext, file, info)
                        } else {
                            Toast.makeText(
                                appContext,
                                "Update download failed",
                                Toast.LENGTH_LONG,
                            ).show()
                            pendingUpdate = info
                        }
                    } catch (_: Throwable) {
                        runCatching { appContext.unregisterReceiver(this) }
                    }
                }
            }
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
        } catch (_: Throwable) {
            downloadApkFallback(activity, info)
        }
    }

    private fun installApk(context: Context, file: File, info: VersionInfo) {
        try {
            if (!file.exists()) {
                Toast.makeText(
                    context,
                    "Update failed — file not found",
                    Toast.LENGTH_LONG,
                ).show()
                pendingUpdate = info
                return
            }
            // Unknown-sources permission: re-check right before installing.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                pendingUpdate = info
                return
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(
                context,
                "Could not install the update",
                Toast.LENGTH_LONG,
            ).show()
            pendingUpdate = info
        }
    }

    private data class VersionInfo(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val changelog: String,
    )
}
