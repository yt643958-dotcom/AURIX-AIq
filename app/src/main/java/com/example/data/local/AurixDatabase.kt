package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, MemoryFactEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AurixDatabase : RoomDatabase() {
    abstract fun aurixDao(): AurixDao

    companion object {
        @Volatile
        private var INSTANCE: AurixDatabase? = null

        fun getInstance(context: Context): AurixDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AurixDatabase::class.java,
                    "aurix_ai_database"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
