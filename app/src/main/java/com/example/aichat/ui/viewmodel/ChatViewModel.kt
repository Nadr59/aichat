package com.example.aichat.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.Message
import com.example.aichat.repository.ChatRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ChatRepository(application)

    val conversations: StateFlow<List<Conversation>> =
        repository.getAllConversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadMessages(conversationId: Long) {
        viewModelScope.launch {
            repository.getMessages(conversationId).collect { msgs ->
                _messages.value = msgs
            }
        }
    }

    fun createConversation(title: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repository.createConversation(title)
            onCreated(id)
        }
    }

    fun sendMessage(conversationId: Long, content: String) {
        viewModelScope.launch {
            _isLoading.value = true
            repository.sendMessage(conversationId, content)
            _isLoading.value = false
        }
    }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            repository.deleteConversation(conversation)
        }
    }
}
