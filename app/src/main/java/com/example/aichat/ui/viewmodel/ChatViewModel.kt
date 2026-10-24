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
import com.example.aichat.data.model.Workspace
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Locale

class ChatViewModel(application: Application) : AndroidViewModel(application) {

private val db = ChatDatabase.getInstance(application)
private val chatDao = db.chatDao()
private val memoryDao = db.memoryDao()
private val agentDao = db.agentDao()

private val aiSettings = AiSettings(application)

val repository = ChatRepository(application)
val customRequestCount: StateFlow = repository.customRequestCount

private val conversationRepository = ConversationRepository(chatDao)
private val memoryRepository = MemoryRepository(memoryDao, application)
private val agentRepository = AgentRepository(agentDao)

private val memoryContextBuilder = MemoryContextBuilder()
private val memoryWindowBuilder = MemoryWindowBuilder()
private val memoryCuratorService = MemoryCuratorService(aiSettings, memoryContextBuilder)

private val embeddingService = EmbeddingService { aiSettings.geminiKey }
private val fileProcessor = FileProcessor(application)
private val webPageFetcher = WebPageFetcher()
private val imageProcessor = ImageProcessor(application)

private var messagesCollectJob: Job? = null

val conversations: StateFlow<List> =
conversationRepository.getAllConversations()
.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

private val _currentConversationId = MutableStateFlow<Long?>(null)
val currentConversationId: StateFlow<Long?> = _currentConversationId.asStateFlow()

private val _currentParentConversationId = MutableStateFlow<Long?>(null)
val currentParentConversationId: StateFlow<Long?> = _currentParentConversationId.asStateFlow()

private val _currentAgentId = MutableStateFlow<String?>(null)
val currentAgentId: StateFlow<String?> = _currentAgentId.asStateFlow()

private val _currentWorkspaceId = MutableStateFlow<Long?>(null)
val currentWorkspaceId: StateFlow<Long?> = _currentWorkspaceId.asStateFlow()

private val _workspaceSummary = MutableStateFlow("")
val workspaceSummary: StateFlow = _workspaceSummary.asStateFlow()

private val _messages = MutableStateFlow<List>(emptyList())
val messages: StateFlow<List> = _messages.asStateFlow()

private val _isLoading = MutableStateFlow(false)
val isLoading: StateFlow = _isLoading.asStateFlow()

private val _error = MutableStateFlow<String?>(null)
val error: StateFlow<String?> = _error.asStateFlow()

private val _successMessage = MutableStateFlow<String?>(null)
val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

private val _selectedImageBase64 = MutableStateFlow<String?>(null)
val selectedImageBase64: StateFlow<String?> = _selectedImageBase64.asStateFlow()

private val _memoryAccessEnabled = MutableStateFlow(true)
val memoryAccessEnabled: StateFlow = _memoryAccessEnabled.asStateFlow()

private val _memoryContext = MutableStateFlow("")
val memoryContext: StateFlow = _memoryContext.asStateFlow()

private val _enhancedQuery = MutableStateFlow<String?>(null)
val enhancedQuery: StateFlow<String?> = _enhancedQuery.asStateFlow()

private val _isEnhancing = MutableStateFlow(false)
val isEnhancing: StateFlow = _isEnhancing.asStateFlow()

val sharedMemories: StateFlow<List> =
memoryRepository.getSharedMemories()
.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

fun getConversationMemories(conversationId: Long): Flow<List> =
memoryRepository.getConversationMemories(conversationId)

fun observeChildConversations(parentConversationId: Long): Flow<List> =
conversationRepository.observeChildConversations(parentConversationId)

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

fun updateMemory(memory: MemoryItem) {
viewModelScope.launch { memoryRepository.updateMemory(memory) }
}

fun deleteMemory(memory: MemoryItem) {
viewModelScope.launch { memoryRepository.deleteMemory(memory) }
}

suspend fun searchSharedMemories(query: String): List =
memoryRepository.searchSharedMemories(query)

suspend fun getMemoryById(memoryId: Long): MemoryItem? =
memoryRepository.getMemoryById(memoryId)

fun deleteConversationMemories(conversationId: Long) {
viewModelScope.launch { memoryRepository.deleteConversationMemories(conversationId) }
}

// ============================================================
// Workspace helpers
// ============================================================

private fun apiScopeKey(): String {
val provider = aiSettings.provider.trim().lowercase(Locale.ROOT)
val model = aiSettings.getActiveModel().trim().lowercase(Locale.ROOT)
return "api:$provider:$model"
}

private fun apiWorkspaceTitle(): String {
val provider = aiSettings.provider.trim()
val model = aiSettings.getActiveModel().trim()
return "API • $provider • $model"
}

private suspend fun setCurrentWorkspaceById(workspaceId: Long?) {
_currentWorkspaceId.value = workspaceId
val ws = workspaceId?.let { conversationRepository.getWorkspaceById(it) }
_workspaceSummary.value = ws?.summary.orEmpty()
}

private suspend fun ensureWorkspaceForConversation(conversationId: Long): Workspace {
val conv = conversationRepository.getConversationById(conversationId)
val existingWsId = conv?.workspaceId
val existingWs = existingWsId?.let { conversationRepository.getWorkspaceById(it) }

if (existingWs != null) {          
    setCurrentWorkspaceById(existingWs.id)          
    return existingWs          
}          
      
val ws = conversationRepository.getOrCreateWorkspace(          
    scopeKey = apiScopeKey(),          
    title = apiWorkspaceTitle()          
)          
      
if (conv != null) {          
    conversationRepository.updateConversationWorkspace(          
        conversationId = conversationId,          
        workspaceId = ws.id,          
        parentConversationId = conv.parentConversationId          
    )          
}          
      
setCurrentWorkspaceById(ws.id)          
return ws

}

private fun buildWorkspacePrompt(summary: String): String {
val s = summary.trim()
if (s.isBlank()) return ""
return buildString {
appendLine("WORKSPACE_SUMMARY:")
appendLine(s)
}.trim()
}

private suspend fun generateUpdatedWorkspaceSummary(
previousSummary: String,
userText: String,
assistantText: String
): String {
val prev = previousSummary.trim().take(2000)
val u = userText.trim().take(1500)
val a = assistantText.trim().take(2000)

val prompt = buildString {          
    appendLine("مهمتك: تحديث ملخص جلسة (Workspace Summary) لاستمرار السياق بين محادثات متعددة.")          
    appendLine()          
    appendLine("الملخص السابق (قد يكون فارغًا):")          
    appendLine(prev.ifBlank { "(فارغ)" })          
    appendLine()          
    appendLine("التبادل الجديد:")          
    appendLine("USER: ${u.ifBlank { "(غير متوفر)" }}")          
    appendLine("ASSISTANT: $a")          
    appendLine()          
    appendLine("قواعد الإخراج:")          
    appendLine("- أعد الملخص المحدّث فقط دون شرح إضافي.")          
    appendLine("- اجعله بالعربية.")          
    appendLine("- حافظ على الحقائق المهمة والتفضيلات والمهام والقرارات والأسماء.")          
    appendLine("- لا تكرر نص الحوار حرفيًا، لخّصه.")          
    appendLine("- طول أقصى ~1200 حرف.")          
}.trim()          
      
return repository.sendMessage(          
    history = emptyList(),          
    userMessage = prompt,          
    imageBase64 = null,          
    memoryContext = "",          
    hasMemorySource = false,          
    agentPrompt = ""          
).trim()

}

private fun updateWorkspaceSummaryAsync(
workspaceId: Long,
userText: String,
assistantText: String
) {
val prev = _workspaceSummary.value
viewModelScope.launch {
runCatching {
val updated = generateUpdatedWorkspaceSummary(prev, userText, assistantText)
if (updated.isNotBlank()) {
conversationRepository.updateWorkspaceSummary(workspaceId, updated)
_workspaceSummary.value = updated
}
}
}
}

private fun updateWorkspaceSummaryAsyncForWorkspace(
workspaceId: Long,
userText: String,
assistantText: String
) {
viewModelScope.launch {
runCatching {
val prev = conversationRepository.getWorkspaceById(workspaceId)?.summary.orEmpty()
val updated = generateUpdatedWorkspaceSummary(prev, userText, assistantText)
if (updated.isNotBlank()) {
conversationRepository.updateWorkspaceSummary(workspaceId, updated)
if (_currentWorkspaceId.value == workspaceId) _workspaceSummary.value = updated
}
}
}
}

// =========================
// Conversations (API)
// =========================

fun openConversation(conversationId: Long) {
_currentConversationId.value = conversationId
_memoryContext.value = ""
_enhancedQuery.value = null

startCollecting(conversationId)          
      
viewModelScope.launch {          
    val conv = conversationRepository.getConversationById(conversationId)          
    _currentParentConversationId.value = conv?.parentConversationId          
    _memoryAccessEnabled.value = conv?.memoryAccessEnabled ?: true          
    _currentAgentId.value = conv?.agentId          
      
    val wsId = conv?.workspaceId          
    if (wsId != null) setCurrentWorkspaceById(wsId)          
    else runCatching { ensureWorkspaceForConversation(conversationId) }          
}

}

fun branchFromCurrentConversation() {
val parentId = _currentConversationId.value
if (parentId == null) {
_error.value = "لا يمكن إنشاء فرع قبل فتح/إنشاء محادثة."
return
}
if (_isLoading.value) return

viewModelScope.launch {          
    runCatching {          
        val parent = conversationRepository.getConversationById(parentId)          
            ?: throw IllegalStateException("المحادثة الأصل غير موجودة")          
      
        val ws = ensureWorkspaceForConversation(parentId)          
      
        val branchTitle = buildString {          
            val base = parent.title.trim().ifBlank { "محادثة" }.take(45)          
            append("فرع: ")          
            append(base)          
        }.take(60)          
      
        val newId = conversationRepository.insertConversation(          
            Conversation(          
                title = branchTitle.ifBlank { "فرع جديد" },          
                memoryAccessEnabled = parent.memoryAccessEnabled,          
                agentId = parent.agentId,          
                workspaceId = ws.id,          
                parentConversationId = parentId          
            )          
        )          
      
        conversationRepository.updateTitleAndTimestamp(          
            id = newId,          
            title = branchTitle.ifBlank { "فرع جديد" },          
            updatedAt = System.currentTimeMillis()          
        )          
      
        _selectedImageBase64.value = null          
        _memoryContext.value = ""          
        _enhancedQuery.value = null          
      
        openConversation(newId)          
    }.onFailure { e ->          
        _error.value = "فشل إنشاء فرع: ${e.message ?: "خطأ غير معروف"}"          
    }          
}

}

/**

- ينشئ فرعًا من الرسالة المختارة، وينسخ كل الرسائل السابقة لها

- بما فيها الرسالة المختارة، مع إنشاء معرّفات جديدة للرسائل.
  */
  fun branchFromMessage(messageId: Long) {
  val parentId = _currentConversationId.value

  if (parentId == null) {
  _error.value = "لا يمكن إنشاء فرع قبل فتح محادثة."
  return
  }

  if (_isLoading.value) {
  _error.value = "انتظر انتهاء الرد الحالي قبل إنشاء فرع."
  return
  }

  viewModelScope.launch {
  runCatching {
  val parent = conversationRepository.getConversationById(parentId)
  ?: throw IllegalStateException("المحادثة الأصلية غير موجودة.")

  val originalMessages = conversationRepository.getMessagesOnce(parentId)          
       val selectedIndex = originalMessages.indexOfFirst { it.id == messageId }          

       if (selectedIndex < 0) {          
           throw IllegalStateException("لم يتم العثور على الرسالة المختارة.")          
       }          

       val messagesToCopy = originalMessages.take(selectedIndex + 1)          
       val ws = ensureWorkspaceForConversation(parentId)          

       val baseTitle = parent.title.trim().ifBlank { "محادثة" }.take(45)          
       val branchTitle = "فرع: $baseTitle".take(60)          

       val newId = conversationRepository.insertConversation(          
           Conversation(          
               title = branchTitle,          
               memoryAccessEnabled = parent.memoryAccessEnabled,          
               agentId = parent.agentId,          
               workspaceId = ws.id,          
               parentConversationId = parentId,          
               branchContextMessageCount = messagesToCopy.size          
           )          
       )          

       conversationRepository.updateTitleAndTimestamp(          
           id = newId,          
           title = branchTitle,          
           updatedAt = System.currentTimeMillis()          
       )          

       messagesToCopy.forEach { message ->          
           conversationRepository.insertMessage(          
               message.copy(          
                   id = 0,          
                   conversationId = newId          
               )          
           )          
       }          

       _selectedImageBase64.value = null          
       _memoryContext.value = ""          
       _enhancedQuery.value = null          
       _error.value = null          
       _successMessage.value = "تم إنشاء فرع من الرسالة المختارة."          

       openConversation(newId)          
   }.onFailure { e ->          
       _error.value = "فشل إنشاء الفرع: ${e.message ?: "خطأ غير معروف"}"          
   }

  }
  }

/**

- Reset workspace بالكامل (API workspace الحالي).

- يمسح المحادثات والفروع داخل نفس workspace وملخصه.

- لا يمسح Shared Memories.
  */
  fun resetCurrentWorkspaceFully() {
  if (_isLoading.value) return

  val convId = _currentConversationId.value
  viewModelScope.launch {
  runCatching {
  val workspaceIdToReset: Long = when {
  convId != null -> {
  val conv = conversationRepository.getConversationById(convId)
  conv?.workspaceId ?: ensureWorkspaceForConversation(convId).id
  }
  _currentWorkspaceId.value != null -> _currentWorkspaceId.value!!
  else -> {
  conversationRepository.getOrCreateWorkspace(
  scopeKey = apiScopeKey(),
  title = apiWorkspaceTitle()
  ).id
  }
  }

  messagesCollectJob?.cancel()          
       messagesCollectJob = null          

       conversationRepository.resetWorkspaceFully(workspaceIdToReset)          
       newConversation()          
       setCurrentWorkspaceById(workspaceIdToReset)          

       _successMessage.value = "✅ تم تصفير Workspace بالكامل (محادثات + ملخص)."          
   }.onFailure { e ->          
       _error.value = "فشل تصفير Workspace: ${e.message ?: "خطأ غير معروف"}"          
   }

  }
  }

fun newConversation() {
messagesCollectJob?.cancel()
messagesCollectJob = null

_currentConversationId.value = null          
_currentParentConversationId.value = null          
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
val id = _currentConversationId.value ?: return
viewModelScope.launch { conversationRepository.updateMemoryAccess(id, enabled) }
}

fun setConversationAgent(agentId: String?) {
_currentAgentId.value = agentId
val id = _currentConversationId.value ?: return
viewModelScope.launch { conversationRepository.updateAgentId(id, agentId) }
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

fun updateEnhancedQuery(newText: String) {
_enhancedQuery.value = newText
}

fun clearEnhancedQuery() {
_enhancedQuery.value = null
}

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
      
        val ws = conversationRepository.getOrCreateWorkspace(          
            scopeKey = apiScopeKey(),          
            title = apiWorkspaceTitle()          
        )          
        setCurrentWorkspaceById(ws.id)          
      
        val convId = _currentConversationId.value ?: run {          
            val id = conversationRepository.insertConversation(          
                Conversation(          
                    title = text.take(50).ifBlank { "محادثة جديدة" },          
                    memoryAccessEnabled = _memoryAccessEnabled.value,          
                    agentId = _currentAgentId.value,          
                    workspaceId = ws.id,          
                    parentConversationId = null          
                )          
            )          
            _currentConversationId.value = id          
            startCollecting(id)          
            id          
        }          
      
        runCatching { ensureWorkspaceForConversation(convId) }          
      
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
      
        val agentPrompt = _currentAgentId.value          
            ?.let { agentRepository.getById(it) }          
            ?.let { AgentPromptBuilder.build(it) }          
            .orEmpty()          
      
        val wsPrompt = buildWorkspacePrompt(_workspaceSummary.value)          
      
        val combinedAgentPrompt = listOfNotNull(          
            agentPrompt.takeIf { it.isNotBlank() },          
            wsPrompt.takeIf { it.isNotBlank() }          
        ).joinToString("\n\n").trim()          
      
        val response = repository.sendMessage(          
            history = historySnapshot,          
            userMessage = text,          
            imageBase64 = imageBase64,          
            memoryContext = memoryContextText,          
            hasMemorySource = hasMemorySource,          
            agentPrompt = combinedAgentPrompt          
        )          
      
        conversationRepository.insertMessage(          
            Message(          
                conversationId = convId,          
                role = "assistant",          
                content = response,          
                messageType = "text"          
            )          
        )          
      
        _currentWorkspaceId.value?.let { workspaceId ->          
            updateWorkspaceSummaryAsync(workspaceId, text, response)          
        }          
      
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
// Web — GLOBAL WORKSPACE
// =========================

private fun webGlobalScopeKey(): String = "web:global"
private fun webGlobalWorkspaceTitle(): String = "WEB • Global"
private fun webDefaultConversationTitle(): String = "Web"

private data class PendingWebRequest(
val conversationId: Long,
val workspaceId: Long,
val userText: String
)

private val pendingWebRequests =
mutableMapOf<String, ArrayDeque>()

private val _lastWebUserText = MutableStateFlow<String?>(null)
private val _currentWebConversationId = MutableStateFlow<Long?>(null)
val currentWebConversationId: StateFlow<Long?> = _currentWebConversationId.asStateFlow()

private val _webWorkspaceId = MutableStateFlow<Long?>(null)
val webWorkspaceId: StateFlow<Long?> = _webWorkspaceId.asStateFlow()

val webConversations: StateFlow<List> =
combine(conversations, webWorkspaceId) { all, wsId ->
if (wsId == null) emptyList()
else all.filter { it.workspaceId == wsId }
}.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

init {
viewModelScope.launch {
runCatching {
val ws = conversationRepository.getOrCreateWorkspace(
scopeKey = webGlobalScopeKey(),
title = webGlobalWorkspaceTitle()
)
_webWorkspaceId.value = ws.id
}
}
}

private suspend fun ensureWebWorkspace(): Workspace {
val ws = conversationRepository.getOrCreateWorkspace(
scopeKey = webGlobalScopeKey(),
title = webGlobalWorkspaceTitle()
)
_webWorkspaceId.value = ws.id
return ws
}

fun openWebConversation(conversationId: Long) {
_currentWebConversationId.value = conversationId
_lastWebUserText.value = null
}

fun clearWebConversationSelection() {
_currentWebConversationId.value = null
_lastWebUserText.value = null
}

fun newWebConversation(title: String? = null) {
viewModelScope.launch {
runCatching {
val ws = ensureWebWorkspace()
val now = System.currentTimeMillis()

val finalTitle = title?.trim().orEmpty().ifBlank { webDefaultConversationTitle() }.take(60)          
        val newId = conversationRepository.insertConversation(          
            Conversation(          
                title = finalTitle,          
                memoryAccessEnabled = true,          
                agentId = aiSettings.webAgentIdOrNull,          
                workspaceId = ws.id,          
                parentConversationId = null          
            )          
        )          
      
        conversationRepository.updateTitleAndTimestamp(          
            id = newId,          
            title = finalTitle,          
            updatedAt = now          
        )          
      
        _currentWebConversationId.value = newId          
        _successMessage.value = "✅ تم إنشاء محادثة ويب جديدة."          
    }.onFailure { e ->          
        _error.value = "فشل إنشاء محادثة ويب: ${e.message ?: "خطأ غير معروف"}"          
    }          
}

}

fun branchFromCurrentWebConversation() {
val parentId = _currentWebConversationId.value
if (parentId == null) {
_error.value = "لا يمكن إنشاء فرع قبل اختيار محادثة ويب."
return
}
if (_isLoading.value) return

viewModelScope.launch {          
    runCatching {          
        val ws = ensureWebWorkspace()          
        val parent = conversationRepository.getConversationById(parentId)          
            ?: throw IllegalStateException("محادثة الويب الأصل غير موجودة")          
      
        val branchTitle = buildString {          
            val base = parent.title.trim().ifBlank { webDefaultConversationTitle() }.take(45)          
            append("فرع: ")          
            append(base)          
        }.take(60)          
      
        val newId = conversationRepository.insertConversation(          
            Conversation(          
                title = branchTitle.ifBlank { "فرع جديد" },          
                memoryAccessEnabled = true,          
                agentId = aiSettings.webAgentIdOrNull,          
                workspaceId = ws.id,          
                parentConversationId = parentId          
            )          
        )          
      
        conversationRepository.updateTitleAndTimestamp(          
            id = newId,          
            title = branchTitle.ifBlank { "فرع جديد" },          
            updatedAt = System.currentTimeMillis()          
        )          
      
        _currentWebConversationId.value = newId          
        _successMessage.value = "✅ تم إنشاء فرع ويب."          
    }.onFailure { e ->          
        _error.value = "فشل إنشاء فرع ويب: ${e.message ?: "خطأ غير معروف"}"          
    }          
}

}

/**

- ينشئ فرع ويب من الرسالة المختارة، وينسخ سياق المحادثة حتى تلك الرسالة.
  */
  fun branchFromWebMessage(messageId: Long) {
  val parentId = _currentWebConversationId.value

  if (parentId == null) {
  _error.value = "لا توجد محادثة ويب مفتوحة لإنشاء فرع منها."
  return
  }

  if (_isLoading.value) {
  _error.value = "انتظر انتهاء العملية الحالية قبل إنشاء الفرع."
  return
  }

  viewModelScope.launch {
  runCatching {
  val parent = conversationRepository.getConversationById(parentId)
  ?: throw IllegalStateException("المحادثة الأصلية غير موجودة.")

  val originalMessages =          
           conversationRepository.getMessagesOnce(parentId)          

       val selectedIndex =          
           originalMessages.indexOfFirst { it.id == messageId }          

       if (selectedIndex < 0) {          
           throw IllegalStateException("الرسالة المختارة غير موجودة في المحادثة.")          
       }          

       val messagesToCopy = originalMessages.take(selectedIndex + 1)          
       val workspace = ensureWebWorkspace()          
       val branchTitle = "فرع: ${          
           parent.title.trim()          
               .ifBlank { webDefaultConversationTitle() }          
               .take(45)          
       }".take(60)          

       val newId = conversationRepository.insertConversation(          
           Conversation(          
               title = branchTitle,          
               memoryAccessEnabled = parent.memoryAccessEnabled,          
               agentId = parent.agentId,          
               workspaceId = parent.workspaceId ?: workspace.id,          
               parentConversationId = parent.id,          
               branchContextMessageCount = messagesToCopy.size          
           )          
       )          

       conversationRepository.updateTitleAndTimestamp(          
           id = newId,          
           title = branchTitle,          
           updatedAt = System.currentTimeMillis()          
       )          

       messagesToCopy.forEach { message ->          
           conversationRepository.insertMessage(          
               message.copy(          
                   id = 0,          
                   conversationId = newId          
               )          
           )          
       }          

       _currentWebConversationId.value = newId          
       _lastWebUserText.value = null          
       _error.value = null          
       _successMessage.value = "تم إنشاء فرع ويب من الرسالة المختارة."          
   }.onFailure { e ->          
       _error.value = "فشل إنشاء فرع ويب: ${          
           e.message ?: "خطأ غير معروف"          
       }"          
   }

  }
  }

