package com.amurayada.guardianapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.amurayada.guardianapp.MainActivity
import com.amurayada.guardianapp.detection.SensorDataAnalyzer
import com.amurayada.guardianapp.detection.VehicleContextManager
import com.amurayada.guardianapp.ui.emergency.EmergencyAlertActivity
import com.amurayada.guardianapp.bluetooth.BLEManager
import android.bluetooth.BluetoothProfile

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*

class CrashDetectionService : Service(), SensorEventListener {
    
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private lateinit var sensorAnalyzer: SensorDataAnalyzer
    private lateinit var vehicleContextManager: VehicleContextManager
    
    // GPS for Context
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    
    // Background thread for sensor processing
    private var sensorThread: android.os.HandlerThread? = null
    private var sensorHandler: android.os.Handler? = null
    
    // State
    private var isInVehicleMode = false
    private var isMonitoring = false
    private var lastGyroValues = FloatArray(3) { 0f }
    
    companion object {
        const val CHANNEL_ID = "crash_detection_service"
        const val NOTIFICATION_ID = 1
        
        fun start(context: Context) {
            val intent = Intent(context, CrashDetectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, CrashDetectionService::class.java)
            context.stopService(intent)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Initial checks
        if (!hasPermissions()) {
            Log.w("CrashDetectionService", "Stopping service: Missing ACCESS_FINE_LOCATION permission")
            stopSelf()
            return
        }
        
        setupHardware()
        setupThreads()
        setupAnalysis()
        setupLocationUpdates()
        
        createNotificationChannel()
        startServiceForeground()
        
        startMonitoring()
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun setupHardware() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    private fun setupThreads() {
        sensorThread = android.os.HandlerThread("SensorThread", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        sensorThread?.start()
        sensorThread?.looper?.let {
            sensorHandler = android.os.Handler(it)
        }
    }

    private fun setupAnalysis() {
        val prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        // Usar 4.5f como valor predeterminado (balanceado entre sensibilidad y falsos positivos)
        val sensitivity = prefs.getFloat("sensitivity", 4.5f)
        
        Log.i("CrashDetectionService", "Initializing analysis with sensitivity: $sensitivity G")
        sensorAnalyzer = SensorDataAnalyzer(sensitivityThreshold = sensitivity)
        vehicleContextManager = VehicleContextManager()
    }

    private fun setupLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(2000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val wasInVehicle = isInVehicleMode
                    isInVehicleMode = vehicleContextManager.onLocationUpdate(location)
                    
                    if (wasInVehicle != isInVehicleMode) {
                        Log.i("CrashDetectionService", "CONTEXT CHANGE: isInVehicleMode=$isInVehicleMode (Speed: ${location.speed * 3.6f} km/h)")
                        updateMonitoringFrequency()
                    }
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e("CrashDetectionService", "Location permission missing for updates", e)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("CrashDetectionService", "Service onStartCommand: Monitoring requested. currentIsMonitoring=$isMonitoring")
        if (!isMonitoring) {
            startMonitoring()
        }
        return START_STICKY
    }
    
    private fun startMonitoring() {
        Log.i("CrashDetectionService", "Starting/Resuming sensor monitoring")
        isMonitoring = true
        if (::sensorAnalyzer.isInitialized) {
            sensorAnalyzer.reset()
        }
        updateMonitoringFrequency()
    }

    private fun updateMonitoringFrequency() {
        if (!::sensorManager.isInitialized) return
        
        sensorManager.unregisterListener(this)
        
        val delay = if (isInVehicleMode) {
            SensorManager.SENSOR_DELAY_GAME // ~50Hz for crash detection
        } else {
            SensorManager.SENSOR_DELAY_NORMAL // ~5Hz for context/battery saving
        }
        
        val handler = sensorHandler
        if (handler != null) {
            accelerometer?.let {
                sensorManager.registerListener(this, it, delay, handler)
            }
            gyroscope?.let {
                sensorManager.registerListener(this, it, delay, handler)
            }
        }
        
        Log.i("CrashDetectionService", "MONITORING MODE UPDATED: ${if (isInVehicleMode) "VEHICLE (HIGH FREQ)" else "NORMAL (LOW FREQ)"}")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    // Only analyze deeply if in vehicle mode OR if magnitude is very high even in normal mode
                    val x = it.values[0]
                    val y = it.values[1]
                    val z = it.values[2]
                    
                    val result = sensorAnalyzer.analyzeSensorData(
                        x = x, y = y, z = z,
                        gyroX = lastGyroValues[0],
                        gyroY = lastGyroValues[1],
                        gyroZ = lastGyroValues[2],
                        previousSpeedKmH = vehicleContextManager.getCurrentSpeed() * 3.6f
                    )
                    
                    if (result.isCrashDetected) {
                        val bleManager = BLEManager.getInstance(this@CrashDetectionService)
                        val hr = bleManager.heartRate.value
                        val connected = bleManager.connectionState.value == BluetoothProfile.STATE_CONNECTED
                        
                        if (connected) {
                            // Verificación por pulso: Si el pulso está elevado (>100) o muy bajo (<40), se confirma el accidente.
                            // Si el pulso es normal, se ignora para evitar falsos positivos (ej. caída del celular).
                            if (hr > 100 || hr < 40) {
                                Log.i("CrashDetectionService", "CRASH CONFIRMADO: Score ${result.score} + Pulso $hr bpm")
                                onCrashDetected(result.maxAcceleration, result.score)
                            } else {
                                Log.i("CrashDetectionService", "FALSO POSITIVO EVITADO: Score ${result.score} pero pulso normal ($hr bpm)")
                                sensorAnalyzer.reset()
                            }
                        } else {
                            // Si no hay pulsera, seguimos la lógica normal
                            Log.i("CrashDetectionService", "DETECTED: Impact Score ${result.score} (Sin pulsera para verificar)")
                            onCrashDetected(result.maxAcceleration, result.score)
                        }
                    }
                }
                Sensor.TYPE_GYROSCOPE -> {
                    lastGyroValues[0] = it.values[0]
                    lastGyroValues[1] = it.values[1]
                    lastGyroValues[2] = it.values[2]
                }
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    private fun onCrashDetected(maxAcceleration: Float, score: Float) {
        // Prevent re-triggering immediately
        isMonitoring = false
        sensorManager.unregisterListener(this)
        
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val intent = Intent(this, EmergencyAlertActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("max_acceleration", maxAcceleration)
                putExtra("crash_score", score)
                putExtra("is_high_probability", score > 0.85f)
            }
            startActivity(intent)
        }
    }
    
    override fun onDestroy() {
        Log.i("CrashDetectionService", "Service onDestroy: Cleaning up resources")
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Protección Guardian",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitorea tu seguridad mientras conduces"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun startServiceForeground() {
        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, 
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("CrashDetectionService", "Failed to start foreground", e)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Guardian Protegiéndote")
            .setContentText("Detección de choques activa en segundo plano")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
