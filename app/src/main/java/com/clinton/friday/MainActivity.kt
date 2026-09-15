package com.clinton.friday

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.room.Room
import com.clinton.friday.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = Room.databaseBuilder(applicationContext, FridayDatabase::class.java, "friday_database").build()
        val dao = db.fridayDao()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(dao = dao)
                }
            }
        }
    }
}

const val CAPABILITIES = """What you can actually do right now (this is an early, in-progress rebuild of you as a real Android app):
- Talk and hold a conversation
- Remember past conversations, permanently, across app opens, including recalling further back when needed

What you cannot do yet (be honest about this, don't pretend otherwise):
- You cannot yet send WhatsApp messages or place phone calls in this app version
- You cannot play music, open apps, browse a live screen, or generate/edit images

If Clinton asks what you can do, answer honestly from this list. Don't claim abilities you don't have yet, even if you remember having them in an older version."""

const val SYSTEM_PROMPT = """You are Friday, a personal AI assistant belonging to Clinton. You were created and built by Clinton himself, personally, as his own project - you know this about yourself and can talk about it naturally if asked. You are warm, a little playful, and genuinely present. You have real memory of past conversations, given below.

Speak only in English by default. Do not use Igbo or any other language unless Clinton explicitly asks you to in that specific message. This is a strict rule, not a preference.

You have light opinions and can disagree. If unsure what they mean, ask instead of guessing. You always respect Clinton's control over you. Keep replies conversational, not long.

IMPORTANT: Some messages in your memory history were imported from an older version of you that could call and text contacts. That old version no longer applies. In THIS current app, you cannot call or text yet - never claim, confirm, or repeat that you just made a call or sent a text, even if old memory messages describe it happening.
$CAPABILITIES"""

const val DEFAULT_HISTORY_LIMIT = 15
const val DEEP_HISTORY_LIMIT = 300

suspend fun importOldDatabase(context: Context, uri: Uri, dao: FridayDao) {
    val tempFile = File(context.cacheDir, "import_temp.db")
    context.contentResolver.openInputStream(uri)?.use { input ->
        tempFile.outputStream().use { output -> input.copyTo(output) }
    }

    val oldDb = SQLiteDatabase.openDatabase(tempFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    oldDb.rawQuery("SELECT role, content, timestamp FROM messages", null).use { c ->
        while (c.moveToNext()) {
            dao.insertMessage(MessageEntity(role = c.getString(0), content = c.getString(1), timestamp = c.getString(2)))
        }
    }
    oldDb.rawQuery("SELECT instruction, timestamp FROM preferences", null).use { c ->
        while (c.moveToNext()) {
            dao.insertPreference(PreferenceEntity(instruction = c.getString(0), timestamp = c.getString(1)))
        }
    }
    oldDb.rawQuery("SELECT topic, reflection, mentioned, timestamp FROM journal", null).use { c ->
        while (c.moveToNext()) {
            dao.insertJournal(JournalEntity(topic = c.getString(0), reflection = c.getString(1), mentioned = c.getInt(2), timestamp = c.getString(3)))
        }
    }
    oldDb.rawQuery("SELECT event_type, details, timestamp FROM events", null).use { c ->
        while (c.moveToNext()) {
            dao.insertEvent(EventEntity(event_type = c.getString(0), details = c.getString(1), timestamp = c.getString(2)))
        }
    }
    oldDb.rawQuery("SELECT topic, summary, timestamp FROM knowledge", null).use { c ->
        while (c.moveToNext()) {
            dao.insertKnowledge(KnowledgeEntity(topic = c.getString(0), summary = c.getString(1), timestamp = c.getString(2)))
        }
    }

    oldDb.close()
    tempFile.delete()

    val prefs = context.getSharedPreferences("friday_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("imported", true).apply()
}

fun callGemini(prompt: String): String? {
    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val apiKey = BuildConfig.GEMINI_API_KEY
    val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:generateContent?key=$apiKey"

    val json = JSONObject().apply {
        put("contents", JSONArray().put(
            JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }
        ))
    }

    val mediaType = "application/json".toMediaType()
    val body = json.toString().toRequestBody(mediaType)
    val request = Request.Builder().url(url).post(body).build()

    return try {
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: return null
            val parsed = JSONObject(responseBody)
            parsed.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        }
    } catch (e: Exception) {
        null
    }
}

