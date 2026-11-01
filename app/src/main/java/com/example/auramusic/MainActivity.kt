package com.example.auramusic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pickImages = false

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ничего не делаем */ }

    // Выбор файлов для <input type="file"> на странице (музыка и обложки)
    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            filePathCallback?.onReceiveValue(uris.toTypedArray())
            filePathCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true                 // localStorage и IndexedDB (библиотека, плейлисты)
            mediaPlaybackRequiresUserGesture = false // чтобы кнопки уведомления могли запускать play()
        }

        // Страница отдаётся по https-адресу из папки assets: так работают IndexedDB и Web Audio
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                pickImages = params.acceptTypes?.any { it.startsWith("image") } == true
                return try {
                    pickFiles.launch(if (pickImages) arrayOf("image/*") else arrayOf("audio/*"))
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }

        // JS -> Kotlin: в странице это window.AuraBridge
        web.addJavascriptInterface(AuraBridge(applicationContext), "AuraBridge")

        // Kotlin -> JS: кнопки уведомления вызывают window.auraControl(cmd, arg)
        PlayerHub.commandSink = { cmd, arg ->
            web.post { web.evaluateJavascript("window.auraControl&&window.auraControl('$cmd',$arg)", null) }
        }

        // Кнопка «Назад»: закрывает слои плеера, а в корне сворачивает приложение (музыка играет дальше)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
            }
        })

        // Android 13+: без этого разрешения уведомление не покажется
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    // ВАЖНО: не вызывайте web.onPause() и web.pauseTimers():
    // иначе музыка остановится, когда экран погаснет.

    override fun onDestroy() {
        if (isFinishing) {
            PlayerHub.commandSink = null
            stopService(Intent(this, MediaService::class.java))
            web.destroy()
        }
        super.onDestroy()
    }
}
