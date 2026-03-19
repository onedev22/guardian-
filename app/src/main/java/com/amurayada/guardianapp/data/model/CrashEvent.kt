package com.amurayada.guardianapp.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "crash_events")
data class CrashEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double,
    val longitude: Double,
    val maxAcceleration: Float,
    val wasCancelled: Boolean = false,
    val alertSent: Boolean = false,
    val notes: String = ""
)
