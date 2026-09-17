package com.example.aichat.data.local

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.data.model.Message
import com.example.aichat.data.model.WebPlatform

@Database(
    entities = [
        Conversation::class,
        Message::class,
        MemoryItem::class,
        WebPlatform::class          // ← جديد
    ],
    version = 6,                    // ← رُفع من 5 إلى 6
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao
    abstract fun webPlatformDao(): WebPlatformDao   // ← جديد

    companion object {

        @Volatile
        private var INSTANCE: ChatDatabase? = null

        // ======== Migrations ========

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS memory_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        content TEXT NOT NULL,
                        embedding TEXT NOT NULL DEFAULT '',
                        category TEXT NOT NULL DEFAULT 'OTHER',
                        isShared INTEGER NOT NULL DEFAULT 1,
                        sourceConversationId INTEGER,
                        sourceMessageId INTEGER,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE memory_items ADD COLUMN embedding TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE memory_items ADD COLUMN embeddingModel TEXT NOT NULL DEFAULT 'text-embedding-004'"
                )
                db.execSQL(
                    "ALTER TABLE memory_items ADD COLUMN embeddingDimensions INTEGER NOT NULL DEFAULT 768"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE memory_items ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_memory_items_contentHash ON memory_items(contentHash)"
                )
            }
        }

        /** Migration 5→6: إنشاء جدول web_platforms */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS web_platforms (
                        id               TEXT    NOT NULL PRIMARY KEY,
                        name             TEXT    NOT NULL,
                        url              TEXT    NOT NULL,
                        iconEmoji        TEXT    NOT NULL DEFAULT '🌐',
                        requiresAccount  INTEGER NOT NULL DEFAULT 0,
                        preferredEngine  TEXT    NOT NULL DEFAULT 'GECKO',
                        isBuiltIn        INTEGER NOT NULL DEFAULT 0,
                        isEnabled        INTEGER NOT NULL DEFAULT 1,
                        sortOrder        INTEGER NOT NULL DEFAULT 100,
                        createdAt        INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        // ======== Builder ========

        fun getDatabase(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "aichat_database"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6   // ← جديد
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
