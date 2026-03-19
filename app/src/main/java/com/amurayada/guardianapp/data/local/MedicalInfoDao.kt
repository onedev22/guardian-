package com.amurayada.guardianapp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.amurayada.guardianapp.data.model.MedicalInfo
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicalInfoDao {
    @Query("SELECT * FROM user_medical_info WHERE id = 1")
    fun getMedicalInfo(): Flow<MedicalInfo?>
    
    @Query("SELECT * FROM user_medical_info WHERE id = 1")
    suspend fun getMedicalInfoOnce(): MedicalInfo?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(medicalInfo: MedicalInfo)
}
