package com.example.aichat.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.aichat.data.model.WebEngine
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.repository.WebPlatformRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class WebPlatformsViewModel(
    private val repo: WebPlatformRepository
) : ViewModel() {

    /** كل المنصات مرتبة حسب sortOrder ثم الاسم */
    val platforms: StateFlow<List<WebPlatform>> = repo.allPlatforms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** رسائل العمليات (نجاح / فشل) */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // زرع الافتراضية عند أول تشغيل (IGNORE يحمي البيانات الموجودة)
        viewModelScope.launch { repo.seedDefaults() }
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
            if (platform.isBuiltIn) {
                // الافتراضية تُعطَّل فقط
                repo.setEnabled(platform, false)
                _message.value = "تم تعطيل ${platform.name} (المنصات الافتراضية لا تُحذف)"
            } else {
                repo.delete(platform.id)
                _message.value = "🗑️ تم حذف ${platform.name}"
            }
        }
    }

    fun toggleEnabled(platform: WebPlatform) {
        viewModelScope.launch {
            repo.setEnabled(platform, !platform.isEnabled)
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    class Factory(private val repo: WebPlatformRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WebPlatformsViewModel(repo) as T
    }
}
