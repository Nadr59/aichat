package com.example.aichat.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichat.AichatApp
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.data.model.Message
import com.example.aichat.data.model.QueryStyle
import com.example.aichat.repository.AgentPromptBuilder
import com.example.aichat.repository.AgentRepository
import com.example.aichat.repository.ChatRepository
import com.example.aichat.repository.ConversationRepository
import com.example.aichat.repository.EmbeddingService
import com.example.aichat.repository.FileProcessor
import com.example.aichat.repository.ImageProcessor
import com.example.aichat.repository.MemoryContextBuilder
import com.example.aichat.repository.MemoryCuratorService
import com.example.aichat.repository.MemoryRepository
import com.example.aichat.repository.MemoryWindowBuilder
import com.example.aichat.repository.WebPageFetcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.MessageDigest

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ChatDatabase.getInstance(application)
    private val chatDao = db.chatDao()
    private val memoryDao = db.memoryDao()
    private val agentDao = db.agentDao()

    private val aiSettings = AiSettings(application)

    val repository = ChatRepository(application)
    val customRequestCount: StateFlow<Int> = repository.customRequestCount

    private val conversationRepository = ConversationRepository(chatDao)
    private val memoryRepository = MemoryRepository(memoryDao, application)

    // ✅ Agents
    private val agentRepository = AgentRepository(agentDao)

    private val memoryContextBuilder = MemoryContextBuilder()
    private val memoryWindowBuilder = MemoryWindowBuilder()
    private val memoryCuratorService = MemoryCuratorService(aiSettings, memoryContextBuilder)

    private val embeddingService = EmbeddingService { aiSettings.geminiKey }
    private val fileProcessor = FileProcessor(application)
    private val webPageFetcher = WebPageFetcher()
    private val imageProcessor = ImageProcessor(application)

    private var messagesCollectJob: Job? = null

    val conversations: StateFlow<List<Conversation>> =
        conversationRepository.getAllConversations()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _currentConversationId = MutableStateFlow<Long?>(null)
    val currentConversationId: StateFlow<Long?> = _currentConversationId.asStateFlow()

    private val _currentAgentId = MutableStateFlow<String?>(null)
    val currentAgentId: StateFlow<String?> = _currentAgentId.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    private val _selectedImageBase64 = MutableStateFlow<String?>(null)
    val selectedImageBase64: StateFlow<String?> = _selectedImageBase64.asStateFlow()

    private val _memoryAccessEnabled = MutableStateFlow(true)
    val memoryAccessEnabled: StateFlow<Boolean> = _memoryAccessEnabled.asStateFlow()

    private val _memoryContext = MutableStateFlow("")
    val memoryContext: StateFlow<String> = _memoryContext.asStateFlow()

    private val _enhancedQuery = MutableStateFlow<String?>(null)
    val enhancedQuery: StateFlow<String?> = _enhancedQuery.asStateFlow()

    private val _isEnhancing = MutableStateFlow(false)
    val isEnhancing: StateFlow<Boolean> = _isEnhancing.asStateFlow()

    val sharedMemories: StateFlow<List<MemoryItem>> =
        memoryRepository.getSharedMemories()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun getConversationMemories(conversationId: Long): Flow<List<MemoryItem>> =
        memoryRepository.getConversationMemories(conversationId)

    fun addMemory(
        content: String,
        category: String = "OTHER",
        isShared: Boolean = true,
        sourceMessageId: Long? = null
    ) {
        val clean = content.trim()
        if (clean.isBlank()) return

        val convId = _currentConversationId.value
        if (!isShared && convId == null) return

        val sourceConvId = if (isShared) null else convId

        viewModelScope.launch {
            memoryRepository.addMemory(
                content = clean,
                sourceConversationId = sourceConvId,
                sourceMessageId = sourceMessageId,
                category = category,
                isShared = isShared
            )
        }
    }

    fun addManualMemory(
        content: String,
        category: String = "OTHER",
        isShared: Boolean = true,
        conversationId: Long? = _currentConversationId.value
    ) {
        val clean = content.trim()
        if (clean.isBlank()) return

        val finalShared = if (!isShared && conversationId == null) true else isShared
        val sourceConvId = if (finalShared) null else conversationId

        viewModelScope.launch {
            memoryRepository.addMemory(
                content = clean,
                sourceConversationId = sourceConvId,
                sourceMessageId = null,
                category = category,
                isShared = finalShared
            )
        }
    }

    fun updateMemory(memory: MemoryItem) { viewModelScope.launch { memoryRepository.updateMemory(memory) } }
    fun deleteMemory(memory: MemoryItem) { viewModelScope.launch { memoryRepository.deleteMemory(memory) } }

    suspend fun searchSharedMemories(query: String): List<MemoryItem> =
        memoryRepository.searchSharedMemories(query)

    suspend fun getMemoryById(memoryId: Long): MemoryItem? =
        memoryRepository.getMemoryById(memoryId)

    fun deleteConversationMemories(conversationId: Long) {
        viewModelScope.launch { memoryRepository.deleteConversationMemories(conversationId) }
    }

    // =========================
    // Conversations
    // =========================

    fun openConversation(conversationId: Long) {
        _currentConversationId.value = conversationId
        _memoryContext.value = ""
        _enhancedQuery.value = null

        startCollecting(conversationId)

        viewModelScope.launch {
            val conv = conversationRepository.getConversationById(conversationId)
            _memoryAccessEnabled.value = conv?.memoryAccessEnabled ?: true
            _currentAgentId.value = conv?.agentId
        }
    }

    fun newConversation() {
        messagesCollectJob?.cancel()
        messagesCollectJob = null

        _currentConversationId.value = null
        _currentAgentId.value = null
        _messages.value = emptyList()
        _selectedImageBase64.value = null
        _memoryContext.value = ""
        _error.value = null
        _successMessage.value = null
        _memoryAccessEnabled.value = true
        _enhancedQuery.value = null
        _isEnhancing.value = false
    }

    fun toggleMemoryAccess(enabled: Boolean) {
        _memoryAccessEnabled.value = enabled
        val convId = _currentConversationId.value ?: return
        viewModelScope.launch { conversationRepository.updateMemoryAccess(convId, enabled) }
    }

    // ✅ جديد: تعيين Agent للمحادثة
    fun setConversationAgent(agentId: String?) {
        _currentAgentId.value = agentId
        val convId = _currentConversationId.value ?: return
        viewModelScope.launch { conversationRepository.updateAgentId(convId, agentId) }
    }

    private fun startCollecting(conversationId: Long) {
        messagesCollectJob?.cancel()
        messagesCollectJob =
            viewModelScope.launch {
                conversationRepository.getMessages(conversationId).collect { _messages.value = it }
            }
    }

    // =========================
    // Query enhancement
    // =========================

    fun enhanceUserQuery(userText: String) {
        val text = userText.trim()
        if (text.isBlank()) return

        _isEnhancing.value = true
        viewModelScope.launch {
            try {
                _enhancedQuery.value = memoryCuratorService.enhanceQuery(
                    userQuery = text,
                    mediatorIdentityText = aiSettings.mediatorIdentityText
                )
            } catch (e: Exception) {
                _error.value = "فشل تحسين السؤال: ${e.message}"
            } finally {
                _isEnhancing.value = false
            }
        }
    }

    fun refineQueryStyle(userText: String, style: QueryStyle) {
        val text = userText.trim()
        if (text.isBlank()) return

        _isEnhancing.value = true
        viewModelScope.launch {
            try {
                _enhancedQuery.value = memoryCuratorService.refineQueryStyle(text, style)
            } catch (e: Exception) {
                _error.value = "فشل تحسين الصياغة: ${e.message}"
            } finally {
                _isEnhancing.value = false
            }
        }
    }

    fun updateEnhancedQuery(newText: String) { _enhancedQuery.value = newText }
    fun clearEnhancedQuery() { _enhancedQuery.value = null }

    // =========================
    // Sending messages (API)
    // =========================

    fun sendChatMessage(userText: String) {
        val text = userText.trim()
        if (text.isBlank()) return
        if (_isLoading.value) return

        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val imageBase64 = _selectedImageBase64.value

                val convId = _currentConversationId.value ?: run {
                    val id = conversationRepository.insertConversation(
                        Conversation(
                            title = text.take(50).ifBlank { "محادثة جديدة" },
                            memoryAccessEnabled = _memoryAccessEnabled.value,
                            agentId = _currentAgentId.value // ✅ مهم
                        )
                    )
                    _currentConversationId.value = id
                    startCollecting(id)
                    id
                }

                val historySnapshot = conversationRepository.getMessagesOnce(convId)

                conversationRepository.insertMessage(
                    Message(
                        conversationId = convId,
                        role = "user",
                        content = text,
                        imageBase64 = imageBase64,
                        messageType = "text"
                    )
                )
                _selectedImageBase64.value = null

                var memoryContextText = ""
                var hasMemorySource = false

                if (_memoryAccessEnabled.value) {
                    val memories = memoryRepository.searchSharedMemories(text)
                    if (memories.isNotEmpty()) {
                        val curatorResult = memoryCuratorService.curateSelection(text, memories)

                        (getApplication<Application>() as? AichatApp)?.apply {
                            logDebug("🧠 CURATOR selected=${curatorResult.selectedIds} fallback=${curatorResult.isFallback} reason=${curatorResult.fallbackReason}")
                            logDebug("🧠 CURATOR reasoning=${curatorResult.reasoning}")
                        }

                        memoryContextText = memoryWindowBuilder.build(
                            selectedIds = curatorResult.selectedIds,
                            candidates = memories
                        )
                        hasMemorySource = memoryContextText.isNotBlank()
                    }
                }

                _memoryContext.value = memoryContextText

                // ✅ Agent prompt
                val agentPrompt = _currentAgentId.value
                    ?.let { agentRepository.getById(it) }
                    ?.let { AgentPromptBuilder.build(it) }
                    .orEmpty()

                val response = repository.sendMessage(
                    history = historySnapshot,
                    userMessage = text,
                    imageBase64 = imageBase64,
                    memoryContext = memoryContextText,
                    hasMemorySource = hasMemorySource,
                    agentPrompt = agentPrompt
                )

                conversationRepository.insertMessage(
                    Message(
                        conversationId = convId,
                        role = "assistant",
                        content = response,
                        messageType = "text"
                    )
                )

                val title = historySnapshot.firstOrNull { it.role == "user" }?.content ?: text

                conversationRepository.updateTitleAndTimestamp(
                    id = convId,
                    title = title.take(50),
                    updatedAt = System.currentTimeMillis()
                )

            } catch (e: Exception) {
                _error.value = e.message ?: "حدث خطأ غير معروف"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // =========================
    // Web AI response (capture)
    // =========================

    fun onWebAiResponse(
        platformId: String,
        platformName: String,
        text: String,
        forceSaveToMemory: Boolean = false
    ) {
        val clean = text.trim()
        if (clean.isBlank()) return

        viewModelScope.launch {
            try {
                val convId = _currentConversationId.value ?: run {
                    val id = conversationRepository.insertConversation(
                        Conversation(
                            title = "$platformName — Web",
                            memoryAccessEnabled = _memoryAccessEnabled.value
                        )
                    )
                    _currentConversationId.value = id
                    startCollecting(id)
                    id
                }

                val messageId = conversationRepository.insertMessage(
                    Message(
                        conversationId = convId,
                        role = "assistant",
                        content = clean,
                        messageType = "text"
                    )
                )

                conversationRepository.updateTitleAndTimestamp(
                    id = convId,
                    title = "$platformName — Web",
                    updatedAt = System.currentTimeMillis()
                )

                val shouldSave = forceSaveToMemory || aiSettings.autoSave
                if (shouldSave) {
                    memoryRepository.addMemory(
                        content = clean,
                        sourceConversationId = null,
                        sourceMessageId = messageId,
                        category = "KNOWLEDGE",
                        isShared = true
                    )
                }
            } catch (_: Exception) {}
        }
    }

    // =========================
    // Images + File/Web saving
    // =========================

    fun selectImage(uri: Uri) {
        viewModelScope.launch {
            try {
                _selectedImageBase64.value = imageProcessor.uriToBase64(uri)
                    ?: run { _error.value = "تعذر قراءة الصورة"; null }
            } catch (e: Exception) {
                _error.value = "فشل تحميل الصورة: ${e.message}"
            }
        }
    }

    fun selectBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            try { _selectedImageBase64.value = imageProcessor.bitmapToBase64(bitmap) }
            catch (e: Exception) { _error.value = "فشل تحميل الصورة: ${e.message}" }
        }
    }

    fun processAndSaveFile(uri: Uri) { /* كما عندك بدون تغيير */ viewModelScope.launch { /* ... */ } }
    fun saveWebPage(url: String) { /* كما عندك بدون تغيير */ viewModelScope.launch { /* ... */ } }

    fun clearSelectedImage() { _selectedImageBase64.value = null }
    fun clearError() { _error.value = null }
    fun clearSuccessMessage() { _successMessage.value = null }

    private fun calculateHash(content: String): String {
        return try {
            MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            "nohash-${content.hashCode().toString(16)}-${content.length}"
        }
    }

    private fun getDisplayName(uri: Uri): String { /* كما عندك */ return uri.lastPathSegment ?: "ملف" }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepository.deleteMessages(conversation.id)
            conversationRepository.deleteConversation(conversation)
            if (_currentConversationId.value == conversation.id) newConversation()
        }
    }
}
