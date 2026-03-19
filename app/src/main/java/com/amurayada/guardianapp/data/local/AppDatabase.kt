package com.amurayada.guardianapp.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.amurayada.guardianapp.data.model.CrashEvent
import com.amurayada.guardianapp.data.model.EmergencyContact
import com.amurayada.guardianapp.data.model.MedicalInfo

@Database(
    entities = [CrashEvent::class, EmergencyContact::class, MedicalInfo::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun crashEventDao(): CrashEventDao
    abstract fun emergencyContactDao(): EmergencyContactDao
    abstract fun medicalInfoDao(): MedicalInfoDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "guardian_database"
                )
                    .fallbackToDestructiveMigration() // Recreate DB on version change
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
