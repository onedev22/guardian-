package com.amurayada.guardianapp.detection

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SensorDataAnalyzerTest {

    private lateinit var analyzer: SensorDataAnalyzer

    @Before
    fun setup() {
        analyzer = SensorDataAnalyzer(4.0f)
    }

    @Test
    fun testSignificantImpact() {
        // Simulate a sequence of high G and high Jerk
        // a = 60 m/s² (~6G), previous speed = 50 km/h
        
        // Setup initial history
        analyzer.analyzeSensorData(0f, 0f, 9.8f, 0f, 0f, 0f, 50f)
        
        // The "Crash" sample
        // High acceleration, huge jump from previous sample for jerk
        val result = analyzer.analyzeSensorData(50f, 30f, 20f, 5f, 5f, 5f, 50f) 
        // Magnitude = sqrt(2500+900+400) = 61.6 m/s² (~6.2G)
        // Jerk will be high because it jumped from 9.8 to 61.6 in ~20ms (dt in analyzer is based on real time, but let's assume it detects significant Jerk)
        
        android.util.Log.d("Test", "Crash Score: ${result.score}")
        assertTrue("Score should be significant for crash", result.score > 0.6f)
    }

    @Test
    fun testHardBrake() {
        // High G but low Jerk and no rotation
        // a = 20 m/s² (~2G), slowly increasing
        
        analyzer.analyzeSensorData(0f, 0f, 9.8f, 0f, 0f, 0f, 60f)
        analyzer.analyzeSensorData(0f, 15f, 9.8f, 0f, 0f, 0f, 55f)
        val result = analyzer.analyzeSensorData(0f, 20f, 9.8f, 0f, 0f, 0f, 50f)
        
        assertTrue("Hard brake should not trigger a crash", result.score < 0.5f)
    }

    @Test
    fun testPothole() {
        // High peak G, but very short duration (simulated by immediate recovery)
        // and low Jerk compared to a real wall-hit crash
        
        analyzer.analyzeSensorData(0f, 0f, 9.8f, 0f, 0f, 0f, 40f)
        val result = analyzer.analyzeSensorData(0f, 0f, 40f, 0f, 0f, 0f, 40f) // Spike
        analyzer.analyzeSensorData(0f, 0f, 9.8f, 0f, 0f, 0f, 40f) // Quick recovery
        
        assertTrue("Pothole should have lower confidence than a real crash", result.score < 0.75f)
    }

    @Test
    fun testPhoneDrop() {
        // High G spike, but Speed is 0
        val result = analyzer.analyzeSensorData(40f, 40f, 40f, 10f, 10f, 10f, 0f)
        
        assertTrue("Phone drop at 0 speed should not trigger crash alert", result.score < 0.5f)
    }
}
