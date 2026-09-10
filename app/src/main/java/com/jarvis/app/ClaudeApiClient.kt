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
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userText)
        })

        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-5")
            put("max_tokens", 300)
            put("system", systemPrompt)
            put("messages", messages)
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                onResult(null)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val raw = response.body?.string() ?: return onResult(null)
                    val json = JSONObject(raw)
                    val contentArray = json.getJSONArray("content")
                    val text = contentArray.getJSONObject(0).getString("text").trim()

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
                    onResult(null)
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