/**

- ينشئ فرعًا من الرسالة المحددة، ثم يُنتج ملخصًا خاصًا

- بالانتقال اعتمادًا على الرسائل حتى نقطة التفريع فقط.

- 

- يعيد نص السياق الجاهز للحقن في المنصة الهدف.
  */
  suspend fun createWebHandoffFromMessage(messageId: Long): String {
  val parentId = _currentWebConversationId.value
  ?: throw IllegalStateException("لا توجد محادثة ويب محددة.")

  val parent = conversationRepository.getConversationById(parentId)
  ?: throw IllegalStateException("المحادثة الأصلية غير موجودة.")

  val originalMessages = conversationRepository.getMessagesOnce(parentId)
  val selectedIndex = originalMessages.indexOfFirst { it.id == messageId }

  if (selectedIndex < 0) {
  throw IllegalStateException("الرسالة المحددة غير موجودة في المحادثة.")
  }

  val messagesToCopy = originalMessages.take(selectedIndex + 1)
  if (messagesToCopy.isEmpty()) {
  throw IllegalStateException("لا توجد رسائل لإنشاء السياق.")
  }

  val workspace = ensureWebWorkspace()
  val branchTitle = "متابعة: ${
  parent.title.trim()
  .ifBlank { webDefaultConversationTitle() }
  .take(42)
  }".take(60)

  val branchId = conversationRepository.insertConversation(
  Conversation(
  title = branchTitle,
  memoryAccessEnabled = parent.memoryAccessEnabled,
  agentId = parent.agentId,
  workspaceId = parent.workspaceId ?: workspace.id,
  parentConversationId = parent.id,
  branchContextMessageCount = messagesToCopy.size
  )
  )

  conversationRepository.updateTitleAndTimestamp(
  id = branchId,
  title = branchTitle,
  updatedAt = System.currentTimeMillis()
  )

  messagesToCopy.forEach { message ->
  conversationRepository.insertMessage(
  message.copy(
  id = 0,
  conversationId = branchId
  )
  )
  }

  // تثبيت الفرع قبل العودة إلى الشاشة.
  _currentWebConversationId.value = branchId
  _lastWebUserText.value = null
  _error.value = null
  _successMessage.value = "تم إنشاء فرع متابعة للانتقال إلى المنصة الأخرى."

  // نستخدم الرسائل السابقة لنقطة التفريع فقط، وليس ملخص Workspace العام.
  val transcript = messagesToCopy.joinToString("\n\n") { message ->
  val role = if (message.role == "user") "المستخدم" else "المساعد"
  "$role: ${message.content.trim().take(1800)}"
  }.take(9000)

  val prompt = """
  أنت تلخص سياق محادثة لنقله إلى منصة ذكاء اصطناعي أخرى.

  لخص المعلومات التالية لتسهيل متابعة النقاش من النقطة نفسها.        

   قواعد الملخص:        
   - استخدم العربية.        
   - احتفظ بالموضوع والسؤال الأساسي والحقائق والأسماء والقرارات المهمة.        
   - احتفظ بما لم يُحسم بعد والأسئلة التي تحتاج إلى متابعة.        
   - ميّز بين الحقائق والادعاءات والآراء.        
   - لا تخترع معلومات أو تستكمل الحوار من عندك.        
   - لا تضف موضوعات من محادثات أخرى.        
   - أخرج الملخص فقط، بحد أقصى 1800 حرف.        

   الرسائل:        
   $transcript

  """.trimIndent()

  val summary = repository.sendMessage(
  history = emptyList(),
  userMessage = prompt,
  imageBase64 = null,
  memoryContext = "",
  hasMemorySource = false,
  agentPrompt = ""
  ).trim()

  if (summary.isBlank()) {
  throw IllegalStateException(
  "أُنشئ الفرع، لكن تعذر توليد ملخص السياق. يمكنك إعادة المحاولة."
  )
  }

  return summary.take(2200)
  }

