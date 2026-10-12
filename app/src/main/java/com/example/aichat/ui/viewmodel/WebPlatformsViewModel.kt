package com.example.aichat.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.aichat.data.model.WebEngine
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.repository.WebPlatformRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WebPlatformsViewModel(
    private val repo: WebPlatformRepository
) : ViewModel() {

    /** كل المنصات مرتبة حسب sortOrder ثم الاسم */
    val platforms: StateFlow<List<WebPlatform>> =
        repo.allPlatforms
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** رسائل العمليات (نجاح / فشل) */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * ✅ مهم: يمنع Race condition
     * true بعد اكتمال seedDefaults (insert + patch selectors)
     */
    private val _seedReady = MutableStateFlow(false)
    val seedReady: StateFlow<Boolean> = _seedReady.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { repo.seedDefaults() }
                .onFailure { _message.value = "⚠️ فشل تهيئة منصات الويب: ${it.message ?: "خطأ غير معروف"}" }
            _seedReady.value = true
        }
    }

    // ── actions ──────────────────────────────────────────────────────────────

    fun add(
        name: String,
        url: String,
        requiresAccount: Boolean,
        engine: WebEngine,
        emoji: String
    ) {
        viewModelScope.launch {
            repo.add(name, url, requiresAccount, engine, emoji)
                .onSuccess { _message.value = "✅ تمت إضافة ${it.name}" }
                .onFailure { _message.value = "❌ ${it.message ?: "فشل الإضافة"}" }
        }
    }

    fun delete(platform: WebPlatform) {
        viewModelScope.launch {
            runCatching {
                if (platform.isBuiltIn) {
                    repo.setEnabled(platform, false)
                    _message.value = "تم تعطيل ${platform.name} (المنصات الافتراضية لا تُحذف)"
                } else {
                    val ok = repo.delete(platform.id)
                    _message.value = if (ok) "🗑️ تم حذف ${platform.name}" else "⚠️ لم يتم الحذف"
                }
            }.onFailure {
                _message.value = "❌ ${it.message ?: "فشلت العملية"}"
            }
        }
    }

    fun toggleEnabled(platform: WebPlatform) {
        viewModelScope.launch {
            runCatching {
                repo.setEnabled(platform, !platform.isEnabled)
            }.onFailure {
                _message.value = "❌ ${it.message ?: "فشل التحديث"}"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /**
     * (اختياري) إعادة seed/patch يدوياً من UI إن رغبت لاحقاً
     */
    fun reseedDefaults() {
        viewModelScope.launch {
            _seedReady.value = false
            runCatching { repo.seedDefaults() }
                .onSuccess { _message.value = "✅ تم تحديث إعدادات المنصات الافتراضية" }
                .onFailure { _message.value = "❌ ${it.message ?: "فشل التحديث"}" }
            _seedReady.value = true
        }
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    class Factory(private val repo: WebPlatformRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WebPlatformsViewModel(repo) as T
    }
}
