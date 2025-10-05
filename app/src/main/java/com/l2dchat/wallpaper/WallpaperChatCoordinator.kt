package com.l2dchat.wallpaper

import android.content.Context
import com.l2dchat.chat.service.ChatServiceClient
import com.l2dchat.chat.service.ChatServiceClient.ChatMessageSnapshot
import com.l2dchat.logging.L2DLogger
import com.l2dchat.logging.LogModule
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 管理桌面小部件与壁纸后台的聊天调度，复用独立进程的聊天服务以共享 WebSocket 连接，并将最新对话同步到小部件。 */
object WallpaperChatCoordinator {
    private val logger = L2DLogger.module(LogModule.WALLPAPER)
    private const val CHAT_PREFS = "chat_prefs"
    private const val KEY_LAST_URL = "last_url"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_PLATFORM = "platform"
    private const val KEY_RECEIVER_ID = "receiver_user_id"
    private const val KEY_RECEIVER_NICKNAME = "receiver_user_nickname"
    private const val CONNECTION_TIMEOUT_MS = 4_000L
    private const val PREVIEW_MAX_CHARS = 80

    private val initMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clientRef = AtomicReference<ChatServiceClient?>()
    private val listeners = CopyOnWriteArraySet<Listener>()

    interface Listener {
        fun onMessageAppended(message: ChatMessageSnapshot)
    }

    suspend fun sendMessage(context: Context, message: String): Boolean {
        val trimmed = message.trim()
    if (trimmed.isEmpty()) {
        logger.warn(
            "忽略空白消息",
            throttleMs = 1_000L,
            throttleKey = "empty_message"
        )
            return false
        }
        val appContext = context.applicationContext
        val client = ensureClient(appContext)
        client.ensureBound()
        updateWidgetPreview(appContext, trimmed, fromUser = true)
        val connected = ensureConnection(appContext, client)
    if (!connected) {
        logger.warn(
            "无法在超时时间内连接到服务器，发送失败",
            throttleMs = 2_000L,
            throttleKey = "send_timeout"
        )
            return false
        }
        client.sendUserMessage(trimmed)
        return true
    }

    suspend fun warmUp(context: Context) {
        ensureClient(context.applicationContext)
    }

    fun updateWidgetPreview(context: Context, message: String, fromUser: Boolean) {
        val label = if (fromUser) "我" else "TA"
        val preview = formatPreview(label, message)
        val prefs =
                context.applicationContext.getSharedPreferences(
                        WallpaperComm.PREF_WIDGET_INPUT,
                        Context.MODE_PRIVATE
                )
        prefs.edit().putString(WallpaperComm.PREF_WIDGET_LAST_INPUT_KEY, preview).apply()
        Live2DChatWidgetProvider.updateAllWidgets(context.applicationContext)
    }

    private suspend fun ensureClient(context: Context): ChatServiceClient {
        val existing = clientRef.get()
        if (existing != null) return existing
        return initMutex.withLock {
            clientRef.get() ?: createClient(context.applicationContext).also { clientRef.set(it) }
        }
    }

    private fun createClient(context: Context): ChatServiceClient {
        val client = ChatServiceClient(context)
        client.bindService()
        applyUserConfig(context, client)
        startMessageCollection(context, client)
        return client
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun applyUserConfig(context: Context, client: ChatServiceClient) {
        client.ensureBound()
        val prefs = context.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
        val platform = prefs.getString(KEY_PLATFORM, null)?.trim().takeUnless { it.isNullOrEmpty() }
        client.updatePlatformPreference(platform)
        prefs.getString(KEY_NICKNAME, null)?.takeUnless { it.isBlank() }?.let {
            client.setUserProfile(it)
        }
        val receiverId = prefs.getString(KEY_RECEIVER_ID, null)?.ifBlank { null }
        val receiverNickname = prefs.getString(KEY_RECEIVER_NICKNAME, null)?.ifBlank { null }
        if (receiverId != null || receiverNickname != null) {
            client.setReceiverInfo(receiverId, receiverNickname)
        }
        // 将当前壁纸模型名作为历史缓存 key，沿用 app 内行为
        val wallpaperPrefs =
                context.getSharedPreferences(WallpaperComm.PREF_WALLPAPER, Context.MODE_PRIVATE)
        wallpaperPrefs.getString(WallpaperComm.PREF_WALLPAPER_MODEL_FOLDER, null)?.let { folder ->
            val modelName = folder.substringAfterLast('/')
            client.setActiveModel(modelName)
        }
        // 初始化连接（若已配置 URL）
        prefs.getString(KEY_LAST_URL, null)?.takeUnless { it.isBlank() }?.let { url ->
            client.connect(url, platform)
        }
        client.requestSnapshot()
    }

    private fun startMessageCollection(context: Context, client: ChatServiceClient) {
        scope.launch {
            val initialSize = client.messages.value.size
            var baselineProcessed = false
            var lastNotifiedId: String? = null
            client.messages.collect { messages ->
                val last = messages.lastOrNull()
                if (last == null) {
                    lastNotifiedId = null
                    return@collect
                }
                updateWidgetPreview(context, last.content, fromUser = last.isFromUser)
                if (!baselineProcessed) {
                    baselineProcessed = true
                    if (initialSize > 0 && messages.size == initialSize) {
                        lastNotifiedId = last.id
                        return@collect
                    }
                }
                if (lastNotifiedId == last.id) return@collect
                lastNotifiedId = last.id
                notifyListeners(last)
                if (!last.isFromUser) {
                    logger.info(
                            "收到回复 -> ${last.content.take(120)}${if (last.content.length > 120) "…" else ""}"
                    )
                }
            }
        }
    }

    private fun notifyListeners(message: ChatMessageSnapshot) {
        listeners.forEach { listener ->
            try {
                listener.onMessageAppended(message)
            } catch (t: Throwable) {
                logger.warn("Listener dispatch failed", t)
            }
        }
    }

    private suspend fun ensureConnection(context: Context, client: ChatServiceClient): Boolean {
        if (client.connectionState.value == ChatServiceClient.ChatConnectionState.CONNECTED) {
            return true
        }
        if (client.connectionState.value == ChatServiceClient.ChatConnectionState.CONNECTING) {
            return waitForConnection(client)
        }
        val prefs = context.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_LAST_URL, null)?.takeUnless { it.isBlank() }
        val platform = prefs.getString(KEY_PLATFORM, null)?.takeUnless { it.isBlank() }
        if (url.isNullOrEmpty()) {
        logger.warn(
            "尚未配置 WebSocket URL，无法建立连接",
            throttleMs = 3_000L,
            throttleKey = "missing_url"
        )
            return false
        }
        client.connect(url, platform)
        return waitForConnection(client)
    }

    private suspend fun waitForConnection(client: ChatServiceClient): Boolean {
        if (client.connectionState.value == ChatServiceClient.ChatConnectionState.CONNECTED) {
            return true
        }
        return withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
            client.connectionState
                    .filter { it == ChatServiceClient.ChatConnectionState.CONNECTED }
                    .first()
            true
        }
                ?: false
    }

    private fun formatPreview(label: String, content: String): String {
        val cleaned = content.trim().replace("\n", " ")
        if (cleaned.isEmpty()) return label
        val previewContent =
                if (cleaned.length > PREVIEW_MAX_CHARS) {
                    cleaned.substring(0, min(PREVIEW_MAX_CHARS, cleaned.length)).trimEnd() + "…"
                } else {
                    cleaned
                }
        return "$label: $previewContent"
    }
}
