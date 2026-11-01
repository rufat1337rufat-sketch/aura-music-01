package com.example.auramusic

import android.graphics.Bitmap

/**
 * Общее состояние между WebView (MainActivity), JS-мостом (AuraBridge)
 * и сервисом уведомления (MediaService).
 */
object PlayerHub {
    data class Track(
        val title: String,
        val artist: String,
        val durationMs: Long,
        val cover: Bitmap?
    )

    @Volatile var track: Track? = null
    @Volatile var playing: Boolean = false
    @Volatile var positionMs: Long = 0L
    @Volatile var serviceRunning: Boolean = false

    /** Задаётся в MainActivity: отправляет команду в WebView. */
    @Volatile var commandSink: ((String, Double) -> Unit)? = null

    /** Задаётся в MediaService: просит перерисовать сессию и уведомление. */
    @Volatile var onChanged: (() -> Unit)? = null

    /** Команды: play, pause, next, prev, seek (arg = секунды). */
    fun send(cmd: String, arg: Double = 0.0) {
        commandSink?.invoke(cmd, arg)
    }
}