fun resetWebGlobalWorkspaceFully() {
if (_isLoading.value) return

viewModelScope.launch {          
    runCatching {          
        val ws = ensureWebWorkspace()          
        conversationRepository.resetWorkspaceFully(ws.id)          
      
        _currentWebConversationId.value = null          
        _lastWebUserText.value = null          
      
        _successMessage.value = "✅ تم تصفير Workspace الويب بالكامل (web:global)."          
    }.onFailure { e ->          
        _error.value = "فشل تصفير Workspace الويب: ${e.message ?: "خطأ غير معروف"}"          
    }          
}

}

private fun buildWebTitle(platformName: String?, seedText: String?): String {
val p = platformName?.trim().orEmpty()
val s = seedText?.trim().orEmpty()
val suffix = if (s.isNotBlank()) " • ${s.take(40)}" else ""
val base = if (p.isNotBlank()) "Web: $p" else webDefaultConversationTitle()
return (base + suffix).trim().take(60)
}

private suspend fun ensureWebConversation(
workspaceId: Long,
suggestedTitleForNewConversation: String
): Long {
val existing = _currentWebConversationId.value
if (existing != null) {
val conv = conversationRepository.getConversationById(existing)
if (conv != null) {
if (conv.workspaceId == workspaceId) return existing
if (conv.workspaceId == null) {
conversationRepository.updateConversationWorkspace(
conversationId = existing,
workspaceId = workspaceId,
parentConversationId = conv.parentConversationId
)
return existing
}
}
}

val newId = conversationRepository.insertConversation(          
    Conversation(          
        title = suggestedTitleForNewConversation.ifBlank { webDefaultConversationTitle() },          
        memoryAccessEnabled = true,          
        agentId = aiSettings.webAgentIdOrNull,          
        workspaceId = workspaceId,          
        parentConversationId = null          
    )          
)          
_currentWebConversationId.value = newId          
return newId

}

