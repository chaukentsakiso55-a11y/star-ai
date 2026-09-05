package com.cyberpulse.starai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

private val Cyan = Color(0xFF43D9FF)
private val Blue = Color(0xFF5B7FFF)
private val Violet = Color(0xFF9B6BFF)
private val Bg = Color(0xFF05070D)
private val Surface = Color(0xFF0D1120)
private val Surface2 = Color(0xFF131829)
private val TextDim = Color(0xFF9098B8)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        tts = TextToSpeech(this, this)
        setContent { StarTheme { StarApp(onSpeak = ::speak) } }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts.language = Locale.getDefault()
        }
    }

    private fun speak(text: String) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "star-ai-response")
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}

private enum class Tab(val label: String, val icon: String) {
    HOME("Home", "✦"), CHAT("Chat", "◌"), LIBRARIES("Libraries", "▤"), SETTINGS("Settings", "⚙")
}

private data class ChatMessage(val role: String, val text: String, val source: String? = null)
private data class SearchHit(val pageId: Int, val title: String, val snippet: String)
private data class LocalLibrary(val pageId: Int, val title: String, val sourceUrl: String, val text: String, val downloadedAt: Long)
private data class OfflineAnswer(val text: String, val source: String?)

@Composable
private fun StarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cyan,
            secondary = Violet,
            background = Bg,
            surface = Surface,
            surfaceVariant = Surface2,
            onPrimary = Color(0xFF00141A),
            onBackground = Color(0xFFEDEFFA),
            onSurface = Color(0xFFEDEFFA)
        ),
        content = content
    )
}

@Composable
private fun StarApp(onSpeak: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LibraryStore(context.applicationContext) }
    val prefs = remember { context.getSharedPreferences("star_ai_settings", Context.MODE_PRIVATE) }

    var tab by remember { mutableStateOf(Tab.HOME) }
    var libraries by remember { mutableStateOf(store.listLibraries()) }
    var input by remember { mutableStateOf("") }
    var autoSpeak by remember { mutableStateOf(prefs.getBoolean("auto_speak", false)) }
    val messages = remember { mutableStateListOf<ChatMessage>() }

    var searchQuery by remember { mutableStateOf("") }
    var searchHits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var queuedTitle by remember { mutableStateOf<String?>(null) }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input = it }
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchSpeechRecognizer(context) { intent -> speechLauncher.launch(intent) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            libraries = store.listLibraries()
            delay(1200)
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        AmbientSpace()
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = Color(0xEE090C16), modifier = Modifier.navigationBarsPadding()) {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Text(item.icon, fontSize = 19.sp) },
                            label = { Text(item.label, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Cyan,
                                selectedTextColor = Cyan,
                                indicatorColor = Cyan.copy(alpha = 0.13f),
                                unselectedIconColor = TextDim,
                                unselectedTextColor = TextDim
                            )
                        )
                    }
                }
            }
        ) { padding ->
            when (tab) {
                Tab.HOME -> HomeScreen(padding, libraries, messages.size) { tab = it }
                Tab.CHAT -> ChatScreen(
                    padding = padding,
                    input = input,
                    onInputChange = { input = it },
                    messages = messages,
                    libraries = libraries,
                    onMic = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            launchSpeechRecognizer(context) { intent -> speechLauncher.launch(intent) }
                        } else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onSend = {
                        val question = input.trim()
                        if (question.isNotEmpty()) {
                            input = ""
                            messages.add(ChatMessage("user", question))
                            scope.launch {
                                val answer = withContext(Dispatchers.Default) { store.answerFromLibraries(question) }
                                    ?: OfflineAnswer("I don't have enough downloaded knowledge to answer that offline yet. Open Libraries and download a relevant topic first.", null)
                                messages.add(ChatMessage("star", answer.text, answer.source))
                                if (autoSpeak) onSpeak(answer.text)
                            }
                        }
                    },
                    onSpeak = onSpeak,
                    openLibraries = { tab = Tab.LIBRARIES }
                )
                Tab.LIBRARIES -> LibrariesScreen(
                    padding = padding,
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    searching = searching,
                    error = searchError,
                    hits = searchHits,
                    libraries = libraries,
                    queuedTitle = queuedTitle,
                    onSearch = {
                        val q = searchQuery.trim()
                        if (q.isNotEmpty()) {
                            searching = true
                            searchError = null
                            scope.launch {
                                try {
                                    searchHits = withContext(Dispatchers.IO) { searchOpenKnowledge(q) }
                                    if (searchHits.isEmpty()) searchError = "No open-library results found for that search."
                                } catch (e: Exception) {
                                    searchError = "Search failed: ${e.message ?: "network unavailable"}"
                                } finally { searching = false }
                            }
                        }
                    },
                    onDownload = { hit ->
                        queuedTitle = hit.title
                        val request = OneTimeWorkRequestBuilder<LibraryDownloadWorker>()
                            .setInputData(Data.Builder().putInt(LibraryDownloadWorker.KEY_PAGE_ID, hit.pageId).putString(LibraryDownloadWorker.KEY_TITLE, hit.title).build())
                            .build()
                        WorkManager.getInstance(context).enqueue(request)
                        scope.launch { delay(1800); queuedTitle = null }
                    },
                    onDelete = { library ->
                        store.deleteLibrary(library.pageId)
                        libraries = store.listLibraries()
                    }
                )
                Tab.SETTINGS -> SettingsScreen(
                    padding = padding,
                    autoSpeak = autoSpeak,
                    onAutoSpeakChange = {
                        autoSpeak = it
                        prefs.edit().putBoolean("auto_speak", it).apply()
                    },
                    firebaseConfigured = FirebaseApp.getApps(context).isNotEmpty(),
                    libraryCount = libraries.size,
                    storageBytes = libraries.sumOf { it.text.toByteArray().size.toLong() }
                )
            }
        }
    }
}

