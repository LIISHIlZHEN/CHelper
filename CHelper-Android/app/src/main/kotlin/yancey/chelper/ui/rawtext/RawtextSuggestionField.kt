/**
 * It is part of CHelper. CHelper is a command helper for Minecraft Bedrock Edition.
 * Copyright (C) 2026  Yancey
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package yancey.chelper.ui.rawtext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import yancey.chelper.ui.common.CHelperTheme
import yancey.chelper.ui.common.layout.Surface
import yancey.chelper.ui.common.widget.Text

/** 带自动补全的输入框：内联式下拉；点建议后保持焦点继续补全 */
@Composable
fun RawtextSuggestionField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    label: String = "",
    suggest: (text: String, caret: Int) -> Pair<Int, List<RawtextSuggestion>> = { _, _ -> -1 to emptyList() },
    recomputeKey: Any? = null,
) {
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // value 外部变化（如按钮/预设/导入赋值）时同步 tfv，避免输入框仍显示旧值
    LaunchedEffect(value) {
        if (tfv.text != value) {
            tfv = TextFieldValue(value, TextRange(value.length))
        }
    }
    val (start, sugs) = remember(tfv.text, tfv.selection.start, recomputeKey) {
        runCatching { suggest(tfv.text, tfv.selection.start) }.getOrElse { -1 to emptyList() }
    }

    fun applySuggestion(s: RawtextSuggestion) {
        val replace = s.replace ?: return
        val caret = tfv.selection.start.coerceIn(0, tfv.text.length)
        val s0 = if (s.appendOnly) caret
        else (if (start in 0..tfv.text.length) start else caret).coerceIn(0, tfv.text.length)
        val newText = tfv.text.substring(0, s0) + replace + tfv.text.substring(caret)
        val pos = if (s.caretPos != null) s0 + s.caretPos else s0 + replace.length
        tfv = TextFieldValue(newText, TextRange(pos.coerceIn(0, newText.length)))
        onChange(newText)
        focused = true
        scope.launch { focusRequester.requestFocus() }
    }

    Column {
        if (label.isNotEmpty()) {
            Text(label, style = TextStyle(fontSize = 12.sp, color = CHelperTheme.colors.textSecondary))
            Spacer(Modifier.height(2.dp))
        }
        BasicTextField(
            value = tfv,
            onValueChange = { tfv = it; onChange(it.text) },
            singleLine = true,
            textStyle = TextStyle(color = CHelperTheme.colors.textMain, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(CHelperTheme.colors.mainColor),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.focusRequester(focusRequester),
            decorationBox = { inner ->
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(CHelperTheme.colors.background).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    if (tfv.text.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = TextStyle(color = CHelperTheme.colors.textHint, fontSize = 14.sp))
                    }
                    inner()
                }
            },
        )
        if (focused && sugs.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                clipCornerSize = 10.dp,
                horizontalPadding = 0.dp,
                verticalPadding = 0.dp,
            ) {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    sugs.forEach { s ->
                        if (s.hintOnly) {
                            Text(
                                s.text,
                                style = TextStyle(fontSize = 13.sp, color = CHelperTheme.colors.textSecondary),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        } else {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { applySuggestion(s) }
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Column {
                                    Text(s.text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp))
                                    if (s.hint.isNotEmpty()) {
                                        Text(s.hint, style = TextStyle(fontSize = 12.sp, color = CHelperTheme.colors.textSecondary))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 选择器自动补全 */
@Composable
fun RawtextSelectorField(
    value: String,
    onChange: (String) -> Unit,
    targets: RawtextDebugTargets,
    label: String = "选择器",
) {
    RawtextSuggestionField(
        value = value,
        onChange = onChange,
        label = label,
        placeholder = "选择器，如 @s[scores={雪球菜单=1}]",
        suggest = { text, caret ->
            val ctx = RawtextAutocomplete.context(text, caret)
            ctx.start to RawtextAutocomplete.suggestions(ctx, targets)
        },
        recomputeKey = targets,
    )
}

/** 翻译识别符补全 */
@Composable
fun RawtextKeyField(
    value: String,
    onChange: (String) -> Unit,
    label: String = "翻译识别符",
) {
    RawtextSuggestionField(
        value = value,
        onChange = onChange,
        label = label,
        placeholder = "如 item.diamond.name（输入自动补全全量键名）",
        suggest = { text, caret ->
            val before = text.substring(0, caret.coerceIn(0, text.length))
            val m = Regex("([\u4e00-\u9fa5a-zA-Z0-9_.]*)$").find(before)
            val prefix = m?.groupValues?.get(1) ?: ""
            val start = caret - prefix.length
            start to RawtextAutocomplete.keySuggestions(prefix)
        },
    )
}

/** 记分板目标补全 */
@Composable
fun RawtextObjectiveField(
    value: String,
    onChange: (String) -> Unit,
    targets: RawtextDebugTargets,
    label: String = "记分板目标",
) {
    RawtextSuggestionField(
        value = value,
        onChange = onChange,
        label = label,
        placeholder = "如 金币（自动补全文档中的目标）",
        suggest = { text, caret ->
            val before = text.substring(0, caret.coerceIn(0, text.length))
            val m = Regex("([\u4e00-\u9fa5a-zA-Z0-9_.]*)$").find(before)
            val prefix = m?.groupValues?.get(1) ?: ""
            val start = caret - prefix.length
            start to RawtextAutocomplete.objectiveSuggestions(prefix, targets)
        },
        recomputeKey = targets,
    )
}