fun onWebUserMessageSent(userText: String, platformName: String? = null) {
val clean = userText.trim()
if (clean.isBlank()) return

_lastWebUserText.value = clean          
      
viewModelScope.launch {          
    runCatching {          
        val ws = ensureWebWorkspace()          
        val convId = ensureWebConversation(          
            workspaceId = ws.id,          
            suggestedTitleForNewConversation = buildWebTitle(platformName, clean)          
        )          
      
        conversationRepository.insertMessage(          
            Message(          
                conversationId = convId,          
                role = "user",          
                content = clean,          
                messageType = "text"          
            )          
        )          
      
        val now = System.currentTimeMillis()          
        val conv = conversationRepository.getConversationById(convId)          
        val currentTitle = conv?.title.orEmpty().trim()          
        val computedTitle =          
            if (currentTitle.isBlank() || currentTitle == webDefaultConversationTitle()) {          
                buildWebTitle(platformName, clean)          
            } else currentTitle          
      
        conversationRepository.updateTitleAndTimestamp(          
            id = convId,          
            title = computedTitle.take(60).ifBlank { webDefaultConversationTitle() },          
            updatedAt = now          
        )          
    }          
}

}

/**
 * يشغّل تسجيل سؤال الويب ضمن عمر ChatViewModel،
 * فلا يُلغى التسجيل لمجرد مغادرة الشاشة للتركيب.
 */
