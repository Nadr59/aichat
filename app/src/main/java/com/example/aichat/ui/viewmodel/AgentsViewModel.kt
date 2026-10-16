package com.example.aichat.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.data.model.AgentProfile
import com.example.aichat.repository.AgentRepository
import com.example.aichat.repository.agents.AgentImportService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AgentsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ChatDatabase.getInstance(application)
    private val agentDao = db.agentDao()

    private val repo = AgentRepository(agentDao)
    private val importer = AgentImportService(application)

    val agents: StateFlow<List<AgentProfile>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun importAgent(uri: Uri, onDone: (Result<AgentProfile>) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val agent = importer.importFromUri(uri)
                repo.upsert(agent)
                agent
            }.also(onDone)
        }
    }

    fun deleteAgent(agent: AgentProfile, onDone: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                repo.deleteById(agent.id)
            }.also { onDone(it.map { Unit }) }
        }
    }

    suspend fun getById(id: String): AgentProfile? = repo.getById(id)

    fun upsert(agent: AgentProfile, onDone: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repo.upsert(agent) }.also { onDone(it.map { Unit }) }
        }
    }
}
