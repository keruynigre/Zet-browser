package dev.zet.browser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
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

    init {
        cancelButton.setOnClickListener { if (jobId != 0L) DownloadService.cancel(activity, jobId) }
    }

    fun start(videoId: String) {
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
            .setTitle(json.optString("title") + "\n(" + json.optString("client") + ")")
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
        // Bildirimde ilerleme göstermek için (Android 13+). Reddedilse de indirme sürer.
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        val streams = option.getJSONArray("streams")
        val n = streams.length()
        DownloadService.start(
            activity,
            urls = Array(n) { streams.getJSONObject(it).getString("url") },
            sizes = LongArray(n) { streams.getJSONObject(it).optLong("size") },
            userAgent = info.getString("userAgent"),
            kind = option.getString("kind"),
            fileName = info.getString("fileName") + "." + option.optString("ext", "mp4"),
        )
        toast(activity.getString(R.string.dl_background))
        startWatching()
    }

    /** Servisin son durumunu uygulama içindeki çubukta gösterir. */
    fun startWatching() {
        handler.removeCallbacksAndMessages(null)
        handler.post(object : Runnable {
            override fun run() {
                val st = DownloadService.latest
                if (st == null) {
                    bar.visibility = View.GONE
                    return
                }
                bar.visibility = View.VISIBLE
                text.text = st.text
                progress.isIndeterminate = st.percent < 0
                if (st.percent >= 0) progress.progress = st.percent
                jobId = if (st.busy) st.jobId else 0L
                if (st.busy) {
                    handler.postDelayed(this, 500)
                } else {
                    DownloadService.lastError?.let {
                        DownloadService.lastError = null
                        showError(activity.getString(R.string.yt_failed), it)
                    }
                    handler.postDelayed({ bar.visibility = View.GONE }, 3000)
                    DownloadService.latest = null
                }
            }
        })
    }

    fun stopWatching() {
        handler.removeCallbacksAndMessages(null)
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
