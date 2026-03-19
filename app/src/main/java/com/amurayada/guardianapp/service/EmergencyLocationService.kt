package com.amurayada.guardianapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.amurayada.guardianapp.R
import com.amurayada.guardianapp.emergency.EmergencyAlertManager
import com.amurayada.guardianapp.location.LocationProvider
import com.amurayada.guardianapp.ui.emergency.EmergencyAlertActivity
import kotlinx.coroutines.*

class EmergencyLocationService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var locationProvider: LocationProvider
    private lateinit var emergencyAlertManager: EmergencyAlertManager
    private var isMonitoring = false
    private var lastSentLocation: android.location.Location? = null

    companion object {
        const val CHANNEL_ID = "emergency_location_sharing"
        const val NOTIFICATION_ID = 2
        private const val UPDATE_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        fun start(context: Context) {
            val intent = Intent(context, EmergencyLocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, EmergencyLocationService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationProvider = LocationProvider(this)
        emergencyAlertManager = EmergencyAlertManager(this)
        createNotificationChannel()
        
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isMonitoring) {
            startLocationUpdates()
        }
        return START_STICKY
    }

    private fun startLocationUpdates() {
        isMonitoring = true
        serviceScope.launch {
            while (isActive && isMonitoring) {
                try {
                    Log.d("EmergencyLocationService", "Fetching location for update...")
                    val location = locationProvider.getCurrentLocation() 
                        ?: locationProvider.getLastKnownLocation()
                    
                    location?.let { currentLocation ->
                        val shouldUpdate = lastSentLocation == null || 
                                          currentLocation.distanceTo(lastSentLocation!!) > 50f
                        
                        if (shouldUpdate) {
                            Log.d("EmergencyLocationService", "Significant change detected. Sending update: ${currentLocation.latitude}, ${currentLocation.longitude}")
                            emergencyAlertManager.sendLocationUpdate(currentLocation)
                            lastSentLocation = currentLocation
                        } else {
                            Log.d("EmergencyLocationService", "No significant change (<50m). Skipping message.")
                        }
                    } ?: Log.w("EmergencyLocationService", "Could not get location for update")
                    
                } catch (e: Exception) {
                    Log.e("EmergencyLocationService", "Error in location update loop", e)
                }
                
                // Wait for next interval
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isMonitoring = false
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Emergency Location Sharing",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Sharing location with emergency contacts"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        // Intent to open the EmergencyAlertActivity when notification is clicked
        // We need to make sure we can get back to the active alert screen
        val intent = Intent(this, EmergencyAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Compartiendo Ubicación de Emergencia")
            .setContentText("Enviando ubicación a contactos cada 5 min")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }
}
