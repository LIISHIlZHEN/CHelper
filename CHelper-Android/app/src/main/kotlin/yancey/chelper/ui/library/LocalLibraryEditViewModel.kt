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

package yancey.chelper.ui.library

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import yancey.chelper.data.LocalLibraryEditDraft
import yancey.chelper.network.library.data.LibraryFunction
import yancey.chelper.ui.library.mcd.LineType
import yancey.chelper.ui.library.mcd.validateMCDContent

enum class EditMode {
    ADD,
    UPDATE
}

data class LocalLibraryEditorSnapshot(
    val name: String,
    val version: String,
    val description: String,
    val tags: String,
    val commands: String,
    val autoSync: Boolean,
    val useV2: Boolean
)

data class LocalLibraryTemplate(
    val label: String,
    val content: String,
    val requiresV2: Boolean = false
)

class LocalLibraryEditViewModel : ViewModel() {
    var id by mutableStateOf<Int?>(null)
    var localEntryId by mutableStateOf<String?>(null)
    val mode get() = if (localEntryId == null && id == null) EditMode.ADD else EditMode.UPDATE
    var name by mutableStateOf(TextFieldState())
    var version by mutableStateOf(TextFieldState())
    var description by mutableStateOf(TextFieldState())
    var tags by mutableStateOf(TextFieldState())
    var commands by mutableStateOf(TextFieldState())

    /**
     * 保存时是否顺手把本条本地库同步到云端
     */
    var autoSync by mutableStateOf(false)

    /**
     * 是否使用 MCD V2 语法。
     */
    var useV2 by mutableStateOf(true)
    var isShowDeleteDialog by mutableStateOf(false)
    var isShowLowCodeHelper by mutableStateOf(false)

    // 二次确认
    var isShowV2DowngradeConfirm by mutableStateOf(false)
    var isSyncing by mutableStateOf(false)
    var isShowExitConfirm by mutableStateOf(false)
    var isShowTemplateDialog by mutableStateOf(false)
    var isInitialized by mutableStateOf(false)
        private set
    var draftRestored by mutableStateOf(false)
        private set
    var draftWritesEnabled by mutableStateOf(true)
        private set
    var exitApproved by mutableStateOf(false)
        private set
    private var initialSnapshot: LocalLibraryEditorSnapshot? = null
    private var initializedEditKey: String? = null
    private var initializedAddMode = false

    fun ensureEditingTarget(localEntryId: String?, targetId: Int?, library: LibraryFunction?) {
        this.localEntryId = localEntryId ?: library?.localEntryId
        id = targetId
        if (localEntryId == null && targetId == null) {
            if (!initializedAddMode) {
                if (initializedEditKey != null) {
                    name.setTextAndPlaceCursorAtEnd("")
                    version.setTextAndPlaceCursorAtEnd("")
                    description.setTextAndPlaceCursorAtEnd("")
                    tags.setTextAndPlaceCursorAtEnd("")
                    commands.setTextAndPlaceCursorAtEnd("")
                    autoSync = false
                }
                // 默认偏好V2
                useV2 = true
                initializedEditKey = null
                initializedAddMode = true
                initialSnapshot = snapshot()
                isInitialized = true
            }
            return
        }
        initializedAddMode = false
        val targetKey = localEntryId ?: "index:$targetId"
        if (initializedEditKey == targetKey || library == null) return

        name.setTextAndPlaceCursorAtEnd(library.name ?: "")
        version.setTextAndPlaceCursorAtEnd(library.version ?: "")
        description.setTextAndPlaceCursorAtEnd(library.note ?: "")
        tags.setTextAndPlaceCursorAtEnd(library.tags?.joinToString(separator = ",") ?: "")

        commands.setTextAndPlaceCursorAtEnd(library.localBody().trim('\n', '\r'))
        autoSync = library.autoSync ?: false
        // 从原始 content 推断 V2 标记。容忍带空格写法
        useV2 = library.usesLocalMcdV2()
        initializedEditKey = targetKey
        initialSnapshot = snapshot()
        isInitialized = true
    }

