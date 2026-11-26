package com.djchany.panel

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject

/**
 * Panel de activación de DJ Chany IPTV como app.
 * El panel web va incluido y se sirve desde un origen https local, así puede cifrar
 * (WebCrypto) y llamar a la API de GitHub igual que en el navegador.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val START = "https://$HOST/assets/panel/index.html"
        private val CODE = Regex("(?i)\\bDJ[-\\s]?([A-Z0-9]{5})[-\\s]?([A-Z0-9]{5})\\b")
    }

    private lateinit var web: WebView
    private var pageReady = false
    private var pendingCode: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingExport: String? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    private val createFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val content = pendingExport
        pendingExport = null
        if (uri == null || content == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            toast("Copia de seguridad guardada")
        } catch (e: Exception) {
            toast("No se pudo guardar el archivo")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
        }
        web.setBackgroundColor(0xFF0A0614.toInt())
        web.addJavascriptInterface(Bridge(), "DJChanyApp")

        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                // Enlaces externos (por ejemplo, crear el token en GitHub) se abren en el navegador.
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: Exception) {
                    toast("No hay navegador para abrir el enlace")
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pendingCode?.let { prefill(it) }
                pendingCode = null
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                pickFile.launch("*/*")
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else finish()
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(START)
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    /** Texto compartido desde WhatsApp: se extrae el código del equipo y se abre su ficha. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val code = findCode(text)
        if (code == null) {
            toast("No encontré un código DJ-XXXXX-XXXXX en el mensaje")
            return
        }
        if (pageReady) prefill(code) else pendingCode = code
    }

    private fun findCode(text: String): String? =
        CODE.find(text)?.let { "DJ-${it.groupValues[1].uppercase()}-${it.groupValues[2].uppercase()}" }

    private fun prefill(code: String) {
        web.evaluateJavascript("window.djPrefillCode && window.djPrefillCode(${JSONObject.quote(code)});", null)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Funciones nativas disponibles para el panel como window.DJChanyApp. */
    inner class Bridge {
        @JavascriptInterface
        fun isApp(): Boolean = true

        @JavascriptInterface
        fun saveFile(name: String, content: String) {
            runOnUiThread {
                pendingExport = content
                createFile.launch(name)
            }
        }

        /** Devuelve el código que haya en el portapapeles (por ejemplo, copiado de WhatsApp). */
        @JavascriptInterface
        fun clipboardCode(): String {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()
            return text?.let { findCode(it) } ?: ""
        }
    }
}
