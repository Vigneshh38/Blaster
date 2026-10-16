package com.vignesh.phonepilot

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Shared background scope for long jobs (model copy etc.). */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Finds, downloads and stores the Gemma 4 E4B model file (~3.7 GB).
 * The model is too big to put inside the APK, so it is downloaded once.
 */
object ModelManager {
    const val MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
    const val FILE_NAME = "gemma-4-E4B-it.litertlm"
    private const val PREFS = "phonepilot"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val MIN_SIZE = 1_000_000_000L // a real model file is > 1 GB

    @Volatile var copyProgress: String? = null

    fun modelFile(ctx: Context): File = File(ctx.getExternalFilesDir(null), FILE_NAME)

    fun isReady(ctx: Context): Boolean {
        val f = modelFile(ctx)
        if (!f.exists() || f.length() < MIN_SIZE || copyProgress != null) return false
        return downloadState(ctx) == null // null = no download running
    }

    fun startDownload(ctx: Context) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        modelFile(ctx).delete()
        val req = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("PhonePilot brain (Gemma 4 E4B)")
            .setDescription("One-time download, about 3.7 GB")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, null, FILE_NAME)
        val id = dm.enqueue(req)
        prefs(ctx).edit().putLong(KEY_DOWNLOAD_ID, id).apply()
    }

    /** Returns a short text if a download is running or failed, or null if nothing is running. */
    fun downloadState(ctx: Context): String? {
        val id = prefs(ctx).getLong(KEY_DOWNLOAD_ID, -1)
        if (id < 0) return null
        val dm = ctx.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return null
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> null
                DownloadManager.STATUS_FAILED -> {
                    prefs(ctx).edit().remove(KEY_DOWNLOAD_ID).apply()
                    "Download failed. Tap Download again."
                }
                DownloadManager.STATUS_PAUSED -> "Download paused (waiting for Wi-Fi?)"
                else -> {
                    val pct = if (total > 0) (done * 100 / total) else 0
                    "Downloading… $pct% (${done / 1_000_000} MB)"
                }
            }
        }
    }

    /** Copies a model file the user picked (e.g. from Downloads) into the app folder. */
    fun copyFrom(ctx: Context, uri: Uri) {
        val out = modelFile(ctx)
        copyProgress = "Copying…"
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(8 * 1024 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        total += n
                        copyProgress = "Copying… ${total / 1_000_000} MB"
                    }
                }
            }
            prefs(ctx).edit().remove(KEY_DOWNLOAD_ID).apply()
        } finally {
            copyProgress = null
        }
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun askBeforeSensitive(ctx: Context) = prefs(ctx).getBoolean("confirm_sensitive", true)
    fun setAskBeforeSensitive(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("confirm_sensitive", v).apply()
}