@Composable
private fun AmbientSpace() {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(brush = Brush.radialGradient(listOf(Blue.copy(alpha = 0.18f), Color.Transparent)), radius = size.minDimension * 0.9f, center = Offset(size.width * 0.85f, size.height * 0.12f))
        drawCircle(brush = Brush.radialGradient(listOf(Violet.copy(alpha = 0.14f), Color.Transparent)), radius = size.minDimension * 0.78f, center = Offset(size.width * 0.08f, size.height * 0.76f))
    }
}

@Composable
private fun BrandHeader(subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        StarOrb()
        Spacer(Modifier.size(11.dp))
        Column(Modifier.weight(1f)) {
            Text("STAR AI", fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = TextDim, fontSize = 12.sp)
        }
        StatusChip("LOCAL-FIRST")
    }
}

@Composable
private fun StarOrb() {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Cyan.copy(alpha = 0.22f), Violet.copy(alpha = 0.22f)))),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) { drawCircle(Cyan.copy(alpha = 0.55f), style = Stroke(width = 1.5.dp.toPx())) }
        Text("★", color = Cyan, fontSize = 24.sp)
    }
}

@Composable
private fun StatusChip(text: String) {
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(text, color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface.copy(alpha = 0.82f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
}

@Composable
private fun HomeScreen(padding: PaddingValues, libraries: List<LocalLibrary>, messageCount: Int, openTab: (Tab) -> Unit) {
    val storageBytes = libraries.sumOf { it.text.toByteArray().size.toLong() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).statusBarsPadding(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { BrandHeader("Knowledge that stays with you") }
        item {
            GlassCard {
                Text("Ask. Download. Keep learning offline.", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black)
                Text("Star AI can build a local knowledge library on your device, then answer from it when the internet disappears.", color = TextDim, lineHeight = 21.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Button(onClick = { openTab(Tab.CHAT) }, colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color(0xFF00141A))) { Text("Ask STAR", fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = { openTab(Tab.LIBRARIES) }) { Text("Download libraries") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RealMetric("Libraries", libraries.size.toString(), Modifier.weight(1f))
                RealMetric("Chat messages", messageCount.toString(), Modifier.weight(1f))
                RealMetric("Local data", readableBytes(storageBytes), Modifier.weight(1f))
            }
        }
        item {
            GlassCard {
                Text("Offline intelligence", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("• Download open knowledge packs inside the app", color = TextDim)
                Text("• Ask questions with no internet connection", color = TextDim)
                Text("• Hear answers aloud with Android text-to-speech", color = TextDim)
                Text("• Dictate questions with speech recognition", color = TextDim)
                Text("• Downloads and indexing continue through WorkManager", color = TextDim)
            }
        }
        item {
            GlassCard {
                Text("No fake progress", color = Cyan, fontWeight = FontWeight.Bold)
                Text(if (libraries.isEmpty()) "You have not downloaded any libraries yet. Your dashboard stays empty until you create real data." else "Everything shown above comes from files and activity currently stored on this device.", color = TextDim, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun RealMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Surface2.copy(alpha = 0.8f)), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))) {
        Column(Modifier.padding(13.dp)) {
            Text(value, fontSize = 19.sp, fontWeight = FontWeight.Black, color = Cyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, fontSize = 10.sp, color = TextDim, maxLines = 2)
        }
    }
}

@Composable
private fun ChatScreen(
    padding: PaddingValues,
    input: String,
    onInputChange: (String) -> Unit,
    messages: List<ChatMessage>,
    libraries: List<LocalLibrary>,
    onMic: () -> Unit,
    onSend: () -> Unit,
    onSpeak: (String) -> Unit,
    openLibraries: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        BrandHeader(if (libraries.isEmpty()) "Offline library not ready" else "${libraries.size} local ${if (libraries.size == 1) "library" else "libraries"} ready")
        Spacer(Modifier.height(12.dp))
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                GlassCard {
                    Text("Start with your own knowledge", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text(if (libraries.isEmpty()) "Download a library first, then STAR can answer from it offline." else "Ask a question. STAR will search only the knowledge actually downloaded on this device.", color = TextDim, lineHeight = 21.sp)
                    if (libraries.isEmpty()) Button(onClick = openLibraries) { Text("Open Libraries") }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                items(messages) { message -> MessageBubble(message, onSpeak) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = input, onValueChange = onInputChange, placeholder = { Text("Ask STAR from your libraries…") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(22.dp), maxLines = 4)
            OutlinedButton(onClick = onMic, contentPadding = PaddingValues(horizontal = 13.dp, vertical = 14.dp)) { Text("🎙") }
            Button(onClick = onSend, enabled = input.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color(0xFF00141A)), contentPadding = PaddingValues(horizontal = 15.dp, vertical = 14.dp)) { Text("➜", fontWeight = FontWeight.Black) }
        }
        Text("Offline answers use downloaded sources; STAR will not pretend the live web is available.", color = TextDim, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onSpeak: (String) -> Unit) {
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.fillMaxWidth(if (user) 0.82f else 0.92f),
            colors = CardDefaults.cardColors(containerColor = if (user) Blue.copy(alpha = 0.72f) else Surface2.copy(alpha = 0.9f)),
            shape = RoundedCornerShape(20.dp),
            border = if (user) null else androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.09f))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(message.text, lineHeight = 21.sp)
                message.source?.let { Text("Source: $it", color = Cyan, fontSize = 10.sp) }
                if (!user) TextButton(onClick = { onSpeak(message.text) }, contentPadding = PaddingValues(0.dp)) { Text("🔊 Read aloud") }
            }
        }
    }
}

@Composable
private fun LibrariesScreen(
    padding: PaddingValues,
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    error: String?,
    hits: List<SearchHit>,
    libraries: List<LocalLibrary>,
    queuedTitle: String?,
    onSearch: () -> Unit,
    onDownload: (SearchHit) -> Unit,
    onDelete: (LocalLibrary) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).statusBarsPadding(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { BrandHeader("Build your offline knowledge") }
        item {
            GlassCard {
                Text("Download Libraries", fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text("Search openly licensed knowledge and save it directly inside Star AI.", color = TextDim)
                OutlinedTextField(value = query, onValueChange = onQueryChange, modifier = Modifier.fillMaxWidth(), placeholder = { Text("e.g. photosynthesis, Python, volcanoes") }, singleLine = true, shape = RoundedCornerShape(18.dp))
                Button(onClick = onSearch, enabled = query.isNotBlank() && !searching, colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color(0xFF00141A))) {
                    if (searching) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF00141A))
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (searching) "Searching…" else "Search open knowledge", fontWeight = FontWeight.Bold)
                }
                Text("Provider in this build: Wikipedia. Each downloaded article keeps its source link for attribution.", color = TextDim, fontSize = 10.sp)
            }
        }
        error?.let { item { Text(it, color = Color(0xFFFF8A8A), modifier = Modifier.padding(horizontal = 4.dp)) } }
        if (hits.isNotEmpty()) {
            item { SectionLabel("Search results") }
            items(hits, key = { it.pageId }) { hit ->
                val downloaded = libraries.any { it.pageId == hit.pageId }
                SearchResultCard(hit, downloaded, queuedTitle == hit.title, onDownload)
            }
        }
        item { SectionLabel("Downloaded on this device") }
        if (libraries.isEmpty()) {
            item { GlassCard { Text("No libraries yet", fontWeight = FontWeight.Bold); Text("Nothing is faked here. Download a topic and it will appear after the background worker saves and indexes it.", color = TextDim) } }
        } else {
            items(libraries, key = { it.pageId }) { lib -> LibraryCard(lib, onDelete) }
        }
    }
}

