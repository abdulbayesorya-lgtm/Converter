package com.example.speechconverter

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/* ---------- Colors ---------- */

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E7FF),
    onPrimaryContainer = Color(0xFF1E1B6B),
    background = Color(0xFFF5F6FB),
    onBackground = Color(0xFF1B1F2A),
    surface = Color.White,
    onSurface = Color(0xFF1B1F2A),
    surfaceVariant = Color(0xFFEDEEF6),
    onSurfaceVariant = Color(0xFF666C7E),
    outline = Color(0xFFD5D8E6)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8B85FF),
    onPrimary = Color(0xFF10102B),
    primaryContainer = Color(0xFF2B2E52),
    onPrimaryContainer = Color(0xFFDCDDFF),
    background = Color(0xFF0F111A),
    onBackground = Color(0xFFECEEF6),
    surface = Color(0xFF1A1D2A),
    onSurface = Color(0xFFECEEF6),
    surfaceVariant = Color(0xFF242839),
    onSurfaceVariant = Color(0xFF9AA1B5),
    outline = Color(0xFF353A50)
)

/* ---------- TTS controller (works offline with installed voices) ---------- */

class TtsController(context: Context) {
    private var tts: TextToSpeech? = null
    var ready by mutableStateOf(false)
    var speaking by mutableStateOf(false)
    var voices by mutableStateOf<List<Voice>>(emptyList())

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { speaking = true }
                    override fun onDone(utteranceId: String?) { if (utteranceId == "last") speaking = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { speaking = false }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { speaking = false }
                })
                refreshVoices()
            }
        }
    }

    fun refreshVoices() {
        voices = try {
            tts?.voices?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun voicesFor(lang: String, offlineOnly: Boolean): List<Voice> {
        val codes = if (lang == "fa") setOf("fa", "prs", "per", "fas") else setOf(lang)
        return voices
            .filter { it.locale.language in codes }
            .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .filter { !offlineOnly || !it.isNetworkConnectionRequired }
            .sortedBy { it.name }
    }

    fun speak(text: String, voice: Voice?, lang: String, rate: Float, pitch: Float) {
        val engine = tts ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return
        engine.stop()
        if (voice != null) engine.voice = voice
        else engine.language = if (lang == "fa") Locale("fa", "IR") else Locale.US
        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
        val parts = clean.split(Regex("(?<=[.!?؟۔\\n])\\s*")).filter { it.isNotBlank() }
        parts.forEachIndexed { i, part ->
            val id = if (i == parts.lastIndex) "last" else "p$i"
            engine.speak(part.take(3500), TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    fun stop() {
        tts?.stop()
        speaking = false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}

/* ---------- Activity ---------- */

class MainActivity : ComponentActivity() {
    private lateinit var tts: TtsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tts = TtsController(this)
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        setContent { App(tts, prefs) }
    }

    override fun onResume() {
        super.onResume()
        if (tts.ready) tts.refreshVoices()
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }
}

/* ---------- UI ---------- */

private val persianChars = Regex("[\\u0600-\\u06FF]")

@Composable
fun App(tts: TtsController, prefs: SharedPreferences) {
    var mode by remember { mutableIntStateOf(prefs.getInt("theme", 0)) } // 0 system, 1 light, 2 dark
    val dark = when (mode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Screen(tts, mode) {
                mode = it
                prefs.edit().putInt("theme", it).apply()
            }
        }
    }
}

@Composable
fun Screen(tts: TtsController, mode: Int, onMode: (Int) -> Unit) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var langPref by rememberSaveable { mutableStateOf("auto") }
    var offlineOnly by rememberSaveable { mutableStateOf(true) }
    var rate by rememberSaveable { mutableFloatStateOf(1f) }
    var pitch by rememberSaveable { mutableFloatStateOf(1f) }
    var voiceName by rememberSaveable { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val lang = if (langPref == "auto") {
        if (persianChars.containsMatchIn(text)) "fa" else "en"
    } else langPref
    val candidates = remember(tts.voices, lang, offlineOnly) { tts.voicesFor(lang, offlineOnly) }
    val selected = candidates.firstOrNull { it.name == voiceName } ?: candidates.firstOrNull()

    val cs = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(24.dp)

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .verticalScroll(rememberScrollState())
    ) {
        /* Header */
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF8B5CF6))))
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🔊 تبدیل متن به صدا", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("فارسی و English • کاملاً آفلاین", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                }
                FilledTonalIconButton(
                    onClick = { onMode((mode + 1) % 3) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White.copy(alpha = 0.2f))
                ) {
                    Text(when (mode) { 1 -> "☀️"; 2 -> "🌙"; else -> "🌗" }, fontSize = 20.sp)
                }
            }
        }

        Column(
            Modifier
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            /* Text input */
            Surface(shape = cardShape, color = cs.surface, tonalElevation = 2.dp, shadowElevation = 2.dp) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        maxLines = 12,
                        shape = RoundedCornerShape(18.dp),
                        textStyle = TextStyle(fontSize = 17.sp, textDirection = TextDirection.Content),
                        placeholder = { Text("متن فارسی یا English را اینجا بنویسید…") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = cs.primary,
                            unfocusedBorderColor = cs.outline,
                            unfocusedContainerColor = cs.background,
                            focusedContainerColor = cs.background
                        )
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${text.length} حرف", color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { text = "" }, enabled = text.isNotEmpty()) { Text("پاک کردن") }
                    }
                }
            }

            /* Settings */
            Surface(shape = cardShape, color = cs.surface, tonalElevation = 2.dp, shadowElevation = 2.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("زبان", color = cs.onSurfaceVariant, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("auto" to "خودکار", "fa" to "فارسی", "en" to "English").forEach { (code, label) ->
                            FilterChip(
                                selected = langPref == code,
                                onClick = { langPref = code; voiceName = null },
                                label = { Text(label) }
                            )
                        }
                    }

                    Text("صدا", color = cs.onSurfaceVariant, fontSize = 13.sp)
                    Box {
                        OutlinedButton(
                            onClick = { menuOpen = true },
                            enabled = candidates.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                selected?.let { "${it.locale.displayName} – ${it.name}" } ?: "صدایی پیدا نشد",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text("▾")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            candidates.forEach { v ->
                                DropdownMenuItem(
                                    text = { Text("${v.locale.displayName} – ${v.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = { voiceName = v.name; menuOpen = false }
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("فقط صداهای آفلاین", modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Switch(checked = offlineOnly, onCheckedChange = { offlineOnly = it; voiceName = null })
                    }

                    Text("سرعت: ${"%.1f".format(rate)}", color = cs.onSurfaceVariant, fontSize = 13.sp)
                    Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.5f..2f)

                    Text("زیر و بمی: ${"%.1f".format(pitch)}", color = cs.onSurfaceVariant, fontSize = 13.sp)
                    Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.5f..2f)
                }
            }

            /* No voice warning */
            if (tts.ready && candidates.isEmpty()) {
                Surface(shape = cardShape, color = cs.primaryContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (lang == "fa") "صدای فارسی آفلاین روی این گوشی نصب نیست."
                            else "صدای انگلیسی آفلاین روی این گوشی نصب نیست.",
                            color = cs.onPrimaryContainer, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "از تنظیمات «تبدیل متن به گفتار» موتور یا بستهٔ صدا را نصب کنید.",
                            color = cs.onPrimaryContainer, fontSize = 13.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                runCatching {
                                    ctx.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }) { Text("نصب صدا") }
                            OutlinedButton(onClick = {
                                runCatching {
                                    ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }) { Text("تنظیمات TTS") }
                        }
                    }
                }
            }

            /* Play / Stop */
            Button(
                onClick = {
                    if (tts.speaking) tts.stop()
                    else tts.speak(text, selected, lang, rate, pitch)
                },
                enabled = tts.ready && (tts.speaking || text.isNotBlank()),
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(if (tts.speaking) "⏹  توقف" else "▶  پخش", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
