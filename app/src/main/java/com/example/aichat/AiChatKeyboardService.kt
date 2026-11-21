package com.example.aichat

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.repository.TextToolRunner
import com.example.aichat.ui.keyboard.MaterialsKeyboardPanel
import com.example.aichat.ui.keyboard.TargetRead
import com.example.aichat.ui.keyboard.TextToolsHost
import com.example.aichat.ui.theme.AiChatTheme

/**
 * لوحة مفاتيح AiChat (أدوات فقط، بلا حروف): علبة المواد والعلب الصغيرة وأدوات النص.
 * لا ترسل شيئًا إلى التطبيق المضيف. لا تقرأ نص الحقل إلا عند ضغطة أداة النص،
 * ولا تحفظه.
 *
 * هذه الخدمة حاضنة: المحتوى في MaterialsKeyboardPanel، وهي وحدها تلمس الحقل.
 * الخدمة نفسها مالكة لدورة الحياة وحالة الحفظ لأن Compose يحتاجهما خارج Activity.
 */
class AiChatKeyboardService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private companion object {
        // أقصى طول نص يُقرأ من الحقل لأدوات النص
        const val MAX_TARGET = 8000
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val vmStore = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = vmStore
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    // سبب تعطيل الإدراج في الحقل الحالي (null = مسموح)
    private var blockedReason by mutableStateOf<String?>(null)

    // يزداد مع كل حقل كتابة جديد فتُمسح نتيجة «أدوات النص» القديمة
    private var session by mutableIntStateOf(0)

    // كسولان لأن الـContext غير جاهز وقت إنشاء الكائن
    private val runner by lazy { TextToolRunner(applicationContext) }

    private val host: TextToolsHost by lazy {
        val agents = ChatDatabase.getDatabase(applicationContext).agentDao().observeAll()
        object : TextToolsHost {
            override val agentsFlow = agents
            override fun providerInfo() = runner.providerInfo()
            override fun readTarget() = readFieldTarget()
            override fun replaceTarget(original: String, wasSelection: Boolean, result: String) =
                replaceFieldTarget(original, wasSelection, result)
            override fun insertAfterTarget(original: String, wasSelection: Boolean, result: String) =
                insertAfterFieldTarget(original, wasSelection, result)
            override suspend fun run(systemPrompt: String, text: String) =
                runner.run(systemPrompt, text)
        }
    }

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
                        session = session,
                        host = host,
                        onInsert = { insertText(it) },
                        onDelete = { deleteBackward() },
                        onEnter = { pressEnter() },
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
        // حقل جديد فعلًا (لا إعادة تشغيل لنفس الحقل): امسح نتيجة أدوات النص السابقة
        if (!restarting) session++
    }

    // لا وضع ملء الشاشة في الوضع الأفقي (لوحتنا بلا مربع كتابة)
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        vmStore.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------
    // الإدراج (المواد والعلب)
    // ------------------------------------------------------------

    /** يدرج النص عند المؤشر ولا يرسل. يعيد false إن تعذّر. */
    private fun insertText(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        return runCatching { ic.commitText(text, 1) }.getOrDefault(false)
    }
    /** يحذف حرفًا قبل المؤشر (أو المحدد) بضغطة مفتاح حذف، دون قراءة نص الحقل. */
    private fun deleteBackward() {
        runCatching { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
    }

    /**
     * Enter كلوحة عادية: إن كان للحقل إجراء (إرسال، بحث، التالي…) نفّذه،
     * وإلا أدرج سطرًا جديدًا. لا يعمل إلا بضغطة المستخدم.
     */
    private fun pressEnter() {
        runCatching {
            val ic = currentInputConnection ?: return
            val opts = currentInputEditorInfo?.imeOptions ?: 0
            val action = opts and EditorInfo.IME_MASK_ACTION
            val noEnterAction = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
            val hasAction = !noEnterAction &&
                action != EditorInfo.IME_ACTION_NONE &&
                action != EditorInfo.IME_ACTION_UNSPECIFIED
            if (hasAction) {
                ic.performEditorAction(action)
            } else {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
        }
    }

    private fun switchKeyboard() {
        runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    // ------------------------------------------------------------
    // أدوات النص: قراءة الحقل واستبدال النتيجة
    // ------------------------------------------------------------

    /** المحدد إن وُجد، وإلا نص الحقل كله. يُستدعى عند ضغطة الأداة فقط. */
    private fun readFieldTarget(): TargetRead {
        val ic = currentInputConnection
            ?: return TargetRead(null, false, "لا يوجد حقل كتابة مفتوح.")
        return try {
            val sel = ic.getSelectedText(0)
            if (!sel.isNullOrEmpty()) {
                if (sel.length > MAX_TARGET) {
                    TargetRead(null, true, "المحدد أطول من $MAX_TARGET حرف.")
                } else {
                    TargetRead(sel.toString(), true, null)
                }
            } else {
                val before = ic.getTextBeforeCursor(MAX_TARGET + 1, 0)?.toString().orEmpty()
                val after = ic.getTextAfterCursor(MAX_TARGET + 1, 0)?.toString().orEmpty()
                val total = before + after
                when {
                    total.isBlank() -> TargetRead(
                        null, false,
                        "لا نص في الحقل (أو لا يسمح التطبيق بقراءته). اكتب نصًا أو حدّده."
                    )
                    total.length > MAX_TARGET -> TargetRead(
                        null, false,
                        "نص الحقل أطول من $MAX_TARGET حرف. حدّد جزءًا منه."
                    )
                    else -> TargetRead(total, false, null)
                }
            }
        } catch (e: Exception) {
            TargetRead(null, false, "تعذّرت قراءة الحقل.")
        }
    }

    /**
     * يستبدل المحدد، أو نص الحقل كله، بالنتيجة. لا يفعل شيئًا إن اختلف
     * ما في الحقل الآن عمّا قُرئ (حماية من محو نص كتبه المستخدم بعد القراءة).
     */
    private fun replaceFieldTarget(original: String, wasSelection: Boolean, result: String): Boolean {
        val ic = currentInputConnection ?: return false
        return try {
            if (wasSelection) {
                if (ic.getSelectedText(0)?.toString() != original) return false
                ic.commitText(result, 1)
            } else {
                if (!ic.getSelectedText(0).isNullOrEmpty()) return false
                val before = ic.getTextBeforeCursor(MAX_TARGET + 1, 0)?.toString().orEmpty()
                val after = ic.getTextAfterCursor(MAX_TARGET + 1, 0)?.toString().orEmpty()
                if (before + after != original) return false
                ic.beginBatchEdit()
                ic.deleteSurroundingText(before.length, after.length)
                val ok = ic.commitText(result, 1)
                ic.endBatchEdit()
                ok
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * بلا تحديد: يدرج عند المؤشر. مع تحديد: يطوي التحديد إلى نهايته ثم يدرج بعده
     * بسطر فاصل، حتى لا تمحو commitText النص المحدد.
     */
    private fun insertAfterFieldTarget(original: String, wasSelection: Boolean, result: String): Boolean {
        if (!wasSelection) return insertText(result)
        val ic = currentInputConnection ?: return false
        return try {
            val sel = ic.getSelectedText(0)?.toString() ?: return false
            if (sel != original) return false
            val before = ic.getTextBeforeCursor(MAX_TARGET + 1, 0)?.toString() ?: return false
            if (before.length > MAX_TARGET) return false
            val end = before.length + sel.length
            ic.setSelection(end, end) && ic.commitText("\n" + result, 1)
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------

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
