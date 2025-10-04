package com.l2dchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import com.l2dchat.chat.service.ChatServiceClient
import com.l2dchat.live2d.ImprovedLive2DRenderer
import com.l2dchat.live2d.Live2DModelManager
import com.l2dchat.ui.screens.ChatWithModelScreen
import com.l2dchat.ui.screens.ModelSelectionDialog
import com.l2dchat.ui.theme.L2DChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ImprovedLive2DRenderer.ensureFrameworkInitialized()
        setContent { L2DChatTheme { Live2DChatApp() } }
    }
    override fun onDestroy() {
        ImprovedLive2DRenderer.safeShutdownFramework()
        super.onDestroy()
    }
}

@Composable
fun Live2DChatApp() {
    var currentScreen by remember { mutableStateOf(ChatAppScreen.ModelChat) }
    var selectedModel by remember { mutableStateOf<Live2DModelManager.ModelInfo?>(null) }
    var showModelSelection by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val chatManager = remember { ChatServiceClient(context.applicationContext) }
    DisposableEffect(chatManager) {
        chatManager.bindService()
        onDispose { chatManager.release() }
    }
    var modelKey by remember { mutableStateOf(0) }

    // 自动连接逻辑：读取偏好并在首次组合时尝试连接
    LaunchedEffect(Unit) {
        chatManager.ensureBound()
        val sp = context.getSharedPreferences("chat_prefs", android.content.Context.MODE_PRIVATE)
        val lastUrl = sp.getString("last_url", null)
        val nickname = sp.getString("nickname", null)
        val recvId = sp.getString("receiver_user_id", null)
        val recvNick = sp.getString("receiver_user_nickname", null)
        if (!recvId.isNullOrBlank() || !recvNick.isNullOrBlank()) {
            chatManager.setReceiverInfo(recvId, recvNick)
        }
        if (!nickname.isNullOrBlank()) {
            chatManager.setUserProfile(nickname)
        }
        if (!lastUrl.isNullOrBlank()) {
            chatManager.connect(lastUrl)
        }
    }
    when (currentScreen) {
        ChatAppScreen.ModelChat ->
                ChatWithModelScreen(
                        selectedModel = selectedModel,
                        chatManager = chatManager,
                        modelKey = modelKey,
                        onModelSelectionRequest = { showModelSelection = true },
                        onModelChanged = { newModel ->
                            selectedModel = newModel
                            modelKey++
                        }
                )
    }
    if (showModelSelection) {
        ModelSelectionDialog(
                currentModel = selectedModel,
                onModelSelected = { m ->
                    selectedModel = m
                    modelKey++
                    showModelSelection = false
                },
                onDismiss = { showModelSelection = false }
        )
    }
}

enum class ChatAppScreen {
    ModelChat
}

@Preview
@Composable
fun PreviewApp() {
    L2DChatTheme { Live2DChatApp() }
}
