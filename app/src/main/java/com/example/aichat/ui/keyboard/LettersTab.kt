package com.example.aichat.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ARABIC_ROWS = listOf(
    listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج", "د"),
    listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"),
    listOf("ئ", "ء", "ؤ", "ر", "لا", "ى", "ة", "و", "ز", "ظ", "ذ")
)

private val ENGLISH_ROWS = listOf(
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
    listOf("z", "x", "c", "v", "b", "n", "m")
)

private val SYMBOL_ROWS = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    listOf("@", "#", "%", "&", "*", "-", "+", "(", ")", "/"),
    listOf("،", "؛", "؟", "!", "\"", "'", ":", ";", "_")
)

private val KEY_SHAPE = RoundedCornerShape(6.dp)

/**
 * حروف أساسية (عربي/إنجليزي/رموز). تُدرج بـ onInsert عند المؤشر.
 * لا تقرأ نص الحقل ولا تحفظ ما يُكتب. اتجاه المفاتيح ثابت من اليسار إلى اليمين.
 * onDelete: حذف حرف (يتكرر بالضغط المطوّل). onEnter: Enter كلوحة عادية.
 */
@Composable
fun LettersTab(
    arabic: Boolean,
    onToggleLanguage: () -> Unit,
    onInsert: (String) -> Boolean,
    onDelete: () -> Unit,
    onEnter: () -> Unit
) {
    // 0 = بلا Shift، 1 = لحرف واحد، 2 = Caps Lock
    var shift by remember { mutableIntStateOf(0) }
    var symbols by remember { mutableStateOf(false) }

    fun type(s: String) {
        onInsert(s)
        if (shift == 1) shift = 0
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (symbols) {
                SYMBOL_ROWS.forEachIndexed { i, row ->
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        row.forEach { k -> LetterKey(k) { type(k) } }
                        if (i == 2) DeleteKey(1f, onDelete)
                    }
                }
            } else if (arabic) {
                ARABIC_ROWS.forEachIndexed { i, row ->
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        if (i == 1) Spacer(Modifier.weight(0.5f))
                        row.forEach { k -> LetterKey(k) { type(k) } }
                        if (i == 1) Spacer(Modifier.weight(0.5f))
                        if (i == 2) DeleteKey(1f, onDelete)
                    }
                }
            } else {
                ENGLISH_ROWS.forEachIndexed { i, row ->
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        if (i == 1) Spacer(Modifier.weight(0.5f))
                        if (i == 2) ShiftKey(shift) { shift = it }
                        row.forEach { k ->
                            val shown = if (shift != 0) k.uppercase() else k
                            LetterKey(shown) { type(shown) }
                        }
                        if (i == 1) Spacer(Modifier.weight(0.5f))
                        if (i == 2) DeleteKey(1.5f, onDelete)
                    }
                }
            }

            // الصف السفلي
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                FunctionKey(
                    label = if (symbols) { if (arabic) "أبج" else "ABC" } else "123",
                    weight = 1.5f
                ) { symbols = !symbols }
                FunctionKey(label = if (arabic) "EN" else "ع", weight = 1.2f) {
                    shift = 0
                    symbols = false
                    onToggleLanguage()
                }
                val comma = if (arabic) "،" else ","
                LetterKey(comma) { type(comma) }
                LetterKey(
                    label = if (arabic) "العربية" else "English",
                    weight = 4f,
                    fontSize = 12
                ) { type(" ") }
                LetterKey(".") { type(".") }
                EnterKey(1.8f, onEnter)
            }
        }
    }
}

@Composable
private fun RowScope.LetterKey(
    label: String,
    weight: Float = 1f,
    fontSize: Int = 20,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .clip(KEY_SHAPE)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, fontSize = fontSize.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RowScope.FunctionKey(label: String, weight: Float, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .clip(KEY_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RowScope.EnterKey(weight: Float, onEnter: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .clip(KEY_SHAPE)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onEnter),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "Enter", fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimary)
    }
}

/** ضغطة واحدة: يفعّل/يلغي لحرف واحد. ضغطتان سريعتان: Caps Lock. */
@Composable
private fun RowScope.ShiftKey(state: Int, onState: (Int) -> Unit) {
    val current by rememberUpdatedState(state)
    val setter by rememberUpdatedState(onState)
    val bg = if (state == 0) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.primaryContainer
    Box(
        modifier = Modifier
            .weight(1.5f)
            .fillMaxHeight()
            .clip(KEY_SHAPE)
            .background(bg)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { setter(if (current == 0) 1 else 0) },
                    onDoubleTap = { setter(2) }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (state == 2) "AA" else "Aa",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** حذف: ضغطة تحذف حرفًا، والضغط المطوّل يكرر الحذف. */
@Composable
private fun RowScope.DeleteKey(weight: Float, onDelete: () -> Unit) {
    val current by rememberUpdatedState(onDelete)
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .clip(KEY_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    current()
                    val job = scope.launch {
                        delay(400)
                        while (true) {
                            current()
                            delay(50)
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(text = "حذف", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
