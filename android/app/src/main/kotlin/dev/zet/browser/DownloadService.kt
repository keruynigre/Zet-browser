package dev.zet.browser

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Arka planda indirme servisi. Uygulama kapatılsa da indirme sürer;
 * ilerleme bildirimde gösterilir. İndirme Rust çekirdeğinde yapılır,
 * gerekirse görüntü+ses burada birleştirilip İndirilenler/Zet'e kaydedilir.
 */
class DownloadService : Service() {

    private class Task(
        val jobId: Long,
        val notifId: Int,
        val fileName: String,
        val kind: String,
        val paths: Array<String>,
        val dir: File,
    )

    /** Uygulama içi ilerleme çubuğu için son durum. */
    data class Status(val jobId: Long, val text: String, val percent: Int, val busy: Boolean)

    private val tasks = LinkedHashMap<Long, Task>() // yalnızca ana thread
    private val finishing = AtomicInteger(0)
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private lateinit var nm: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.dl_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_CANCEL -> {
                val id = intent.getLongExtra(EXTRA_JOB, 0L)
                if (id != 0L) ZetCore.dlCancel(id)
            }
        }
        if (tasks.isEmpty() && finishing.get() == 0) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        val urls = intent.getStringArrayExtra(EXTRA_URLS) ?: return
        val sizes = intent.getLongArrayExtra(EXTRA_SIZES) ?: LongArray(urls.size)
        val ua = intent.getStringExtra(EXTRA_UA) ?: ""
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "muxed"
        val fileName = intent.getStringExtra(EXTRA_NAME) ?: "video.mp4"

        val notifId = NEXT_NOTIF.incrementAndGet()
        val dir = File(cacheDir, "dl/$notifId").apply {
            deleteRecursively()
            mkdirs()
        }
        val paths = Array(urls.size) { File(dir, "part$it").path }
        val jobId = ZetCore.dlStart(urls, paths, sizes, ua)
        val task = Task(jobId, notifId, fileName, kind, paths, dir)
        tasks[jobId] = task

        startForeground(notifId, progressNotification(task, 0, 0, 0))
        acquireWakeLock()
        latest = Status(jobId, "⬇ $fileName", 0, true)
        if (!polling) {
            polling = true
            handler.post(::poll)
        }
    }

    private fun poll() {
        for (task in tasks.values.toList()) {
            val st = JSONObject(ZetCore.dlStatus(task.jobId))
            when (st.optString("state")) {
                "running" -> {
                    val total = st.optLong("total")
                    val done = st.optLong("downloaded")
                    val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                    nm.notify(task.notifId, progressNotification(task, pct, done, total))
                    val sizeText = if (total > 0) {
                        "%$pct · ${fmt(done)} / ${fmt(total)}"
                    } else {
                        fmt(done)
                    }
                    latest = Status(task.jobId, "⬇ $sizeText · ${task.fileName}", pct, true)
                }
                "done" -> {
                    tasks.remove(task.jobId)
                    ZetCore.dlForget(task.jobId)
                    finish(task)
                }
                "cancelled" -> {
                    tasks.remove(task.jobId)
                    ZetCore.dlForget(task.jobId)
                    task.dir.deleteRecursively()
                    nm.cancel(task.notifId)
                    latest = Status(task.jobId, getString(R.string.yt_cancelled), 0, false)
                }
                else -> {
                    tasks.remove(task.jobId)
                    ZetCore.dlForget(task.jobId)
                    task.dir.deleteRecursively()
                    val err = st.optString("error")
                    nm.notify(task.notifId, doneNotification(getString(R.string.yt_failed), "${task.fileName}\n$err", false))
                    latest = Status(task.jobId, getString(R.string.yt_failed) + ": " + err, 0, false)
                    lastError = err
                }
            }
        }
        if (tasks.isNotEmpty()) {
            handler.postDelayed(::poll, 700)
        } else {
            polling = false
            maybeStop()
        }
    }

    private fun finish(task: Task) {
        finishing.incrementAndGet()
        nm.notify(task.notifId, progressNotification(task, -1, 0, 0))
        latest = Status(task.jobId, getString(R.string.yt_merging, task.fileName), -1, true)
        thread(name = "zet-dl-save") {
            val result = runCatching {
                val output = if (task.kind == "merge") {
                    File(task.dir, "out.mp4").also {
                        Muxer.merge(File(task.paths[0]), File(task.paths[1]), it)
                    }
                } else {
                    File(task.paths[0])
                }
                val mime = if (task.fileName.endsWith(".m4a")) "audio/mp4" else "video/mp4"
                saveToDownloads(output, task.fileName, mime)
            }
            task.dir.deleteRecursively()
            handler.post {
                result.fold(
                    {
                        nm.notify(
                            task.notifId,
                            doneNotification(getString(R.string.dl_done), task.fileName, true),
                        )
                        latest = Status(task.jobId, getString(R.string.yt_saved, task.fileName), 100, false)
                    },
                    {
                        nm.notify(
                            task.notifId,
                            doneNotification(getString(R.string.yt_failed), it.toString(), false),
                        )
                        latest = Status(task.jobId, getString(R.string.yt_failed), 0, false)
                        lastError = it.toString()
                    },
                )
                finishing.decrementAndGet()
                maybeStop()
            }
        }
    }

    private fun maybeStop() {
        if (tasks.isEmpty() && finishing.get() == 0) {
            // Son bildirim (tamamlandı/başarısız) ekranda kalsın.
            stopForeground(STOP_FOREGROUND_DETACH)
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zet:download").apply {
            acquire(3 * 60 * 60 * 1000L)
        }
    }

    private fun progressNotification(task: Task, percent: Int, done: Long, total: Long): Notification {
        val cancel = PendingIntent.getService(
            this, task.notifId,
            Intent(this, DownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_JOB, task.jobId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            percent < 0 -> getString(R.string.yt_merging, "")
            total > 0 -> "%$percent · ${fmt(done)} / ${fmt(total)}"
            else -> fmt(done)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task.fileName)
            .setContentText(text)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(null, getString(R.string.cancel), cancel).build())
            .build()
    }

    private fun doneNotification(title: String, text: String, success: Boolean): Notification {
        val open = if (success) {
            PendingIntent.getActivity(
                this, 0,
                Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            openAppIntent()
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error,
            )
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 1,
        Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun saveToDownloads(src: File, name: String, mime: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Zet")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("İndirilenler klasörüne yazılamadı")
            try {
                contentResolver.openOutputStream(uri)!!.use { out ->
                    src.inputStream().use { it.copyTo(out, 1 shl 20) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
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

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ACTION_START = "dev.zet.browser.DOWNLOAD_START"
        private const val ACTION_CANCEL = "dev.zet.browser.DOWNLOAD_CANCEL"
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_SIZES = "sizes"
        private const val EXTRA_UA = "ua"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_JOB = "job"
        private val NEXT_NOTIF = AtomicInteger(1000)

        /** Uygulama içi çubuğun gösterdiği son durum (servis günceller). */
        @Volatile
        var latest: Status? = null

        /** Son başarısız indirmenin hata metni (uygulama içinde göstermek için). */
        @Volatile
        var lastError: String? = null

        fun start(
            context: Context,
            urls: Array<String>,
            sizes: LongArray,
            userAgent: String,
            kind: String,
            fileName: String,
        ) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URLS, urls)
                .putExtra(EXTRA_SIZES, sizes)
                .putExtra(EXTRA_UA, userAgent)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_NAME, fileName)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context, jobId: Long) {
            context.startService(
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_JOB, jobId),
            )
        }

        fun fmt(bytes: Long): String = YouTubeDownloader.formatSize(bytes)
    }
}
