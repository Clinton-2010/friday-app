package com.clinton.friday

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatScreen()
                }
            }
        }
    }
}

const val CAPABILITIES = """What you can actually do right now (this is an early, in-progress rebuild of you as a real Android app):
- Talk and hold a conversation

What you cannot do yet (be honest about this, don't pretend otherwise):
- You do NOT yet remember past conversations in this app (memory hasn't been added yet)
- You cannot yet send WhatsApp messages or place phone calls in this app version
- You cannot play music, open apps, browse a live screen, or generate/edit images

If Clinton asks what you can do, answer honestly from this list. Don't claim abilities you don't have yet, even if you remember having them in an older version."""

const val SYSTEM_PROMPT = """You are Friday, a personal AI assistant belonging to Clinton. You were created and built by Clinton himself, personally, as his own project - you know this about yourself and can talk about it naturally if asked. You are warm, a little playful, and genuinely present.

Speak only in English by default. Do not use Igbo or any other language unless Clinton explicitly asks you to in that specific message. This is a strict rule, not a preference.

You have light opinions and can disagree. If unsure what they mean, ask instead of guessing. You always respect Clinton's control over you. Keep replies conversational, not long.

$CAPABILITIES"""

fun askFriday(message: String): String {
    val client = OkHttpClient()
    val apiKey = BuildConfig.GEMINI_API_KEY
    val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:generateContent?key=$apiKey"

    val fullPrompt = "$SYSTEM_PROMPT\n\nClinton: $message\nFriday:"

    val json = JSONObject().apply {
        put("contents", JSONArray().put(
            JSONObject().apply {
                put("parts", JSONArray().put(
                    JSONObject().put("text", fullPrompt)
                ))
            }
        ))
    }

    val mediaType = "application/json".toMediaType()
    val body = json.toString().toRequestBody(mediaType)
    val request = Request.Builder().url(url).post(body).build()

    return try {
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: return "No response"
            val parsed = JSONObject(responseBody)
            parsed.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        }
    } catch (e: Exception) {
        "Error reaching Friday: ${e.message}"
    }
}

@Composable
fun ChatScreen() {
    val messages = remember { mutableStateListOf("Friday: Hello, I'm Friday.") }
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        SelectionContainer(modifier = Modifier.weight(1f)) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(messages) { msg ->
                    Text(text = msg, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                if (input.isNotBlank()) {
                    val userMessage = input
                    messages.add("You: $userMessage")
                    input = ""
                    scope.launch {
                        val reply = withContext(Dispatchers.IO) {
                            askFriday(userMessage)
                        }
                        messages.add("Friday: $reply")
                    }
                }
            }) {
                Text("Send")
            }
        }
    }
}
