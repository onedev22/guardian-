package com.amurayada.guardianapp.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_medical_info")
data class MedicalInfo(
    @PrimaryKey
    val id: Long = 1, // Solo habrá un registro
    val fullName: String = "",
    val bloodType: String = "", // A+, O-, etc.
    val allergies: String = "",
    val medications: String = "",
    val medicalConditions: String = "",
    val address: String = "",
    val emergencyNotes: String = ""
)
