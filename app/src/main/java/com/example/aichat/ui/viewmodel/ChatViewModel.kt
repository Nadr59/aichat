package com.example.aichat.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.data.model.Message
import com.example.aichat.repository.ChatRepository
import com.example.aichat.repository.ConversationRepository
import com.example.aichat.repository.ImageProcessor
import com.example.aichat.repository.MemoryContextBuilder
import com.example.aichat.repository.MemoryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ChatDatabase.getInstance(application)
    private val dao = db.chatDao()
    private val memoryDao = db.memoryDao()

    val repository = ChatRepository(application)

    private val conversationRepository =
        ConversationRepository(dao)

    private val memoryRepository =
    MemoryRepository(
        memoryDao,
        getApplication()  // ✅ Context
    )

    private val memoryContextBuilder =
        MemoryContextBuilder()

    private val imageProcessor =
        ImageProcessor(application)
        
    private val fileProcessor = FileProcessor(getApplication())

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

    private val _selectedImageBase64 =
        MutableStateFlow<String?>(null)

    val selectedImageBase64: StateFlow<String?> =
        _selectedImageBase64.asStateFlow()

    // ============================================================
    // سياق الذاكرة
    // ============================================================

    private val _memoryContext =
        MutableStateFlow("")

    val memoryContext: StateFlow<String> =
        _memoryContext.asStateFlow()

    // ============================================================
    // الذاكرة
    // ============================================================

    val sharedMemories: StateFlow<List<MemoryItem>> =
        memoryRepository.getSharedMemories()
            .stateIn(
                viewModelScope,
                SharingStarted.Lazily,
                emptyList()
            )

    fun getConversationMemories(
        conversationId: Long
    ): Flow<List<MemoryItem>> {
        return memoryRepository.getConversationMemories(
            conversationId
        )
    }

    fun addMemory(
        content: String,
        category: String = "OTHER",
        isShared: Boolean = true,
        sourceMessageId: Long? = null
    ) {
        val cleanContent = content.trim()

        if (cleanContent.isBlank()) {
            return
        }

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
    ): List<MemoryItem> {

        return memoryRepository.searchSharedMemories(
            query = query,
            useSemanticAnalysis = true,
            ollamaUrl = "http://127.0.0.1:11434"
        )
    }

    suspend fun getMemoryById(
        memoryId: Long
    ): MemoryItem? {

        return memoryRepository.getMemoryById(
            memoryId
        )
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

    /**
     * يبحث عن الذكريات المرتبطة بالسؤال الحالي
     * ثم يحولها إلى سياق نصي.
     *
     * يتم تجهيز السياق هنا قبل إرسال الطلب
     * إلى ChatRepository.
     *
     * يستخدم البحث الهجين:
     * - بحث محلي سريع أولاً
     * - تحليل دلالي بـ Qwen إذا كان التطابق ضعيفاً
     */
    private suspend fun prepareMemoryContext(
    userText: String
) {
    if (userText.isBlank()) {
        _memoryContext.value = ""
        return
    }

    try {
        val memories = memoryRepository.searchSharedMemories(
            query = userText,
            useSemanticAnalysis = false,
            ollamaUrl = "http://127.0.0.1:11434"
        )

        android.util.Log.d("ChatViewModel", "📚 Found ${memories.size} memories")

        _memoryContext.value = memoryContextBuilder.build(memories)

        android.util.Log.d("ChatViewModel", "✅ Memory context length: ${_memoryContext.value.length}")

    } catch (e: Exception) {
        android.util.Log.e("ChatViewModel", "❌ prepareMemoryContext failed: ${e.message}", e)
        _memoryContext.value = ""
    }
    }
    
    

    // ============================================================
    // المحادثات
    // ============================================================

    fun openConversation(
        conversationId: Long
    ) {
        _currentConversationId.value = conversationId
        _memoryContext.value = ""
        startCollecting(conversationId)
    }

    fun newConversation() {

        messagesCollectJob?.cancel()
        messagesCollectJob = null

        _currentConversationId.value = null
        _messages.value = emptyList()
        _selectedImageBase64.value = null
        _memoryContext.value = ""
        _error.value = null
    }

    // ============================================================
    // مراقبة رسائل Room
    // ============================================================

    private fun startCollecting(
        conversationId: Long
    ) {
        messagesCollectJob?.cancel()

        messagesCollectJob =
            viewModelScope.launch {

                conversationRepository
                    .getMessages(conversationId)
                    .collect { msgs ->

                        _messages.value = msgs
                    }
            }
    }

    // ============================================================
    // إرسال
    // ============================================================

    fun sendMessage(
        userText: String
    ) {
        if (_isLoading.value) {
            return
        }

        val image =
            _selectedImageBase64.value

        if (
            userText.isBlank() &&
            image == null
        ) {
            return
        }

        sendChatMessage(userText)
    }

    // ============================================================
    // المحادثة النصية وتحليل الصور
    // ============================================================

    private fun sendChatMessage(
        userText: String
    ) {

        if (_isLoading.value) {
            return
        }

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
                                                }
                                        )
                                    )

                            _currentConversationId.value = id

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
                            imageBase64 = imageBase64,
                            messageType = "text"
                        )
                    )

                _selectedImageBase64.value = null

                // تجهيز سياق الذاكرة قبل إرسال الطلب
                // يستخدم البحث الهجين (محلي + Qwen)
                prepareMemoryContext(
                    userText
                )

                val response =
                    repository.sendMessage(
                        history = historySnapshot,
                        userMessage = userText,
                        imageBase64 = imageBase64,
                        memoryContext = _memoryContext.value
                    )

                conversationRepository
                    .insertMessage(
                        Message(
                            conversationId = convId,
                            role = "assistant",
                            content = response,
                            messageType = "text"
                        )
                    )

                val title =
                    historySnapshot
                        .firstOrNull {
                            it.role == "user"
                        }
                        ?.content
                        ?: userText

                conversationRepository
                    .updateConversation(
                        Conversation(
                            id = convId,
                            title = title.take(50),
                            updatedAt =
                                System.currentTimeMillis()
                        )
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
    // الصور
    // ============================================================

    fun selectImage(
        uri: Uri
    ) {

        viewModelScope.launch {

            try {

                val base64 =
                    imageProcessor.uriToBase64(
                        uri
                    )

                if (base64 == null) {

                    _error.value =
                        "تعذر قراءة الصورة"

                    return@launch
                }

                _selectedImageBase64.value =
                    base64

            } catch (e: Exception) {

                _error.value =
                    "فشل تحميل الصورة: ${e.message}"
            }
        }
    }

    fun selectBitmap(
        bitmap: Bitmap
    ) {

        viewModelScope.launch {

            try {

                _selectedImageBase64.value =
                    imageProcessor.bitmapToBase64(
                        bitmap
                    )

            } catch (e: Exception) {

                _error.value =
                    "فشل تحميل الصورة: ${e.message}"
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
                    context.contentResolver
                        .getType(uri)
                        ?: ""

                if (mimeType.startsWith("image/")) {

                    selectImage(uri)

                } else {

                    val name =
                        uri.lastPathSegment
                            ?.substringAfterLast("/")
                            ?: "ملف"

                    _error.value =
                        "⚠️ الملفات غير الصورة غير مدعومة: $name"
                }

            } catch (e: Exception) {

                _error.value =
                    "فشل تحميل الملف: ${e.message}"
            }
        }
    }

    

/**
 * معالجة ملف ورفعه للذاكرة
 */
fun processAndSaveFile(uri: Uri) {
    viewModelScope.launch {
        _isLoading.value = true
        _error.value = null

        try {
            android.util.Log.d("ChatViewModel", "📄 Processing file: $uri")

            // قراءة الملف
            val content = fileProcessor.readFile(uri)

            if (content.isBlank()) {
                _error.value = "⚠️ الملف فارغ"
                return@launch
            }

            android.util.Log.d("ChatViewModel", "✅ File read: ${content.length} characters")

            // تقسيم إلى أجزاء إذا كان طويلاً
            val chunks = if (content.length > 2000) {
                fileProcessor.chunkText(content, maxChunkSize = 1500, overlap = 200)
            } else {
                listOf(content)
            }

            android.util.Log.d("ChatViewModel", "📦 Split into ${chunks.size} chunks")

            // حفظ كل جزء في الذاكرة
            chunks.forEachIndexed { index, chunk ->
                val title = if (chunks.size > 1) {
                    "ملف - جزء ${index + 1}/${chunks.size}"
                } else {
                    "ملف"
                }

                memoryRepository.addMemory(
                    content = chunk,
                    category = "KNOWLEDGE",
                    isShared = true
                )

                android.util.Log.d("ChatViewModel", "✅ Saved chunk ${index + 1}")
            }

            _error.value = "✅ تم حفظ ${chunks.size} ${if (chunks.size > 1) "أجزاء" else "جزء"} في الذاكرة"

        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "❌ File processing failed: ${e.message}")
            _error.value = "❌ ${e.message}"
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

    // ============================================================
    // حذف المحادثة
    // ============================================================

    fun deleteConversation(
        conversation: Conversation
    ) {

        viewModelScope.launch {

            conversationRepository.deleteMessages(
                conversation.id
            )

            conversationRepository.deleteConversation(
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
