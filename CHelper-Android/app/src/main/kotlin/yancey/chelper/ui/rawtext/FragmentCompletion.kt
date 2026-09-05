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

import yancey.chelper.core.FragmentContext
import yancey.chelper.core.Suggestion

/**
 * rawtext 编辑器补全入口：统一走共享内核（core）的补全（Parser/AutoSuggestion），
 * 不维护本地候选表。内核未就绪或无候选 → 返回空列表。
 * 仅"记分板目标名"一项是文档数据（内核不提供），仍在 RawtextAutocomplete 本地提供。
 */
object FragmentCompletion {

    private const val MAX_SUGGESTIONS = 256

    private fun Suggestion.sugName(): String = name ?: ""

    private fun Suggestion.desc(): String = description ?: ""

    /** 光标前字符判断"参数名阶段"（= 前无 =，即刚进入 [ 或 , 后的键位） */
    private fun isKeyStage(value: String, start: Int): Boolean {
        if (start <= 0) return false
        val before = value.substring(0, start)
        return before.endsWith("[") || before.endsWith(",") || before.endsWith(", ")
    }

    /** 选择器字段补全：只走内核（变量/@x、参数名/myflag、值候选、结构符号全部来自 core） */
    fun selector(
        value: String,
        caret: Int,
        @Suppress("UNUSED_PARAMETER") targets: RawtextDebugTargets,
    ): Pair<Int, List<RawtextSuggestion>> {
        val core = RawtextCompletionKernel.core() ?: return caret to emptyList()
        val c = caret.coerceIn(0, value.length)
        val fragment = try {
            FragmentContext.openSelector(core, value)
        } catch (_: Throwable) {
            null
        } ?: return caret to emptyList()
        try {
            val list = engineSuggestions(fragment, value, c, isNegatable = true) { s, n ->
                if (isKeyStage(value, s.start)) {
                    // 参数名阶段：裸键名 → 补上等号/嵌套开头（scores/hasitem 需要光标后移）
                    when (n) {
                        "scores" -> RawtextSuggestion("scores", "scores={", s.desc() + "（记分板）", caretPos = "scores={".length)
                        "hasitem" -> RawtextSuggestion("hasitem", "hasitem={item=", s.desc(), caretPos = "hasitem={item=".length)
                        else -> RawtextSuggestion(n, n + "=", s.desc())
                    }
                } else {
                    // 值阶段：引擎无布尔值中文说明，这里补通用提示（true/false）
                    val base = s.desc()
                    val hint = base.ifEmpty {
                        when (n) {
                            "true" -> "开（是）"
                            "false" -> "关（否）"
                            else -> ""
                        }
                    }
                    RawtextSuggestion(n, n, hint)
                }
            }
            return c to list
        } catch (_: Throwable) {
            return caret to emptyList()
        } finally {
            fragment.close()
        }
    }

    /** 翻译识别符补全：只走内核 translate 键表（含中文名） */
    fun translate(value: String, caret: Int): Pair<Int, List<RawtextSuggestion>> {
        val c = caret.coerceIn(0, value.length)
        // 空前缀不刷全量键表（与旧行为一致）
        val prefix = Regex("([\u4e00-\u9fa5a-zA-Z0-9_.]*)$")
            .find(value.substring(0, c))?.groupValues?.get(1) ?: ""
        if (prefix.isEmpty()) {
            return c to emptyList()
        }
        val core = RawtextCompletionKernel.core() ?: return c to emptyList()
        val fragment = try {
            FragmentContext.openId(core, "translate", value)
        } catch (_: Throwable) {
            null
        } ?: return c to emptyList()
        try {
            val list = engineSuggestions(fragment, value, c, isNegatable = false) { s, n ->
                RawtextSuggestion(n, n, s.desc())
            }.filter { s ->
                s.text.startsWith(prefix) || s.text.contains(prefix) || s.hint.contains(prefix)
            }
            return c to list
        } catch (_: Throwable) {
            return caret to emptyList()
        } finally {
            fragment.close()
        }
    }

    /**
     * 拉取内核在当前光标的建议并转成 RawtextSuggestion（按引擎区间 [start, end] 替换；
     * 仅保留"替换到光标"的候选，中段光标不弹补全）。
     */
    private inline fun engineSuggestions(
        fragment: FragmentContext,
        value: String,
        c: Int,
        isNegatable: Boolean,
        map: (Suggestion, String) -> RawtextSuggestion,
    ): List<RawtextSuggestion> {
        val size = fragment.getSuggestionsSize(c)
        if (size <= 0) return emptyList()
        val list = mutableListOf<RawtextSuggestion>()
        for (which in 0 until size) {
            val s = fragment.getSuggestion(c, which) ?: continue
            if (s.end != c) {
                continue // 只弹"光标处可替换"的建议
            }
            val n = s.sugName()
            if (n.isEmpty()) {
                continue
            }
            if (isNegatable && n == "!" && s.start > 0 && value.substring(0, s.start).endsWith("=")) {
                continue // =! 由引擎结构引导处理，不作为独立候选
            }
            val mapped = map(s, n)
            // @ 变量候选：引擎区间可能只覆盖字母部分；整词前缀匹配时强制从 @ 之前整词替换
            var start = s.start.coerceIn(0, c)
            if (n.startsWith("@") && c >= n.length && value.substring(c - n.length, c) == n) {
                start = c - n.length
            }
            list += mapped.copy(
                text = mapped.text,
                replaceFrom = start,
            )
            if (list.size >= MAX_SUGGESTIONS) break
        }
        return list
    }
}