fun onWebUserMessageSentAndTrackInViewModelScope(
    userText: String,
    platformId: String,
    platformName: String? = null
): Deferred<Boolean> = viewModelScope.async {
    registerWebUserMessageAndTrack(
        userText = userText,
        platformId = platformId,
        platformName = platformName
    )
}

/**
 * للتوافق مع أي استدعاءات حالية تستخدم الدالة suspend.
 */
suspend fun onWebUserMessageSentAndTrack(
    userText: String,
    platformId: String,
    platformName: String? = null
): Boolean {
    return registerWebUserMessageAndTrack(
        userText = userText,
        platformId = platformId,
        platformName = platformName
    )
}

private suspend fun registerWebUserMessageAndTrack(
    userText: String,
    platformId: String,
    platformName: String? = null
): Boolean {
    val clean = userText.trim()
    val platformKey = platformId.trim().lowercase(Locale.ROOT)

    if (clean.isBlank() || platformKey.isBlank()) return false

    return try {
        val ws = ensureWebWorkspace()

        val convId = ensureWebConversation(
            workspaceId = ws.id,
            suggestedTitleForNewConversation =
                buildWebTitle(platformName, clean)
        )

        conversationRepository.insertMessage(
            Message(
                conversationId = convId,
                role = "user",
                content = clean,
                messageType = "text"
            )
        )

        val conv = conversationRepository.getConversationById(convId)
        val currentTitle = conv?.title.orEmpty().trim()

        val computedTitle =
            if (
                currentTitle.isBlank() ||
                currentTitle == webDefaultConversationTitle()
            ) {
                buildWebTitle(platformName, clean)
            } else {
                currentTitle
            }

        conversationRepository.updateTitleAndTimestamp(
            id = convId,
            title = computedTitle.take(60)
                .ifBlank { webDefaultConversationTitle() },
            updatedAt = System.currentTimeMillis()
        )

        synchronized(pendingWebRequests) {
            val queue = pendingWebRequests.getOrPut(platformKey) {
                ArrayDeque()
            }

            queue.addLast(
                PendingWebRequest(
                    conversationId = convId,
                    workspaceId = ws.id,
                    userText = clean
                )
            )
        }

        _lastWebUserText.value = clean
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _error.value =
            "فشل تسجيل سؤال الويب: ${e.message ?: "خطأ غير معروف"}"
        false
    }
}

