package com.example.aichat.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichat.AichatApp
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.data.model.Message
import com.example.aichat.data.model.QueryStyle
import com.example.aichat.repository.ChatRepository
import com.example.aichat.repository.ConversationRepository
import com.example.aichat.repository.EmbeddingService
import com.example.aichat.repository.FileProcessor
import com.example.aichat.repository.ImageProcessor
import com.example.aichat.repository.MemoryContextBuilder
import com.example.aichat.repository.MemoryCuratorService
import com.example.aichat.repository.MemoryRepository
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

    // ============================================================
    // Dependencies
    // ============================================================

    private val db = ChatDatabase.getInstance(application)
    private val dao = db.chatDao()
    private val memoryDao = db.memoryDao()

    private val aiSettings = AiSettings(getApplication())

    val repository = ChatRepository(application)

    private val conversationRepository = ConversationRepository(dao)

    private val memoryRepository = MemoryRepository(
        memoryDao,
        getApplication()
    )

    private val memoryContextBuilder = MemoryContextBuilder()
    private val imageProcessor = ImageProcessor(application)
    private val fileProcessor = FileProcessor(getApplication())
    private val webPageFetcher = WebPageFetcher()

    private val memoryCuratorService = MemoryCuratorService(
        settings = aiSettings,
        fallbackBuilder = memoryContextBuilder
    )

    private val embeddingService =
        EmbeddingService { aiSettings.geminiKey }

    val customRequestCount: StateFlow<Int> =
        repository.customRequestCount

    private var messagesCollectJob: Job? = null

    // ============================================================
    // State
    // ============================================================

    val conversations = conversationRepository
        .getAllConversations()
        .stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            emptyList()
        )

    private val _currentConversationId =
        MutableStateFlow<Long?>(null)

    val currentConversationId: StateFlow<Long?> =
        _currentConversationId.asStateFlow()

    private val _messages =
        MutableStateFlow<List<Message>>(emptyList())

    val messages: StateFlow<List<Message>> =
        _messages.asStateFlow()

    private val _isLoading =
        MutableStateFlow(false)

    val isLoading: StateFlow<Boolean> =
        _isLoading.asStateFlow()

    private val _error =
        MutableStateFlow<String?>(null)

    val error: StateFlow<String?> =
        _error.asStateFlow()

    private val _successMessage =
        MutableStateFlow<String?>(null)

    val successMessage: StateFlow<String?> =
        _successMessage.asStateFlow()

    private val _selectedImageBase64 =
        MutableStateFlow<String?>(null)

    val selectedImageBase64: StateFlow<String?> =
        _selectedImageBase64.asStateFlow()

    private val _memoryContext =
        MutableStateFlow("")

    val memoryContext: StateFlow<String> =
        _memoryContext.asStateFlow()

    private val _memoryAccessEnabled =
        MutableStateFlow(true)

    val memoryAccessEnabled: StateFlow<Boolean> =
        _memoryAccessEnabled.asStateFlow()

    private val _enhancedQuery =
        MutableStateFlow<String?>(null)

    val enhancedQuery: StateFlow<String?> =
        _enhancedQuery.asStateFlow()

    private val _isEnhancing =
        MutableStateFlow(false)

    val isEnhancing: StateFlow<Boolean> =
        _isEnhancing.asStateFlow()

    private val _hasMemorySource =
        MutableStateFlow(false)

    // ============================================================
    // الذاكرة
    // ============================================================

    val sharedMemories: StateFlow<List<MemoryItem>> =
        memoryRepository
            .getSharedMemories()
            .stateIn(
                viewModelScope,
                SharingStarted.Lazily,
                emptyList()
            )

    fun getConversationMemories(
        conversationId: Long
    ): Flow<List<MemoryItem>> =
        memoryRepository.getConversationMemories(
            conversationId
        )

    fun addMemory(
        content: String,
        category: String = "OTHER",
        isShared: Boolean = true,
        sourceMessageId: Long? = null
    ) {
        val cleanContent = content.trim()

        if (cleanContent.isBlank()) return

        viewModelScope.launch {
            memoryRepository.addMemory(
                content = cleanContent,
                sourceConversationId =
                    _currentConversationId.value,
                sourceMessageId = sourceMessageId,
                category = category,
                isShared = isShared
            )
        }
    }

    fun updateMemory(memory: MemoryItem) {
        viewModelScope.launch {
            memoryRepository.updateMemory(memory)
        }
    }

    fun deleteMemory(memory: MemoryItem) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(memory)
        }
    }

    suspend fun searchSharedMemories(
        query: String
    ): List<MemoryItem> =
        memoryRepository.searchSharedMemories(query)

    suspend fun getMemoryById(
        memoryId: Long
    ): MemoryItem? =
        memoryRepository.getMemoryById(memoryId)

    suspend fun getSharedMemories(): List<MemoryItem> {
        return memoryRepository.getAllSharedMemoriesList()
    }

    fun deleteConversationMemories(
        conversationId: Long
    ) {
        viewModelScope.launch {
            memoryRepository.deleteConversationMemories(
                conversationId
            )
        }
    }

    // ============================================================
    // تحضير سياق الذاكرة
    // ============================================================

    private suspend fun prepareMemoryContext(
        userText: String
    ) {
        if (userText.isBlank()) {
            _memoryContext.value = ""
            _hasMemorySource.value = false
            return
        }

        if (!_memoryAccessEnabled.value) {
            android.util.Log.d(
                "ChatViewModel",
                "⚪ Memory access disabled for this conversation"
            )

            _memoryContext.value = ""
            _hasMemorySource.value = false
            return
        }

        try {
            android.util.Log.d(
                "ChatViewModel",
                "🔍 Memory context for: " +
                    userText.take(50)
            )

            val memories =
                memoryRepository.searchSharedMemories(
                    userText
                )

            _hasMemorySource.value =
                memories.isNotEmpty()

            android.util.Log.d(
                "ChatViewModel",
                "📚 Found ${memories.size} memories"
            )

            android.util.Log.d(
                "ChatViewModel",
                "DEBUG-TEMP: curatorEnabled=" +
                    "${aiSettings.memoryCuratorEnabled}, " +
                    "hasGeminiKey=" +
                    "${aiSettings.geminiKey.isNotBlank()}, " +
                    "candidatesCount=${memories.size}"
            )

            _memoryContext.value =
                memoryCuratorService.curate(
                    userQuery = userText,
                    candidates = memories,
                    mediatorIdentityText =
                        aiSettings.mediatorIdentityText
                )

            if (memories.isNotEmpty()) {
                _error.value =
                    "DEBUG-TEMP CONTEXT:\n" +
                        _memoryContext.value.take(400)
            }

        } catch (e: Exception) {
            android.util.Log.e(
                "ChatViewModel",
                "❌ prepareMemoryContext: " +
                    e.message,
                e
            )

            _memoryContext.value = ""
            _hasMemorySource.value = false
        }
    }

    // ============================================================
    // المحادثات
    // ============================================================

    fun openConversation(
        conversationId: Long
    ) {
        _currentConversationId.value =
            conversationId

        _memoryContext.value = ""
        _hasMemorySource.value = false

        startCollecting(conversationId)

        viewModelScope.launch {
            val conv =
                conversationRepository
                    .getConversationById(
                        conversationId
                    )

            _memoryAccessEnabled.value =
                conv?.memoryAccessEnabled ?: true
        }
    }

    fun newConversation() {
        messagesCollectJob?.cancel()
        messagesCollectJob = null

        _currentConversationId.value = null
        _messages.value = emptyList()
        _selectedImageBase64.value = null
        _memoryContext.value = ""
        _hasMemorySource.value = false
        _error.value = null
        _successMessage.value = null
        _memoryAccessEnabled.value = true
        _enhancedQuery.value = null
        _isEnhancing.value = false
    }

    fun toggleMemoryAccess(
        enabled: Boolean
    ) {
        _memoryAccessEnabled.value = enabled

        val convId =
            _currentConversationId.value
                ?: return

        viewModelScope.launch {
            conversationRepository
                .updateMemoryAccess(
                    convId,
                    enabled
                )
        }
    }

    private fun startCollecting(
        conversationId: Long
    ) {
        messagesCollectJob?.cancel()

        messagesCollectJob =
            viewModelScope.launch {
                conversationRepository
                    .getMessages(conversationId)
                    .collect {
                        _messages.value = it
                    }
            }
    }

    // ============================================================
    // تحسين السؤال
    // ============================================================

    fun enhanceUserQuery(
        userText: String
    ) {
        if (userText.isBlank()) return

        if (
            aiSettings
                .mediatorIdentityText
                .isBlank()
        ) {
            _error.value =
                "⚠️ لم يتم تعيين هوية/أسلوب في الإعدادات"

            return
        }

        _isEnhancing.value = true

        viewModelScope.launch {
            try {
                android.util.Log.d(
                    "ChatViewModel",
                    "🔄 Requesting query enhancement " +
                        "(identity-only)..."
                )

                val enhanced =
                    memoryCuratorService
                        .enhanceQuery(
                            userQuery = userText,
                            mediatorIdentityText =
                                aiSettings
                                    .mediatorIdentityText
                        )

                _enhancedQuery.value =
                    enhanced

                android.util.Log.d(
                    "ChatViewModel",
                    "✅ Enhancement result: " +
                        "${enhanced.take(80)}..."
                )

            } catch (e: Exception) {
                android.util.Log.e(
                    "ChatViewModel",
                    "❌ enhanceQuery: " +
                        e.message,
                    e
                )

                _error.value =
                    "فشل تحسين السؤال: ${e.message}"

            } finally {
                _isEnhancing.value = false
            }
        }
    }

    fun refineQueryStyle(
        userText: String,
        style: QueryStyle
    ) {
        if (userText.isBlank()) return

        _isEnhancing.value = true

        viewModelScope.launch {
            try {
                android.util.Log.d(
                    "ChatViewModel",
                    "🎨 Refining style: " +
                        style.displayName
                )

                val refined =
                    memoryCuratorService
                        .refineQueryStyle(
                            userQuery = userText,
                            style = style
                        )

                _enhancedQuery.value =
                    refined

                android.util.Log.d(
                    "ChatViewModel",
                    "✅ Refined (" +
                        "${style.displayName}): " +
                        "${refined.take(80)}..."
                )

            } catch (e: Exception) {
                android.util.Log.e(
                    "ChatViewModel",
                    "❌ refineStyle: " +
                        e.message,
                    e
                )

                _error.value =
                    "فشل تحسين الصياغة: ${e.message}"

            } finally {
                _isEnhancing.value = false
            }
        }
    }

    fun updateEnhancedQuery(
        newText: String
    ) {
        _enhancedQuery.value = newText
    }

    fun clearEnhancedQuery() {
        _enhancedQuery.value = null
    }

    // ============================================================
    // إرسال الرسائل
    // ============================================================

    fun sendChatMessage(
        userText: String
    ) {
        if (_isLoading.value) return

        _isLoading.value = true

        viewModelScope.launch {
            _error.value = null

            try {
                val imageBase64 =
                    _selectedImageBase64.value

                val convId =
                    _currentConversationId.value
                        ?: run {
                            val id =
                                conversationRepository
                                    .insertConversation(
                                        Conversation(
                                            title = userText
                                                .take(50)
                                                .ifBlank {
                                                    "محادثة جديدة"
                                                },
                                            memoryAccessEnabled =
                                                _memoryAccessEnabled.value
                                        )
                                    )

                            _currentConversationId.value =
                                id

                            startCollecting(id)

                            id
                        }

                val historySnapshot =
                    conversationRepository
                        .getMessagesOnce(
                            convId
                        )

                conversationRepository
                    .insertMessage(
                        Message(
                            conversationId = convId,
                            role = "user",
                            content = userText,
                            imageBase64 =
                                imageBase64,
                            messageType = "text"
                        )
                    )

                _selectedImageBase64.value =
                    null

                var memoryContextText = ""
                var hasMemorySource = false

                if (_memoryAccessEnabled.value) {
                    try {
                        android.util.Log.d(
                            "ChatViewModel",
                            "🔍 Searching memory for: " +
                                userText.take(50)
                        )

                        val memories =
                            memoryRepository
                                .searchSharedMemories(
                                    userText
                                )

                        hasMemorySource =
                            memories.isNotEmpty()

                        android.util.Log.d(
                            "ChatViewModel",
                            "📚 Found " +
                                "${memories.size} memories"
                        )

                        // ====================================================
                        // CURATOR TEST
                        // استخدام curateSelection() مباشرة لاختبار
                        // اختيار IDs الحقيقية.
                        // ====================================================

                        val curatorResult =
                            memoryCuratorService
                                .curateSelection(
                                    userQuery = userText,
                                    candidates = memories
                                )

                        val app =
                            getApplication<Application>()
                                as AichatApp

                        app.logDebug(
                            "🧠 CURATOR selected IDs: " +
                                curatorResult.selectedIds
                        )

                        app.logDebug(
                            "🧠 CURATOR irrelevant IDs: " +
                                curatorResult.irrelevantIds
                        )

                        app.logDebug(
                            "🧠 CURATOR fallback: " +
                                curatorResult.isFallback +
                                ", reason=" +
                                curatorResult.fallbackReason
                        )

                        app.logDebug(
                            "🧠 CURATOR reasoning: " +
                                curatorResult.reasoning
                        )

                        memoryContextText =
                            curatorResult.selectedIds
                                .mapNotNull { id ->
                                    memories
                                        .firstOrNull {
                                            it.id == id
                                        }
                                        ?.content
                                }
                                .joinToString(
                                    "\n\n"
                                )

                        android.util.Log.d(
                            "ChatViewModel",
                            "✅ Curator selected " +
                                "${curatorResult.selectedIds.size} " +
                                "memories"
                        )

                    } catch (e: Exception) {
                        android.util.Log.e(
                            "ChatViewModel",
                            "❌ Memory context prep failed: " +
                                e.message,
                            e
                        )

                        memoryContextText = ""
                        hasMemorySource = false
                    }
                }

                val response =
                    repository.sendMessage(
                        history = historySnapshot,
                        userMessage = userText,
                        imageBase64 = imageBase64,
                        memoryContext =
                            memoryContextText,
                        hasMemorySource =
                            hasMemorySource
                    )

                val assistantMessage =
                    Message(
                        conversationId = convId,
                        role = "assistant",
                        content = response,
                        messageType = "text"
                    )

                conversationRepository
                    .insertMessage(
                        assistantMessage
                    )

                val title =
                    historySnapshot
                        .firstOrNull {
                            it.role == "user"
                        }
                        ?.content
                        ?: userText

                conversationRepository
                    .updateTitleAndTimestamp(
                        id = convId,
                        title = title.take(50),
                        updatedAt =
                            System.currentTimeMillis()
                    )

            } catch (e: Exception) {
                _error.value =
                    e.message
                        ?: "حدث خطأ غير معروف"

            } finally {
                _isLoading.value = false
            }
        }
    }

    // ============================================================
    // Web AI response
    // ============================================================

    fun onWebAiResponse(
        platformId: String,
        platformName: String,
        text: String,
        forceSaveToMemory: Boolean = false
    ) {
        if (text.isBlank()) return

        viewModelScope.launch {
            try {
                val cleanText =
                    text.trim()

                android.util.Log.d(
                    "ChatViewModel",
                    "🌐 Web AI response from " +
                        "$platformName: " +
                        "${cleanText.take(80)}..."
                )

                // ====================================================
                // 1. الحصول على المحادثة الحالية
                // ====================================================

                val convId =
                    _currentConversationId.value
                        ?: run {
                            val id =
                                conversationRepository
                                    .insertConversation(
                                        Conversation(
                                            title =
                                                "$platformName — Web",
                                            memoryAccessEnabled =
                                                _memoryAccessEnabled.value
                                        )
                                    )

                            _currentConversationId.value =
                                id

                            startCollecting(id)

                            id
                        }

                // ====================================================
                // 2. حفظ الرد في سجل المحادثة
                // ====================================================

                val messageId =
                    conversationRepository
                        .insertMessage(
                            Message(
                                conversationId = convId,
                                role = "assistant",
                                content = cleanText,
                                messageType = "text"
                            )
                        )

                conversationRepository
                    .updateTitleAndTimestamp(
                        id = convId,
                        title =
                            conversationRepository
                                .getMessagesOnce(
                                    convId
                                )
                                .firstOrNull {
                                    it.role == "user"
                                }
                                ?.content
                                ?.take(50)
                                ?: "$platformName — Web",
                        updatedAt =
                            System.currentTimeMillis()
                    )

                android.util.Log.d(
                    "ChatViewModel",
                    "✅ Web response saved to conversation: " +
                        "id=$messageId, " +
                        "conversation=$convId"
                )

                // ====================================================
                // 3. تحديد هل نحفظ الرد في الذاكرة المشتركة
                // ====================================================

                val shouldSaveToMemory =
                    forceSaveToMemory ||
                        aiSettings.autoSave

                if (shouldSaveToMemory) {

                    val memoryId =
                        memoryRepository
                            .addMemory(
                                content = cleanText,
                                sourceConversationId =
                                    convId,
                                sourceMessageId =
                                    messageId,
                                category = "KNOWLEDGE",
                                isShared = true
                            )

                    android.util.Log.d(
                        "ChatViewModel",
                        "🧠 Web response saved to " +
                            "SHARED MEMORY: " +
                            "memoryId=$memoryId, " +
                            "manual=$forceSaveToMemory, " +
                            "autoSave=${aiSettings.autoSave}"
                    )

                } else {

                    android.util.Log.d(
                        "ChatViewModel",
                        "⚪ Auto-save disabled — " +
                            "automatic response not saved " +
                            "to shared memory"
                    )
                }

            } catch (e: Exception) {

                android.util.Log.e(
                    "ChatViewModel",
                    "❌ onWebAiResponse: " +
                        e.message,
                    e
                )
            }
        }
    }

    // ============================================================
    // الصور
    // ============================================================

    fun selectImage(
        uri: Uri
    ) {
        viewModelScope.launch {
            try {
                val base64 =
                    imageProcessor
                        .uriToBase64(uri)

                if (base64 == null) {
                    _error.value =
                        "تعذر قراءة الصورة"

                    return@launch
                }

                _selectedImageBase64.value =
                    base64

            } catch (e: Exception) {
                _error.value =
                    "فشل تحميل الصورة: " +
                        e.message
            }
        }
    }

    fun selectBitmap(
        bitmap: Bitmap
    ) {
        viewModelScope.launch {
            try {
                _selectedImageBase64.value =
                    imageProcessor
                        .bitmapToBase64(
                            bitmap
                        )

            } catch (e: Exception) {
                _error.value =
                    "فشل تحميل الصورة: " +
                        e.message
            }
        }
    }

    fun selectFile(
        uri: Uri
    ) {
        viewModelScope.launch {
            try {
                val context =
                    getApplication<Application>()

                val mimeType =
                    context
                        .contentResolver
                        .getType(uri)
                        ?: ""

                if (mimeType.startsWith("image/")) {
                    selectImage(uri)
                } else {
                    _error.value =
                        "⚠️ الملفات غير الصورة " +
                            "غير مدعومة: " +
                            getDisplayName(uri)
                }

            } catch (e: Exception) {
                _error.value =
                    "فشل تحميل الملف: " +
                        e.message
            }
        }
    }

    // ============================================================
    // معالجة الملفات — Batch Embeddings
    // ============================================================

    fun processAndSaveFile(
        uri: Uri
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _successMessage.value = null

            try {
                android.util.Log.d(
                    "ChatViewModel",
                    "📄 Processing file: $uri"
                )

                val content =
                    fileProcessor.readFile(uri)

                if (content.isBlank()) {
                    _error.value =
                        "⚠️ الملف فارغ أو لا يحتوي " +
                            "على نص قابل للقراءة"

                    return@launch
                }

                android.util.Log.d(
                    "ChatViewModel",
                    "✅ File read: " +
                        "${content.length} chars"
                )

                val chunks =
                    if (content.length > 1000) {
                        fileProcessor.chunkText(
                            text = content,
                            maxChunkSize = 800,
                            overlap = 100
                        )
                    } else {
                        listOf(content)
                    }

                val totalChunks =
                    chunks.size

                val limitedChunks =
                    chunks.take(20)

                val newChunks =
                    mutableListOf<String>()

                var skippedCount = 0

                limitedChunks.forEach { chunk ->

                    if (
                        memoryRepository
                            .existsByHash(
                                calculateHash(chunk)
                            )
                    ) {
                        skippedCount++
                    } else {
                        newChunks.add(chunk)
                    }
                }

                android.util.Log.d(
                    "ChatViewModel",
                    "🆕 New: " +
                        "${newChunks.size}, " +
                        "Skipped: $skippedCount"
                )

                var savedCount = 0

                if (newChunks.isNotEmpty()) {

                    val embeddings =
                        try {
                            embeddingService
                                .getBatchEmbeddings(
                                    newChunks
                                )
                                .also {
                                    android.util.Log.d(
                                        "ChatViewModel",
                                        "🚀 Batch: " +
                                            "${newChunks.size} chunks " +
                                            "→ 1 API call"
                                    )
                                }

                        } catch (e: Exception) {

                            android.util.Log.w(
                                "ChatViewModel",
                                "⚠️ Batch failed, saving " +
                                    "without embeddings: " +
                                    e.message
                            )

                            newChunks.map {
                                emptyList()
                            }
                        }

                    newChunks
                        .forEachIndexed { i, chunk ->

                            val embeddingList =
                                embeddings
                                    .getOrElse(i) {
                                        emptyList()
                                    }

                            val id =
                                memoryRepository
                                    .addMemoryWithEmbedding(
                                        content = chunk,
                                        embedding =
                                            embeddingList,
                                        category =
                                            "KNOWLEDGE",
                                        isShared = true
                                    )

                            if (id > 0) {
                                savedCount++

                                android.util.Log.d(
                                    "ChatViewModel",
                                    "💾 Chunk " +
                                        "${i + 1}: saved " +
                                        "(id=$id)"
                                )
                            }
                        }
                }

                _successMessage.value =
                    buildString {

                        append(
                            "✅ تم حفظ $savedCount جزء " +
                                "في الذاكرة"
                        )

                        if (skippedCount > 0) {
                            append(
                                "\n⚠️ تم تخطي " +
                                    "$skippedCount " +
                                    "(موجودة مسبقاً)"
                            )
                        }

                        if (totalChunks > 20) {
                            append(
                                "\n📌 من أصل " +
                                    "$totalChunks " +
                                    "(الحد الأقصى 20)"
                            )
                        }

                        if (newChunks.isNotEmpty()) {
                            append(
                                "\n🚀 Batch: طلب API واحد"
                            )
                        }
                    }

            } catch (e: Exception) {

                android.util.Log.e(
                    "ChatViewModel",
                    "❌ File processing: " +
                        e.message,
                    e
                )

                _error.value =
                    "❌ ${e.message}"

            } finally {
                _isLoading.value = false
            }
        }
    }

    // ============================================================
    // حفظ صفحة ويب — Batch Embeddings
    // ============================================================

    fun saveWebPage(
        url: String
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _successMessage.value = null

            try {
                android.util.Log.d(
                    "ChatViewModel",
                    "🌐 Fetching: $url"
                )

                val result =
                    webPageFetcher
                        .fetchAndClean(url)

                android.util.Log.d(
                    "ChatViewModel",
                    "✅ Fetched: " +
                        "${result.title} " +
                        "(${result.wordCount} words)"
                )

                val fullText =
                    buildString {
                        append(result.title)
                        append("\n\n")
                        append(result.content)
                    }

                val chunks =
                    fileProcessor.chunkText(
                        text = fullText,
                        maxChunkSize = 800,
                        overlap = 100
                    )

                val totalChunks =
                    chunks.size

                val limitedChunks =
                    chunks.take(20)

                val newChunks =
                    mutableListOf<String>()

                var skippedCount = 0

                limitedChunks.forEach { chunk ->

                    if (
                        memoryRepository
                            .existsByHash(
                                calculateHash(chunk)
                            )
                    ) {
                        skippedCount++
                    } else {
                        newChunks.add(chunk)
                    }
                }

                android.util.Log.d(
                    "ChatViewModel",
                    "📦 Total: $totalChunks, " +
                        "New: ${newChunks.size}, " +
                        "Skipped: $skippedCount"
                )

                var savedCount = 0

                if (newChunks.isNotEmpty()) {

                    val embeddings =
                        try {
                            embeddingService
                                .getBatchEmbeddings(
                                    newChunks
                                )

                        } catch (e: Exception) {

                            android.util.Log.w(
                                "ChatViewModel",
                                "⚠️ Batch failed: " +
                                    e.message
                            )

                            newChunks.map {
                                emptyList()
                            }
                        }

                    newChunks
                        .forEachIndexed { i, chunk ->

                            val embeddingList =
                                embeddings
                                    .getOrElse(i) {
                                        emptyList()
                                    }

                            val id =
                                memoryRepository
                                    .addMemoryWithEmbedding(
                                        content = chunk,
                                        embedding =
                                            embeddingList,
                                        category = "WEB",
                                        isShared = true
                                    )

                            if (id > 0) {
                                savedCount++
                            }
                        }
                }

                _successMessage.value =
                    buildString {

                        append(
                            "✅ تم حفظ $savedCount جزء من:\n"
                        )

                        append(
                            "📄 ${result.title}"
                        )

                        if (skippedCount > 0) {
                            append(
                                "\n⚠️ تم تخطي " +
                                    "$skippedCount " +
                                    "(موجودة مسبقاً)"
                            )
                        }

                        if (totalChunks > 20) {
                            append(
                                "\n📌 من أصل " +
                                    "$totalChunks " +
                                    "(الحد الأقصى 20)"
                            )
                        }

                        append(
                            "\n📊 ${result.wordCount} كلمة"
                        )

                        if (newChunks.isNotEmpty()) {
                            append(
                                "\n🚀 Batch: طلب API واحد"
                            )
                        }
                    }

            } catch (e: Exception) {

                android.util.Log.e(
                    "ChatViewModel",
                    "❌ Web fetch: " +
                        e.message,
                    e
                )

                _error.value =
                    when {
                        e.message?.contains(
                            "Unable to resolve host"
                        ) == true ->
                            "❌ لا يوجد اتصال بالإنترنت"

                        e.message?.contains(
                            "timeout",
                            ignoreCase = true
                        ) == true ->
                            "❌ انتهت مهلة الاتصال"

                        e.message?.contains(
                            "URL"
                        ) == true ->
                            "❌ رابط غير صالح"

                        e.message?.contains(
                            "محتوى"
                        ) == true ->
                            "❌ ${e.message}"

                        else ->
                            "❌ فشل جلب الصفحة: " +
                                e.message
                    }

            } finally {
                _isLoading.value = false
            }
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    fun clearSelectedImage() {
        _selectedImageBase64.value = null
    }

    fun clearError() {
        _error.value = null
    }

    fun clearSuccessMessage() {
        _successMessage.value = null
    }

    private fun calculateHash(
        content: String
    ): String =
        try {
            MessageDigest
                .getInstance("SHA-256")
                .digest(
                    content.toByteArray(
                        Charsets.UTF_8
                    )
                )
                .joinToString("") {
                    "%02x".format(it)
                }
        } catch (e: Exception) {
            ""
        }

    private fun getDisplayName(
        uri: Uri
    ): String =
        try {
            val context =
                getApplication<Application>()

            context
                .contentResolver
                .query(
                    uri,
                    arrayOf(
                        android.provider.OpenableColumns
                            .DISPLAY_NAME
                    ),
                    null,
                    null,
                    null
                )
                ?.use { cursor ->

                    if (cursor.moveToFirst()) {

                        val index =
                            cursor.getColumnIndex(
                                android.provider
                                    .OpenableColumns
                                    .DISPLAY_NAME
                            )

                        if (index >= 0) {
                            cursor.getString(index)
                        } else {
                            null
                        }

                    } else {
                        null
                    }

                }
                ?: uri.lastPathSegment
                ?: "ملف"

        } catch (e: Exception) {
            uri.lastPathSegment ?: "ملف"
        }

    // ============================================================
    // حذف المحادثة
    // ============================================================

    fun deleteConversation(
        conversation: Conversation
    ) {
        viewModelScope.launch {

            conversationRepository
                .deleteMessages(
                    conversation.id
                )

            conversationRepository
                .deleteConversation(
                    conversation
                )

            if (
                _currentConversationId.value ==
                    conversation.id
            ) {
                newConversation()
            }
        }
    }
}
