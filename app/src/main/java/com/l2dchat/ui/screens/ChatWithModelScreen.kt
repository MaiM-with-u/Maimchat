package com.l2dchat.ui.screens

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.l2dchat.chat.MessageBase
import com.l2dchat.chat.service.ChatServiceClient
import com.l2dchat.live2d.ImprovedLive2DRenderer
import com.l2dchat.live2d.Live2DModelLifecycleManager
import com.l2dchat.live2d.Live2DModelManager
import com.l2dchat.logging.L2DLogger
import com.l2dchat.logging.LogModule
import com.l2dchat.preferences.ChatPreferenceKeys
import com.l2dchat.ui.components.LogViewerDialog
import com.l2dchat.wallpaper.Live2DWallpaperService
import com.l2dchat.wallpaper.WallpaperComm
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 固定保留给输入区域的空白高度（模型不绘制到此区域之下）
// 原为 120.dp，按需求缩小约 30% -> 84.dp
private val ReservedBottomHeight = 84.dp
// 顶部 AppBar 高度（防止模型头部被遮或越界），Material3 默认 56.dp
private val TopBarHeight = 56.dp

private data class ConnectionErrorBanner(val id: Long, val message: String)

private val uiLogger = L2DLogger.module(LogModule.MAIN_VIEW)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatWithModelScreen(
        selectedModel: Live2DModelManager.ModelInfo?,
        chatManager: ChatServiceClient,
        modelKey: Int,
        onModelSelectionRequest: () -> Unit,
        onModelChanged: (Live2DModelManager.ModelInfo?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val connectionState by chatManager.connectionState.collectAsState()
    val messages by chatManager.messages.collectAsState()
    val standardMessages by chatManager.standardMessages.collectAsState()
    val currentUserNickname by chatManager.userNickname.collectAsState()
    val prefs =
            remember(context) {
                context.getSharedPreferences(
                        ChatPreferenceKeys.PREFS_NAME,
                        android.content.Context.MODE_PRIVATE
                )
            }
    val wallpaperPrefs =
            remember(context) {
                context.getSharedPreferences(
                        WallpaperComm.PREF_WALLPAPER,
                        android.content.Context.MODE_PRIVATE
                )
            }
    val persistedWallpaperPath =
            remember(wallpaperPrefs) {
                wallpaperPrefs.getString(WallpaperComm.PREF_WALLPAPER_BG_PATH, null)
            }
    var inputText by remember { mutableStateOf("") }
    var showConnectionDialog by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("ws://localhost:8080/ws") }
    var nickname by remember { mutableStateOf(chatManager.getUserNickname() ?: "") }
    var receiverUserId by remember { mutableStateOf("") }
    var receiverUserNickname by remember { mutableStateOf("") }
    var wallpaperBgPath by rememberSaveable { mutableStateOf(persistedWallpaperPath.orEmpty()) }
    var wallpaperTempPath by rememberSaveable { mutableStateOf(persistedWallpaperPath.orEmpty()) }
    var showWallpaperDialog by remember { mutableStateOf(false) }
    var showLogViewer by remember { mutableStateOf(false) }
    var backgroundBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // 可选平台字段（不填写则使用默认）
    var platform by remember { mutableStateOf(chatManager.getPlatform().orEmpty()) }
    // 连接确认弹窗
    var showConnectConfirm by remember { mutableStateOf(false) }
    var chatInputHeightPx by remember { mutableStateOf(0) }
    val connectionErrorBanners = remember { mutableStateListOf<ConnectionErrorBanner>() }
    var suppressMissingUrlWarning by rememberSaveable { mutableStateOf(true) }

    var isLoadingDefaultModel by remember { mutableStateOf(selectedModel == null) }
    var currentModel by remember(modelKey) { mutableStateOf(selectedModel) }
    var isResetting by remember(modelKey) { mutableStateOf(false) }
    var lifecycleManager by
            remember(modelKey) { mutableStateOf<Live2DModelLifecycleManager?>(null) }
    var resetCounter by remember(modelKey) { mutableStateOf(0) }

    val cropLauncher =
            rememberLauncherForActivityResult(StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val output = result.data?.let { UCrop.getOutput(it) }
                    if (output != null) {
                        scope.launch {
                            val copied = copyImageToInternal(context, output)
                            if (copied != null) {
                                wallpaperTempPath = copied
                            } else {
                                uiLogger.error("裁剪后的壁纸复制失败")
                                wallpaperTempPath = ""
                            }
                            deleteTempUri(context, output)
                        }
                    }
                } else if (result.resultCode == UCrop.RESULT_ERROR) {
                    val error = result.data?.let { UCrop.getError(it) }
                    uiLogger.error("裁剪失败", error)
                }
            }

    val imagePickerLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    val destFile =
                            File(
                                    context.cacheDir,
                                    "wallpaper_crop_${System.currentTimeMillis()}.jpg"
                            )
                    val authority = "${context.packageName}.fileprovider"
                    val destUri = FileProvider.getUriForFile(context, authority, destFile)
                    val options =
                            UCrop.Options().apply {
                                setCompressionFormat(Bitmap.CompressFormat.JPEG)
                                setCompressionQuality(95)
                                setHideBottomControls(false)
                                setFreeStyleCropEnabled(true)
                            }
                    try {
                        val metrics = context.resources.displayMetrics
                        val aspectX = metrics.widthPixels.coerceAtLeast(1)
                        val aspectY = metrics.heightPixels.coerceAtLeast(1)
                        val intent =
                                UCrop.of(uri, destUri)
                                        .withOptions(options)
                                        .withAspectRatio(aspectX.toFloat(), aspectY.toFloat())
                                        .withMaxResultSize(2048, 2048)
                                        .getIntent(context)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        grantUriToCropApp(context, destUri)
                        cropLauncher.launch(intent)
                    } catch (e: Exception) {
                        uiLogger.error("启动裁剪失败", e)
                        context.deleteTempCacheFile(destFile)
                    }
                }
            }

    LaunchedEffect(chatManager) {
        val missingUrlKeywords = listOf("未设置服务器地址", "未提供有效的服务器地址", "未配置连接地址", "尚未配置 WebSocket URL")
        chatManager.errors.collect { raw ->
            val message = raw.trim().ifEmpty { "连接出现未知错误" }
            val suppressThis =
                    suppressMissingUrlWarning &&
                            missingUrlKeywords.any { keyword -> keyword in message }
            if (suppressThis) return@collect

            val entry = ConnectionErrorBanner(System.nanoTime(), message)
            connectionErrorBanners.add(entry)
            if (connectionErrorBanners.size > 5) {
                connectionErrorBanners.removeAt(0)
            }
        }
    }

    LaunchedEffect(Unit) {
        prefs.getString("last_url", null)?.let { serverUrl = it }
        prefs.getString("nickname", null)?.let {
            nickname = it
            if (it.isNotBlank()) chatManager.setUserProfile(it)
        }
        prefs.getString("receiver_user_id", null)?.let { receiverUserId = it }
        prefs.getString("receiver_user_nickname", null)?.let { receiverUserNickname = it }
        prefs.getString("platform", null)?.let {
            val storedPlatform = it.trim()
            platform = storedPlatform
            chatManager.updatePlatformPreference(storedPlatform)
        }
        if (receiverUserId.isNotBlank() || receiverUserNickname.isNotBlank()) {
            chatManager.setReceiverInfo(
                    receiverUserId.ifBlank { null },
                    receiverUserNickname.ifBlank { null }
            )
        }
        wallpaperPrefs.getString(WallpaperComm.PREF_WALLPAPER_BG_PATH, null)?.let {
            if (wallpaperBgPath != it) wallpaperBgPath = it
            if (wallpaperTempPath != it) wallpaperTempPath = it
        }
    }

    LaunchedEffect(wallpaperBgPath) {
        val newBitmap = wallpaperBgPath.takeIf { it.isNotBlank() }?.let { loadBackgroundBitmap(it) }
        val previous = backgroundBitmap
        backgroundBitmap = newBitmap
        if (previous != null && previous != newBitmap && !previous.isRecycled) {
            previous.recycle()
        }
    }

    LaunchedEffect(lifecycleManager, wallpaperBgPath) {
        lifecycleManager?.updateBackgroundTexture(wallpaperBgPath.takeIf { it.isNotBlank() })
    }

    DisposableEffect(Unit) { onDispose { backgroundBitmap?.takeIf { !it.isRecycled }?.recycle() } }

    LaunchedEffect(currentModel) {
        val folder = currentModel?.folderPath
        val editor = wallpaperPrefs.edit()
        if (folder != null) {
            editor.putString(WallpaperComm.PREF_WALLPAPER_MODEL_FOLDER, folder)
        } else {
            editor.remove(WallpaperComm.PREF_WALLPAPER_MODEL_FOLDER)
        }
        editor.apply()
        sendWallpaperModelBroadcast(context, folder)
    }

    LaunchedEffect(modelKey) {
        if (selectedModel == null) {
            isLoadingDefaultModel = true
            val storedFolder = prefs.getString(ChatPreferenceKeys.SELECTED_MODEL_FOLDER, null)
            scope.launch {
                try {
                    val models = Live2DModelManager.scanModels(context)
                    val preferred =
                            storedFolder?.let { folder ->
                                models.firstOrNull { it.folderPath == folder }
                            }
                    val fallback =
                            models.find {
                                it.folderPath.contains("hiyori", true) ||
                                        it.name.contains("hiyori", true)
                            }
                                    ?: models.firstOrNull()
                    val resolved = preferred ?: fallback
                    if (resolved != null) {
                        currentModel = resolved
                        onModelChanged(resolved)
                    }
                } catch (_: Exception) {} finally {
                    isLoadingDefaultModel = false
                }
            }
        } else {
            currentModel = selectedModel
            isLoadingDefaultModel = false
        }
    }

    LaunchedEffect(currentModel, modelKey) {
        if (currentModel != null) {
            isResetting = true
            try {
                lifecycleManager?.destroy()
                lifecycleManager = null
                resetCubismFramework()
                delay(200)
                val newManager = Live2DModelLifecycleManager.create(context, currentModel!!)
                newManager.setStateCallback(
                        object : Live2DModelLifecycleManager.StateCallback {
                            override fun onStateChanged(
                                    newState: Live2DModelLifecycleManager.LifecycleState,
                                    message: String?
                            ) {}
                            override fun onError(error: String, exception: Throwable?) {}
                        }
                )
                if (newManager.initialize()) {
                    lifecycleManager = newManager
                    chatManager.setMotionTriggerCallback { group, index, loop ->
                        newManager.playMotionByGroup(group, index, loop)
                    }
                    chatManager.clearMessagesEphemeral()
                    chatManager.setActiveModel(currentModel?.name)
                }
            } catch (_: Exception) {} finally {
                isResetting = false
                resetCounter++
            }
        }
    }

    DisposableEffect(modelKey) { onDispose { lifecycleManager?.destroy() } }

    val chatInputHeightDp = with(LocalDensity.current) { chatInputHeightPx.toDp() }
    val floatingBottomPadding = maxOf(ReservedBottomHeight, chatInputHeightDp) + 8.dp

    Box(modifier = Modifier.fillMaxSize()) {
        backgroundBitmap?.let { bmp ->
            Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "聊天背景",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
            )
        }
                ?: Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101010)))

        if (isLoadingDefaultModel) {
            Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("正在加载默认模型...")
            }
        } else if (isResetting) {
            Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("正在重置模型...")
                Spacer(modifier = Modifier.height(8.dp))
                Text("正在清理资源并重新初始化", style = MaterialTheme.typography.bodySmall)
            }
        } else if (currentModel != null) {
            // 重新布局：模型始终全屏放在最底层；TopBar + 消息 + 输入框作为浮层，不再挤压 GLSurfaceView 尺寸
            Box(modifier = Modifier.fillMaxSize()) {
                // 使用 Column 保留底部固定高度空白区域，防止模型绘制与输入区重叠
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                            modifier =
                                    Modifier.weight(1f)
                                            .fillMaxWidth()
                                            // 下移模型绘制区域，避免头像穿过顶部栏
                                            .padding(top = TopBarHeight)
                    ) {
                        Live2DModelViewer(
                                model = currentModel!!,
                                modelKey = resetCounter,
                                chatManager = chatManager,
                                lifecycleManager = lifecycleManager,
                                modifier = Modifier.fillMaxSize()
                        )
                    }
                    // 固定空白区域，不随输入框/键盘变化
                    Spacer(modifier = Modifier.fillMaxWidth().height(ReservedBottomHeight))
                }

                // 顶部栏浮层
                var overflowExpanded by remember { mutableStateOf(false) }
                TopAppBar(
                        title = {
                            Column {
                                Text(currentModel!!.name)
                                Text(
                                        text =
                                                chatManager.getConnectionStateDescription() +
                                                        when (connectionState) {
                                                            ChatServiceClient.ChatConnectionState
                                                                    .CONNECTING -> " (校验配置...)"
                                                            else -> ""
                                                        },
                                        style = MaterialTheme.typography.bodySmall
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { applyLiveWallpaper(context) }) {
                                Icon(Icons.Default.Wallpaper, contentDescription = "应用为系统壁纸")
                            }
                            IconButton(
                                    onClick = {
                                        wallpaperTempPath = wallpaperBgPath
                                        showWallpaperDialog = true
                                    }
                            ) { Icon(Icons.Default.Image, contentDescription = "壁纸背景设置") }
                            // 配置按钮
                            IconButton(onClick = { showConnectionDialog = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "连接配置")
                            }
                            // 连接按钮（仅在未连接时显示）
                            if (connectionState ==
                                            ChatServiceClient.ChatConnectionState.DISCONNECTED ||
                                            connectionState ==
                                                    ChatServiceClient.ChatConnectionState.ERROR
                            ) {
                                IconButton(
                                        onClick = {
                                            val errors = validateConfig(serverUrl, nickname)
                                            uiLogger.debug(
                                                    "Connect action tapped state=${connectionState.name} url=$serverUrl nickname=$nickname errors=${errors.joinToString()}"
                                            )
                                            if (errors.isEmpty()) {
                                                showConnectConfirm = true
                                            } else {
                                                // 打开配置对话框并提示
                                                showConnectionDialog = true
                                            }
                                        }
                                ) { Icon(Icons.Filled.PlayArrow, contentDescription = "连接") }
                            }
                            // 断开按钮（仅在连接中或已连接时显示）
                            if (connectionState ==
                                            ChatServiceClient.ChatConnectionState.CONNECTED ||
                                            connectionState ==
                                                    ChatServiceClient.ChatConnectionState.CONNECTING
                            ) {
                                IconButton(onClick = { chatManager.disconnect() }) {
                                    Icon(Icons.Filled.Stop, contentDescription = "断开")
                                }
                            }
                            Box {
                                IconButton(onClick = { overflowExpanded = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                                }
                                DropdownMenu(
                                        expanded = overflowExpanded,
                                        onDismissRequest = { overflowExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                            text = { Text("查看日志") },
                                            onClick = {
                                                overflowExpanded = false
                                                showLogViewer = true
                                                uiLogger.info(
                                                        "Log viewer opened from overflow menu"
                                                )
                                            }
                                    )
                                    DropdownMenuItem(
                                            text = { Text("更换模型") },
                                            onClick = {
                                                overflowExpanded = false
                                                onModelSelectionRequest()
                                            }
                                    )
                                    DropdownMenuItem(
                                            text = { Text("清空聊天记录") },
                                            onClick = {
                                                overflowExpanded = false
                                                chatManager.clearMessages()
                                            }
                                    )
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.TopCenter)
                )

                if (connectionErrorBanners.isNotEmpty()) {
                    Column(
                            modifier =
                                    Modifier.align(Alignment.TopStart)
                                            .padding(
                                                    start = 12.dp,
                                                    top = TopBarHeight + 12.dp,
                                                    end = 12.dp
                                            )
                                            .widthIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        connectionErrorBanners.forEach { banner ->
                            key(banner.id) {
                                LaunchedEffect(banner.id) {
                                    delay(6_000)
                                    connectionErrorBanners.remove(banner)
                                }
                                ConnectionErrorToast(
                                        message = banner.message,
                                        onDismiss = { connectionErrorBanners.remove(banner) }
                                )
                            }
                        }
                    }
                }

                FloatingMessagesOverlay(
                        recentMessages = messages,
                        standardMessages = standardMessages,
                        userNickname = currentUserNickname,
                        modifier =
                                Modifier.align(Alignment.BottomStart)
                                        .padding(start = 12.dp, bottom = floatingBottomPadding)
                )

                ChatInputBar(
                        inputText = inputText,
                        onInputChange = { inputText = it },
                        enabled =
                                connectionState == ChatServiceClient.ChatConnectionState.CONNECTED,
                        onSend = {
                            if (inputText.isNotBlank()) {
                                if (!chatManager.hasUserNickname()) {
                                    // 没有昵称则打开配置弹窗让用户填写
                                    showConnectionDialog = true
                                } else {
                                    chatManager.sendUserMessage(inputText.trim())
                                    inputText = ""
                                }
                            }
                        },
                        modifier =
                                Modifier.align(Alignment.BottomCenter).onSizeChanged { coords ->
                                    val newHeight = coords.height
                                    if (chatInputHeightPx != newHeight) {
                                        chatInputHeightPx = newHeight
                                    }
                                }
                )
            }
        } else {
            Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("没有找到可用的Live2D模型")
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onModelSelectionRequest) { Text("选择模型") }
            }
        }
        if (showConnectionDialog) {
            ConnectionConfigDialog(
                    currentUrl = serverUrl,
                    nickname = nickname,
                    platform = platform,
                    receiverUserId = receiverUserId,
                    receiverUserNickname = receiverUserNickname,
                    onUrlChange = { serverUrl = it },
                    onNicknameChange = { nickname = it },
                    onPlatformChange = { platform = it },
                    onReceiverUserIdChange = { receiverUserId = it },
                    onReceiverUserNicknameChange = { receiverUserNickname = it },
                    onSave = { valid ->
                        if (valid) {
                            val sanitizedPlatform = platform.trim()
                            platform = sanitizedPlatform
                            chatManager.updatePlatformPreference(sanitizedPlatform)
                            chatManager.setUserProfile(nickname)
                            chatManager.setReceiverInfo(
                                    receiverUserId.ifBlank { null },
                                    receiverUserNickname.ifBlank { null }
                            )
                            prefs.edit()
                                    .putString("last_url", serverUrl)
                                    .putString("nickname", nickname)
                                    .putString("platform", sanitizedPlatform.ifBlank { null })
                                    .putString("receiver_user_id", receiverUserId.ifBlank { null })
                                    .putString(
                                            "receiver_user_nickname",
                                            receiverUserNickname.ifBlank { null }
                                    )
                                    .apply()
                            showConnectionDialog = false
                        }
                    },
                    onDismiss = { showConnectionDialog = false }
            )
        }
        if (showLogViewer) {
            LogViewerDialog(onDismiss = { showLogViewer = false })
        }
        if (showConnectConfirm) {
            AlertDialog(
                    onDismissRequest = { showConnectConfirm = false },
                    title = { Text("确认连接配置") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("URL: $serverUrl")
                            Text("我的昵称: ${nickname.ifBlank { "(未填写)" }}")
                            val previewPlatform = platform.trim()
                            Text("Platform: ${previewPlatform.ifBlank { "(默认)" }}")
                            Text(
                                    "对方身份: " +
                                            listOf(
                                                            receiverUserNickname.takeIf {
                                                                it.isNotBlank()
                                                            },
                                                            receiverUserId.takeIf {
                                                                it.isNotBlank()
                                                            }
                                                    )
                                                    .filterNotNull()
                                                    .joinToString(" / ")
                                                    .ifBlank { "(未设置)" }
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                                onClick = {
                                    showConnectConfirm = false
                                    // 最终连接
                                    val sanitizedPlatform = platform.trim()
                                    platform = sanitizedPlatform
                                    chatManager.setUserProfile(nickname)
                                    chatManager.setReceiverInfo(
                                            receiverUserId.ifBlank { null },
                                            receiverUserNickname.ifBlank { null }
                                    )
                                    chatManager.updatePlatformPreference(sanitizedPlatform)
                                    suppressMissingUrlWarning = false
                                    uiLogger.info(
                                            "Confirm connect triggered url=$serverUrl platform=$sanitizedPlatform nickname=$nickname receiverId=${receiverUserId.ifBlank { "(null)" }}"
                                    )
                                    chatManager.connect(serverUrl, sanitizedPlatform)
                                }
                        ) { Text("连接") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConnectConfirm = false }) { Text("取消") }
                    }
            )
        }
        if (showWallpaperDialog) {
            WallpaperSettingsDialog(
                    currentPath = wallpaperBgPath,
                    tempPath = wallpaperTempPath,
                    onPickImage = { imagePickerLauncher.launch("image/*") },
                    onClearImage = { wallpaperTempPath = "" },
                    onApply = {
                        val finalPath = wallpaperTempPath.ifBlank { null }
                        wallpaperBgPath = finalPath.orEmpty()
                        wallpaperTempPath = wallpaperBgPath
                        val saveSucceeded =
                                wallpaperPrefs
                                        .edit()
                                        .apply {
                                            if (finalPath != null) {
                                                putString(
                                                        WallpaperComm.PREF_WALLPAPER_BG_PATH,
                                                        finalPath
                                                )
                                            } else {
                                                remove(WallpaperComm.PREF_WALLPAPER_BG_PATH)
                                            }
                                        }
                                        .commit()
                        uiLogger.debug(
                                "保存壁纸路径${if (saveSucceeded) "成功" else "失败"}: ${finalPath ?: "(清除)"}"
                        )
                        lifecycleManager?.updateBackgroundTexture(finalPath)
                        sendWallpaperRefreshBroadcast(context, finalPath)
                        showWallpaperDialog = false
                    },
                    onDismiss = {
                        wallpaperTempPath = wallpaperBgPath
                        showWallpaperDialog = false
                    }
            )
        }
    }
}

