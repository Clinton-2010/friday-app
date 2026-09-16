package com.clinton.friday

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
import java.net.URLEncoder
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
- Send WhatsApp messages to Clinton's real phone contacts (Clinton still has to tap Send once, that last step isn't automated yet)

What you cannot do yet (be honest about this, don't pretend otherwise):
- You cannot yet place phone calls in this app version
- You cannot play music, open apps, browse a live screen, or generate/edit images
- You cannot research topics or browse the internet yet

If Clinton asks what you can do, answer honestly from this list. Don't claim abilities you don't have yet, even if you remember having them in an older version."""

const val SYSTEM_PROMPT = """You are Friday, a personal AI assistant belonging to Clinton. You were created and built by Clinton himself, personally, as his own project - you know this about yourself and can talk about it naturally if asked. You are warm, a little playful, and genuinely present. You have real memory of past conversations, given below.

Speak only in English by default. Do not use Igbo or any other language unless Clinton explicitly asks you to in that specific message. This is a strict rule, not a preference.

You have light opinions and can disagree. If unsure what they mean, ask instead of guessing. You always respect Clinton's control over you. Keep replies conversational, not long.

IMPORTANT: Some messages in your memory history were imported from an older version of you. That old version could do things (like phone calls) this current app version cannot yet. Only claim abilities listed in your capabilities below - never claim or confirm an action from your capability list's "cannot do" section, even if old memory messages describe it happening.

CRITICAL: Sending a WhatsApp message or making a call only actually happens through your app's own confirmed action u2014 never through your own conversational reply. If Clinton asks you to text or call someone, do NOT say "I sent it" or "message fired off" yourself in a normal reply. Only the app's built-in confirmation counts as a real sent message.
$CAPABILITIES"""

const val DEFAULT_HISTORY_LIMIT = 15
const val DEEP_HISTORY_LIMIT = 100000

data class Contact(val name: String, val number: String)

fun getAllContacts(context: Context): List<Contact> {
    val contacts = mutableListOf<Contact>()
    val resolver = context.contentResolver
    val cursor = resolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        null, null, null
    )
    cursor?.use {
        val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        while (it.moveToNext()) {
            val name = it.getString(nameIdx) ?: continue
            val number = it.getString(numberIdx) ?: continue
            contacts.add(Contact(name, number))
        }
    }
    return contacts
}

fun levenshtein(a: String, b: String): Int {
    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) dp[i][0] = i
    for (j in 0..b.length) dp[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
            else 1 + minOf(dp[i - 1][j - 1], dp[i - 1][j], dp[i][j - 1])
        }
    }
    return dp[a.length][b.length]
}

fun similarity(a: String, b: String): Double {
    val s1 = a.lowercase().trim()
    val s2 = b.lowercase().trim()
    if (s1 == s2) return 1.0
    if (s2.contains(s1) || s1.contains(s2)) return 0.9
    val longer = if (s1.length > s2.length) s1 else s2
    val shorter = if (s1.length > s2.length) s2 else s1
    if (longer.isEmpty()) return 1.0
    val dist = levenshtein(longer, shorter)
    return (longer.length - dist) / longer.length.toDouble()
}

fun findMatchingContacts(query: String, contacts: List<Contact>): List<Contact> {
    val scored = contacts.map { it to similarity(query, it.name) }
        .filter { it.second > 0.35 }
        .sortedByDescending { it.second }
    if (scored.isEmpty()) return emptyList()
    if (scored.size == 1) return listOf(scored[0].first)
    val topScore = scored[0].second
    val secondScore = scored.getOrNull(1)?.second ?: 0.0
    return if (topScore - secondScore >= 0.15) {
        listOf(scored[0].first)
    } else {
        scored.filter { topScore - it.second < 0.2 }.take(5).map { it.first }
    }
}

fun openWhatsApp(context: Context, number: String, message: String) {
    val digits = number.filter { it.isDigit() || it == '+' }
    val encodedMessage = URLEncoder.encode(message, "UTF-8")
    val url = "https://wa.me/$digits?text=$encodedMessage"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

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

data class TextCommandResult(val isTextCommand: Boolean, val name: String?, val message: String?)

fun detectTextCommand(userMessage: String): TextCommandResult {
    val prompt = """Clinton just said this to Friday: "$userMessage"

Is Clinton asking Friday to send a WhatsApp message/text to someone? This includes casual phrasing like "text X that...", "tell X...", "let X know...", "message X saying...".

If YES, respond with ONLY this exact format, nothing else:
YES|contact_name|message_to_send

Where message_to_send is rewritten in natural first-person, as if Clinton is speaking directly to that contact (not describing it in third person).

If NO, respond with ONLY the word: NO"""
    val raw = callGemini(prompt)?.trim() ?: return TextCommandResult(false, null, null)
    if (!raw.startsWith("YES", ignoreCase = true)) return TextCommandResult(false, null, null)
    val parts = raw.split("|", limit = 3)
    if (parts.size < 3) return TextCommandResult(false, null, null)
    return TextCommandResult(true, parts[1].trim(), parts[2].trim())
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
    val context = LocalContext.current
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
    val context = LocalContext.current
    val messages = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var pendingContacts by remember { mutableStateOf<List<Contact>?>(null) }
    var pendingMessage by remember { mutableStateOf<String?>(null) }
    var hasContactsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasContactsPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasContactsPermission) {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

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
                    input = ""
                    messages.add("You: $userMessage")

                    scope.launch {
                        withContext(Dispatchers.IO) {
                            dao.insertMessage(MessageEntity(role = "user", content = userMessage, timestamp = System.currentTimeMillis().toString()))
                        }

                        val currentPending = pendingContacts
                        val currentPendingMsg = pendingMessage

                        if (currentPending != null && currentPendingMsg != null) {
                            val choice = userMessage.trim().lowercase()
                            val ordinalMap = mapOf(
                                "first" to 1, "1st" to 1,
                                "second" to 2, "2nd" to 2,
                                "third" to 3, "3rd" to 3,
                                "fourth" to 4, "4th" to 4,
                                "fifth" to 5, "5th" to 5
                            )
                            val index = choice.toIntOrNull()
                                ?: ordinalMap.entries.firstOrNull { choice.contains(it.key) }?.value
                                ?: if (choice.contains("last")) currentPending.size else null

                            val chosen = if (index != null && index in 1..currentPending.size) {
                                currentPending[index - 1]
                            } else {
                                currentPending.firstOrNull { it.name.lowercase().contains(choice) }
                            }

                            if (chosen != null) {
                                pendingContacts = null
                                pendingMessage = null
                                if (!hasContactsPermission) {
                                    val reply = "I need contacts permission to do that, boss. Try allowing it in your phone settings."
                                    messages.add("Friday: $reply")
                                    withContext(Dispatchers.IO) {
                                        dao.insertMessage(MessageEntity(role = "assistant", content = reply, timestamp = System.currentTimeMillis().toString()))
                                    }
                                } else {
                                    openWhatsApp(context, chosen.number, currentPendingMsg)
                                    val reply = "Opened WhatsApp for " + chosen.name + " with your message ready, just tap send."
                                    messages.add("Friday: $reply")
                                    withContext(Dispatchers.IO) {
                                        dao.insertMessage(MessageEntity(role = "assistant", content = reply, timestamp = System.currentTimeMillis().toString()))
                                    }
                                }
                            } else {
                                val names = currentPending.mapIndexed { i, c -> (i + 1).toString() + ". " + c.name }.joinToString("\n")
                                val reply = "Didn't catch which one, boss. Reply with a number:\n" + names
                                messages.add("Friday: $reply")
                                withContext(Dispatchers.IO) {
                                    dao.insertMessage(MessageEntity(role = "assistant", content = reply, timestamp = System.currentTimeMillis().toString()))
                                }
                            }
                            return@launch
                        }

                        thinking = true
                        val textCommand = withContext(Dispatchers.IO) { detectTextCommand(userMessage) }
                        val isTextCmd = textCommand.isTextCommand
                        val cmdName = textCommand.name
                        val cmdMessage = textCommand.message

                        if (isTextCmd && cmdName != null && cmdMessage != null) {
                            if (!hasContactsPermission) {
                                thinking = false
                                val reply = "I'd need contacts permission to text anyone, boss. Try allowing it in your phone settings and ask again."
                                messages.add("Friday: $reply")
                                withContext(Dispatchers.IO) {
                                    dao.insertMessage(MessageEntity(role = "assistant", content = reply, timestamp = System.currentTimeMillis().toString()))
                                }
                            } else {
                                val contacts = withContext(Dispatchers.IO) { getAllContacts(context) }
                                val matches = withContext(Dispatchers.IO) { findMatchingContacts(cmdName, contacts) }
                                thinking = false

                                if (matches.isEmpty()) {
                                    val reply = "I couldn't find anyone named " + cmdName + " in your contacts, boss."
                                    messages.add("Friday: $reply")
                                    withContext(Dispatchers.IO) {
                                        dao.insertMessage(MessageEntity(role = "assistant", content = reply, timestamp = System.currentTimeMillis().toString()))
                                    }
                                } else if (matches.size == 1) {
                                    openWhatsApp(context, matches[0].number, cmdMessage)
                                    val reply = "Opened WhatsApp for " + matches[0].name + " with your message ready, just tap send."
                                    messages.add("Friday: $reply")
                                    withContext(Dispatchers.IO) {
                                        dao.insertMessage(MessageEntity(role = "assistant", content = reply, timestamp = System.currentTimeMillis().toString()))
                                    }
                                } else {
                                    pendingContacts = matches
                                    pendingMessage = cmdMessage
                                    val names = matches.mapIndexed { i, c -> (i + 1).toString() + ". " + c.name }.joinToString("\n")
                                    val reply = "I found a few contacts that could match " + cmdName + ":\n" + names + "\nWhich one, boss?"
                                    messages.add("Friday: $reply")
                                    withContext(Dispatchers.IO) {
                                        dao.insertMessage(MessageEntity(role = "assistant", content = reply, timestamp = System.currentTimeMillis().toString()))
                                    }
                                }
                            }
                        } else {
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
                }
            }) {
                Text("Send")
            }
        }
    }
}