@Composable
private fun SearchResultCard(hit: SearchHit, downloaded: Boolean, queued: Boolean, onDownload: (SearchHit) -> Unit) {
    GlassCard {
        Text(hit.title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(cleanSnippet(hit.snippet), color = TextDim, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Button(onClick = { onDownload(hit) }, enabled = !downloaded && !queued, colors = ButtonDefaults.buttonColors(containerColor = if (downloaded) Surface2 else Cyan, contentColor = if (downloaded) Color.White else Color(0xFF00141A))) {
            Text(when { downloaded -> "Downloaded"; queued -> "Queued in background"; else -> "Download" })
        }
    }
}

@Composable
private fun LibraryCard(lib: LocalLibrary, onDelete: (LocalLibrary) -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(lib.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("${readableBytes(lib.text.toByteArray().size.toLong())} · stored locally", color = TextDim, fontSize = 11.sp)
                Text(lib.sourceUrl, color = Cyan, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { onDelete(lib) }) { Text("Remove", color = Color(0xFFFF8A8A)) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
}

@Composable
private fun SettingsScreen(padding: PaddingValues, autoSpeak: Boolean, onAutoSpeakChange: (Boolean) -> Unit, firebaseConfigured: Boolean, libraryCount: Int, storageBytes: Long) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).statusBarsPadding(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { BrandHeader("Real settings, real status") }
        item {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic voice output", fontWeight = FontWeight.Bold)
                        Text("Read STAR's offline answers aloud using Android TTS", color = TextDim, fontSize = 12.sp)
                    }
                    Switch(checked = autoSpeak, onCheckedChange = onAutoSpeakChange)
                }
            }
        }
        item { GlassCard { Text("Speech", fontWeight = FontWeight.Bold, fontSize = 17.sp); Text("Speech-to-text requests offline recognition when the device has an offline language pack. Availability depends on the installed Android speech service.", color = TextDim, lineHeight = 20.sp); Text("Text-to-speech uses the device's installed TTS engine and voices.", color = TextDim, lineHeight = 20.sp) } }
        item { GlassCard { Text("Background work", fontWeight = FontWeight.Bold, fontSize = 17.sp); Text("Library downloads and preparation are queued through Android WorkManager so supported jobs can continue after you leave this screen.", color = TextDim, lineHeight = 20.sp) } }
        item {
            GlassCard {
                Text("System status", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                SettingStatus("Firebase configuration", if (firebaseConfigured) "Configured" else "Not configured")
                SettingStatus("Downloaded libraries", libraryCount.toString())
                SettingStatus("Knowledge storage", readableBytes(storageBytes))
                SettingStatus("Live AI model", "Not configured")
            }
        }
        item { GlassCard { Text("Privacy", fontWeight = FontWeight.Bold, fontSize = 17.sp); Text("Downloaded library files and offline chat retrieval stay on this device in this build. Online library search is only used when you explicitly search for new material.", color = TextDim, lineHeight = 20.sp) } }
    }
}