@Composable
private fun ChatInputBar(
        inputText: String,
        onInputChange: (String) -> Unit,
        enabled: Boolean,
        onSend: () -> Unit,
        modifier: Modifier = Modifier
) {
    Surface(
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            modifier = modifier.fillMaxWidth().imePadding().navigationBarsPadding()
    ) {
        Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息...") },
                    maxLines = 4,
                    enabled = enabled
            )
            Spacer(modifier = Modifier.width(8.dp))
            FloatingActionButton(
                    onClick = onSend,
                    modifier = Modifier.size(48.dp),
                    containerColor = MaterialTheme.colorScheme.primary
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送") }
        }
    }
}

private suspend fun resetCubismFramework() {
    try {
        ImprovedLive2DRenderer.safeShutdownFramework()
        delay(100)
        ImprovedLive2DRenderer.ensureFrameworkInitialized()
    } catch (_: Exception) {}
}

@Composable
@Suppress("UNUSED_PARAMETER")
fun Live2DModelViewer(
        model: Live2DModelManager.ModelInfo,
        modelKey: Int,
        chatManager: ChatServiceClient,
        lifecycleManager: Live2DModelLifecycleManager?,
        modifier: Modifier = Modifier
) {
    var errorMessage by remember(modelKey) { mutableStateOf<String?>(null) }
    Box(modifier = modifier) {
        if (lifecycleManager != null && errorMessage == null) {
            val glSurfaceView = remember(modelKey) { lifecycleManager.createGLSurfaceView() }
            if (glSurfaceView != null) {
                AndroidView(
                        factory = { glSurfaceView },
                        modifier = Modifier.fillMaxSize(),
                        update = { lifecycleManager.startRendering() }
                )
            } else {
                Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("无法创建渲染视图")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Key: $modelKey", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else if (errorMessage != null) {
            Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error)
            }
        } else {
            Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("正在加载模型...")
                Spacer(modifier = Modifier.height(8.dp))
                Text("Key: $modelKey", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ConnectionErrorToast(message: String, onDismiss: () -> Unit) {
    Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            shape = RoundedCornerShape(12.dp)
    ) {
        Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
            )
            Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, fill = false)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "关闭错误提示")
            }
        }
    }
}

