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

package yancey.chelper.ui.completion

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hjq.toast.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yancey.chelper.android.util.MonitorUtil
import yancey.chelper.core.CHelperCore
import yancey.chelper.core.CommandContext
import yancey.chelper.core.ErrorReason
import yancey.chelper.core.SelectedString
import yancey.chelper.core.Suggestion
import yancey.chelper.data.CopyHistoryDataStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

class CompletionViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    var isShowMenu by mutableStateOf(false)
    var isCommandEditorMode by mutableStateOf(false)
    var command by mutableStateOf(TextFieldState())
    var structure by mutableStateOf<String?>(null)
    var paramHint by mutableStateOf<String?>(null)
    var errorReasons by mutableStateOf<Array<ErrorReason>?>(null)
    var suggestionsSize by mutableIntStateOf(0)
    var suggestionsUpdateTimes by mutableIntStateOf(0)
    var syntaxHighlightTokens by mutableStateOf<IntArray?>(null)
    var nodeCount by mutableIntStateOf(0)
    var core: CHelperCore? = null

    /**
     * 当前命令文本对应的命令上下文，文本内容改变时重新创建
     */
    var context: CommandContext? = null
        private set

    /**
     * 当前补全提示列表对应的光标位置
     * 补全提示是按光标位置计算的，点击补全提示时需要用同一个位置
     */
    private var suggestionIndex = 0

    /**
     * 当前命令上下文对应的文本内容，用于避免文本不变时重复解析
     */
    private var contextText: String? = null
    var lastInput: SelectedString = SelectedString("", 0, 0)
    var syntaxHighlightMaxLength = 20000
    private val copyHistoryDataStore = CopyHistoryDataStore(appContext)
    private val file: File = appContext.filesDir.resolve("cache").resolve("lastInput.dat")
    private var isResumed = false

    fun resumeText() {
        if (isResumed) {
            return
        }
        isResumed = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    file.readCachedCommand()
                }?.let { command = it }
            } catch (_: IOException) {

            }
        }
    }

    /**
     * 获取当前补全提示列表中的其中一个补全提示
     *
     * @param which 第几个补全提示，从0开始
     */
    fun getSuggestion(which: Int): Suggestion? {
        return context?.getSuggestion(suggestionIndex, which)
    }

    fun onSelectionChanged(
        isCheckingBySelection: Boolean,
        isSyntaxHighlight: Boolean,
        isShowErrorReason: Boolean
    ) {
        val core = core ?: return
        val selectedString = SelectedString(
            command.text.toString(),
            min(command.selection.start, command.selection.end),
            max(command.selection.start, command.selection.end)
        )
        val isSyntaxHighlight =
            isSyntaxHighlight && command.text.length < syntaxHighlightMaxLength
        val isUpdateErrorReason = isShowErrorReason || isSyntaxHighlight
        if (selectedString.text.isEmpty()) {
            // 输入内容为空
            lastInput = selectedString
            // 显示欢迎词
            structure = "欢迎使用CHelper"
            // 显示作者信息
            paramHint = "作者：Yancey"
            // 更新错误原因
            if (isUpdateErrorReason) {
                errorReasons = null
            }
            // 清除语法高亮
            syntaxHighlightTokens = null
            // 重新解析命令
            refreshContext(selectedString.text)
            nodeCount = 0
            // 更新补全提示
            suggestionIndex = 0
            suggestionsSize = context?.getSuggestionsSize(0) ?: 0
            suggestionsUpdateTimes++
            return
        }
        if (selectedString.text == lastInput.text) {
            if (selectedString.selectionStart == lastInput.selectionStart) {
                return
            }
            lastInput = selectedString
            // 只有光标改变了
            // 如果关闭了"根据光标位置提供补全提示"，就什么都不做
            if (!isCheckingBySelection) {
                return
            }
            // 文本内容不变，无需重新解析，直接用新的光标位置查询
            suggestionIndex = selectedString.selectionStart
        } else {
            lastInput = selectedString
            // 文本内容改变了，需要重新解析命令
            // 如果关闭了"根据光标位置提供补全提示"，就把光标位置当成在文本最后面
            suggestionIndex = if (isCheckingBySelection) {
                selectedString.selectionStart
            } else {
                selectedString.text.length
            }
            refreshContext(selectedString.text)
            // 更新颜色
            syntaxHighlightTokens = if (isSyntaxHighlight) {
                context?.syntaxToken
            } else {
                null
            }
            // 更新命令语法结构
            structure = context?.structure
            nodeCount = context?.nodeCount ?: 0
            // 更新错误原因
            if (isUpdateErrorReason) {
                errorReasons = context?.errorReasons
            }
        }
        // 更新命令参数介绍
        paramHint = context?.getParamHint(suggestionIndex)
        // 更新补全提示列表
        suggestionsSize = context?.getSuggestionsSize(suggestionIndex) ?: 0
        suggestionsUpdateTimes++
    }

    fun onItemClick(which: Int) {
        val result = context?.applySuggestion(suggestionIndex, which) ?: return
        command.edit {
            replace(0, length, result.text)
            selection = TextRange(
                result.selection,
                result.selection
            )
        }
    }

    /**
     * 文本内容改变时重新解析命令，生成新的命令上下文
     * 文本内容不变时不会重复解析
     */
    private fun refreshContext(text: String) {
        if (context != null && contextText == text) {
            return
        }
        context?.close()
        context = try {
            core?.createContext(text)
        } catch (throwable: Throwable) {
            Log.w("CompletionViewModel", "fail to create CommandContext", throwable)
            null
        }
        contextText = if (context != null) text else null
    }

    fun refreshCHelperCore(
        context: Context,
        cpackBranch: String,
        isCheckingBySelection: Boolean,
        isSyntaxHighlight: Boolean,
        isShowErrorReason: Boolean
    ) {
        if (cpackBranch.isEmpty()) {
            this.context?.close()
            this.context = null
            contextText = null
            core?.close()
            core = null
            nodeCount = 0
            return
        }
        var cpackPath: String? = null
        for (filename in context.assets.list("cpack") ?: return) {
            if (filename.startsWith(cpackBranch)) {
                cpackPath = "cpack/$filename"
            }
        }
        if (cpackPath == null) {
            return
        }
        core.let {
            if (it == null || it.path != cpackPath) {
                var newCore: CHelperCore? = null
                try {
                    newCore = CHelperCore.fromAssets(context.assets, cpackPath)
                } catch (throwable: Throwable) {
                    Toaster.show("资源包加载失败")
                    Log.w("CompletionViewModel", "fail to load resource pack", throwable)
                    MonitorUtil.generateCustomLog(throwable, "LoadResourcePackException")
                }
                if (newCore != null) {
                    this.context?.close()
                    this.context = null
                    contextText = null
                    it?.close()
                    core = newCore
                    lastInput = SelectedString("", 0, 0)
                    onSelectionChanged(isCheckingBySelection, isSyntaxHighlight, isShowErrorReason)
                }
            }
        }
    }

    fun onCopy(content: String) {
        viewModelScope.launch {
            copyHistoryDataStore.add(content)
        }
    }

    override fun onCleared() {
        super.onCleared()
        context?.close()
        context = null
        contextText = null
        core?.close()
        // 保存上次的输入内容
        try {
            file.writeCachedCommand(command)
        } catch (_: IOException) {

        }
    }

    private fun File.readCachedCommand(): TextFieldState? {
        if (!exists()) {
            return null
        }
        return try {
            DataInputStream(BufferedInputStream(inputStream())).use { dataInputStream ->
                val text = dataInputStream.readUTF()
                val start = dataInputStream.readInt()
                val end = dataInputStream.readInt()
                TextFieldState(text, TextRange(start, end))
            }
        } catch (_: EOFException) {
            delete()
            null
        } catch (_: IOException) {
            delete()
            null
        }
    }

    private fun File.writeCachedCommand(command: TextFieldState) {
        parentFile?.mkdirs()
        val tempFile = resolveSibling("$name.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { dataOutputStream ->
            dataOutputStream.writeUTF(command.text.toString())
            dataOutputStream.writeInt(command.selection.start)
            dataOutputStream.writeInt(command.selection.end)
        }
        if (exists() && !delete()) {
            tempFile.delete()
            throw IOException("Failed to replace cached command file: $absolutePath")
        }
        if (!tempFile.renameTo(this)) {
            tempFile.delete()
            throw IOException("Failed to move cached command file into place: $absolutePath")
        }
    }
}