fun onWebAiResponse(
platformId: String,
platformName: String,
text: String,
forceSaveToMemory: Boolean = false
) {
val clean = text.trim()
val platformKey = platformId.trim().lowercase(Locale.ROOT)

if (clean.isBlank()) return          
      
viewModelScope.launch {          
    try {          
        val pending = synchronized(pendingWebRequests) {          
            val queue = pendingWebRequests[platformKey]          
      
            if (queue.isNullOrEmpty()) {          
                null          
            } else {          
                val request = queue.removeFirst()          
      
                if (queue.isEmpty()) {          
                    pendingWebRequests.remove(platformKey)          
                }          
      
                request          
            }          
        }          
      
        val target = if (pending != null) {          
            pending          
        } else {          
            // توافق مع الالتقاط اليدوي أو الردود التي لا يقابلها طلب مسجل.          
            val ws = ensureWebWorkspace()          
            val convId = ensureWebConversation(          
                workspaceId = ws.id,          
                suggestedTitleForNewConversation =          
                    buildWebTitle(platformName, null)          
            )          
      
            PendingWebRequest(          
                conversationId = convId,          
                workspaceId = ws.id,          
                userText = _lastWebUserText.value.orEmpty()          
            )          
        }          
      
        val messageId = conversationRepository.insertMessage(          
            Message(          
                conversationId = target.conversationId,          
                role = "assistant",          
                content = clean,          
                messageType = "text"          
            )          
        )          
      
        val conv = conversationRepository.getConversationById(          
            target.conversationId          
        )          
      
        val title = conv?.title?.trim()          
            .orEmpty()          
            .ifBlank { buildWebTitle(platformName, null) }          
      
        conversationRepository.updateTitleAndTimestamp(          
            id = target.conversationId,          
            title = title.take(60),          
            updatedAt = System.currentTimeMillis()          
        )          
      
        if (forceSaveToMemory || aiSettings.autoSave) {          
            memoryRepository.addMemory(          
                content = clean,          
                sourceConversationId = null,          
                sourceMessageId = messageId,          
                category = "KNOWLEDGE",          
                isShared = true          
            )          
        }          
      
        updateWorkspaceSummaryAsyncForWorkspace(          
            workspaceId = target.workspaceId,          
            userText = target.userText,          
            assistantText = clean          
        )          
      
        if (_lastWebUserText.value == target.userText) {          
            _lastWebUserText.value = null          
        }          
    } catch (e: Exception) {          
        (getApplication<Application>() as? AichatApp)          
            ?.logDebug(          
                "WEB_RESPONSE_SAVE_ERROR: ${e.message}"          
            )          
    }          
}

}