    val draftKey: String
        get() = localEntryId?.let { "local-library:update:$it" }
            ?: id?.let { "local-library:update:index:$it" }
            ?: "local-library:add"

    val isDirty: Boolean
        get() = initialSnapshot?.let { snapshot() != it } == true

    fun snapshot(): LocalLibraryEditorSnapshot = LocalLibraryEditorSnapshot(
        name = name.text.toString(),
        version = version.text.toString(),
        description = description.text.toString(),
        tags = tags.text.toString(),
        commands = commands.text.toString().replace("\r\n", "\n"),
        autoSync = autoSync,
        useV2 = useV2
    )

    fun restoreDraft(draft: LocalLibraryEditDraft?) {
        if (draftRestored) return
        draft?.let {
            name.setTextAndPlaceCursorAtEnd(it.name)
            version.setTextAndPlaceCursorAtEnd(it.version)
            description.setTextAndPlaceCursorAtEnd(it.description)
            tags.setTextAndPlaceCursorAtEnd(it.tags)
            commands.setTextAndPlaceCursorAtEnd(it.commands)
            autoSync = it.autoSync
            useV2 = it.useV2
        }
        draftRestored = true
    }

    fun toDraft(snapshot: LocalLibraryEditorSnapshot = snapshot()): LocalLibraryEditDraft =
        LocalLibraryEditDraft(
            name = snapshot.name,
            version = snapshot.version,
            description = snapshot.description,
            tags = snapshot.tags,
            commands = snapshot.commands,
            autoSync = snapshot.autoSync,
            useV2 = snapshot.useV2,
            updatedAt = System.currentTimeMillis()
        )

    fun buildLocalLibrary(
        existingLibrary: LibraryFunction?,
        requestedLocalEntryId: String?
    ): LibraryFunction {
        val current = snapshot()
        val tagList = current.tags.split(",").map(String::trim).filter(String::isNotEmpty)
        return (existingLibrary ?: LibraryFunction()).copy(
            localEntryId = requestedLocalEntryId ?: existingLibrary?.localEntryId,
            localIsV2 = current.useV2,
            name = current.name,
            version = current.version,
            note = current.description,
            tags = tagList,
            content = current.commands,
            autoSync = current.autoSync,
            localUnsynced = !existingLibrary?.uuid.isNullOrEmpty()
        )
    }

    fun markSaved() {
        draftWritesEnabled = false
        initialSnapshot = snapshot()
    }

    fun approveExit() {
        exitApproved = true
    }

    fun applyTemplate(template: LocalLibraryTemplate) {
        val current = commands.text.toString()
        val next = if (current.isBlank()) template.content else "$current\n\n${template.content}"
        commands.setTextAndPlaceCursorAtEnd(next)
    }

    fun availableTemplates(): List<LocalLibraryTemplate> = buildList {
        add(LocalLibraryTemplate("单条命令", "say hello"))
        add(LocalLibraryTemplate("注释 + 命令", "# 说明这条命令的用途\nsay hello"))
        if (useV2) {
            add(LocalLibraryTemplate("脉冲命令方块", "> I\nsay hello", requiresV2 = true))
            add(LocalLibraryTemplate("连锁命令链", "> I\nsay start\n> C!\nsay next", requiresV2 = true))
            add(LocalLibraryTemplate("循环执行", "> R!\nsay loop", requiresV2 = true))
            add(LocalLibraryTemplate("聊天文本", "> H\n这是一段聊天文本", requiresV2 = true))
        }
    }

