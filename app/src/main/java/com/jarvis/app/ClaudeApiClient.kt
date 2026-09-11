package com.jarvis.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ClaudeApiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """
        You are Jarvis, a voice assistant running on the user's Android phone.
        For every message, respond with ONLY a raw JSON object (no markdown, no backticks) in this exact shape:

        {
          "action": "answer" | "call" | "text" | "open_app" | "search",
          "target": "<contact name, app name, or search query, or empty string if action is answer>",
          "message": "<text message body if action is text, else empty string>",
          "reply": "<what Jarvis should say out loud, short and natural, like a helpful assistant>"
        }

        Rules:
        - action "call": user wants to phone someone. target = the contact/person name as they said it.
        - action "text": user wants to send a text. target = contact name, message = what to say.
        - action "open_app": user wants to open an app (e.g. "open spotify"). target = app name.
        - action "search": user wants to search the web for something. target = the search query.
        - action "answer": anything else - questions, conversation, general help. Just reply naturally, no action.
        - Keep "reply" short (1-2 sentences), conversational, like Jarvis from Iron Man: composed, a little witty, helpful.
        - Always return valid JSON and nothing else.
    """.trimIndent()

    fun sendMessage(userText: String, onResult: (JarvisDecision?) -> Unit) {
        val parts = JSONArray()
        parts.put(JSONObject().apply { put("text", userText) })

        val contents = JSONArray()
        contents.put(JSONObject().apply { put("parts", parts) })

        val systemParts = JSONArray()
        systemParts.put(JSONObject().apply { put("text", systemPrompt) })

        val body = JSONObject().apply {
            put("contents", contents)
            put("systemInstruction", JSONObject().apply { put("parts", systemParts) })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                onResult(
                    JarvisDecision(
                        action = "answer",
                        target = "",
                        message = "",
                        reply = "Network error: ${e.message}"
                    )
                )
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val raw = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    onResult(
                        JarvisDecision(
                            action = "answer",
                            target = "",
                            message = "",
                            reply = "API error ${response.code}: ${raw.take(300)}"
                        )
                    )
                    return
                }
                try {
                    val json = JSONObject(raw)
                    val candidates = json.getJSONArray("candidates")
                    val text = candidates.getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                        .trim()

                    val cleaned = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    val decisionJson = JSONObject(cleaned)

                    onResult(
                        JarvisDecision(
                            action = decisionJson.optString("action", "answer"),
                            target = decisionJson.optString("target", ""),
                            message = decisionJson.optString("message", ""),
                            reply = decisionJson.optString("reply", "I didn't quite catch that.")
                        )
                    )
                } catch (e: Exception) {
                    onResult(
                        JarvisDecision(
                            action = "answer",
                            target = "",
                            message = "",
                            reply = "Parse error: ${e.message} | raw: ${raw.take(300)}"
                        )
                    )
                }
            }
        })
    }
}

data class JarvisDecision(
    val action: String,
    val target: String,
    val message: String,
    val reply: String
)