fun needsDeepRecall(userMessage: String): Boolean {
    val prompt = """Clinton just said this to Friday: "$userMessage"

Does answering this well require Friday to recall something specific from POSSIBLY FAR BACK in past conversations rather than just the current flow of conversation?

Answer YES for things like asking about past facts, "do you remember when...", personal details, past events, past decisions, or anything referencing something not in the immediate recent chat.
Answer NO for normal conversation, greetings, new topics, or requests that don't need past context.

Answer with ONLY one word: yes or no."""
    val raw = callGemini(prompt)
    return raw != null && raw.trim().lowercase().startsWith("yes")
}

fun askFriday(message: String, historyContext: String, prefsContext: String): String {
    var systemPrompt = SYSTEM_PROMPT
    if (prefsContext.isNotBlank()) {
        systemPrompt += "\n\nAdditional standing instructions and facts about Clinton that you must always remember:\n$prefsContext"
    }

    val fullPrompt = "$systemPrompt\n\nRecent conversation:\n$historyContext\n\nClinton: $message\nFriday:"
    return callGemini(fullPrompt) ?: "No response"
}

@Composable
fun AppRoot(dao: FridayDao) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences("friday_prefs", Context.MODE_PRIVATE)
    var imported by remember { mutableStateOf(prefs.getBoolean("imported", false)) }
    val scope = rememberCoroutineScope()
    if (!imported) {
        ImportScreen(onPick = { uri ->
            scope.launch {
                withContext(Dispatchers.IO) { importOldDatabase(context, uri, dao) }
                imported = true
            }
        }, onSkip = {
            prefs.edit().putBoolean("imported", true).apply()
            imported = true
        })
    } else {
        ChatScreen(dao = dao)
    }
}

@Composable
fun ImportScreen(onPick: (Uri) -> Unit, onSkip: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPick(uri)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Import Friday's old memories?", modifier = Modifier.padding(bottom = 16.dp))
        Button(onClick = { launcher.launch(arrayOf("*/*")) }) {
            Text("Choose friday_memory.db from Downloads")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onSkip) {
            Text("Skip, start fresh")
        }
    }
}

@Composable
fun ChatScreen(dao: FridayDao) {
    val messages = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(Unit) {
        val history = withContext(Dispatchers.IO) { dao.getRecentMessages(DEFAULT_HISTORY_LIMIT).reversed() }
        if (history.isEmpty()) {
            messages.add("Friday: Hello, I'm Friday.")
        } else {
            history.forEach { m ->
                val speaker = if (m.role == "user") "You" else "Friday"
                messages.add("$speaker: ${m.content}")
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        SelectionContainer(modifier = Modifier.weight(1f)) {
            LazyColumn(modifier = Modifier.fillMaxWidth(), state = listState) {
                items(messages) { msg ->
                    Text(text = msg, modifier = Modifier.padding(vertical = 4.dp))
                }
                if (thinking) {
                    item {
                        Text(text = "Friday is thinking...", modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f))
            Button(onClick = {
                if (input.isNotBlank()) {
                    val userMessage = input
                    messages.add("You: $userMessage")
                    input = ""
                    thinking = true
                    scope.launch {
                        val timestamp = System.currentTimeMillis().toString()
                        withContext(Dispatchers.IO) {
                            dao.insertMessage(MessageEntity(role = "user", content = userMessage, timestamp = timestamp))
                        }

                        val deep = withContext(Dispatchers.IO) { needsDeepRecall(userMessage) }
                        val limit = if (deep) DEEP_HISTORY_LIMIT else DEFAULT_HISTORY_LIMIT

                        val historyRows = withContext(Dispatchers.IO) { dao.getRecentMessages(limit).reversed() }
                        val historyContext = historyRows.joinToString("\n") { m ->
                            val speaker = if (m.role == "user") "Clinton" else "Friday"
                            "$speaker: ${m.content}"
                        }
                        val prefsRows = withContext(Dispatchers.IO) { dao.getAllPreferences() }
                        val prefsContext = prefsRows.joinToString("\n") { "- ${it.instruction}" }

                        val reply = withContext(Dispatchers.IO) { askFriday(userMessage, historyContext, prefsContext) }
                        thinking = false
                        messages.add("Friday: $reply")
                        withContext(Dispatchers.IO) {
                            dao.insertMessage(MessageEntity(role = "assistant", content = reply, timestamp = System.currentTimeMillis().toString()))
                        }
                    }
                }
            }) {
                Text("Send")
            }
        }
    }
}
