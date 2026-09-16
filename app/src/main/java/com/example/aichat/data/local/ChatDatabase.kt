package com.example.aichat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.Message
import com.example.aichat.data.model.MemoryItem

@Database(
    entities = [
        Conversation::class,
        Message::class,
        MemoryItem::class
    ],
    version = 4, // ✅ مُحدَّث: من 3 إلى 4
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var INSTANCE: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "aichat_database"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4 // ✅ مُضاف
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }

        // Migration 1 → 2: إنشاء جدول memory_items
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS memory_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        content TEXT NOT NULL,
                        sourceConversationId INTEGER,
                        sourceMessageId INTEGER,
                        category TEXT NOT NULL,
                        isShared INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        // Migration 2 → 3: إضافة عمود embedding
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    ALTER TABLE memory_items 
                    ADD COLUMN embedding TEXT NOT NULL DEFAULT ''
                    """.trimIndent()
                )
            }
        }

        // ✅ Migration 3 → 4: إضافة embeddingModel و embeddingDimensions
        // السبب: تتبع النموذج المستخدم لكل vector لضمان التوافق مستقبلاً
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    ALTER TABLE memory_items 
                    ADD COLUMN embeddingModel TEXT NOT NULL DEFAULT 'text-embedding-004'
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    ALTER TABLE memory_items 
                    ADD COLUMN embeddingDimensions INTEGER NOT NULL DEFAULT 768
                    """.trimIndent()
                )
            }
        }
    }
}
