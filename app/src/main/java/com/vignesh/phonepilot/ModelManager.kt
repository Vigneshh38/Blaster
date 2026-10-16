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
    /** FAST = Gemma 4 E2B (~2-3x faster). SMART = Gemma 4 E4B (more accurate, slower). */
    enum class Choice(val label: String, val fileName: String, val sizeText: String) {
        FAST("Fast (Gemma 4 E2B)", "gemma-4-E2B-it.litertlm", "2.6 GB"),
        SMART("Smart (Gemma 4 E4B)", "gemma-4-E4B-it.litertlm", "3.7 GB");
        val url get() = "https://huggingface.co/litert-community/${fileName.removeSuffix(".litertlm")}-litert-lm/resolve/main/$fileName"
    }

    fun choice(ctx: Context): Choice =
        runCatching { Choice.valueOf(prefs(ctx).getString("model", "FAST")!!) }.getOrDefault(Choice.FAST)
    fun setChoice(ctx: Context, c: Choice) = prefs(ctx).edit().putString("model", c.name).apply()
    private const val PREFS = "phonepilot"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val MIN_SIZE = 1_000_000_000L // a real model file is > 1 GB

    @Volatile var copyProgress: String? = null

    fun modelFile(ctx: Context): File = File(ctx.getExternalFilesDir(null), choice(ctx).fileName)

    fun isReady(ctx: Context): Boolean {
        val f = modelFile(ctx)
        if (!f.exists() || f.length() < MIN_SIZE || copyProgress != null) return false
        return downloadState(ctx) == null // null = no download running
    }

    fun startDownload(ctx: Context) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        modelFile(ctx).delete()
        val c = choice(ctx)
        val req = DownloadManager.Request(Uri.parse(c.url))
            .setTitle("PhonePilot brain: ${c.label}")
            .setDescription("One-time download, about ${c.sizeText}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, null, c.fileName)
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
