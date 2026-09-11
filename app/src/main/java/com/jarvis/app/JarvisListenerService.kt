package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class JarvisListenerService : Service(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private lateinit var actionExecutor: ActionExecutor
    private var isRunning = false
    private var isSpeaking = false
    private val handler = Handler(Looper.getMainLooper())

    private val prefs by lazy { getSharedPreferences("jarvis_prefs", MODE_PRIVATE) }
    private fun getApiKey(): String = prefs.getString("api_key", "") ?: ""

    companion object {
        const val CHANNEL_ID = "jarvis_channel"
        const val NOTIF_ID = 1
        const val WAKE_WORD = "jarvis"
    }

    override fun onCreate() {
        super.onCreate()
        actionExecutor = ActionExecutor(this)
        tts = TextToSpeech(this, this)
        createNotificationChannel()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Listening for \"Hey Jarvis\"..."))
        if (!isRunning) {
            isRunning = true
            listenOnce()
        }
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Jarvis Listening", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun listenOnce() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            updateNotification("Microphone permission needed")
            return
        }
        if (isSpeaking) return

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        }

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val heard = matches?.firstOrNull()?.lowercase(Locale.US) ?: ""
                if (heard.contains(WAKE_WORD)) {
                    val command = heard.substringAfter(WAKE_WORD).trim().trim(',', '.', ' ')
                    if (command.isNotBlank()) {
                        handleCommand(command)
                        return
                    } else {
                        updateNotification("Yes? Listening...")
                    }
                }
                relisten()
            }

            override fun onError(error: Int) {
                relisten()
            }
        })

        try {
            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            relisten()
        }
    }

    private fun relisten() {
        if (!isRunning || isSpeaking) return
        handler.postDelayed({ listenOnce() }, 500)
    }

    private fun handleCommand(text: String) {
        updateNotification("Thinking: \"$text\"")
        val client = ClaudeApiClient(getApiKey())
        client.sendMessage(text) { decision ->
            handler.post {
                if (decision == null) {
                    speak("Sorry, I couldn't reach the AI.")
                    return@post
                }
                try {
                    actionExecutor.execute(decision)
                } catch (e: Exception) {
                    // action failed, still speak the reply
                }
                speak(decision.reply)
            }
        }
    }

    private fun speak(text: String) {
        isSpeaking = true
        updateNotification(text)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                updateNotification("Listening for \"Hey Jarvis\"...")
                handler.postDelayed({ listenOnce() }, 300)
            }
            override fun onError(utteranceId: String?) {
                isSpeaking = false
                relisten()
            }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "jarvis_utt")
    }

    override fun onDestroy() {
        isRunning = false
        speechRecognizer?.destroy()
        tts.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
