package dev.zet.browser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * YouTube indirme akışı: seçenekleri göster → Rust ile indir → gerekirse
 * görüntü+sesi birleştir → İndirilenler/Zet klasörüne kaydet.
 */
class YouTubeDownloader(
    private val activity: Activity,
    private val bar: View,
    private val text: TextView,
    private val progress: ProgressBar,
    cancelButton: TextView,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var jobId = 0L
    private var busy = false

    init {
        cancelButton.setOnClickListener { if (jobId != 0L) ZetCore.dlCancel(jobId) }
    }

    fun start(videoId: String) {
        if (busy) {
            toast(activity.getString(R.string.yt_busy))
            return
        }
        toast(activity.getString(R.string.yt_fetching))
        thread(name = "zet-yt-info") {
            val json = runCatching { JSONObject(ZetCore.ytOptions(videoId)) }
                .getOrElse { JSONObject().put("ok", false).put("error", it.toString()) }
            activity.runOnUiThread { showOptions(json) }
        }
    }

    private fun showOptions(json: JSONObject) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!json.optBoolean("ok")) {
            showError(activity.getString(R.string.yt_fetch_failed), json.optString("error"))
            return
        }
        val options = json.getJSONArray("options")
        val labels = Array(options.length()) { i ->
            val o = options.getJSONObject(i)
            val size = o.optLong("total_size")
            if (size > 0) "${o.getString("label")}  ·  ${formatSize(size)}" else o.getString("label")
        }
        AlertDialog.Builder(activity)
            .setTitle(json.optString("title"))
            .setItems(labels) { _, which -> begin(json, options.getJSONObject(which)) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun begin(info: JSONObject, option: JSONObject) {
        if (Build.VERSION.SDK_INT < 29 &&
            activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            toast(activity.getString(R.string.yt_permission))
            return
        }
        val kind = option.getString("kind")
        val streams = option.getJSONArray("streams")
        val dir = File(activity.cacheDir, "yt").apply {
            deleteRecursively()
            mkdirs()
        }
        val n = streams.length()
        val urls = Array(n) { streams.getJSONObject(it).getString("url") }
        val paths = Array(n) { File(dir, "part$it").path }
        val sizes = LongArray(n) { streams.getJSONObject(it).optLong("size") }
        val fileName = info.getString("fileName") + "." + option.optString("ext", "mp4")

        jobId = ZetCore.dlStart(urls, paths, sizes, info.getString("userAgent"))
        busy = true
        bar.visibility = View.VISIBLE
        progress.isIndeterminate = false
        progress.progress = 0
        text.text = "⬇ $fileName"
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        poll(kind, paths, fileName)
    }

    private fun poll(kind: String, paths: Array<String>, fileName: String) {
        handler.postDelayed({
            val st = JSONObject(ZetCore.dlStatus(jobId))
            when (st.optString("state")) {
                "running" -> {
                    val total = st.optLong("total")
                    val done = st.optLong("downloaded")
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                        progress.progress = pct
                        text.text = "⬇ %$pct · ${formatSize(done)} / ${formatSize(total)} · $fileName"
                    } else {
                        text.text = "⬇ ${formatSize(done)} · $fileName"
                    }
                    poll(kind, paths, fileName)
                }
                "done" -> {
                    ZetCore.dlForget(jobId)
                    finish(kind, paths, fileName)
                }
                "cancelled" -> {
                    ZetCore.dlForget(jobId)
                    end()
                    toast(activity.getString(R.string.yt_cancelled))
                }
                else -> {
                    ZetCore.dlForget(jobId)
                    end()
                    showError(activity.getString(R.string.yt_failed), st.optString("error"))
                }
            }
        }, 500)
    }

    private fun finish(kind: String, paths: Array<String>, fileName: String) {
        text.text = activity.getString(R.string.yt_merging, fileName)
        progress.isIndeterminate = true
        thread(name = "zet-yt-save") {
            val result = runCatching {
                val dir = File(paths[0]).parentFile!!
                val output = if (kind == "merge") {
                    File(dir, "out.mp4").also { Muxer.merge(File(paths[0]), File(paths[1]), it) }
                } else {
                    File(paths[0])
                }
                val mime = if (fileName.endsWith(".m4a")) "audio/mp4" else "video/mp4"
                saveToDownloads(output, fileName, mime)
                dir.deleteRecursively()
            }
            activity.runOnUiThread {
                end()
                result.fold(
                    { toast(activity.getString(R.string.yt_saved, fileName)) },
                    { showError(activity.getString(R.string.yt_failed), it.toString()) },
                )
            }
        }
    }

    private fun saveToDownloads(src: File, name: String, mime: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = activity.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Zet")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("İndirilenler klasörüne yazılamadı")
            try {
                resolver.openOutputStream(uri)!!.use { out ->
                    src.inputStream().use { it.copyTo(out, 1 shl 20) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Zet",
            ).apply { mkdirs() }
            src.copyTo(File(dir, name), overwrite = true)
        }
    }

    private fun end() {
        busy = false
        jobId = 0L
        bar.visibility = View.GONE
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showError(title: String, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message.ifBlank { "?" })
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.copy) { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Zet hata", "$title\n$message"))
                toast(activity.getString(R.string.copied))
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
            else -> String.format(Locale.US, "%d KB", bytes / 1024)
        }
    }
}