@Composable
private fun SettingStatus(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextDim)
        Text(value, color = if (value == "Not configured") Color(0xFFFFB86B) else Cyan, fontWeight = FontWeight.Bold)
    }
}

private class LibraryStore(private val context: Context) {
    private val dir get() = java.io.File(context.filesDir, "star_libraries").apply { mkdirs() }

    fun listLibraries(): List<LocalLibrary> = dir.listFiles()
        ?.filter { it.extension == "json" }
        ?.mapNotNull { file ->
            runCatching {
                val json = JSONObject(file.readText())
                LocalLibrary(json.getInt("pageId"), json.getString("title"), json.getString("sourceUrl"), json.getString("text"), json.optLong("downloadedAt", 0L))
            }.getOrNull()
        }
        ?.sortedByDescending { it.downloadedAt }
        ?: emptyList()

    fun deleteLibrary(pageId: Int) { java.io.File(dir, "$pageId.json").delete() }

    fun answerFromLibraries(question: String): OfflineAnswer? {
        val libraries = listLibraries()
        if (libraries.isEmpty()) return null
        val stopWords = setOf("the", "a", "an", "is", "are", "was", "were", "what", "who", "why", "how", "when", "where", "and", "or", "to", "of", "in", "on", "for", "with", "it", "this", "that")
        val queryTokens = question.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in stopWords }.toSet()
        if (queryTokens.isEmpty()) return null
        data class Candidate(val score: Int, val sentence: String, val library: LocalLibrary)
        val candidates = mutableListOf<Candidate>()
        libraries.forEach { library ->
            val titleTokens = library.title.lowercase().split(Regex("[^a-z0-9]+")) .toSet()
            library.text.split(Regex("(?<=[.!?])\\s+"))
                .asSequence().map { it.trim() }.filter { it.length in 45..520 }.forEach { sentence ->
                    val words = sentence.lowercase().split(Regex("[^a-z0-9]+")) .toSet()
                    val overlap = queryTokens.count { it in words }
                    val titleBoost = queryTokens.count { it in titleTokens }
                    val score = overlap * 4 + titleBoost * 2
                    if (score > 0) candidates.add(Candidate(score, sentence, library))
                }
        }
        val best = candidates.sortedByDescending { it.score }.take(3)
        if (best.isEmpty()) return null
        val main = best.first().library
        val selected = best.filter { it.library.pageId == main.pageId }.take(3)
        return OfflineAnswer(selected.joinToString(" ") { it.sentence }, main.title)
    }
}

