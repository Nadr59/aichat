package com.example.aichat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.aichat.data.model.AgentProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {

    @Query("SELECT * FROM agents ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AgentProfile>>

    @Query("SELECT * FROM agents WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AgentProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(agent: AgentProfile)

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
