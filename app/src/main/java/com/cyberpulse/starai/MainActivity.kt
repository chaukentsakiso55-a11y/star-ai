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
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val librarySources = listOf(
        LibrarySource(
            id = "cyber_pulse_info",
            name = "Cyber Pulse Info",
            baseUrl = "https://cyber-pulse-info.netlify.app"
        ),
        LibrarySource(
            id = "cyber_learn_projects",
            name = "Cyber Learn Projects",
            baseUrl = "https://cyber-learn-projects.netlify.app"
        )
    )

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
            injectRuntimeConfig(decodeStarHtml()),
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

    private fun injectRuntimeConfig(html: String): String {
        val sourcesJson = librarySourcesJson()
        val script = """
            <script>
            window.STAR_LIBRARY_SOURCES = $sourcesJson;
            window.STAR_OFFLINE_LIBRARY_CONFIG = {
              sources: window.STAR_LIBRARY_SOURCES,
              sourceMode: 'cyber-pulse-reference-sites',
              localFirst: true
            };
            window.__starNativePending = {};
            window.__starNativeResolve = function(id, ok, payload){
              var pending = window.__starNativePending[id];
              if(!pending) return;
              delete window.__starNativePending[id];
              if(ok) pending.resolve(payload); else pending.reject(new Error(payload));
            };
            window.addEventListener('load', function(){
              window.callLiveAPI = function(conversationMessages){
                if(!window.StarNative || !window.StarNative.askGemini){
                  return Promise.reject(new Error('Native Gemini bridge unavailable'));
                }
                var id = 'g' + Date.now() + Math.random().toString(16).slice(2);
                var systemPrompt = (typeof state !== 'undefined' && state.systemPrompt) ? state.systemPrompt : '';
                var payload = JSON.stringify({ messages: conversationMessages || [], systemPrompt: systemPrompt });
                return new Promise(function(resolve, reject){
                  window.__starNativePending[id] = { resolve: resolve, reject: reject };
                  window.StarNative.askGemini(id, payload);
                });
              };
            });
            </script>
        """.trimIndent()
        return if (html.contains("</head>", ignoreCase = true)) {
            html.replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), "$script\n</head>")
        } else {
            "$script\n$html"
        }
    }

    private fun librarySourcesJson(): String {
        val array = JSONArray()
        librarySources.forEach { source ->
            array.put(
                JSONObject()
                    .put("id", source.id)
                    .put("name", source.name)
                    .put("baseUrl", source.baseUrl)
            )
        }
        return array.toString()
    }

    private fun decodeApiKey(): String {
        val mask = byteArrayOf(
            0x53, 0x74, 0x61, 0x72, 0x41, 0x49, 0x2D, 0x4F, 0x66, 0x66, 0x6C, 0x69, 0x6E, 0x65, 0x21
        )
        val cipher = Base64.decode(BuildConfig.STAR_AI_KEY_BLOB, Base64.DEFAULT)
        val decoded = ByteArray(cipher.size) { index ->
            (cipher[index].toInt() xor mask[index % mask.size].toInt()).toByte()
        }
        return decoded.toString(Charsets.UTF_8)
    }

    private fun callGemini(payloadJson: String): String {
        val key = decodeApiKey()
        if (!key.startsWith("AQ.")) throw IllegalStateException("Gemini key is not configured")

        val payload = JSONObject(payloadJson)
        val inputMessages = payload.optJSONArray("messages") ?: JSONArray()
        val contents = JSONArray()
        for (i in 0 until inputMessages.length()) {
            val item = inputMessages.optJSONObject(i) ?: continue
            val role = item.optString("role")
            if (role != "user" && role != "star" && role != "assistant" && role != "model") continue
            val text = item.optString("text").ifBlank { item.optString("content") }
            if (text.isBlank()) continue
            contents.put(
                JSONObject()
                    .put("role", if (role == "user") "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
        }

        val request = JSONObject().put("contents", contents)
        val systemPrompt = payload.optString("systemPrompt")
        if (systemPrompt.isNotBlank()) {
            request.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
        }

        val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", key)
        }

        connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("Gemini request failed ($code)")

        val response = JSONObject(raw)
        val candidates = response.optJSONArray("candidates") ?: throw IllegalStateException("Gemini returned no candidates")
        val parts = candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw IllegalStateException("Gemini returned no text")
        val text = buildString {
            for (i in 0 until parts.length()) {
                val partText = parts.optJSONObject(i)?.optString("text").orEmpty()
                if (partText.isNotBlank()) append(partText)
            }
        }.trim()
        if (text.isBlank()) throw IllegalStateException("Gemini returned an empty response")
        return text
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

    private fun deliverGeminiResult(requestId: String, ok: Boolean, payload: String) {
        val idJson = JSONObject.quote(requestId)
        val payloadJson = JSONObject.quote(payload)
        runOnUiThread {
            webView.evaluateJavascript(
                "window.__starNativeResolve && window.__starNativeResolve($idJson, ${if (ok) "true" else "false"}, $payloadJson);",
                null
            )
        }
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
        fun isApiKeyConfigured(): Boolean = decodeApiKey().startsWith("AQ.")

        @JavascriptInterface
        fun askGemini(requestId: String, payloadJson: String) {
            Thread {
                runCatching { callGemini(payloadJson) }
                    .onSuccess { deliverGeminiResult(requestId, true, it) }
                    .onFailure { deliverGeminiResult(requestId, false, it.message ?: "Gemini request failed") }
            }.start()
        }

        @JavascriptInterface
        fun appVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun librarySources(): String = librarySourcesJson()
    }

    data class LibrarySource(
        val id: String,
        val name: String,
        val baseUrl: String
    )
}