private suspend fun searchOpenKnowledge(query: String): List<SearchHit> {
    val encoded = URLEncoder.encode(query, "UTF-8")
    val body = httpGet("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=1&format=json&srlimit=10")
    val array = JSONObject(body).getJSONObject("query").getJSONArray("search")
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            add(SearchHit(item.getInt("pageid"), item.getString("title"), item.optString("snippet")))
        }
    }
}

internal fun downloadLibraryToDisk(context: Context, pageId: Int, title: String): Boolean {
    val encodedTitle = URLEncoder.encode(title, "UTF-8")
    val body = httpGetBlocking("https://en.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&redirects=1&titles=$encodedTitle&format=json")
    val pages = JSONObject(body).getJSONObject("query").getJSONObject("pages")
    val key = pages.keys().asSequence().firstOrNull() ?: return false
    val page = pages.getJSONObject(key)
    val text = page.optString("extract").trim()
    if (text.length < 80) return false
    val actualPageId = page.optInt("pageid", pageId)
    val actualTitle = page.optString("title", title)
    val sourceUrl = "https://en.wikipedia.org/wiki/${URLEncoder.encode(actualTitle.replace(' ', '_'), "UTF-8")}"
    val json = JSONObject().put("pageId", actualPageId).put("title", actualTitle).put("sourceUrl", sourceUrl).put("text", text).put("downloadedAt", System.currentTimeMillis())
    val dir = java.io.File(context.filesDir, "star_libraries").apply { mkdirs() }
    java.io.File(dir, "$actualPageId.json").writeText(json.toString())
    return true
}

private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) { httpGetBlocking(url) }

private fun httpGetBlocking(url: String): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 12000
        readTimeout = 20000
        setRequestProperty("User-Agent", "StarAI/1.0 (Cyber Pulse Android)")
        setRequestProperty("Accept", "application/json")
    }
    return try {
        val code = connection.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally { connection.disconnect() }
}

private fun launchSpeechRecognizer(context: Context, launch: (Intent) -> Unit) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Star AI")
    }
    if (intent.resolveActivity(context.packageManager) != null) launch(intent)
}

private fun readableBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

private fun cleanSnippet(text: String): String = text.replace(Regex("<[^>]+>"), "").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
