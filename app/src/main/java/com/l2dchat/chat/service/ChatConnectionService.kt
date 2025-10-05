package com.l2dchat.chat.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import com.l2dchat.chat.ChatWebSocketManager
import com.l2dchat.chat.ChatWebSocketManager.ChatMessage
import com.l2dchat.chat.ChatWebSocketManager.ConnectionState
import com.l2dchat.chat.MessageBase
import com.l2dchat.logging.L2DLogger
import com.l2dchat.logging.LogModule
import com.l2dchat.wallpaper.WallpaperComm
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ChatConnectionService : Service() {

    private val logger = L2DLogger.module(LogModule.CHAT)

    private val clients = CopyOnWriteArraySet<Messenger>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val incomingHandler = IncomingHandler(this)
    private val messenger = Messenger(incomingHandler)

    private lateinit var manager: ChatWebSocketManager

    private var lastKnownUrl: String? = null
    private var lastKnownPlatform: String? = null
    private var lastKnownAuth: String? = null
    private var lastKnownNickname: String? = null
    private var lastKnownReceiverId: String? = null
    private var lastKnownReceiverNickname: String? = null

    override fun onCreate() {
        super.onCreate()
        manager = ChatWebSocketManager()
        manager.setActiveModel(applicationContext, restoreModelName())
        applyStoredConfiguration()
        startObservers()
        logger.info("ChatConnectionService created (pid=${Process.myPid()})")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 保持粘性，便于在进程被系统回收后自动重启
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        logger.info("ChatConnectionService destroyed")
        serviceScope.cancel()
        clients.clear()
        manager.disconnect()
    }

    private fun restoreModelName(): String? {
        val prefs = getSharedPreferences(WallpaperComm.PREF_WALLPAPER, MODE_PRIVATE)
        val folder = prefs.getString(WallpaperComm.PREF_WALLPAPER_MODEL_FOLDER, null)
        return folder?.substringAfterLast('/')?.ifBlank { null }
    }

    private fun applyStoredConfiguration() {
        val prefs = getSharedPreferences(CHAT_PREFS, MODE_PRIVATE)
        lastKnownUrl = prefs.getString(KEY_LAST_URL, null)?.takeUnless { it.isNullOrBlank() }
        lastKnownPlatform = prefs.getString(KEY_PLATFORM, null)?.takeUnless { it.isNullOrBlank() }
        lastKnownAuth = prefs.getString(KEY_AUTH_TOKEN, null)?.takeUnless { it.isNullOrBlank() }
        lastKnownNickname = prefs.getString(KEY_NICKNAME, null)
        lastKnownReceiverId = prefs.getString(KEY_RECEIVER_ID, null)?.ifBlank { null }
        lastKnownReceiverNickname = prefs.getString(KEY_RECEIVER_NICKNAME, null)?.ifBlank { null }

        lastKnownPlatform?.let { manager.updatePlatformPreference(it) }
        manager.setConnectionConfig(manager.getPlatform(), lastKnownAuth)
        lastKnownNickname?.takeUnless { it.isNullOrBlank() }?.let { manager.setUserProfile(it) }
        if (!lastKnownReceiverId.isNullOrBlank() || !lastKnownReceiverNickname.isNullOrBlank()) {
            manager.setReceiverInfo(lastKnownReceiverId, lastKnownReceiverNickname)
        }
        lastKnownUrl?.let { url -> manager.connect(url, lastKnownPlatform, lastKnownAuth) }
    }

    private fun startObservers() {
        serviceScope.launch {
            manager.connectionState.collect { state -> broadcastConnectionState(state) }
        }
        serviceScope.launch {
            var lastBroadcastId: String? = null
            manager.messages.collect { list ->
                val last = list.lastOrNull() ?: return@collect
                if (last.id == lastBroadcastId) return@collect
                lastBroadcastId = last.id
                broadcastChatMessage(last)
            }
        }
        serviceScope.launch {
            var lastStandardId: String? = null
            manager.standardMessages.collect { list ->
                val last = list.lastOrNull() ?: return@collect
                val id = last.messageInfo.messageId ?: return@collect
                if (id == lastStandardId) return@collect
                lastStandardId = id
                broadcastStandardMessage(last)
            }
        }
        serviceScope.launch { manager.errors.collect { message -> notifyError(message) } }
    }

    private fun broadcastConnectionState(state: ConnectionState) {
        val bundle =
                Bundle().apply {
                    putInt(ChatServiceProtocol.EXTRA_CONNECTION_STATE, state.ordinal)
                    putString(
                            ChatServiceProtocol.EXTRA_CONNECTION_LABEL,
                            when (state) {
                                ConnectionState.DISCONNECTED -> "未连接"
                                ConnectionState.CONNECTING -> "连接中"
                                ConnectionState.CONNECTED -> "已连接"
                                ConnectionState.ERROR -> "错误"
                            }
                    )
                }
        sendToClients(ChatServiceProtocol.MSG_EVENT_CONNECTION_STATE, bundle)
    }

    private fun broadcastChatMessage(message: ChatMessage) {
        val bundle =
                Bundle().apply {
                    putString(ChatServiceProtocol.EXTRA_MESSAGE_ID, message.id)
                    putString(ChatServiceProtocol.EXTRA_MESSAGE_CONTENT, message.content)
                    putBoolean(ChatServiceProtocol.EXTRA_MESSAGE_FROM_USER, message.isFromUser)
                    putLong(ChatServiceProtocol.EXTRA_MESSAGE_TIMESTAMP, message.timestamp)
                }
        sendToClients(ChatServiceProtocol.MSG_EVENT_NEW_MESSAGE, bundle)
    }

    private fun sendSnapshot(target: Messenger? = null) {
        val snapshot =
                ArrayList<Bundle>(manager.messages.value.size).apply {
                    manager.messages.value.forEach { m ->
                        add(
                                Bundle().apply {
                                    putString(ChatServiceProtocol.EXTRA_MESSAGE_ID, m.id)
                                    putString(ChatServiceProtocol.EXTRA_MESSAGE_CONTENT, m.content)
                                    putBoolean(
                                            ChatServiceProtocol.EXTRA_MESSAGE_FROM_USER,
                                            m.isFromUser
                                    )
                                    putLong(
                                            ChatServiceProtocol.EXTRA_MESSAGE_TIMESTAMP,
                                            m.timestamp
                                    )
                                }
                        )
                    }
                }
        val standardSnapshot =
                ArrayList<String>(manager.standardMessages.value.size).apply {
                    manager.standardMessages.value.forEach { add(it.toJsonString()) }
                }
        val bundle =
                Bundle().apply {
                    putParcelableArrayList(ChatServiceProtocol.EXTRA_MESSAGE_BUNDLE_LIST, snapshot)
                    putStringArrayList(
                            ChatServiceProtocol.EXTRA_STANDARD_MESSAGE_LIST,
                            standardSnapshot
                    )
                }
        if (target != null) {
            sendToClient(target, ChatServiceProtocol.MSG_EVENT_SNAPSHOT, bundle)
        } else {
            sendToClients(ChatServiceProtocol.MSG_EVENT_SNAPSHOT, bundle)
        }
    }

    private fun broadcastStandardMessage(message: MessageBase) {
        val json = message.toJsonString()
        val bundle =
                Bundle().apply { putString(ChatServiceProtocol.EXTRA_STANDARD_MESSAGE_JSON, json) }
        sendToClients(ChatServiceProtocol.MSG_EVENT_STANDARD_MESSAGE, bundle)
    }

    private fun sendToClients(what: Int, data: Bundle) {
        val toRemove = mutableListOf<Messenger>()
        clients.forEach { client ->
            if (!sendToClient(client, what, data)) {
                toRemove.add(client)
            }
        }
        if (toRemove.isNotEmpty()) {
            clients.removeAll(toRemove.toSet())
        }
    }

    private fun sendToClient(client: Messenger, what: Int, data: Bundle): Boolean =
            try {
                val msg = Message.obtain(null, what).apply { this.data = data }
                client.send(msg)
                true
            } catch (e: RemoteException) {
                logger.warn("Client callback failed, removing target", e)
                false
            }

    private fun ensureConnected(triggerReconnect: Boolean = true) {
        val state = manager.connectionState.value
        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) return
        if (!triggerReconnect) return
        val url = lastKnownUrl
        if (url.isNullOrBlank()) {
            notifyError("未设置服务器地址，无法连接")
            return
        }
        logger.debug(
                "ensureConnected() with state=$state trigger=$triggerReconnect url=$url",
                throttleMs = 1_000L,
                throttleKey = "ensure_connected"
        )
        manager.connect(url, lastKnownPlatform, lastKnownAuth)
    }

    private fun handleSendMessage(data: Bundle) {
        val text = data.getString(ChatServiceProtocol.EXTRA_MESSAGE_TEXT)?.trim()
        if (text.isNullOrEmpty()) {
            notifyError("发送内容不能为空")
            return
        }
        ensureConnected()
        manager.sendUserMessage(text)
    }

    private fun handleConnectRequest(data: Bundle) {
        val url =
                data.getString(ChatServiceProtocol.EXTRA_URL)?.takeIf { it.isNotBlank() }
                        ?: lastKnownUrl
        if (url.isNullOrBlank()) {
            notifyError("未提供有效的服务器地址")
            return
        }
        val platform =
                data.getString(ChatServiceProtocol.EXTRA_PLATFORM)?.takeUnless { it.isBlank() }
                        ?: lastKnownPlatform
        val auth =
                data.getString(ChatServiceProtocol.EXTRA_AUTH_TOKEN)?.takeUnless { it.isBlank() }
                        ?: lastKnownAuth

        logger.info(
                "handleConnectRequest url=$url platform=$platform " +
                        "authPresent=${auth != null} caller=${data.keySet()}"
        )

        lastKnownUrl = url
        lastKnownPlatform = platform
        lastKnownAuth = auth
        persistConnectionConfig()

        if (!platform.isNullOrBlank() || auth != null) {
            manager.setConnectionConfig(platform ?: manager.getPlatform(), auth)
        }
        logger.info("Invoking ChatWebSocketManager.connect url=$url platform=$platform")
        manager.connect(url, platform, auth)
    }

    private fun handleConfigUpdate(data: Bundle) {
        var needReconnect = false
        data.getString(ChatServiceProtocol.EXTRA_PLATFORM)?.let { platform ->
            val trimmed = platform.trim()
            lastKnownPlatform = trimmed.ifBlank { null }
            manager.updatePlatformPreference(lastKnownPlatform)
            needReconnect = true
        }
        data.getString(ChatServiceProtocol.EXTRA_AUTH_TOKEN)?.let { token ->
            lastKnownAuth = token.takeUnless { it.isBlank() }
            manager.setConnectionConfig(lastKnownPlatform ?: manager.getPlatform(), lastKnownAuth)
            needReconnect = true
        }
        data.getString(ChatServiceProtocol.EXTRA_NICKNAME)?.let { name ->
            lastKnownNickname = name
            if (name.isNotBlank()) {
                manager.setUserProfile(name)
            }
        }
        if (data.containsKey(ChatServiceProtocol.EXTRA_RECEIVER_ID) ||
                        data.containsKey(ChatServiceProtocol.EXTRA_RECEIVER_NICKNAME)
        ) {
            lastKnownReceiverId =
                    data.getString(ChatServiceProtocol.EXTRA_RECEIVER_ID)?.ifBlank { null }
            lastKnownReceiverNickname =
                    data.getString(ChatServiceProtocol.EXTRA_RECEIVER_NICKNAME)?.ifBlank { null }
            manager.setReceiverInfo(lastKnownReceiverId, lastKnownReceiverNickname)
        }
        data.getString(ChatServiceProtocol.EXTRA_URL)?.let { url ->
            val trimmed = url.trim()
            lastKnownUrl = trimmed.ifBlank { null }
            needReconnect = true
        }
        persistConnectionConfig()
        if (needReconnect) {
            ensureConnected(triggerReconnect = true)
        }
    }

    private fun handleClearMessages(persist: Boolean) {
        if (persist) manager.clearMessages() else manager.clearMessagesEphemeral()
        sendSnapshot()
    }

    private fun handleSetActiveModel(data: Bundle) {
        val modelName = data.getString(ChatServiceProtocol.EXTRA_MODEL_NAME)
        manager.setActiveModel(applicationContext, modelName)
        sendSnapshot()
    }

    private fun persistConnectionConfig() {
        val editor = getSharedPreferences(CHAT_PREFS, MODE_PRIVATE).edit()
        if (lastKnownUrl != null) editor.putString(KEY_LAST_URL, lastKnownUrl)
        else editor.remove(KEY_LAST_URL)
        if (lastKnownPlatform != null) editor.putString(KEY_PLATFORM, lastKnownPlatform)
        else editor.remove(KEY_PLATFORM)
        if (lastKnownAuth != null) editor.putString(KEY_AUTH_TOKEN, lastKnownAuth)
        else editor.remove(KEY_AUTH_TOKEN)
        if (!lastKnownNickname.isNullOrEmpty()) editor.putString(KEY_NICKNAME, lastKnownNickname)
        else editor.remove(KEY_NICKNAME)
        if (lastKnownReceiverId != null) editor.putString(KEY_RECEIVER_ID, lastKnownReceiverId)
        else editor.remove(KEY_RECEIVER_ID)
        if (lastKnownReceiverNickname != null)
                editor.putString(KEY_RECEIVER_NICKNAME, lastKnownReceiverNickname)
        else editor.remove(KEY_RECEIVER_NICKNAME)
        editor.apply()
    }

    private fun notifyError(message: String) {
        val data = Bundle().apply { putString(ChatServiceProtocol.EXTRA_ERROR_MESSAGE, message) }
        sendToClients(ChatServiceProtocol.MSG_EVENT_ERROR, data)
    }

    private class IncomingHandler(service: ChatConnectionService) :
            Handler(Looper.getMainLooper()) {
        private val serviceRef = WeakReference(service)

        override fun handleMessage(msg: Message) {
            val service = serviceRef.get() ?: return
            when (msg.what) {
                ChatServiceProtocol.MSG_REGISTER_CLIENT -> {
                    msg.replyTo?.let {
                        service.clients.add(it)
                        service.sendSnapshot(it)
                        service.broadcastConnectionState(service.manager.connectionState.value)
                    }
                }
                ChatServiceProtocol.MSG_UNREGISTER_CLIENT -> {
                    msg.replyTo?.let { service.clients.remove(it) }
                }
                ChatServiceProtocol.MSG_CONNECT -> service.handleConnectRequest(msg.data)
                ChatServiceProtocol.MSG_DISCONNECT -> service.manager.disconnect()
                ChatServiceProtocol.MSG_SEND_MESSAGE -> service.handleSendMessage(msg.data)
                ChatServiceProtocol.MSG_UPDATE_CONFIG -> service.handleConfigUpdate(msg.data)
                ChatServiceProtocol.MSG_REQUEST_SNAPSHOT -> {
                    val target = msg.replyTo
                    if (target != null) service.sendSnapshot(target) else service.sendSnapshot()
                }
                ChatServiceProtocol.MSG_CLEAR_MESSAGES -> service.handleClearMessages(true)
                ChatServiceProtocol.MSG_CLEAR_MESSAGES_EPHEMERAL ->
                        service.handleClearMessages(false)
                ChatServiceProtocol.MSG_SET_ACTIVE_MODEL -> service.handleSetActiveModel(msg.data)
                else -> super.handleMessage(msg)
            }
        }
    }

    companion object {
        private const val CHAT_PREFS = "chat_prefs"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_PLATFORM = "platform"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_RECEIVER_ID = "receiver_user_id"
        private const val KEY_RECEIVER_NICKNAME = "receiver_user_nickname"
    }
}
