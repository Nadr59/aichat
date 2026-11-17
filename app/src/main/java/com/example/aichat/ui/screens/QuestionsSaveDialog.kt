package com.example.aichat.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aichat.repository.MaterialItem
import com.example.aichat.repository.MaterialStore
import org.json.JSONObject

/** علامة الفصل: سطر يحوي هذا النص وحده قبل كل سؤال. */
const val QUESTION_MARKER = "[[Q]]"

// يجب أن يطابق MAX_MATERIALS في MaterialStore.kt (ثابتها private فلا أصل إليها)
private const val STORE_CAP = 100
private const val NAME_MAX = 40

/**
 * يقسّم الرد إلى كتل: كل سطر يساوي [[Q]] وحده (يُتسامح مع ** و ` حوله) يبدأ كتلة جديدة.
 * ما قبل أول علامة يُهمل. الكتل الفارغة تُهمل. لا يستعمل أي نموذج.
 */
fun splitQuestionBlocks(text: String): List<String> {
    val blocks = ArrayList<String>()
    var current: StringBuilder? = null

    fun flush() {
        val s = current?.toString()?.trim().orEmpty()
        if (s.isNotEmpty()) blocks.add(s)
    }

    for (line in text.lines()) {
        val bare = line.trim().trim('*', '`', '_', '#', ' ').trim()
        if (bare.equals(QUESTION_MARKER, ignoreCase = true)) {
            flush()
            current = StringBuilder()
        } else {
            current?.append(line)?.append('\n')
        }
    }
    flush()
    return blocks
}

class SaveQuestionsResult(val added: Int, val skipped: Int, val needFree: Int)

/**
 * يحفظ كل كتلة بطاقة مستقلة باسم «المجموعة – سؤال n»، بترتيب الأسئلة في أول العلبة.
 * لا يحذف شيئًا: إن كان الحفظ سيدفع مواد قديمة خارج السقف يرفض كله.
 * يُستدعى من الخيط الرئيسي (شرط MaterialStore).
 */
fun saveQuestionBlocks(
    store: MaterialStore,
    groupName: String,
    blocks: List<String>
): SaveQuestionsResult {
    val name = groupName.trim().take(NAME_MAX)
    val existing = store.items.map { it.id }.toHashSet()
    val seen = HashSet<String>()
    val now = System.currentTimeMillis()
    val fresh = ArrayList<MaterialItem>()
    var skipped = 0

    blocks.forEachIndexed { i, block ->
        // المعرّف ثابت لنفس الاسم والنص: إعادة الحفظ لا تكرر ولا تمحو خلاصة
        val id = "q:" + Integer.toHexString((name + "\u0000" + block).hashCode())
        if (id in existing || !seen.add(id)) {
            skipped++
            return@forEachIndexed
        }
        val item = MaterialItem.fromJson(
            JSONObject()
                .put("id", id)
                .put("host", "محادثة")
                .put("title", "$name – سؤال ${i + 1}")
                .put("text", block)
                .put("method", "chat_questions")
                .put("quality", "good")
                .put("reason", "")
                .put("capturedAt", now)
        )
        if (item == null) skipped++ else fresh.add(item)
    }

    val over = store.items.size + fresh.size - STORE_CAP
    if (over > 0) return SaveQuestionsResult(0, skipped, over)

    val batch = "qs-$now"
    for (item in fresh) store.upsert(item, batch)
    return SaveQuestionsResult(fresh.size, skipped, 0)
}

@Composable
fun QuestionsSaveDialog(
    store: MaterialStore,
    text: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val blocks = remember(text) { splitQuestionBlocks(text) }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("حفظ الأسئلة في العلبة") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "اكتُشف ${blocks.size} سؤال. ستُحفظ كل واحدة بطاقة مستقلة.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(NAME_MAX) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("اسم المجموعة (مثل: جغرافيا)") }
                )
                if (name.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "ستُسمّى: ${name.trim()} – سؤال 1، ${name.trim()} – سؤال 2 …",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                blocks.firstOrNull()?.let { first ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "معاينة السؤال 1:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = first,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val r = saveQuestionBlocks(store, name, blocks)
                    val msg = when {
                        r.needFree > 0 ->
                            "العلبة ممتلئة: فرّغ ${r.needFree} خانة على الأقل (السقف $STORE_CAP مادة). لم يُحفظ شيء."
                        r.added == 0 ->
                            "هذه الأسئلة محفوظة سابقًا بالاسم نفسه."
                        else ->
                            "حُفظ ${r.added} سؤال في العلبة" +
                                if (r.skipped > 0) " (${r.skipped} موجودة مسبقًا)" else ""
                    }
                    Toast.makeText(
                        context,
                        msg,
                        if (r.needFree > 0) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                    ).show()
                    onDismiss()
                },
                enabled = name.isNotBlank() && blocks.isNotEmpty()
            ) { Text("حفظ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}
