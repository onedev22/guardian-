package com.amurayada.guardianapp.detection

import android.location.Location
import android.util.Log

class VehicleContextManager {
    private var isVehicleModeActive = false
    private var lastSpeeds = mutableListOf<Float>()
    private val speedThreshold = 20f / 3.6f // 20 km/h in m/s
    private val deactivationThreshold = 10f / 3.6f // 10 km/h in m/s
    private val windowSize = 10 // Number of location updates to consider (approx 10-20 seconds if 1-2s updates)

    fun onLocationUpdate(location: Location): Boolean {
        val speed = location.speed // speed in m/s
        lastSpeeds.add(speed)
        
        if (lastSpeeds.size > windowSize) {
            lastSpeeds.removeAt(0)
        }

        updateVehicleMode()
        return isVehicleModeActive
    }

    private fun updateVehicleMode() {
        if (lastSpeeds.size < 5) return // Not enough data yet

        val averageSpeed = lastSpeeds.average().toFloat()
        
        if (!isVehicleModeActive) {
            // Activate if average speed is consistently above 20 km/h
            val sustainedSpeed = lastSpeeds.all { it > speedThreshold * 0.8f }
            if (averageSpeed > speedThreshold && sustainedSpeed) {
                isVehicleModeActive = true
                Log.d("VehicleContext", "Vehicle mode ACTIVATED. Avg speed: ${averageSpeed * 3.6f} km/h")
            }
        } else {
            // Deactivate if speed drops below 10 km/h for a while
            if (averageSpeed < deactivationThreshold) {
                isVehicleModeActive = false
                Log.d("VehicleContext", "Vehicle mode DEACTIVATED. Avg speed: ${averageSpeed * 3.6f} km/h")
            }
        }
    }

    fun isInVehicle(): Boolean = isVehicleModeActive
    
    fun getCurrentSpeed(): Float {
        return lastSpeeds.lastOrNull() ?: 0f
    }
}
