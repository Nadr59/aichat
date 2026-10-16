package com.example.aichat.repository

import com.example.aichat.data.local.AgentDao
import com.example.aichat.data.model.AgentProfile
import kotlinx.coroutines.flow.Flow

class AgentRepository(
    private val dao: AgentDao
) {
    fun observeAll(): Flow<List<AgentProfile>> = dao.observeAll()
    suspend fun getById(id: String): AgentProfile? = dao.getById(id)
    suspend fun upsert(agent: AgentProfile) = dao.upsert(agent)
    suspend fun deleteById(id: String) = dao.deleteById(id)
}
