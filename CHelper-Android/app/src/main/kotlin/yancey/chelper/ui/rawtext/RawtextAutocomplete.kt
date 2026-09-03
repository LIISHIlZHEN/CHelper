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
)

/** 选择器/翻译识别符自动补全 */
object RawtextAutocomplete {

    data class Ctx(
        val kind: String,
        val prefix: String = "",
        val key: String = "",
        val text: String = "",
        val inBraces: Boolean = false,
        val start: Int = 0,
    )

    fun context(value: String, caret: Int): Ctx {
        val c = caret.coerceIn(0, value.length)
        val before = normalizeFullwidth(value).substring(0, c)
        // 1. 选择器变量
        val varm = Regex("@([a-zA-Z]*)$").find(before)
        if (varm != null) {
            val name = varm.groupValues[1]
            if (name.isNotEmpty() && RawtextSelectorParser.SEL_VARS.contains("@" + name)) {
                return Ctx("varcomplete", start = c)
            }
            return Ctx("variable", prefix = "@" + name, start = c - varm.value.length)
        }
        // 2. 括号内部
        val lastOpen = before.lastIndexOf('[')
        val lastClose = before.lastIndexOf(']')
        val emptyBracket = lastOpen >= 0 && lastClose > lastOpen && before.substring(lastOpen + 1, lastClose).trim().isEmpty()
        if (lastOpen > lastClose || emptyBracket) {
            val inner = if (emptyBracket) "" else before.substring(lastOpen + 1)
            Regex("scores=\\{[^}=]+=([^}]*)$").find(inner)?.let { m ->
                val p = m.groupValues[1]
                return Ctx("range", prefix = p, start = c - p.length)
            }
            Regex("scores=\\{\\s*([^}=]*)$").find(inner)?.let { m ->
                val p = m.groupValues[1]
                return Ctx("objective", prefix = p, start = c - p.length)
            }
            Regex("(?:^|,)\\s*hasitem=\\s*\\{[^}]*item=\\s*([^,}]*)$").find(inner)?.let { m ->
                val p = m.groupValues[1]
                return Ctx("item", prefix = p, start = c - p.length)
            }
            (Regex("(?:^|,)\\s*hasitem=\\s*\\{\\s*([a-z]*)$").find(inner)
                ?: Regex("(?:^|,)\\s*hasitem=\\s*\\{[^}]*,\\s*([a-z]*)$").find(inner))?.let { m ->
                val p = m.groupValues[1]
                return Ctx("hasitemkey", prefix = p, start = c - p.length)
            }
            Regex("(?:^|,)\\s*hasitem=\\s*\\{[^}]*location=\\s*([a-zA-Z0-9_.]*)$").find(inner)?.let { m ->
                val p = m.groupValues[1]
                return Ctx("slot", prefix = p, start = c - p.length)
            }
            Regex("(?:^|,)\\s*hasitem=\\s*\\{[^}]*(quantity|data|slot)=\\s*([^,}]*)$").find(inner)?.let { m ->
                val tips = mapOf(
                    "quantity" to "数量：整数范围，如 5..10；0 表示没有该物品；!范围 表示取反",
                    "data" to "数据值：整数，如 1000（可选）",
                    "slot" to "槽位：整数范围，如 5..8（需先指定 location）",
                )
                return Ctx("hint", text = tips[m.groupValues[1]] ?: "", inBraces = true, start = c)
            }
            Regex("(?:^|,)\\s*(tag|m|family|type|name)\\s*=\\s*([^,}\\]]*)$").find(inner)?.let { m ->
                val p = m.groupValues[2]
                return Ctx("value", key = m.groupValues[1], prefix = p, start = c - p.length)
            }
            Regex("(?:^|,)\\s*([a-z]+)=\\s*([^,}\\]]*)$").find(inner)?.let { m ->
                val p = m.groupValues[2]
                return Ctx("value", key = m.groupValues[1], prefix = p, start = c - p.length)
            }
            if (Regex("\\}\\s*$").containsMatchIn(inner)) return Ctx("comma", start = c)
            if (Regex("=\\s*$").containsMatchIn(inner)) return Ctx("hint", text = "输入参数值：数字（如 1..）、名称或标签", start = c)
            Regex("(?:^|,)\\s*([a-z]*)$").find(inner)?.let { m ->
                val p = m.groupValues[1]
                return Ctx("argkey", prefix = p, start = c - p.length)
            }
            return Ctx("none", start = c)
        }
        return Ctx("none", start = c)
    }

