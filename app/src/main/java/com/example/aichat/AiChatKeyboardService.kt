package com.example.aichat

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.aichat.ui.keyboard.MaterialsKeyboardPanel
import com.example.aichat.ui.theme.AiChatTheme

/**
 * لوحة مفاتيح AiChat (أدوات فقط، بلا حروف): تعرض علبة المواد والعلب الصغيرة
 * وتدرج النص عند المؤشر. لا ترسل شيئًا، ولا تقرأ ما يُكتب في الحقل.
 *
 * هذه الخدمة حاضنة فقط: المحتوى كله في MaterialsKeyboardPanel.
 * الخدمة نفسها مالكة لدورة الحياة وحالة الحفظ لأن Compose يحتاجهما خارج Activity.
 */
class AiChatKeyboardService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val vmStore = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = vmStore
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    // سبب تعطيل الإدراج في الحقل الحالي (null = مسموح)
    private var blockedReason by mutableStateOf<String?>(null)

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateInputView(): View {
        val app = application as AichatApp

        val view = ComposeView(this).apply {
            setContent {
                AiChatTheme {
                    MaterialsKeyboardPanel(
                        store = app.materialStore,
                        boxStore = app.boxStore,
                        blockedReason = blockedReason,
                        onInsert = { insertText(it) },
                        onSwitchKeyboard = { switchKeyboard() }
                    )
                }
            }
        }

        // Compose يبحث عن المالكين من جذر نافذة لوحة المفاتيح
        val decor = window?.window?.decorView
        decor?.setViewTreeLifecycleOwner(this)
        decor?.setViewTreeViewModelStoreOwner(this)
        decor?.setViewTreeSavedStateRegistryOwner(this)

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        blockedReason = blockReason(info)
    }

    // لا وضع ملء الشاشة في الوضع الأفقي (لوحتنا بلا مربع كتابة)
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        vmStore.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------

    /** يدرج النص عند المؤشر ولا يرسل. يعيد false إن تعذّر. */
    private fun insertText(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        return runCatching { ic.commitText(text, 1) }.getOrDefault(false)
    }

    private fun switchKeyboard() {
        runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    private fun blockReason(info: EditorInfo?): String? {
        if (info == null || info.inputType == InputType.TYPE_NULL) {
            return "لا يوجد حقل كتابة مفتوح."
        }
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val v = info.inputType and InputType.TYPE_MASK_VARIATION
        val isPassword =
            (cls == InputType.TYPE_CLASS_TEXT && (
                v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (cls == InputType.TYPE_CLASS_NUMBER &&
                    v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        return if (isPassword) "هذا حقل كلمة مرور: الإدراج معطّل هنا." else null
    }
    }