@Composable
private fun FloatingMessagesOverlay(
        recentMessages: List<ChatServiceClient.ChatMessageSnapshot>,
        standardMessages: List<MessageBase>,
        userNickname: String?,
        modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val tail = remember(recentMessages) { recentMessages.takeLast(5) }
    val senderNameById =
            remember(standardMessages) {
                val m = mutableMapOf<String, String>()
                standardMessages.forEach { mb ->
                    val id = mb.messageInfo.messageId
                    if (!id.isNullOrBlank()) {
                        val sInfo = mb.messageInfo.senderInfo?.userInfo
                        val name =
                                sInfo?.userNickname?.takeIf { !it.isNullOrBlank() }
                                        ?: sInfo?.userId?.takeIf { !it.isNullOrBlank() } ?: "对方"
                        m[id] = name
                    }
                }
                m
            }
    val hiddenMap = remember { mutableStateMapOf<String, Boolean>() }
    val fadingSet = remember { mutableStateMapOf<String, Boolean>() }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tail.forEachIndexed { index, msg ->
            if ((hiddenMap[msg.id] ?: false)) return@forEachIndexed
            val animAlpha = remember(msg.id) { Animatable(1f) }
            val shouldFadeOut = tail.size >= 5 && index == 0
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                            if (msg.isFromUser) Arrangement.End else Arrangement.Start
            ) {
                Card(
                        colors =
                                CardDefaults.cardColors(
                                        containerColor =
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                                ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.alpha(animAlpha.value)
                ) {
                    Column(modifier = Modifier.padding(10.dp).widthIn(max = 320.dp)) {
                        val title =
                                if (msg.isFromUser) (userNickname ?: "我")
                                else (senderNameById[msg.id] ?: "对方")
                        Text(
                                text = title,
                                style = MaterialTheme.typography.labelMedium,
                                color =
                                        if (msg.isFromUser) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = msg.content)
                    }
                }
                LaunchedEffect(shouldFadeOut) {
                    if (shouldFadeOut && fadingSet[msg.id] != true) {
                        fadingSet[msg.id] = true
                        scope.launch {
                            animAlpha.animateTo(0f, animationSpec = tween(durationMillis = 2000))
                            hiddenMap[msg.id] = true
                        }
                    }
                }
            }
        }
    }
}

