// app/src/main/java/com/example/aichat/data/local/ChatDatabase.kt
package com.example.aichat.data.local

import android.content.Context
import android.database.Cursor
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.aichat.data.model.AgentProfile
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.data.model.Message
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.data.model.Workspace

@Database(
    entities = [
        Conversation::class,
        Message::class,
        MemoryItem::class,
        WebPlatform::class,
        AgentProfile::class, // ✅ v10
        Workspace::class,    // ✅ v11 (Workspace Summary)
    ],
    version = 12, // ✅ 10 → 11 (Workspaces + workspaceId/parentConversationId)
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao
    abstract fun webPlatformDao(): WebPlatformDao
    abstract fun agentDao(): AgentDao // ✅ v10

    companion object {

        @Volatile
        private var INSTANCE: ChatDatabase? = null

        // ── Helpers ──────────────────────────────────────────────

        private fun Cursor.useAndClose(block: (Cursor) -> Boolean): Boolean =
            try {
                block(this)
            } finally {
                close()
            }

        private fun SupportSQLiteDatabase.tableExists(table: String): Boolean {
            return query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table)
            ).useAndClose { c -> c.moveToFirst() }
        }

        private fun SupportSQLiteDatabase.columnExists(table: String, column: String): Boolean {
            if (!tableExists(table)) return false
            return query("PRAGMA table_info(`$table`)").useAndClose { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    val n = c.getString(nameIdx)
                    if (n == column) return@useAndClose true
                }
                false
            }
        }

        private fun SupportSQLiteDatabase.addColumnIfMissing(
            table: String,
            column: String,
            sqlTypeAndConstraints: String
        ) {
            if (!columnExists(table, column)) {
                execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $sqlTypeAndConstraints")
            }
        }

        // ── Migrations ───────────────────────────────────────────

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
                db.addColumnIfMissing("memory_items", "embedding", "TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing(
                    "memory_items",
                    "embeddingModel",
                    "TEXT NOT NULL DEFAULT 'text-embedding-004'"
                )
                db.addColumnIfMissing(
                    "memory_items",
                    "embeddingDimensions",
                    "INTEGER NOT NULL DEFAULT 768"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("memory_items", "contentHash", "TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_items_contentHash ON memory_items(contentHash)")
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
                db.addColumnIfMissing("web_platforms", "aiMessageSelector", "TEXT NOT NULL DEFAULT ''")
                db.addColumnIfMissing("web_platforms", "memoryEnabled", "INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("conversations", "memoryAccessEnabled", "INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("web_platforms", "inputSelector", "TEXT NOT NULL DEFAULT ''")
                db.addColumnIfMissing("web_platforms", "sendSelector", "TEXT NOT NULL DEFAULT ''")
                db.addColumnIfMissing("web_platforms", "sendWithEnter", "INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("conversations", "agentId", "TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agents (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        avatarUri TEXT,
                        description TEXT NOT NULL DEFAULT '',
                        personality TEXT NOT NULL DEFAULT '',
                        scenario TEXT NOT NULL DEFAULT '',
                        firstMessage TEXT NOT NULL DEFAULT '',
                        exampleMessages TEXT NOT NULL DEFAULT '',
                        systemPrompt TEXT NOT NULL DEFAULT '',
                        postHistoryInstructions TEXT NOT NULL DEFAULT '',
                        creatorNotes TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )

                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_agentId ON conversations(agentId)")
            }
        }

        // ✅ v11: Workspaces + conversation.workspaceId/parentConversationId
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {

                // 1) Create workspaces table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workspaces (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        scopeKey TEXT NOT NULL,
                        title TEXT NOT NULL DEFAULT '',
                        summary TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )

                // unique scopeKey (workspace per scope مثل api:model أو web:platform)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_workspaces_scopeKey ON workspaces(scopeKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workspaces_updatedAt ON workspaces(updatedAt)")

                // 2) Add workspaceId + parentConversationId to conversations
                db.addColumnIfMissing("conversations", "workspaceId", "INTEGER")
                db.addColumnIfMissing("conversations", "parentConversationId", "INTEGER")

                // 3) Indexes for fast filtering/tree navigation
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_workspaceId ON conversations(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_parentConversationId ON conversations(parentConversationId)")
            }
        }
        val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumnIfMissing(
            "conversations",
            "branchContextMessageCount",
            "INTEGER NOT NULL DEFAULT 0"
        )
    }
        }

        // ── Builder ──────────────────────────────────────────────

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
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12
                )
                .build()

        fun getDatabase(context: Context): ChatDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }

        fun getInstance(context: Context): ChatDatabase = getDatabase(context)
    }
}