// =========================
// Images + File/Web saving
// =========================

fun selectImage(uri: Uri) {
viewModelScope.launch {
try {
_selectedImageBase64.value = imageProcessor.uriToBase64(uri)
?: run {
_error.value = "تعذر قراءة الصورة"
null
}
} catch (e: Exception) {
_error.value = "فشل تحميل الصورة: ${e.message}"
}
}
}

fun selectBitmap(bitmap: Bitmap) {
viewModelScope.launch {
try {
_selectedImageBase64.value = imageProcessor.bitmapToBase64(bitmap)
} catch (e: Exception) {
_error.value = "فشل تحميل الصورة: ${e.message}"
}
}
}

fun processAndSaveFile(uri: Uri) {
viewModelScope.launch {
_isLoading.value = true
_error.value = null
_successMessage.value = null

try {          
        val content = fileProcessor.readFile(uri)          
        if (content.isBlank()) {          
            _error.value = "⚠️ الملف فارغ أو لا يحتوي على نص قابل للقراءة"          
            return@launch          
        }          
      
        val chunks =          
            if (content.length > 1000) {          
                fileProcessor.chunkText(content, maxChunkSize = 800, overlap = 100)          
            } else {          
                listOf(content)          
            }          
      
        val limitedChunks = chunks.take(20)          
      
        val newChunks = mutableListOf<String>()          
        var skipped = 0          
        for (chunk in limitedChunks) {          
            if (memoryRepository.existsByHash(calculateHash(chunk))) skipped++ else newChunks.add(chunk)          
        }          
      
        var saved = 0          
        if (newChunks.isNotEmpty()) {          
            val embeddings = try {          
                embeddingService.getBatchEmbeddings(newChunks)          
            } catch (_: Exception) {          
                newChunks.map { emptyList() }          
            }          
      
            newChunks.forEachIndexed { i, chunk ->          
                val emb = embeddings.getOrElse(i) { emptyList() }          
                val id = memoryRepository.addMemoryWithEmbedding(          
                    content = chunk,          
                    embedding = emb,          
                    category = "KNOWLEDGE",          
                    isShared = true          
                )          
                if (id > 0) saved++          
            }          
        }          
      
        _successMessage.value = buildString {          
            append("✅ تم حفظ $saved جزء في الذاكرة")          
            if (skipped > 0) append("\n⚠️ تم تخطي $skipped (موجودة مسبقاً)")          
            if (chunks.size > 20) append("\n📌 من أصل ${chunks.size} (الحد الأقصى 20)")          
            if (newChunks.isNotEmpty()) append("\n🚀 Batch: طلب API واحد")          
        }          
      
    } catch (e: Exception) {          
        _error.value = "❌ ${e.message}"          
    } finally {          
        _isLoading.value = false          
    }          
}

}