private fun applyLiveWallpaper(context: Context) {
    val component = ComponentName(context, Live2DWallpaperService::class.java)
    val activity = context.findActivity()

    fun launch(intent: Intent): Boolean {
        return try {
            if (activity != null) {
                activity.startActivity(intent)
            } else {
                context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            true
        } catch (t: Throwable) {
            uiLogger.warn("启动壁纸意图失败: ${intent.action}", t)
            false
        }
    }

    val pm = context.packageManager
    val directIntent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                    putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
                }
            } else {
                null
            }

    val launchedDirect =
            directIntent?.takeIf { it.resolveActivity(pm) != null }?.let { launch(it) } ?: false

    if (!launchedDirect) {
        val chooserIntent =
                Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
                    }
                }
        if (!launch(chooserIntent)) {
            Toast.makeText(context, "无法打开系统壁纸设置，请前往系统设置手动选择", Toast.LENGTH_LONG).show()
        }
    }
}

private fun validateConfig(url: String, nickname: String): List<String> {
    val errors = mutableListOf<String>()
    if (nickname.isBlank()) errors.add("昵称不能为空")
    if (url.isBlank()) errors.add("URL 不能为空")
    else if (!(url.startsWith("ws://") || url.startsWith("wss://")))
            errors.add("URL 必须以 ws:// 或 wss:// 开头")
    return errors
}

