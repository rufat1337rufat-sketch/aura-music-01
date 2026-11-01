package com.example.auramusic

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat

/**
 * JS -> Kotlin. В странице доступен как window.AuraBridge.
 * Методы вызываются из служебного потока WebView, не из главного.
 */
class AuraBridge(private val app: Context) {

    /** Новый трек (или сменилась обложка). coverB64 — JPEG в base64 без префикса data:. */
    @JavascriptInterface
    fun onTrack(title: String, artist: String, durationMs: Long, coverB64: String) {
        val bmp = if (coverB64.isEmpty()) null else try {
            val bytes = Base64.decode(coverB64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
        PlayerHub.track = PlayerHub.Track(title, artist, durationMs, bmp)
        changed()
    }

    /** Играет / пауза и текущая позиция в миллисекундах. */
    @JavascriptInterface
    fun onPlayback(playing: Boolean, positionMs: Long) {
        PlayerHub.playing = playing
        PlayerHub.positionMs = positionMs
        changed()
    }

    private fun changed() {
        // Сервис запускаем один раз, пока приложение на экране (требование Android 12+).
        if (!PlayerHub.serviceRunning && PlayerHub.playing && PlayerHub.track != null) {
            try {
                ContextCompat.startForegroundService(app, Intent(app, MediaService::class.java))
            } catch (e: Exception) {
                // запуск из фона запрещён системой: уведомления не будет, музыка при этом играет
            }
        }
        PlayerHub.onChanged?.invoke()
    }
}