fun saveWebPage(url: String) {
viewModelScope.launch {
_isLoading.value = true
_error.value = null
_successMessage.value = null

try {          
        val result = webPageFetcher.fetchAndClean(url)          
      
        val fullText = buildString {          
            append(result.title)          
            append("\n\n")          
            append(result.content)          
        }          
      
        val chunks = fileProcessor.chunkText(fullText, maxChunkSize = 800, overlap = 100)          
        val limited = chunks.take(20)          
      
        val newChunks = mutableListOf<String>()          
        var skipped = 0          
        for (chunk in limited) {          
            if (memoryRepository.existsByHash(calculateHash(chunk))) skipped++ else newChunks.add(chunk)          
        }          
      
        var saved = 0          
        if (newChunks.isNotEmpty()) {          
            val embeddings = try {          
                embeddingService.getBatchEmbeddings(newChunks)          
            } catch (_: Exception) {          
                newChunks.map { emptyList() }          
            }          
      
            newChunks.forEachIndexed { i, chunk ->          
                val emb = embeddings.getOrElse(i) { emptyList() }          
                val id = memoryRepository.addMemoryWithEmbedding(          
                    content = chunk,          
                    embedding = emb,          
                    category = "WEB",          
                    isShared = true          
                )          
                if (id > 0) saved++          
            }          
        }          
      
        _successMessage.value = buildString {          
            append("✅ تم حفظ $saved جزء من:\n📄 ${result.title}")          
            if (skipped > 0) append("\n⚠️ تم تخطي $skipped (موجودة مسبقاً)")          
            if (chunks.size > 20) append("\n📌 من أصل ${chunks.size} (الحد الأقصى 20)")          
            append("\n📊 ${result.wordCount} كلمة")          
            if (newChunks.isNotEmpty()) append("\n🚀 Batch: طلب API واحد")          
        }          
      
    } catch (e: Exception) {          
        _error.value = when {          
            e.message?.contains("Unable to resolve host") == true -> "❌ لا يوجد اتصال بالإنترنت"          
            e.message?.contains("timeout", ignoreCase = true) == true -> "❌ انتهت مهلة الاتصال"          
            e.message?.contains("URL") == true -> "❌ رابط غير صالح"          
            else -> "❌ فشل جلب الصفحة: ${e.message}"          
        }          
    } finally {          
        _isLoading.value = false          
    }          
}

}

fun clearSelectedImage() {
_selectedImageBase64.value = null
}

fun clearError() {
_error.value = null
}

fun clearSuccessMessage() {
_successMessage.value = null
}

private fun calculateHash(content: String): String {
return try {
MessageDigest.getInstance("SHA-256")
.digest(content.toByteArray(Charsets.UTF_8))
.joinToString("") { "%02x".format(it) }
} catch (_: Exception) {
"nohash-${content.hashCode().toString(16)}-${content.length}"
}
}

private fun getDisplayName(uri: Uri): String {
return try {
val resolver = getApplication().contentResolver
resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
?.use { cursor ->
if (cursor.moveToFirst()) {
val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
if (idx >= 0) cursor.getString(idx) else null
} else null
}
?: (uri.lastPathSegment ?: "ملف")
} catch (_: Exception) {
uri.lastPathSegment ?: "ملف"
}
}

fun deleteConversation(conversation: Conversation) {
viewModelScope.launch {
conversationRepository.deleteMessages(conversation.id)
conversationRepository.deleteConversation(conversation)

if (_currentConversationId.value == conversation.id) newConversation()          
    if (_currentWebConversationId.value == conversation.id) clearWebConversationSelection()          
}

}

}
