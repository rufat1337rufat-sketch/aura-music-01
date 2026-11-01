package com.example.auramusic

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground-сервис: держит процесс живым, пока играет музыка,
 * ведёт MediaSession и показывает уведомление MediaStyle
 * (его же Android выводит на экран блокировки и в шторку).
 */
class MediaService : Service() {

    companion object {
        const val CHANNEL_ID = "aura_playback"
        const val NOTIF_ID = 1001
        const val ACT_PLAY = "com.example.auramusic.PLAY"
        const val ACT_PAUSE = "com.example.auramusic.PAUSE"
        const val ACT_NEXT = "com.example.auramusic.NEXT"
        const val ACT_PREV = "com.example.auramusic.PREV"
        private const val FG_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    }

    private lateinit var session: MediaSessionCompat
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createChannel()

        session = MediaSessionCompat(this, "AuraMusic").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    PlayerHub.send("play")
                }

                override fun onPause() {
                    PlayerHub.send("pause")
                }

                override fun onSkipToNext() {
                    PlayerHub.send("next")
                }

                override fun onSkipToPrevious() {
                    PlayerHub.send("prev")
                }

                override fun onSeekTo(pos: Long) {
                    PlayerHub.send("seek", pos / 1000.0)
                }

                override fun onStop() {
                    PlayerHub.send("pause")
                    stopSelf()
                }
            })
            isActive = true
        }

        PlayerHub.serviceRunning = true
        PlayerHub.onChanged = { main.post { refresh() } }

        updateSession()
        // startForeground нужно вызвать в течение 5 секунд после startForegroundService
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), FG_TYPE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_PLAY -> PlayerHub.send("play")
            ACT_PAUSE -> PlayerHub.send("pause")
            ACT_NEXT -> PlayerHub.send("next")
            ACT_PREV -> PlayerHub.send("prev")
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onDestroy() {
        PlayerHub.onChanged = null
        PlayerHub.serviceRunning = false
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- обновление ----------

    @SuppressLint("MissingPermission")
    private fun refresh() {
        updateSession()
        val n = buildNotification()
        if (PlayerHub.playing) {
            ServiceCompat.startForeground(this, NOTIF_ID, n, FG_TYPE)
        } else {
            // на паузе уведомление остаётся, но его можно смахнуть
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            try {
                NotificationManagerCompat.from(this).notify(NOTIF_ID, n)
            } catch (e: SecurityException) {
                // нет разрешения на уведомления
            }
        }
    }

    private fun updateSession() {
        val t = PlayerHub.track
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, t?.title ?: "")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, t?.artist ?: "")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, t?.durationMs ?: 0L)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, t?.cover)
                .build()
        )
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
        val state = if (PlayerHub.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, PlayerHub.positionMs, if (PlayerHub.playing) 1f else 0f)
                .build()
        )
    }

    private fun buildNotification(): Notification {
        val t = PlayerHub.track
        val playing = PlayerHub.playing
        val title = (t?.title ?: "").ifEmpty { "Aura Music" }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(title)
            .setContentText(t?.artist ?: "")
            .setLargeIcon(t?.cover)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .addAction(android.R.drawable.ic_media_previous, "Назад", action(ACT_PREV))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Пауза" else "Играть",
                action(if (playing) ACT_PAUSE else ACT_PLAY)
            )
            .addAction(android.R.drawable.ic_media_next, "Вперёд", action(ACT_NEXT))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun action(name: String): PendingIntent =
        PendingIntent.getService(
            this, name.hashCode(),
            Intent(this, MediaService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Воспроизведение", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}