    fun validationError(): String? {
        if (name.text.isBlank()) return "名称不能为空"
        if (commands.text.isBlank()) return "执行脚本不能为空"
        if (sequenceOf(name.text, version.text, description.text, tags.text).any { '\n' in it || '\r' in it }) {
            return "名称、版本、描述和标签不能包含换行"
        }
        val body = commands.text.toString()
        if (body.contains("###Function###", ignoreCase = true) ||
            body.contains("###End###", ignoreCase = true) ||
            containsMcdMetadataOutsideChat(body)
        ) {
            return "脚本区只填写命令正文，不要粘贴 MCD 头部或 Function 标记"
        }
        val validation = validateMCDContent(body)
        val firstError = validation.lines.firstOrNull { it.type == LineType.AMBIGUOUS }
        if (firstError != null) {
            return "第 ${firstError.lineNumber} 行无法识别，共 ${validation.errorCount} 行需要修正"
        }
        if (useV2) {
            val stateRegex = Regex(
                """^>\s*([ICRH_])?([?_])?([!_])?(?:t(\d+|_))?\s*$""",
                RegexOption.IGNORE_CASE
            )
            val invalidState = body.lines().withIndex().firstOrNull {
                it.value.trim().startsWith(">") && !stateRegex.matches(it.value.trim())
            }
            if (invalidState != null) return "第 ${invalidState.index + 1} 行不是合法的 V2 状态标记"
            findUnboundV2StateLine(body, stateRegex)?.let { line ->
                return "第 $line 行状态标记没有对应的命令或聊天文本"
            }
        }
        return null
    }

    private fun findUnboundV2StateLine(body: String, stateRegex: Regex): Int? {
        var pendingLine: Int? = null
        var pendingChat = false

        for ((index, line) in body.lines().withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (pendingLine != null) {
                if (pendingChat) {
                    pendingLine = null
                    pendingChat = false
                    continue
                }
                if (trimmed.startsWith("#") || trimmed.startsWith("//")) continue
                if (trimmed.startsWith(">") ||
                    (trimmed.startsWith("---") && trimmed.endsWith("---"))
                ) {
                    return pendingLine
                }
                pendingLine = null
            }

            val match = stateRegex.matchEntire(trimmed) ?: continue
            pendingLine = index + 1
            pendingChat = match.groupValues[1].equals("H", ignoreCase = true)
        }
        return pendingLine
    }

    private fun containsMcdMetadataOutsideChat(body: String): Boolean {
        val metadataLineRegex = Regex(
            """^@(name|version|tags|note|uuid|mcd_version)\s*=""",
            RegexOption.IGNORE_CASE
        )
        val chatStateRegex = Regex(
            """^>\s*H([?_])?([!_])?(?:t(\d+|_))?\s*$""",
            RegexOption.IGNORE_CASE
        )
        var pendingChat = false

        for (line in body.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (pendingChat) {
                pendingChat = false
                continue
            }
            if (chatStateRegex.matches(trimmed)) {
                pendingChat = true
                continue
            }
            if (metadataLineRegex.containsMatchIn(trimmed)) return true
        }
        return false
    }

    /**
     * 把用户输入的 content（不带 MCD 头）拼成完整 MCD 文本
     */
    fun buildFullMCD(
        existingLibrary: LibraryFunction?,
        fallbackUuid: String?
    ): String {
        val tagList = tags.text.toString()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val effectiveUuid = existingLibrary?.uuid?.takeIf { it.isNotEmpty() } ?: fallbackUuid
        val sb = StringBuilder()
        sb.append("@name=${name.text}\n")
        sb.append("@version=${version.text.toString().ifEmpty { "1.0.0" }}\n")
        if (tagList.isNotEmpty()) {
            sb.append("@tags=${tagList.joinToString(",")}\n")
        }
        sb.append("@note=${description.text}\n")
        if (useV2) {
            sb.append("@mcd_version=2\n")
        }
        if (!effectiveUuid.isNullOrEmpty()) {
            sb.append("@uuid=$effectiveUuid\n")
        }
        sb.append("\n")
        sb.append("###Function###\n")
        sb.append(commands.text.toString())
        sb.append("\n###End###")
        return sb.toString()
    }
}
