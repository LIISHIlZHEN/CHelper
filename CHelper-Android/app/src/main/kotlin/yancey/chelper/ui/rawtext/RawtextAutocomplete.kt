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

/** 补全建议 */
data class RawtextSuggestion(
    val text: String,
    val replace: String?,      // null = 仅提示（不可选）
    val hint: String = "",
    val hintOnly: Boolean = false,
    val caretPos: Int? = null, // 应用后的光标位置（相对 replace）
    val appendOnly: Boolean = false, // true = 在光标处追加，不替换已输入的前缀
    val replaceFrom: Int? = null, // 替换起点（引擎建议区间；null = 沿用字段传入的起点）
)

/** 仅保留"记分板目标名"一项本地数据（来自文档/调试目标，内核不提供）；选择器/翻译补全统一走内核 */
object RawtextAutocomplete {

    private fun hintOnly(text: String) = listOf(RawtextSuggestion(text, null, hintOnly = true))

    /** 记分板目标补全 */
    fun objectiveSuggestions(prefix: String, targets: RawtextDebugTargets): List<RawtextSuggestion> {
        val objs = (targets.condScores + targets.displayScores).sorted()
        val list = objs.filter { it.startsWith(prefix) }.map { RawtextSuggestion(it, it, "记分板目标") }.toMutableList()
        if (list.isEmpty()) list.addAll(hintOnly("输入记分板目标，如 kills / 金币"))
        return list
    }
}
