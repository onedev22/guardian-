package com.amurayada.guardianapp.ui.emergency

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.amurayada.guardianapp.data.local.AppDatabase
import com.amurayada.guardianapp.data.model.CrashEvent
import com.amurayada.guardianapp.emergency.EmergencyAlertManager
import com.amurayada.guardianapp.location.LocationProvider
import com.amurayada.guardianapp.service.CrashDetectionService
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.ToneGenerator
import com.amurayada.guardianapp.ui.theme.GuardianAppTheme
import com.amurayada.guardianapp.utils.TTSManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class EmergencyAlertActivity : ComponentActivity() {
    
    private var countDownTimer: CountDownTimer? = null
    private var ringtone: Ringtone? = null
    private var initialWarningJob: kotlinx.coroutines.Job? = null
    private lateinit var vibrator: Vibrator
    private lateinit var emergencyAlertManager: EmergencyAlertManager
    private lateinit var locationProvider: LocationProvider
    private lateinit var ttsManager: TTSManager
    private lateinit var audioManager: AudioManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        android.util.Log.i("EmergencyAlertActivity", "onCreate: Alerta iniciada")
        
        // Configurar para mostrar sobre lockscreen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        
        emergencyAlertManager = EmergencyAlertManager(this)
        locationProvider = LocationProvider(this)
        emergencyAlertManager = EmergencyAlertManager(this)
        locationProvider = LocationProvider(this)
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        ttsManager = TTSManager(this)
        
        val maxAcceleration = intent.getFloatExtra("max_acceleration", 0f)
        val isTestMode = intent.getBooleanExtra("test_mode", false) // Modo de prueba
        
        // Start alarm sound
        startAlarm()
        
        setContent {
            GuardianAppTheme {
                EmergencyAlertScreen(
                    maxAcceleration = maxAcceleration,
                    isTestMode = isTestMode,
                    onCancel = { handleCancel() },
                    onTimeout = { handleTimeout(maxAcceleration, isTestMode) },
                    onArrivedAtHospital = { handleArrivedAtHospital() }
                )
            }
        }
    }
    
    private fun startAlarm() {
        // Move heavy initialization to background to avoid blocking UI
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ringtone = RingtoneManager.getRingtone(this@EmergencyAlertActivity, alarmUri)
                ringtone?.play()
                
                // Vibrate pattern: wait 0ms, vibrate 500ms, wait 200ms, vibrate 500ms
                val pattern = longArrayOf(0, 500, 200, 500)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
                
                // Manage Audio: Ring for 500ms, then stop to speak
                delay(500)
                ringtone?.stop() // Silence alarm to let TTS be heard
                
                // Speak initial warning (Shortened for speed)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    ttsManager.speak("¿Se encuentra bien? Si es falsa alarma, cancele ahora.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun stopAlarm() {
        ringtone?.stop()
        vibrator.cancel()
        initialWarningJob?.cancel()
        ttsManager.stop()
    }
    
    private fun handleCancel() {
        // Stop alarm immediately on main thread to silence noise
        stopAlarm()
        
        // Finish activity immediately for instant UI response
        finish()
        
        // Do heavy cleanup in background
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch {
            // Restart crash detection service
            CrashDetectionService.start(applicationContext)
            // Stop location sharing if it was active
            com.amurayada.guardianapp.service.EmergencyLocationService.stop(applicationContext)
        }
    }
    
    private fun handleArrivedAtHospital() {
        // Show immediate feedback
        android.widget.Toast.makeText(
            this,
            "✅ Procesando...",
            android.widget.Toast.LENGTH_SHORT
        ).show()
        
        // Finish activity immediately
        finish()
        
        // Perform network/location operations in background that survives activity death
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch {
            // Stop location sharing service
            com.amurayada.guardianapp.service.EmergencyLocationService.stop(applicationContext)
            
            // Try to get location with timeout to avoid hanging
            val location = try {
                kotlinx.coroutines.withTimeout(5000) { // 5 second timeout
                    locationProvider.getCurrentLocation() 
                        ?: locationProvider.getLastKnownLocation()
                }
            } catch (e: Exception) {
                locationProvider.getLastKnownLocation()
            }
            
            location?.let {
                emergencyAlertManager.sendHospitalArrivalMessage(it)
            }
            
            // Restart crash detection service
            CrashDetectionService.start(applicationContext)
        }
    }
    
    private fun handleTimeout(maxAcceleration: Float, isTestMode: Boolean) {
        stopAlarm()
        
        // Speak unresponsive message (Shortened for speed)
        ttsManager.speak("Sin respuesta. Llamando a emergencias. Indique su ubicación al hablar.")
        
        // 1. SEND INITIAL SOS ALERT FIRST (SMS/WhatsApp)
        lifecycleScope.launch {
            val location = locationProvider.getCurrentLocation() 
                ?: locationProvider.getLastKnownLocation()
            
            val crashEvent = CrashEvent(
                latitude = location?.latitude ?: 0.0,
                longitude = location?.longitude ?: 0.0,
                maxAcceleration = maxAcceleration,
                wasCancelled = false,
                alertSent = true
            )
            
            emergencyAlertManager.sendEmergencyAlert(crashEvent, isTest = isTestMode)
            
            val toastMsg = if (isTestMode) "✅ Alertas SOS (PRUEBA) enviadas" else "✅ Alertas SOS enviadas"
            android.widget.Toast.makeText(
                this@EmergencyAlertActivity,
                toastMsg,
                android.widget.Toast.LENGTH_LONG
            ).show()
            
            // 2. START LOCATION SHARING SERVICE (AFTER SOS MESSAGE)
            // Wait 2 seconds to ensure messages arrive in order
            delay(2000)
            com.amurayada.guardianapp.service.EmergencyLocationService.start(this@EmergencyAlertActivity)
        }
        
        if (isTestMode) {
            // MODO DE PRUEBA: Omitir llamada real
            android.widget.Toast.makeText(
                this,
                "🛠️ MODO PRUEBA: Omitiendo llamada real",
                android.widget.Toast.LENGTH_LONG
            ).show()
        } else {
            // MODO REAL - Llama a emergencias
            val prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
            val emergencyNumber = prefs.getString("emergency_number", "123") ?: "123"
            
            android.widget.Toast.makeText(
                this,
                "📞 Llamando a emergencias: $emergencyNumber",
                android.widget.Toast.LENGTH_LONG
            ).show()
            
            try {
                lifecycleScope.launch {
                    delay(5000) // 5 seconds delay
                    val callIntent = Intent(Intent.ACTION_CALL).apply {
                        data = Uri.parse("tel:$emergencyNumber")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    if (ActivityCompat.checkSelfPermission(this@EmergencyAlertActivity, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                        startActivity(callIntent)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Restart service for monitoring
        CrashDetectionService.start(this)
        
        // DO NOT FINISH - Keep screen open to show "Alert Sent" and Medical Info
        // finish() 
    }
    
    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        stopAlarm()
        // Reset audio mode
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
        ttsManager.shutdown()
    }
}