private tailrec fun Context.findActivity(): Activity? =
        when (this) {
            is Activity -> this
            is android.content.ContextWrapper -> baseContext.findActivity()
            else -> null
        }

@Composable
private fun ConnectionConfigDialog(
        currentUrl: String,
        nickname: String,
        platform: String,
        receiverUserId: String,
        receiverUserNickname: String,
        onUrlChange: (String) -> Unit,
        onNicknameChange: (String) -> Unit,
        onPlatformChange: (String) -> Unit,
        onReceiverUserIdChange: (String) -> Unit,
        onReceiverUserNicknameChange: (String) -> Unit,
        onSave: (Boolean) -> Unit,
        onDismiss: () -> Unit
) {
    var tempUrl by remember { mutableStateOf(currentUrl) }
    var tempNickname by remember { mutableStateOf(nickname) }
    var tempPlatform by remember { mutableStateOf(platform) }
    var tempRecvId by remember { mutableStateOf(receiverUserId) }
    var tempRecvNick by remember { mutableStateOf(receiverUserNickname) }
    var showErrors by remember { mutableStateOf(false) }
    val errors = remember(tempUrl, tempNickname) { validateConfig(tempUrl, tempNickname) }
    AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("连接配置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                            value = tempUrl,
                            onValueChange = {
                                tempUrl = it
                                onUrlChange(it)
                            },
                            label = { Text("WebSocket 地址") },
                            placeholder = { Text("ws://host:port/path") },
                            singleLine = true,
                            isError = showErrors && errors.any { it.contains("URL") }
                    )
                    OutlinedTextField(
                            value = tempNickname,
                            onValueChange = {
                                tempNickname = it
                                onNicknameChange(it)
                            },
                            label = { Text("我的昵称 (必填)") },
                            placeholder = { Text("请输入昵称") },
                            singleLine = true,
                            isError = showErrors && errors.any { it.contains("昵称") }
                    )
                    OutlinedTextField(
                            value = tempPlatform,
                            onValueChange = {
                                tempPlatform = it
                                onPlatformChange(it)
                            },
                            label = { Text("Platform(可选，默认: ${chatManagerPlatformDefault()})") },
                            placeholder = { Text("留空使用默认") },
                            singleLine = true
                    )
                    HorizontalDivider()
                    OutlinedTextField(
                            value = tempRecvNick,
                            onValueChange = {
                                tempRecvNick = it
                                onReceiverUserNicknameChange(it)
                            },
                            label = { Text("对方昵称(可选)") },
                            singleLine = true
                    )
                    OutlinedTextField(
                            value = tempRecvId,
                            onValueChange = {
                                tempRecvId = it
                                onReceiverUserIdChange(it)
                            },
                            label = { Text("对方ID(可选)") },
                            singleLine = true
                    )
                    if (showErrors && errors.isNotEmpty()) {
                        errors.forEach { err ->
                            Text(
                                    err,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        val summary = buildString {
                            append("将使用此配置进行连接：\n")
                            append("URL: ${tempUrl}\n")
                            append("我: ${tempNickname.ifBlank { "(未填写)" }}\n")
                            append(
                                    "Platform: ${tempPlatform.trim().ifBlank { "(默认:${chatManagerPlatformDefault()})" }}\n"
                            )
                            append(
                                    "对方: " +
                                            listOf(
                                                            tempRecvNick.takeIf { it.isNotBlank() },
                                                            tempRecvId.takeIf { it.isNotBlank() }
                                                    )
                                                    .filterNotNull()
                                                    .joinToString(" / ")
                                                    .ifBlank { "(未设置)" }
                            )
                        }
                        Text(
                                summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Text(
                            text = "示例: ws://[host]:[port]/ws",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                    )
                }
            },
            confirmButton = {
                TextButton(
                        onClick = {
                            if (errors.isEmpty()) {
                                onSave(true)
                            } else {
                                showErrors = true
                            }
                        }
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// 提供默认 platform（保持与服务端默认值一致）
private fun chatManagerPlatformDefault(): String = "live2d_chat"

private suspend fun loadBackgroundBitmap(path: String): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 2048)
                val decodeOptions =
                        BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                BitmapFactory.decodeFile(path, decodeOptions)
            } catch (e: Exception) {
                uiLogger.error("加载背景位图失败", e)
                null
            }
        }

private suspend fun copyImageToInternal(context: Context, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            try {
                val dir = File(context.filesDir, "wallpaper")
                if (!dir.exists()) dir.mkdirs()

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openInputStream(context, uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

                val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 2048)
                val decodeOptions =
                        BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }

                val decoded =
                        openInputStream(context, uri)?.use { input ->
                            BitmapFactory.decodeStream(input, null, decodeOptions)
                        }
                                ?: return@withContext null

                val processedBitmap =
                        if (max(decoded.width, decoded.height) > 2048) {
                            val scale = 2048f / max(decoded.width, decoded.height)
                            Bitmap.createScaledBitmap(
                                            decoded,
                                            (decoded.width * scale).roundToInt().coerceAtLeast(1),
                                            (decoded.height * scale).roundToInt().coerceAtLeast(1),
                                            true
                                    )
                                    .also { decoded.recycle() }
                        } else {
                            decoded
                        }

                val mime = resolver.getType(uri)?.lowercase().orEmpty()
                val isPng = mime.contains("png")
                val extension = if (isPng) "png" else "jpg"
                val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                val targetFile = File(dir, "wallpaper_${System.currentTimeMillis()}.$extension")

                FileOutputStream(targetFile).use { output ->
                    processedBitmap.compress(format, 90, output)
                    output.flush()
                }

                processedBitmap.recycle()

                dir.listFiles()?.forEach { file ->
                    if (file.absolutePath != targetFile.absolutePath &&
                                    file.name.startsWith("wallpaper_")
                    ) {
                        file.delete()
                    }
                }

                targetFile.absolutePath
            } catch (e: Exception) {
                uiLogger.error("复制壁纸图片失败", e)
                null
            }
        }

private fun deleteTempUri(context: Context, uri: Uri) {
    when (uri.scheme?.lowercase()) {
        "file" -> {
            val path = uri.path
            if (!path.isNullOrBlank()) {
                try {
                    File(path).takeIf { it.exists() }?.delete()
                } catch (_: Exception) {}
            }
        }
        "content" -> {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (_: Exception) {}
        }
    }
}

private fun openInputStream(context: Context, uri: Uri): InputStream? {
    return try {
        when (uri.scheme?.lowercase()) {
            "content" -> context.contentResolver.openInputStream(uri)
            "file" ->
                    uri.path?.let { path ->
                        File(path).takeIf { it.exists() }?.let { FileInputStream(it) }
                    }
            else -> context.contentResolver.openInputStream(uri)
        }
    } catch (e: Exception) {
        uiLogger.error("打开图片流失败", e)
        null
    }
}

private fun Context.deleteTempCacheFile(file: File) {
    try {
        if (file.exists()) file.delete()
    } catch (_: Exception) {}
}

private fun grantUriToCropApp(context: Context, uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    try {
        context.grantUriPermission(context.packageName, uri, flags)
        context.grantUriPermission("com.yalantis.ucrop", uri, flags)
    } catch (e: Exception) {
        uiLogger.warn("授权裁剪应用访问 URI 失败", e)
    }
}

private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
    if (width <= 0 || height <= 0 || maxDim <= 0) return 1
    var sampleSize = 1
    while (max(width / sampleSize, height / sampleSize) > maxDim) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun sendWallpaperRefreshBroadcast(context: Context, path: String?) {
    val intent =
            Intent(WallpaperComm.ACTION_REFRESH_BACKGROUND).apply {
                putExtra(WallpaperComm.EXTRA_BACKGROUND_PATH, path)
            }
    context.sendBroadcast(intent)
}

private fun sendWallpaperModelBroadcast(context: Context, folder: String?) {
    val intent =
            Intent(WallpaperComm.ACTION_REFRESH_MODEL).apply {
                putExtra(WallpaperComm.EXTRA_MODEL_FOLDER, folder)
            }
    context.sendBroadcast(intent)
}
