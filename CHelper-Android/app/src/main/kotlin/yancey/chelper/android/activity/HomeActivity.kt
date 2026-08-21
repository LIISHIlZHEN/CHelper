/**
 * It is part of CHelper. CHelper is a command helper for Minecraft Bedrock Edition.
 * Copyright (C) 2026  Yancey
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package yancey.chelper.android.activity

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.toast.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yancey.chelper.android.util.MonitorUtil
import yancey.chelper.android.window.FloatingWindowManager
import yancey.chelper.data.BackgroundStore
import yancey.chelper.ui.NavHost
import yancey.chelper.ui.CommandLabDeepLink
import yancey.chelper.ui.CommandLabDeepLinkParser
import yancey.chelper.ui.openCommandLabDeepLink
import java.io.BufferedInputStream
import java.io.IOException

/**
 * 首页
 */
class HomeActivity : BaseComposeActivity() {
    private lateinit var floatingWindowManager: FloatingWindowManager
    private var backgroundJob: Job? = null
    private lateinit var onBackPressedCallback: OnBackPressedCallback
    private lateinit var photoPicker: ActivityResultLauncher<PickVisualMediaRequest>
    private var isShowSavingBackgroundDialog = mutableStateOf(false)
    private var pendingDeepLink by mutableStateOf<CommandLabDeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        floatingWindowManager = FloatingWindowManager(application)
        onBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (backgroundJob != null && backgroundJob!!.isActive) {
                    isShowSavingBackgroundDialog.value = true
                }
            }
        }
        onBackPressedDispatcher.addCallback(onBackPressedCallback)
        photoPicker =
            registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { setBackground(it) }
        queueDeepLink(intent)
        setContent {
            val navController = rememberNavController()
            val deepLink = pendingDeepLink
            LaunchedEffect(deepLink) {
                deepLink?.let {
                    navController.currentBackStackEntryFlow.first()
                    navController.openCommandLabDeepLink(it)
                    pendingDeepLink = null
                }
            }
            NavHost(
                navController = navController,
                floatingWindowManager = floatingWindowManager,
                chooseBackground = this::chooseBackground,
                restoreBackground = this::restoreBackground,
                isShowSavingBackgroundDialog = isShowSavingBackgroundDialog,
                shutdown = this::finishAffinity,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        queueDeepLink(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingWindowManager.stopFloatingWindow()
    }

    private fun queueDeepLink(intent: Intent?) {
        CommandLabDeepLinkParser.parse(intent?.dataString)?.let {
            pendingDeepLink = it
        }
    }

    private fun chooseBackground() {
        if (XXPermissions.isGrantedPermission(
                this,
                PermissionLists.getReadMediaImagesPermission()
            )
        ) {
            photoPicker.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .build()
            )
        } else {
            XXPermissions.with(this)
                .permission(PermissionLists.getReadMediaImagesPermission())
                .request { _, deniedList ->
                    if (deniedList.isEmpty()) {
                        Toaster.show("图片访问权限申请成功")
                    } else {
                        Toaster.show("图片访问权限申请失败")
                    }
                }
        }
    }

    private fun setBackground(uri: Uri?) {
        backgroundJob?.cancel()
        if (uri == null) {
            return
        }
        backgroundJob = lifecycleScope.launch {
            onBackPressedCallback.isEnabled = true
            try {
                withContext(Dispatchers.Default) {
                    val bitmap = contentResolver.openInputStream(uri)?.use { inputStream ->
                        BufferedInputStream(inputStream).use {
                            BitmapFactory.decodeStream(it)
                        }
                    } ?: throw IOException("无法打开图片")
                    BackgroundStore.INSTANCE.setBackGroundDrawable(bitmap)
                }
            } catch (e: Exception) {
                Toaster.show(e.message ?: "未知错误")
            } finally {
                onBackPressedCallback.isEnabled = false
                isShowSavingBackgroundDialog.value = false
            }
        }
    }

    private fun restoreBackground() {
        try {
            BackgroundStore.INSTANCE.setBackGroundDrawable(null)
        } catch (e: IOException) {
            Toaster.show(e.message)
            MonitorUtil.generateCustomLog(e, "ResetBackgroundException")
        }
    }
}
