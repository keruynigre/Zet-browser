package dev.zet.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * GitHub Releases üzerinden güncelleme: son sürümü denetler, uyarı gösterir,
 * APK'yı indirip PackageInstaller ile kurar (aynı imza → veriler korunur).
 */
object Updater {
    private const val TAG = "ZetUpdater"
    private const val API = "https://api.github.com/repos/keruynigre/Zet-browser/releases/latest"
    private const val CHANNEL = "updates"
    private const val NOTIF_AVAILABLE = 4101
    private const val NOTIF_PROGRESS = 4102
    private const val PREF_LAST_CHECK = "update_last_check"
    private const val PREF_SKIPPED = "update_skipped"
    const val PREF_AUTO = "update_auto"
    const val EXTRA_SHOW_UPDATE = "zet_show_update"
    private val CHECK_INTERVAL = TimeUnit.HOURS.toMillis(6)

    data class Release(val code: Int, val tag: String, val notes: String, val apkUrl: String)

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var downloading = false

    @Volatile
    var latest: Release? = null
        private set

    /** Uygulama ön planda mı (kurulum onayını doğrudan açabilmek için). */
    @Volatile
    var foreground = false

    fun currentCode(c: Context): Int =
        c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode.toInt()

    fun autoEnabled(c: Context) = Settings.prefs(c).getBoolean(PREF_AUTO, true)

    /**
     * [manual] true: her durumda sonucu bildirir ("güncelsin" dahil).
     * false: 6 saatte bir, atlanan sürümü göstermeden, sessizce denetler.
     */
    fun check(activity: Activity, manual: Boolean) {
        val prefs = Settings.prefs(activity)
        if (!manual) {
            if (!autoEnabled(activity)) return
            val last = prefs.getLong(PREF_LAST_CHECK, 0)
            if (System.currentTimeMillis() - last < CHECK_INTERVAL) {
                // Yakında denetlendi; bilinen yeni sürüm varsa yine de hatırlat.
                latest?.let { if (isNewer(activity, it) && !skipped(activity, it)) showDialog(activity, it) }
                return
            }
        }
        val app = activity.applicationContext
        if (manual) Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show()
        thread(name = "zet-update-check", isDaemon = true) {
            val rel = runCatching { fetchLatest() }.onFailure { Log.w(TAG, "Denetlenemedi", it) }.getOrNull()
            prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
            main.post {
                if (rel == null) {
                    if (manual) Toast.makeText(app, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                    return@post
                }
                latest = rel
                when {
                    !isNewer(app, rel) -> {
                        if (manual) Toast.makeText(app, R.string.update_none, Toast.LENGTH_SHORT).show()
                    }
                    !manual && skipped(app, rel) -> Unit
                    else -> {
                        notifyAvailable(app, rel)
                        if (!activity.isFinishing && !activity.isDestroyed) showDialog(activity, rel)
                    }
                }
            }
        }
    }

    private fun isNewer(c: Context, r: Release) = r.code > currentCode(c)
    private fun skipped(c: Context, r: Release) = Settings.prefs(c).getInt(PREF_SKIPPED, 0) == r.code

    private fun fetchLatest(): Release? {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "ZetBrowser")
        if (conn.responseCode != 200) return null
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val tag = json.optString("tag_name")
        val code = Regex("""android-(\d+)""").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        var url: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
        }
        return Release(code, tag, cleanNotes(json.optString("body")), url ?: return null)
    }

    /** Sürüm notlarından teknik satırları (commit kimliği vb.) ayıklar. */
    private fun cleanNotes(body: String): String = body.lines()
        .filterNot { it.startsWith("Commit:") || it.contains("zet-browser.apk") || it.startsWith("Co-Authored-By") ||
            it.startsWith("Claude-Session") }
        .joinToString("\n").trim()

    private fun ensureChannel(c: Context) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, c.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun notifyAvailable(c: Context, r: Release) {
        ensureChannel(c)
        val open = PendingIntent.getActivity(
            c, 0,
            Intent(c, BrowserActivity::class.java).putExtra(EXTRA_SHOW_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(c.getString(R.string.update_available_title))
            .setContentText(c.getString(R.string.update_available_text, r.tag))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(NOTIF_AVAILABLE, n) }
    }

    /** Bildirime dokunulunca veya ayarlardan: bilinen sürüm için uyarıyı göster. */
    fun showPending(activity: Activity) {
        val r = latest
        if (r != null && isNewer(activity, r)) showDialog(activity, r) else check(activity, manual = true)
    }

    private var dialogShown: AlertDialog? = null

    fun showDialog(activity: Activity, r: Release) {
        if (dialogShown?.isShowing == true || downloading) return
        val msg = buildString {
            append(activity.getString(R.string.update_dialog_msg, r.tag, currentCode(activity)))
            if (r.notes.isNotBlank()) append("\n\n").append(r.notes.take(1200))
        }
        dialogShown = AlertDialog.Builder(activity)
            .setTitle(R.string.update_available_title)
            .setMessage(msg)
            .setPositiveButton(R.string.update_now) { _, _ -> downloadAndInstall(activity.applicationContext, r) }
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_skip) { _, _ ->
                Settings.prefs(activity).edit().putInt(PREF_SKIPPED, r.code).apply()
            }
            .show()
    }

    private fun downloadAndInstall(c: Context, r: Release) {
        if (downloading) return
        downloading = true
        ensureChannel(c)
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIF_AVAILABLE)
        Toast.makeText(c, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        fun progress(pct: Int) {
            val n = Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(c.getString(R.string.update_downloading))
                .setProgress(100, pct, pct < 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            runCatching { nm.notify(NOTIF_PROGRESS, n) }
        }
        thread(name = "zet-update-dl", isDaemon = true) {
            val file = File(c.cacheDir, "update.apk")
            try {
                progress(-1)
                val conn = URL(r.apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "ZetBrowser")
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                var done = 0L
                var lastPct = -1
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; progress(pct) }
                            }
                        }
                    }
                }
                if (total > 0 && done != total) error("Eksik indirme")
                install(c, file)
            } catch (e: Exception) {
                Log.w(TAG, "Güncelleme indirilemedi", e)
                main.post { Toast.makeText(c, R.string.update_failed, Toast.LENGTH_LONG).show() }
                file.delete()
            } finally {
                nm.cancel(NOTIF_PROGRESS)
                downloading = false
            }
        }
    }

    private fun install(c: Context, apk: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("zet.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val pi = PendingIntent.getBroadcast(
                c, id, Intent(c, InstallReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(pi.intentSender)
        }
        apk.delete()
    }

    /** Kurulum durumunu alır; kullanıcı onayı gerekiyorsa onay ekranını açar. */
    class InstallReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (foreground) {
                        runCatching { context.startActivity(confirm) }.onSuccess { return }
                    }
                    // Arka plandaysak bildirimle sor.
                    Updater.ensureChannel(context)
                    val pi = PendingIntent.getActivity(
                        context, 1, confirm,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    val n = Notification.Builder(context, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle(context.getString(R.string.update_ready))
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build()
                    runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIF_AVAILABLE, n) }
                }
                PackageInstaller.STATUS_SUCCESS -> Unit
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                    Log.w(TAG, "Kurulum başarısız: $msg")
                    Toast.makeText(context, context.getString(R.string.update_failed) + " $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
