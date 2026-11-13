package dev.zet.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.PowerManager

/**
 * Sayfada video/ses çalarken çalışan ön plan servisi: uygulama arka plandayken
 * ya da ekran kilitliyken Android'in süreci durdurmasını engeller ve kilit
 * ekranında/bildirimde oynat-duraklat kontrolleri gösterir.
 */
class PlaybackService : Service() {

    private lateinit var session: MediaSession
    private var wakeLock: PowerManager.WakeLock? = null
    private var title = ""
    private var playing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.pb_channel), NotificationManager.IMPORTANCE_LOW),
        )
        session = MediaSession(this, "zet").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { PlaybackService.controller?.invoke("play") }
                override fun onPause() { PlaybackService.controller?.invoke("pause") }
                override fun onStop() { PlaybackService.controller?.invoke("pause") }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> controller?.invoke("toggle")
            ACTION_CLOSE -> {
                controller?.invoke("pause")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> update(
                intent?.getStringExtra(EXTRA_TITLE) ?: title,
                intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true,
            )
        }
        return START_NOT_STICKY
    }

    fun update(newTitle: String, isPlaying: Boolean) {
        title = newTitle
        playing = isPlaying
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "YouTube · Zet")
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f,
                )
                .build(),
        )
        startForeground(NOTIF_ID, buildNotification())
        if (playing) acquireWakeLock() else releaseWakeLock()
    }

    private fun buildNotification(): Notification {
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val close = PendingIntent.getService(
            this, 2, Intent(this, PlaybackService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 3,
            Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleAction = Notification.Action.Builder(
            Icon.createWithResource(
                this,
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            ),
            getString(if (playing) R.string.pb_pause else R.string.pb_play),
            toggle,
        ).build()
        val closeAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            getString(R.string.pb_close),
            close,
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title.ifBlank { "YouTube" })
            .setContentText("Zet")
            .setContentIntent(open)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(toggleAction)
            .addAction(closeAction)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zet:playback")
            .apply { acquire(4 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        instance = null
        releaseWakeLock()
        session.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 42
        private const val ACTION_TOGGLE = "dev.zet.browser.PLAYBACK_TOGGLE"
        private const val ACTION_CLOSE = "dev.zet.browser.PLAYBACK_CLOSE"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PLAYING = "playing"

        /** Çalışan servis (aynı süreçte). */
        @Volatile
        var instance: PlaybackService? = null

        /** Sayfadaki oynatıcıya komut gönderen fonksiyon ("play", "pause", "toggle"). */
        @Volatile
        var controller: ((String) -> Unit)? = null

        /** Çalma durumunu bildirir; servis yoksa (uygulama ön plandayken) başlatır. */
        fun report(context: Context, title: String, playing: Boolean) {
            val running = instance
            if (running != null) {
                running.update(title, playing)
                return
            }
            if (!playing) return
            try {
                context.startForegroundService(
                    Intent(context, PlaybackService::class.java)
                        .putExtra(EXTRA_TITLE, title)
                        .putExtra(EXTRA_PLAYING, true),
                )
            } catch (e: Exception) {
                // Android 12+: arka plandan servis başlatılamaz; bir sonraki
                // ön plan çalmasında başlatılır.
            }
        }

        fun stop(context: Context) {
            if (instance != null) context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
