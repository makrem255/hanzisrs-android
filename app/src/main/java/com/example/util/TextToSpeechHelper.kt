package com.example.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TextToSpeechHelper(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private var speechRate = 0.85f

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.CHINESE)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
            }
            tts?.setSpeechRate(speechRate)
            isInitialized = true
            Log.d("TTSHelper", "Mandarin TTS Initialized Successfully")
        } else {
            Log.e("TTSHelper", "TTS Initialization failed with code $status")
        }
    }

    fun setSpeed(slow: Boolean) {
        speechRate = if (slow) 0.65f else 0.90f
        tts?.setSpeechRate(speechRate)
    }

    fun speak(text: String, rate: Float? = null) {
        if (!isInitialized || text.isBlank()) return
        if (rate != null) {
            tts?.setSpeechRate(rate)
        } else {
            tts?.setSpeechRate(speechRate)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "HanziTTS_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
