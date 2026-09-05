package com.cyberpulse.starai

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) deliverSpeechResult(text)
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchSpeechRecognizer() else openMicrophoneSettingsHint()
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(3, 5, 10)
        window.navigationBarColor = Color.rgb(3, 5, 10)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        textToSpeech = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) textToSpeech?.language = Locale.getDefault()
        }

        webView = WebView(this)
        webView.setBackgroundColor(Color.rgb(3, 5, 10))
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.addJavascriptInterface(StarNativeBridge(), "StarNative")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                if (!request.isForMainFrame) return false
                if (uri.host == "star.local") return false
                if (uri.scheme == "http" || uri.scheme == "https") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    return true
                }
                return false
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = (fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT)).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = fileChooserParams?.acceptTypes?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "*/*"
                }
                return runCatching {
                    fileChooserLauncher.launch(intent)
                    true
                }.getOrElse {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        setContentView(webView)
        webView.loadDataWithBaseURL(
            "https://star.local/",
            decodeStarHtml(),
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun decodeStarHtml(): String {
        val parts = listOf(
            "star_html_1a.b64",
            "star_html_1b.b64",
            "star_html_1c.b64",
            "star_html_1d.b64",
            "star_html_2a.b64",
            "star_html_2b1.b64",
            "star_html_2b2.b64",
            "star_html_2b3.b64",
            "star_html_2b4.b64",
            "star_html_2b5_1.b64",
            "star_html_2b5_2.b64",
            "star_html_2b5_3.b64",
            "star_html_2b5_4.b64",
            "star_html_2b5_5.b64",
            "star_html_2c.b64",
            "star_html_2d.b64",
            "star_html_3a.b64",
            "star_html_3b.b64",
            "star_html_3c.b64",
            "star_html_3d.b64",
            "star_html_4.b64"
        )
        val encoded = buildString {
            parts.forEach { name ->
                append(assets.open(name).bufferedReader(Charsets.US_ASCII).use { it.readText() })
            }
        }
        val compressed = Base64.decode(encoded, Base64.DEFAULT)
        return GZIPInputStream(ByteArrayInputStream(compressed))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }

    private fun requestSpeech() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            launchSpeechRecognizer()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchSpeechRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to STAR")
        }
        runCatching { speechLauncher.launch(intent) }
            .onFailure { openMicrophoneSettingsHint() }
    }

    private fun deliverSpeechResult(text: String) {
        val safe = JSONObject.quote(text)
        webView.evaluateJavascript(
            "window.onNativeSpeechResult && window.onNativeSpeechResult($safe);",
            null
        )
    }

    private fun openMicrophoneSettingsHint() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }
    }

    private fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "star-ai-response")
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        webView.removeJavascriptInterface("StarNative")
        webView.destroy()
        super.onDestroy()
    }

    inner class StarNativeBridge {
        @JavascriptInterface
        fun startSpeech() {
            runOnUiThread { requestSpeech() }
        }

        @JavascriptInterface
        fun speak(text: String) {
            runOnUiThread { this@MainActivity.speak(text) }
        }

        @JavascriptInterface
        fun stopSpeaking() {
            runOnUiThread { textToSpeech?.stop() }
        }

        @JavascriptInterface
        fun isApiKeyConfigured(): Boolean = BuildConfig.STAR_AI_API_KEY.isNotBlank()

        @JavascriptInterface
        fun appVersion(): String = BuildConfig.VERSION_NAME
    }
}
