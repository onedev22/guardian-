package com.amurayada.guardianapp.detection

import kotlin.math.sqrt
import android.util.Log

class SensorDataAnalyzer(
    private var sensitivityThreshold: Float = 4.0f // G-force threshold
) {
    // Window Settings: 1 second at ~50Hz (50 samples)
    private val maxHistorySize = 50
    private val accelerationHistory = mutableListOf<Float>()
    private val timestampHistory = mutableListOf<Long>()
    private val rotationHistory = mutableListOf<Float>() // Angular velocity magnitude
    
    // Jerk = Δa / Δt
    // a = m/s², t = seconds
    
    fun analyzeSensorData(
        x: Float, y: Float, z: Float, 
        gyroX: Float = 0f, gyroY: Float = 0f, gyroZ: Float = 0f,
        previousSpeedKmH: Float = 0f
    ): CrashDetectionResult {
        val currentTime = System.currentTimeMillis()
        val magnitude = sqrt(x * x + y * y + z * z)
        val gravity = 9.81f
        val magnitudeG = magnitude / gravity
        
        val gyroMagnitude = sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ)
        
        // Add to history
        accelerationHistory.add(magnitude)
        timestampHistory.add(currentTime)
        rotationHistory.add(gyroMagnitude)
        
        if (accelerationHistory.size > maxHistorySize) {
            accelerationHistory.removeAt(0)
            timestampHistory.removeAt(0)
            rotationHistory.removeAt(0)
        }
        
        // Calculate Jerk if we have historical data
        val jerk = calculateJerk(magnitude, currentTime)
        
        // Calculate Score (weighted factors)
        val score = calculateScore(
            magnitudeG = magnitudeG,
            jerk = jerk,
            previousSpeedKmH = previousSpeedKmH,
            gyroMagnitude = gyroMagnitude
        )
        
        // Log significant events
        if (score > 0.4f) {
            // To make this syntactically correct without introducing new variables or changing the return type of calculateScore,
            // we will use the available parameters for logging.
            // The original log message used magnitudeG, jerk, and previousSpeedKmH.
            // The requested log message uses peakG, jerkContribution, and rotationContribution.
            // We'll map these to magnitudeG, jerk, and gyroMagnitude respectively for correctness.
            Log.i("SensorDataAnalyzer", "Significant Event detected - Score: %.2f (PeakG: %.2f, Speed: %.1f km/h, Jerk: %.2f, Rot: %.2f)".format(score, magnitudeG, previousSpeedKmH, jerk, gyroMagnitude))
        }

        return CrashDetectionResult(
            isCrashDetected = score > 0.75f,
            requiresConfirmation = score >= 0.5f && score <= 0.75f,
            maxAcceleration = magnitude,
            confidence = score,
            score = score
        )
    }
    
    private fun calculateJerk(currentAcc: Float, currentTime: Long): Float {
        if (accelerationHistory.size < 2) return 0f
        
        val prevAcc = accelerationHistory[accelerationHistory.size - 2]
        val prevTime = timestampHistory[timestampHistory.size - 2]
        
        val dt = (currentTime - prevTime) / 1000f // to seconds
        if (dt <= 0) return 0f
        
        return kotlin.math.abs(currentAcc - prevAcc) / dt
    }
    
    private fun calculateScore(
        magnitudeG: Float,
        jerk: Float,
        previousSpeedKmH: Float,
        gyroMagnitude: Float
    ): Float {
        var totalScore = 0f
        
        // 1. Peak G (Weight: 0.3)
        // Scaled: 2G = 0, 8G = 1.0
        val gScore = ((magnitudeG - 2f) / 6f).coerceIn(0f, 1f)
        totalScore += gScore * 0.3f
        
        // 2. Previous Speed (Weight: 0.2)
        // Scaled: 20 km/h = 0, 60 km/h = 1.0
        val speedScore = ((previousSpeedKmH - 20f) / 40f).coerceIn(0f, 1f)
        totalScore += speedScore * 0.2f
        
        // 3. Jerk (Weight: 0.3)
        // Crash jerky is HUGE. 100 m/s³ = 0, 500 m/s³ = 1.0
        val jerkScore = ((jerk - 100f) / 400f).coerceIn(0f, 1f)
        totalScore += jerkScore * 0.3f
        
        // 4. Rotation (Weight: 0.1)
        // Abrupt rotation. 2 rad/s = 0, 10 rad/s = 1.0
        val rotScore = ((gyroMagnitude - 2f) / 8f).coerceIn(0f, 1f)
        totalScore += rotScore * 0.1f
        
        // 5. Variance (Physical instability) (Weight: 0.1)
        if (accelerationHistory.size >= 10) {
            val variance = calculateVariance()
            val varScore = (variance / 100f).coerceIn(0f, 1f)
            totalScore += varScore * 0.1f
        }
        
        return totalScore.coerceIn(0f, 1f)
    }
    
    private fun calculateVariance(): Float {
        if (accelerationHistory.isEmpty()) return 0f
        val mean = accelerationHistory.average().toFloat()
        return accelerationHistory.map { (it - mean) * (it - mean) }.average().toFloat()
    }
    
    fun reset() {
        accelerationHistory.clear()
        timestampHistory.clear()
        rotationHistory.clear()
    }
    
    fun setSensitivity(sensitivity: Float) {
        this.sensitivityThreshold = sensitivity
    }
}

data class CrashDetectionResult(
    val isCrashDetected: Boolean,
    val requiresConfirmation: Boolean,
    val maxAcceleration: Float,
    val confidence: Float,
    val score: Float
)
