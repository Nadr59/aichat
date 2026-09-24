package com.example.aichat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.data.model.Message
import com.example.aichat.data.model.WebPlatform

@Database(
    entities = [
        Conversation::class,
        Message::class,
        MemoryItem::class,
        WebPlatform::class
    ],
    version = 8,          // ✅ 7 → 8
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao
    abstract fun webPlatformDao(): WebPlatformDao

    companion object {

        @Volatile
        private var INSTANCE: ChatDatabase? = null

        // ── Migrations ──────────────────────────────────────────────────────

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
                    "ALTER TABLE memory_items " +
                    "ADD COLUMN embeddingModel TEXT NOT NULL DEFAULT 'text-embedding-004'"
                )
                db.execSQL(
                    "ALTER TABLE memory_items " +
                    "ADD COLUMN embeddingDimensions INTEGER NOT NULL DEFAULT 768"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE memory_items " +
                    "ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_memory_items_contentHash " +
                    "ON memory_items(contentHash)"
                )
            }
        }

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

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE web_platforms " +
                    "ADD COLUMN aiMessageSelector TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE web_platforms " +
                    "ADD COLUMN memoryEnabled INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        // ✅ جديد — تحكم في الوصول للذاكرة لكل محادثة على حدة
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversations " +
                    "ADD COLUMN memoryAccessEnabled INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        // ── Builder ─────────────────────────────────────────────────────────

        private fun buildDatabase(context: Context): ChatDatabase =
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
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8   // ✅ مضاف
                )
                .build()

        fun getDatabase(context: Context): ChatDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }

        fun getInstance(context: Context): ChatDatabase = getDatabase(context)
    }
}
