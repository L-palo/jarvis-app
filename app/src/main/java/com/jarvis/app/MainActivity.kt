package com.jarvis.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.animation.ScaleAnimation
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var micButton: android.widget.Button
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var settingsButton: ImageButton

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var actionExecutor: ActionExecutor

    private val prefs by lazy { getSharedPreferences("jarvis_prefs", MODE_PRIVATE) }

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        micButton = findViewById(R.id.micButton)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        settingsButton = findViewById(R.id.settingsButton)

        actionExecutor = ActionExecutor(this)
        tts = TextToSpeech(this, this)

        requestNeededPermissions()
        setupSpeechRecognizer()

        micButton.setOnClickListener { onMicTapped() }
        settingsButton.setOnClickListener { showApiKeyDialog() }

        if (getApiKey().isEmpty()) {
            showApiKeyDialog()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    private fun requestNeededPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    private fun getApiKey(): String = prefs.getString("api_key", "") ?: ""

    private fun showApiKeyDialog() {
        val input = EditText(this)
        input.hint = "sk-ant-..."
        input.setText(getApiKey())
        AlertDialog.Builder(this)
            .setTitle("Anthropic API Key")
            .setMessage("Get one free at console.anthropic.com")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("api_key", input.text.toString().trim()).apply()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                statusText.text = "Listening..."
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val heard = matches?.firstOrNull()
                if (!heard.isNullOrBlank()) {
                    appendLog("You: $heard")
                    handleCommand(heard)
                } else {
                    statusText.text = "Didn't catch that. Tap to try again."
                }
            }

            override fun onError(error: Int) {
                statusText.text = "Tap the mic and speak"
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { statusText.text = "Thinking..." }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun onMicTapped() {
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            showApiKeyDialog()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
        }
        speechRecognizer.startListening(intent)
        pulseMic()
    }

    private fun handleCommand(text: String) {
        val client = ClaudeApiClient(getApiKey())
        client.sendMessage(text) { decision ->
            runOnUiThread {
                if (decision == null) {
                    val msg = "Sorry, I couldn't reach the AI. Check your API key and connection."
                    statusText.text = "Tap the mic and speak"
                    appendLog("Jarvis: $msg")
                    tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, null)
                    return@runOnUiThread
                }

                val actionResult = actionExecutor.execute(decision)
                val spoken = decision.reply
                appendLog("Jarvis: $spoken")
                if (decision.action != "answer") {
                    appendLog("[$actionResult]")
                }
                statusText.text = "Tap the mic and speak"
                tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    private fun appendLog(line: String) {
        logText.append("$line\n\n")
        logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun pulseMic() {
        val anim = ScaleAnimation(
            1f, 1.15f, 1f, 1.15f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
        )
        anim.duration = 200
        anim.repeatCount = 1
        anim.repeatMode = android.view.animation.Animation.REVERSE
        micButton.startAnimation(anim)
    }

    override fun onDestroy() {
        speechRecognizer.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}
