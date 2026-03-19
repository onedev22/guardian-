package com.amurayada.guardianapp.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.amurayada.guardianapp.data.model.CrashEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface CrashEventDao {
    @Query("SELECT * FROM crash_events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<CrashEvent>>
    
    @Query("SELECT * FROM crash_events WHERE id = :id")
    suspend fun getEventById(id: Long): CrashEvent?
    
    @Insert
    suspend fun insertEvent(event: CrashEvent): Long
    
    @Update
    suspend fun updateEvent(event: CrashEvent)
    
    @Delete
    suspend fun deleteEvent(event: CrashEvent)
    
    @Query("SELECT COUNT(*) FROM crash_events")
    fun getEventCount(): Flow<Int>
}
