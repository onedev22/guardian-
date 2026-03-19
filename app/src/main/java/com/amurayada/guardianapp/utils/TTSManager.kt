package com.amurayada.guardianapp.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TTSManager(private val context: Context) : TextToSpeech.OnInitListener {
    
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val pendingMessages = mutableListOf<String>()
    
    init {
        tts = TextToSpeech(context, this)
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Use generic "es" for maximum compatibility (es-US, es-CO, es-ES, etc.)
            val result = tts?.setLanguage(Locale("es"))
            
            isInitialized = true
            Log.i("TTSManager", "Initialization successful with locale: ${tts?.voice?.locale ?: "unknown"}")
            // Speak any pending messages
            pendingMessages.forEach { speak(it) }
            pendingMessages.clear()
        } else {
            Log.e("TTSManager", "Initialization failed with status: $status")
        }
    }
    
    fun speak(text: String, streamType: Int? = null) {
        if (!isInitialized) {
            Log.d("TTSManager", "TTS not initialized yet, adding to pending: $text")
            pendingMessages.add(text)
            return
        }

        try {
            val params = android.os.Bundle()
            // Default to ALARM stream for maximum visibility during crashes
            val targetStream = streamType ?: android.media.AudioManager.STREAM_ALARM
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, targetStream)
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            
            Log.d("TTSManager", "Speaking: $text")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "emergency_tts_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Log.e("TTSManager", "Error during speak", e)
        }
    }

    fun synthesizeToFile(text: String): java.io.File? {
        if (!isInitialized) return null
        
        try {
            val file = java.io.File(context.cacheDir, "emergency_message.wav")
            val params = android.os.Bundle()
            val result = tts?.synthesizeToFile(text, params, file, "emergency_tts")
            
            return if (result == TextToSpeech.SUCCESS) file else null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    fun stop() {
        tts?.stop()
    }
    
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
