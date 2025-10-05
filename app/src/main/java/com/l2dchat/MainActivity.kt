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
import com.l2dchat.preferences.ChatPreferenceKeys
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
    val prefs =
            remember(context) {
                context.getSharedPreferences(
                        ChatPreferenceKeys.PREFS_NAME,
                        android.content.Context.MODE_PRIVATE
                )
            }
    val persistModelSelection =
            remember(prefs) {
                { model: Live2DModelManager.ModelInfo? ->
                    prefs.edit()
                            .also { editor ->
                                if (model != null) {
                                    editor.putString(
                                            ChatPreferenceKeys.SELECTED_MODEL_FOLDER,
                                            model.folderPath
                                    )
                                } else {
                                    editor.remove(ChatPreferenceKeys.SELECTED_MODEL_FOLDER)
                                }
                            }
                            .apply()
                }
            }

    // 自动连接逻辑：读取偏好并在首次组合时尝试连接
    LaunchedEffect(Unit) {
        chatManager.ensureBound()
        val lastUrl = prefs.getString("last_url", null)
        val nickname = prefs.getString("nickname", null)
        val recvId = prefs.getString("receiver_user_id", null)
        val recvNick = prefs.getString("receiver_user_nickname", null)
        if (!recvId.isNullOrBlank() || !recvNick.isNullOrBlank()) {
            chatManager.setReceiverInfo(recvId, recvNick)
        }
        if (!nickname.isNullOrBlank()) {
            chatManager.setUserProfile(nickname)
        }
        if (!lastUrl.isNullOrBlank()) {
            chatManager.connect(lastUrl)
        }
        if (selectedModel == null) {
            val savedModelFolder =
                    prefs.getString(ChatPreferenceKeys.SELECTED_MODEL_FOLDER, null)
            if (!savedModelFolder.isNullOrBlank()) {
                val models = Live2DModelManager.scanModels(context)
                val matched = models.firstOrNull { it.folderPath == savedModelFolder }
                if (matched != null) {
                    selectedModel = matched
                    modelKey++
                }
            }
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
                            persistModelSelection(newModel)
                            modelKey++
                        }
                )
    }
    if (showModelSelection) {
        ModelSelectionDialog(
                currentModel = selectedModel,
                onModelSelected = { m ->
                    selectedModel = m
                    persistModelSelection(m)
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
