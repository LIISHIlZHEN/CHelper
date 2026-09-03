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

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 基岩版全量数据集（物品复用 CHelper 自带 items.json；实体类型/族来自 CHelper-Resource；
 * 翻译识别符来自 @minecraft/vanilla-data + 官方 zh_CN.lang + 中文 Minecraft Wiki）。
 * 进入功能时才惰性加载，避免拖慢启动。
 */
object RawtextDatasets {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    var items: List<Pair<String, String>> = emptyList()
        private set

    @Volatile
    var types: List<Pair<String, String>> = emptyList()
        private set

    @Volatile
    var families: List<Pair<String, String>> = emptyList()
        private set

    @Volatile
    var translateKeys: List<Pair<String, String>> = emptyList()
        private set

    @Volatile
    var translateMap: Map<String, String> = emptyMap()
        private set

    @Volatile
    private var loaded = false

    @Volatile
    private var translateLoaded = false

    /** 加载小体积数据集（物品/实体类型/族），进入编辑器时调用，速度快 */
    suspend fun ensureLoaded(context: Context) {
        if (loaded) return
        withContext(Dispatchers.IO) {
            if (loaded) return@withContext
            items = readMap(context, "rawtext/items.json")
            types = readContent(context, "rawtext/entity.json")
            families = readContent(context, "rawtext/entityFamily.json")
            loaded = true
        }
    }

    /** 延迟加载翻译识别符（约 1.16MB），避免进入编辑器时卡顿 */
    suspend fun ensureTranslateLoaded(context: Context) {
        if (translateLoaded) return
        withContext(Dispatchers.IO) {
            if (translateLoaded) return@withContext
            translateKeys = readMap(context, "rawtext/translate.json")
            translateMap = translateKeys.toMap()
            translateLoaded = true
        }
    }

    /** {id: 中文名} 映射格式（items.json） */
    private fun readMap(context: Context, path: String): List<Pair<String, String>> = try {
        val text = context.assets.open(path).bufferedReader().use { it.readText() }
        (json.parseToJsonElement(text) as? JsonObject)
            ?.entries
            ?.map { (id, value) -> id to ((value as? JsonPrimitive)?.content ?: id) }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** CHelper-Resource 的 {id,type,content:[{name,description}]} 格式（entity / entityFamily） */
    private fun readContent(context: Context, path: String): List<Pair<String, String>> = try {
        val text = context.assets.open(path).bufferedReader().use { it.readText() }
        val content = (json.parseToJsonElement(text) as? JsonObject)?.get("content") as? JsonArray
        content?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val desc = (obj["description"] as? JsonPrimitive)?.content ?: name
            name to desc
        } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun shortId(id: String): String = id.removePrefix("minecraft:")
}

/** 选择器参数与常量 */
object RawtextConstants {
    data class ArgDef(val key: String, val label: String, val desc: String, val placeholder: String)

    val SEL_VAR_TITLE = mapOf(
        "@a" to "所有玩家", "@p" to "最近的存活玩家", "@r" to "随机存活玩家", "@e" to "所有实体",
        "@n" to "最近的实体", "@s" to "当前执行者自身", "@initiator" to "与 NPC 交互的玩家",
    )

    val GAMEMODES = listOf(
        "survival" to "生存", "creative" to "创造", "adventure" to "冒险", "spectator" to "旁观",
    )

    val SLOT_TYPES = listOf(
        "slot.weapon.mainhand" to "主手", "slot.weapon.offhand" to "副手",
        "slot.armor.head" to "头盔", "slot.armor.chest" to "胸甲", "slot.armor.legs" to "护腿", "slot.armor.feet" to "靴子",
        "slot.inventory" to "物品栏", "slot.enderchest" to "末影箱", "slot.hotbar" to "快捷栏",
        "slot.saddle" to "鞍", "slot.armor" to "马铠", "slot.chest" to "箱子/容器", "slot.equippable" to "可装备栏",
    )

    val ARG_ORDER = listOf(
        ArgDef("r", "最大半径 r", "按球形距离限制（最大），如 r=3", "3"),
        ArgDef("rm", "最小半径 rm", "按球形距离限制（最小），如 rm=1", "1"),
        ArgDef("c", "数量 c", "最多选择几个目标；负数取最远的", "1"),
        ArgDef("type", "实体类型 type", "按实体标识符筛选，可配合取反排除", "minecraft:zombie"),
        ArgDef("name", "名称 name", "按名称筛选，可配合取反排除", "Steve"),
        ArgDef("tag", "标签 tag", "按标签筛选，可配合取反排除，可重复使用", "my_tag"),
        ArgDef("family", "族 family", "按实体族筛选，可配合取反排除", "monster"),
        ArgDef("m", "游戏模式 m", "按玩家游戏模式筛选，可配合取反排除", ""),
        ArgDef("scores", "分数 scores", "按记分板分数筛选，支持 N..、..N、N..M 范围", ""),
        ArgDef("x", "坐标 x", "搜索起点 x（可用 ~ 相对坐标）", "0"),
        ArgDef("y", "坐标 y", "搜索起点 y（可用 ~ 相对坐标）", "64"),
        ArgDef("z", "坐标 z", "搜索起点 z（可用 ~ 相对坐标）", "0"),
        ArgDef("dx", "体积 dx", "长方体区域 x 方向尺寸", "10"),
        ArgDef("dy", "体积 dy", "长方体区域 y 方向尺寸", "10"),
        ArgDef("dz", "体积 dz", "长方体区域 z 方向尺寸", "10"),
        ArgDef("rx", "俯仰角上限 rx", "x 旋转最大（-90~90）", "90"),
        ArgDef("rxm", "俯仰角下限 rxm", "x 旋转最小（-90~90）", "-90"),
        ArgDef("ry", "偏航角上限 ry", "y 旋转最大（-180~180）", "180"),
        ArgDef("rym", "偏航角下限 rym", "y 旋转最小（-180~180）", "-180"),
        ArgDef("l", "等级上限 l", "经验等级最大", "20"),
        ArgDef("lm", "等级下限 lm", "经验等级最小", "10"),
        ArgDef("hasitem", "物品 hasitem", "按背包物品筛选：可指定物品、数量、槽位、数据值，支持同时检测多个物品", ""),
    )

    val ARG_DEFS: Map<String, ArgDef> = ARG_ORDER.associateBy { it.key }
}
