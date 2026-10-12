package com.example.aichat.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.aichat.data.model.MemoryItem
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemory(memory: MemoryItem): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemories(memories: List<MemoryItem>): List<Long>

    @Update
    suspend fun updateMemory(memory: MemoryItem)

    @Delete
    suspend fun deleteMemory(memory: MemoryItem)

    @Query(
        """
        SELECT * FROM memory_items
        WHERE isShared = 1
        ORDER BY updatedAt DESC
        """
    )
    fun getSharedMemories(): Flow<List<MemoryItem>>

    @Query(
        """
        SELECT * FROM memory_items
        WHERE isShared = 1
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getAllSharedMemories(): List<MemoryItem>

    @Query(
        """
        SELECT * FROM memory_items
        WHERE contentHash = :hash
        LIMIT 1
        """
    )
    suspend fun getByHash(hash: String): MemoryItem?

    @Query(
        """
        SELECT * FROM memory_items
        WHERE contentHash IN (:hashes)
        """
    )
    suspend fun getByHashes(hashes: List<String>): List<MemoryItem>

    @Query(
        """
        SELECT * FROM memory_items
        WHERE isShared = 1
        AND content LIKE '%' || :query || '%'
        ORDER BY updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchByContent(query: String, limit: Int = 10): List<MemoryItem>

    @Query(
        """
        SELECT * FROM memory_items
        WHERE sourceConversationId = :conversationId
        AND isShared = 0
        ORDER BY updatedAt DESC
        """
    )
    fun getConversationMemories(conversationId: Long): Flow<List<MemoryItem>>

    @Query(
        """
        SELECT * FROM memory_items
        WHERE id = :memoryId
        LIMIT 1
        """
    )
    suspend fun getMemoryById(memoryId: Long): MemoryItem?

    @Query(
        """
        DELETE FROM memory_items
        WHERE sourceConversationId = :conversationId
        AND isShared = 0
        """
    )
    suspend fun deleteConversationMemories(conversationId: Long)

    @Query(
        """
        SELECT COUNT(*) FROM memory_items
        WHERE isShared = 1
        """
    )
    suspend fun getSharedMemoriesCount(): Int

    @Query(
        """
        DELETE FROM memory_items
        WHERE id IN (
            SELECT id FROM memory_items
            WHERE isShared = 1
            ORDER BY updatedAt ASC
            LIMIT :count
        )
        """
    )
    suspend fun deleteOldestMemories(count: Int)
}
