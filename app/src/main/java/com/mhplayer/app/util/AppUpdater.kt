package com.mhplayer.app.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
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
 * If a newer versionCode exists, a French dialog offers
 * "Mettre à jour" / "Plus tard" (never forced). The APK downloads with
 * the system DownloadManager (notification + progress) and installs on
 * top of the current install (same package + signature, no uninstall,
 * user data kept) via ACTION_VIEW + FileProvider.
 *
 * If the user has not allowed "install unknown apps", the app shows a
 * clear message explaining to enable it once in Settings; when they
 * come back, [onResume] offers the pending update again.
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
        try {
            val message = buildString {
                append("Une nouvelle version (v${info.versionName}) est disponible.")
                if (info.changelog.isNotBlank()) {
                    append("\n\n")
                    append(info.changelog)
                }
            }
            AlertDialog.Builder(activity)
                .setTitle("Mise à jour disponible")
                .setMessage(message)
                .setPositiveButton("Mettre à jour") { _, _ ->
                    startUpdate(activity, info)
                }
                .setNegativeButton("Plus tard", null)
                .setCancelable(true)
                .show()
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
                .setTitle("Autorisation d'installation")
                .setMessage(
                    "Pour installer la mise à jour, autorisez une seule fois " +
                        "l'installation d'applications inconnues pour MH Player : " +
                        "activez « Autoriser l'installation d'applications inconnues » " +
                        "pour MH Player, puis revenez ici et appuyez sur « Mettre à jour ».",
                )
                .setPositiveButton("Ouvrir les paramètres") { _, _ ->
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
                .setNegativeButton("Annuler") { _, _ ->
                    pendingUpdate = null
                }
                .setCancelable(true)
                .show()
        } catch (_: Throwable) {
            // Never crash the app.
        }
    }

    private fun downloadApk(activity: AppCompatActivity, info: VersionInfo) {
        try {
            val fileName = "MH-Player-v${info.versionName}.apk"
            val request = DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("MH Player v${info.versionName}")
                .setDescription("Téléchargement de la mise à jour…")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName,
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)
            registerCompletionReceiver(activity, downloadId, fileName)
        } catch (_: Throwable) {
            // Never crash the app.
        }
    }

    private fun registerCompletionReceiver(
        activity: AppCompatActivity,
        downloadId: Long,
        fileName: String,
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
                        installApk(appContext, fileName)
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
            // Never crash the app.
        }
    }

    private fun installApk(context: Context, fileName: String) {
        try {
            val file = File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS,
                ),
                fileName,
            )
            if (!file.exists()) return
            // Unknown-sources permission: re-check right before installing.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
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
            // Never crash the app.
        }
    }

    private data class VersionInfo(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val changelog: String,
    )
}
