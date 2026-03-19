package com.amurayada.guardianapp.emergency

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.amurayada.guardianapp.R
import com.amurayada.guardianapp.data.local.AppDatabase
import com.amurayada.guardianapp.data.model.CrashEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class EmergencyAlertManager(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    
    companion object {
        const val CHANNEL_ID = "emergency_alerts"
        const val NOTIFICATION_ID = 999
    }
    
    init {
        createNotificationChannel()
    }
    
    fun sendEmergencyAlert(crashEvent: CrashEvent, isTest: Boolean = false) {
        scope.launch {
            // Save crash event to database
            val eventId = database.crashEventDao().insertEvent(crashEvent)
            
            // Get emergency contacts (take only the current state once)
            val contacts = database.emergencyContactDao().getAllContacts().first()
            
            // Get user name
            val medicalInfo = database.medicalInfoDao().getMedicalInfoOnce()
            val userName = medicalInfo?.fullName?.takeIf { it.isNotBlank() } ?: "El usuario"
            
            if (contacts.isEmpty()) {
                android.util.Log.e("EmergencyAlertManager", "NO CONTACTS FOUND! SOS alert aborted.")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        context,
                        "⚠️ ERROR: No tienes contactos de emergencia. Por favor agrégalos en la sección de Contactos.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }
            
            android.util.Log.d("EmergencyAlertManager", "Sending alerts to ${contacts.size} contacts")
            
            // Send BOTH SMS and WhatsApp to each contact for redundancy
            contacts.forEach { contact ->
                // Try SMS first (works without internet)
                sendSMS(contact.phoneNumber, crashEvent, userName = userName, isTest = isTest)
                
                // Try WhatsApp if internet is available (more reliable delivery)
                sendWhatsApp(contact.phoneNumber, crashEvent, userName = userName, isTest = isTest)
            }
            
            // Show notification
            showEmergencyNotification(crashEvent)
            
            // Update event as sent
            database.crashEventDao().updateEvent(
                crashEvent.copy(id = eventId, alertSent = true)
            )
        }
    }
    
    fun sendLocationUpdate(location: android.location.Location) {
        scope.launch {
            // Get emergency contacts
            val contacts = database.emergencyContactDao().getAllContacts().first()
            
            val message = "📍 ACTUALIZACIÓN Guardian: El usuario sigue en emergencia. Nueva ubicación: https://maps.google.com/?q=${location.latitude},${location.longitude}"
            
            contacts.forEach { contact ->
                sendSMS(contact.phoneNumber, null, message)
                sendWhatsApp(contact.phoneNumber, null, message)
            }
        }
    }
    
    fun sendHospitalArrivalMessage(location: android.location.Location) {
        scope.launch {
            // Get emergency contacts
            val contacts = database.emergencyContactDao().getAllContacts().first()
            
            val message = "🏥 ACTUALIZACIÓN Guardian: El usuario ha llegado a un centro médico/hospital. Se detiene el compartimiento de ubicación. Última posición: https://maps.google.com/?q=${location.latitude},${location.longitude}"
            
            contacts.forEach { contact ->
                sendSMS(contact.phoneNumber, null, message)
                sendWhatsApp(contact.phoneNumber, null, message)
            }
        }
    }

    private fun sendSMS(phoneNumber: String, crashEvent: CrashEvent?, customMessage: String? = null, userName: String? = null, isTest: Boolean = false) {
        // Clean phone number (remove spaces, dashes, etc.)
        var cleanNumber = phoneNumber.filter { it.isDigit() || it == '+' }
        
        // For Colombian numbers, try LOCAL format first (without +57)
        // Many carriers prefer this for domestic SMS
        if (cleanNumber.startsWith("+57")) {
            cleanNumber = cleanNumber.substring(3) // Remove +57
            android.util.Log.d("EmergencyAlertManager", "Using local format (removed +57): $cleanNumber")
        } else if (cleanNumber.length == 10) {
            // Already in local format, keep it
            android.util.Log.d("EmergencyAlertManager", "Using local 10-digit format: $cleanNumber")
        }
        
        android.util.Log.d("EmergencyAlertManager", "Attempting to send SMS to $cleanNumber (original: $phoneNumber)")
        
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.e("EmergencyAlertManager", "SMS Permission NOT granted")
            return
        }
        
        val message = customMessage ?: buildEmergencyMessage(crashEvent!!, userName ?: "El usuario", isTest)
        
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            
            // Create PendingIntents for delivery tracking
            val sentIntent = android.app.PendingIntent.getBroadcast(
                context, 
                0, 
                Intent("SMS_SENT"),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            val deliveredIntent = android.app.PendingIntent.getBroadcast(
                context,
                0,
                Intent("SMS_DELIVERED"),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            val parts = smsManager.divideMessage(message)
            val sentIntents = ArrayList<android.app.PendingIntent>()
            val deliveredIntents = ArrayList<android.app.PendingIntent>()
            
            repeat(parts.size) {
                sentIntents.add(sentIntent)
                deliveredIntents.add(deliveredIntent)
            }
            
            smsManager.sendMultipartTextMessage(cleanNumber, null, parts, sentIntents, deliveredIntents)
            android.util.Log.d("EmergencyAlertManager", "SMS queued to $cleanNumber (Parts: ${parts.size}, Length: ${message.length} chars)")
            android.util.Log.d("EmergencyAlertManager", "Message content: $message")
        } catch (e: Exception) {
            android.util.Log.e("EmergencyAlertManager", "Failed to send SMS", e)
            e.printStackTrace()
        }
    }
    
    private fun sendWhatsApp(phoneNumber: String, crashEvent: CrashEvent?, customMessage: String? = null, userName: String? = null, isTest: Boolean = false) {
        try {
            // Clean and format phone number for WhatsApp
            var cleanNumber = phoneNumber.filter { it.isDigit() || it == '+' }
            
            // WhatsApp requires international format with country code
            if (cleanNumber.length == 10 && !cleanNumber.startsWith("+")) {
                cleanNumber = "57$cleanNumber" // Add Colombia code without +
            } else if (cleanNumber.startsWith("+")) {
                cleanNumber = cleanNumber.substring(1) // Remove + for WhatsApp URL
            }
            
            val message = customMessage ?: buildEmergencyMessage(crashEvent!!, userName ?: "El usuario", isTest)
            val encodedMessage = java.net.URLEncoder.encode(message, "UTF-8")
            
            // WhatsApp URL scheme
            val whatsappIntent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://wa.me/$cleanNumber?text=$encodedMessage")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            
            // Check if WhatsApp is installed
            if (whatsappIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(whatsappIntent)
                android.util.Log.d("EmergencyAlertManager", "WhatsApp message sent to $cleanNumber")
            } else {
                android.util.Log.w("EmergencyAlertManager", "WhatsApp not installed, skipping")
            }
        } catch (e: Exception) {
            android.util.Log.e("EmergencyAlertManager", "Failed to send WhatsApp message", e)
            e.printStackTrace()
        }
    }
    
    private fun buildEmergencyMessage(crashEvent: CrashEvent, userName: String, isTest: Boolean = false): String {
        val mapsUrl = "https://maps.google.com/?q=${crashEvent.latitude},${crashEvent.longitude}"
        val prefix = if (isTest) "[MODO PRUEBA] " else ""
        return "$prefix SOS por choque detectado $userName llamó a servicios de emergencia cerca de esta ubicación aproximada a través de la App Guardian. Recibiste este mensaje porque $userName te agregó como contacto de emergencia. Ubicación: $mapsUrl"
    }
    
    private fun showEmergencyNotification(crashEvent: CrashEvent) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Emergency Alert Sent")
            .setContentText("Crash detected. Emergency contacts have been notified.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alerts for crash detection"
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