    private fun hintOnly(text: String) = listOf(RawtextSuggestion(text, null, hintOnly = true))

    private fun rankZh(n: String, p: String) = when {
        n.startsWith(p) -> 0
        n.contains(p) -> 1
        else -> 2
    }

    fun suggestions(ctx: Ctx, targets: RawtextDebugTargets): List<RawtextSuggestion> {
        if (ctx.kind == "none") return emptyList()
        val shortId = { id: String -> RawtextDatasets.shortId(id) }
        val list = mutableListOf<RawtextSuggestion>()
        fun addStructural(inBraces: Boolean) {
            val close = if (inBraces) '}' else ']'
            list.add(RawtextSuggestion(",", ",", if (inBraces) "继续添加子键" else "继续添加参数", appendOnly = true))
            list.add(RawtextSuggestion(close.toString(), close.toString(), if (inBraces) "闭合 hasitem" else "结束选择器", appendOnly = true))
        }
        fun finalize(inBraces: Boolean) {
            if (list.size > 256) list.subList(256, list.size).clear()
            if (ctx.prefix.isNotEmpty()) addStructural(inBraces)
        }
        when (ctx.kind) {
            "variable" -> {
                RawtextSelectorParser.SEL_VARS.filter { it.startsWith(ctx.prefix) }
                    .forEach { v -> list.add(RawtextSuggestion(v, v, RawtextConstants.SEL_VAR_TITLE[v] ?: "")) }
                if (list.isEmpty()) list.addAll(hintOnly("可用变量：@a / @p / @r / @e / @n / @s / @initiator"))
            }

            "varcomplete" -> list.add(RawtextSuggestion("[", "[", "开始添加参数（如 [tag=a,scores={x=1}]）", appendOnly = true))
            "argkey" -> {
                RawtextConstants.ARG_ORDER.filter { it.key.startsWith(ctx.prefix) }.forEach { def ->
                    when (def.key) {
                        "scores" -> list.add(RawtextSuggestion("scores", "scores={", def.label + "（记分板）", caretPos = "scores={".length))
                        "hasitem" -> list.add(RawtextSuggestion("hasitem", "hasitem={item=", def.label, caretPos = "hasitem={item=".length))
                        else -> list.add(RawtextSuggestion(def.key, def.key + "=", def.label))
                    }
                }
            }

            "objective" -> {
                val matched = (targets.condScores + targets.displayScores).sorted()
                    .filter { it.startsWith(ctx.prefix) }
                matched.forEach { o -> list.add(RawtextSuggestion(o, o + "=", "记分板目标")) }
                if (ctx.prefix.isNotEmpty()) {
                    list.add(RawtextSuggestion("=", "=", "完成目标名，进入分数范围", appendOnly = true))
                }
                if (matched.isEmpty()) list.addAll(hintOnly("未匹配到已有目标，可直接输入"))
            }

            "range" -> {
                listOf("1", "0", "1..", "1..3", "1..10", "..9", "!0")
                    .filter { it.startsWith(ctx.prefix) }
                    .forEach { r -> list.add(RawtextSuggestion(r, r + "}", "分数范围")) }
                if (ctx.prefix.isNotEmpty()) {
                    list.add(RawtextSuggestion("}", "}", "完成范围，闭合 scores", appendOnly = true))
                }
            }

            "item" -> {
                val neg = ctx.prefix.startsWith("!")
                val p = if (neg) ctx.prefix.substring(1) else ctx.prefix
                val zh = RawtextDatasets.items.associate { (id, n) -> shortId(id) to n }
                val matched = mutableListOf<Pair<String, String>>()
                RawtextDatasets.items.forEach { (id, n) ->
                    val s = shortId(id)
                    val z = zh[s] ?: ""
                    if (s.startsWith(p) || z.contains(p)) matched.add(s to z)
                }
                matched.sortedBy { rankZh(it.second, p) }
                    .take(256)
                    .forEach { (t, z) -> list.add(RawtextSuggestion(t, (if (neg) "!" else "") + t, z.ifEmpty { "物品" })) }
                if (list.isEmpty()) list.addAll(hintOnly("输入物品 ID 或中文名，如 diamond / 钻石"))
                finalize(true)
            }

            "hasitemkey" -> {
                listOf(
                    "item" to "物品 ID（必填）", "quantity" to "数量（整数范围）", "data" to "数据值（整数）",
                    "location" to "槽位类型", "slot" to "槽位（整数范围）",
                ).filter { it.first.startsWith(ctx.prefix) }
                    .forEach { (k, l) -> list.add(RawtextSuggestion(k, k + "=", l)) }
                if (list.isEmpty()) list.addAll(hintOnly("子键：item / quantity / data / location / slot"))
            }

            "slot" -> {
                RawtextConstants.SLOT_TYPES.filter { it.first.startsWith(ctx.prefix) }
                    .forEach { (v, l) -> list.add(RawtextSuggestion(v, v, l)) }
                if (list.isEmpty()) list.addAll(hintOnly("槽位类型，如 slot.weapon.mainhand"))
                finalize(true)
            }

            "value" -> {
                val key = ctx.key
                val neg = ctx.prefix.startsWith("!")
                val p = if (neg) ctx.prefix.substring(1) else ctx.prefix
                when (key) {
                    "tag" -> {
                        targets.tags.sorted().filter { it.startsWith(p) }
                            .forEach { t -> list.add(RawtextSuggestion(t, (if (neg) "!" else "") + t, "标签")) }
                        if (list.isEmpty()) list.addAll(hintOnly("输入标签名，如 my_tag；! 前缀表示取反"))
                    }

                    "m" -> {
                        RawtextConstants.GAMEMODES.filter { it.first.startsWith(p) }
                            .forEach { (v, l) -> list.add(RawtextSuggestion(v, v, l)) }
                        if (list.isEmpty()) list.addAll(hintOnly("游戏模式：survival / creative / adventure / spectator"))
                    }

                    "type", "family" -> {
                        val isType = key == "type"
                        val data = if (isType) RawtextDatasets.types else RawtextDatasets.families
                        val useLong = p.startsWith("minecraft")
                        val pick: (String) -> String = { t -> if (isType) (if (useLong) t else shortId(t)) else t }
                        val match: (String, String) -> Boolean = { t, n ->
                            if (isType) {
                                (if (useLong) t.startsWith(p) else (t.startsWith(p) || shortId(t).startsWith(p))) || n.contains(p)
                            } else {
                                t.startsWith(p) || n.contains(p)
                            }
                        }
                        data.filter { (t, n) -> match(t, n) }
                            .sortedBy { rankZh(it.second, p) }
                            .take(256)
                            .forEach { (t, n) ->
                                val v = pick(t)
                                list.add(RawtextSuggestion(v, (if (neg) "!" else "") + v, n.ifEmpty { if (isType) "实体类型" else "族" }))
                            }
                        if (list.isEmpty()) {
                            list.addAll(hintOnly(if (isType) "输入实体类型，如 minecraft:zombie / 僵尸" else "输入族名，如 monster / 敌对生物"))
                        }
                    }

                    "name" -> list.addAll(hintOnly("输入玩家名/实体名，如 Steve"))
                    else -> {
                        val def = RawtextConstants.ARG_DEFS[key]
                        list.addAll(hintOnly(def?.let { it.label + "：" + it.desc } ?: ("输入 " + key + " 的值")))
                    }
                }
                finalize(false)
            }

            "hint" -> {
                list.addAll(hintOnly(ctx.text))
                if (ctx.inBraces) addStructural(true)
            }

            "comma" -> {
                list.add(RawtextSuggestion(",", ",", "继续添加参数", appendOnly = true))
                list.add(RawtextSuggestion("]", "]", "结束选择器", appendOnly = true))
            }
        }
        return list.take(256)
    }

    /** 翻译识别符补全（键名 + 中文） */
    fun keySuggestions(prefix: String): List<RawtextSuggestion> {
        if (prefix.isEmpty()) return emptyList()
        return RawtextDatasets.translateKeys
            .filter { (k, v) -> k.startsWith(prefix) || v.contains(prefix) }
            .sortedBy { rankZh(it.second, prefix) }
            .take(256)
            .map { (k, v) -> RawtextSuggestion(k, k, v) }
    }

    /** 记分板目标补全 */
    fun objectiveSuggestions(prefix: String, targets: RawtextDebugTargets): List<RawtextSuggestion> {
        val objs = (targets.condScores + targets.displayScores).sorted()
        val list = objs.filter { it.startsWith(prefix) }.map { RawtextSuggestion(it, it, "记分板目标") }.toMutableList()
        if (list.isEmpty()) list.addAll(hintOnly("输入记分板目标，如 kills / 金币"))
        return list
    }
}
