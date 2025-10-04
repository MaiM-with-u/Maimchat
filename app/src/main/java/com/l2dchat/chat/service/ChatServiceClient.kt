package com.l2dchat.chat.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.l2dchat.chat.MessageBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatServiceClient(context: Context) : ServiceConnection {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = appContext.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)

    private val incomingHandler =
            object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    when (msg.what) {
                        ChatServiceProtocol.MSG_EVENT_CONNECTION_STATE -> {
                            val ordinal =
                                    msg.data.getInt(ChatServiceProtocol.EXTRA_CONNECTION_STATE, 0)
                            val label =
                                    msg.data.getString(ChatServiceProtocol.EXTRA_CONNECTION_LABEL)
                            _connectionState.value = ChatConnectionState.fromOrdinal(ordinal)
                            _connectionLabel.value = label.orEmpty()
                        }
                        ChatServiceProtocol.MSG_EVENT_NEW_MESSAGE -> handleNewMessage(msg.data)
                        ChatServiceProtocol.MSG_EVENT_SNAPSHOT -> handleSnapshot(msg.data)
                        ChatServiceProtocol.MSG_EVENT_STANDARD_MESSAGE ->
                                handleStandardMessage(msg.data)
                        ChatServiceProtocol.MSG_EVENT_ERROR -> {
                            msg.data
                                    .getString(ChatServiceProtocol.EXTRA_ERROR_MESSAGE)
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { error -> scope.launch { _errors.emit(error) } }
                        }
                        else -> super.handleMessage(msg)
                    }
                }
            }

    private val messenger = Messenger(incomingHandler)
    private var serviceMessenger: Messenger? = null
    private var isBound = false
    private val pendingCommands = mutableListOf<Pair<Int, Bundle?>>()

    private val _connectionState = MutableStateFlow(ChatConnectionState.DISCONNECTED)
    private val _connectionLabel = MutableStateFlow("")
    private val _messages = MutableStateFlow<List<ChatMessageSnapshot>>(emptyList())
    private val _standardMessages = MutableStateFlow<List<MessageBase>>(emptyList())
    private val _newMessages =
            MutableSharedFlow<ChatMessageSnapshot>(
                    replay = 0,
                    extraBufferCapacity = 8,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
    private val _snapshot =
            MutableSharedFlow<List<ChatMessageSnapshot>>(
                    replay = 0,
                    extraBufferCapacity = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
    private val _errors =
            MutableSharedFlow<String>(
                    replay = 0,
                    extraBufferCapacity = 4,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
            )

    private val _userNickname =
            MutableStateFlow(prefs.getString(KEY_NICKNAME, null)?.takeIf { it.isNotBlank() })
    private val _platform =
            MutableStateFlow(prefs.getString(KEY_PLATFORM, null)?.takeIf { it.isNotBlank() })
    private val _receiverId =
            MutableStateFlow(prefs.getString(KEY_RECEIVER_ID, null)?.takeIf { it.isNotBlank() })
    private val _receiverNickname =
            MutableStateFlow(
                    prefs.getString(KEY_RECEIVER_NICKNAME, null)?.takeIf { it.isNotBlank() }
            )
    private val _lastUrl =
            MutableStateFlow(prefs.getString(KEY_LAST_URL, null)?.takeIf { it.isNotBlank() })
    private val _activeModel = MutableStateFlow<String?>(null)
    private var motionCallback: ((String, Int, Boolean) -> Unit)? = null

    val connectionState: StateFlow<ChatConnectionState> = _connectionState.asStateFlow()
    val connectionLabel: StateFlow<String> = _connectionLabel.asStateFlow()
    val messages: StateFlow<List<ChatMessageSnapshot>> = _messages.asStateFlow()
    val standardMessages: StateFlow<List<MessageBase>> = _standardMessages.asStateFlow()
    val newMessages: SharedFlow<ChatMessageSnapshot> = _newMessages.asSharedFlow()
    val snapshot: SharedFlow<List<ChatMessageSnapshot>> = _snapshot.asSharedFlow()
    val errors: SharedFlow<String> = _errors.asSharedFlow()
    val userNickname: StateFlow<String?> = _userNickname.asStateFlow()
    val platform: StateFlow<String?> = _platform.asStateFlow()
    val activeModel: StateFlow<String?> = _activeModel.asStateFlow()

    fun bindService() {
        if (isBound) return
        val intent = Intent(appContext, ChatConnectionService::class.java)
        isBound = appContext.bindService(intent, this, Context.BIND_AUTO_CREATE)
        if (!isBound) {
            Log.e(TAG, "bindService failed")
        }
    }

    fun unbindService() {
        if (!isBound) return
        try {
            sendCommand(ChatServiceProtocol.MSG_UNREGISTER_CLIENT)
        } catch (_: Exception) {}
        appContext.unbindService(this)
        isBound = false
        serviceMessenger = null
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        serviceMessenger = Messenger(service)
        isBound = true
        Log.i(TAG, "Service connected component=$name pending=${pendingCommands.size}")
        flushPendingCommands()
        sendCommand(ChatServiceProtocol.MSG_REGISTER_CLIENT)
        requestSnapshot()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        isBound = false
        serviceMessenger = null
        Log.w(TAG, "Service disconnected component=$name")
        _connectionState.value = ChatConnectionState.DISCONNECTED
    }

    fun ensureBound() {
        if (!isBound) {
            Log.d(TAG, "ensureBound() -> binding to ChatConnectionService")
            bindService()
        }
    }

    fun connect(url: String?, platform: String? = null, authToken: String? = null) {
        ensureBound()
        val resolvedUrl = url?.takeIf { it.isNotBlank() } ?: _lastUrl.value
        if (resolvedUrl.isNullOrBlank()) {
            scope.launch { _errors.emit("未设置服务器地址") }
            return
        }
        Log.d(
                TAG,
                "connect() called resolvedUrl=$resolvedUrl platform=${platform ?: _platform.value} bound=$isBound messengerReady=${serviceMessenger != null}"
        )
        _lastUrl.value = resolvedUrl
        platform?.takeIf { it.isNotBlank() }?.let { _platform.value = it }
        val bundle =
                Bundle().apply {
                    putString(ChatServiceProtocol.EXTRA_URL, resolvedUrl)
                    _platform.value?.let { putString(ChatServiceProtocol.EXTRA_PLATFORM, it) }
                    authToken?.takeIf { it.isNotBlank() }?.let {
                        putString(ChatServiceProtocol.EXTRA_AUTH_TOKEN, it)
                    }
                }
        sendCommand(ChatServiceProtocol.MSG_CONNECT, bundle)
    }

    fun disconnect() {
        sendCommand(ChatServiceProtocol.MSG_DISCONNECT)
    }

    fun sendUserMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        sendCommand(
                ChatServiceProtocol.MSG_SEND_MESSAGE,
                Bundle().apply { putString(ChatServiceProtocol.EXTRA_MESSAGE_TEXT, trimmed) }
        )
    }

    fun setUserProfile(nickname: String?) {
        val sanitized = nickname?.trim().takeUnless { it.isNullOrEmpty() }
        _userNickname.value = sanitized
        sendConfigUpdate { putString(ChatServiceProtocol.EXTRA_NICKNAME, sanitized ?: "") }
    }

    fun setReceiverInfo(userId: String?, userNickname: String?) {
        val sanitizedId = userId?.trim().takeUnless { it.isNullOrEmpty() }
        val sanitizedNickname = userNickname?.trim().takeUnless { it.isNullOrEmpty() }
        _receiverId.value = sanitizedId
        _receiverNickname.value = sanitizedNickname
        sendConfigUpdate {
            putString(ChatServiceProtocol.EXTRA_RECEIVER_ID, sanitizedId ?: "")
            putString(ChatServiceProtocol.EXTRA_RECEIVER_NICKNAME, sanitizedNickname ?: "")
        }
    }

    fun updatePlatformPreference(platform: String?) {
        val sanitized = platform?.trim().takeUnless { it.isNullOrEmpty() }
        _platform.value = sanitized
        sendConfigUpdate { putString(ChatServiceProtocol.EXTRA_PLATFORM, sanitized ?: "") }
    }

    fun updateConnectionUrl(url: String?) {
        val sanitized = url?.trim().takeUnless { it.isNullOrEmpty() }
        _lastUrl.value = sanitized
        sendConfigUpdate { putString(ChatServiceProtocol.EXTRA_URL, sanitized ?: "") }
    }

    fun setActiveModel(modelName: String?) {
        val trimmed = modelName?.trim().takeUnless { it.isNullOrEmpty() }
        _activeModel.value = trimmed
        sendCommand(
                ChatServiceProtocol.MSG_SET_ACTIVE_MODEL,
                Bundle().apply { putString(ChatServiceProtocol.EXTRA_MODEL_NAME, trimmed) }
        )
    }

    fun clearMessages() {
        _messages.value = emptyList()
        _standardMessages.value = emptyList()
        sendCommand(ChatServiceProtocol.MSG_CLEAR_MESSAGES)
    }

    fun clearMessagesEphemeral() {
        _messages.value = emptyList()
        _standardMessages.value = emptyList()
        sendCommand(ChatServiceProtocol.MSG_CLEAR_MESSAGES_EPHEMERAL)
    }

    fun setMotionTriggerCallback(callback: (String, Int, Boolean) -> Unit) {
        motionCallback = callback
    }

    fun requestSnapshot() {
        sendCommand(ChatServiceProtocol.MSG_REQUEST_SNAPSHOT)
    }

    fun hasUserNickname(): Boolean = !_userNickname.value.isNullOrBlank()

    fun getUserNickname(): String? = _userNickname.value

    fun getPlatform(): String? = _platform.value

    fun getConnectionStateDescription(): String =
            connectionLabel.value.ifBlank {
                when (connectionState.value) {
                    ChatConnectionState.DISCONNECTED -> "未连接"
                    ChatConnectionState.CONNECTING -> "连接中"
                    ChatConnectionState.CONNECTED -> "已连接"
                    ChatConnectionState.ERROR -> "错误"
                }
            }

    fun release() {
        unbindService()
        scope.cancel()
    }

    private fun sendCommand(what: Int, data: Bundle? = null) {
        val target = serviceMessenger
        if (target == null) {
            queueCommand(what, data)
            if (!isBound) bindService()
            return
        }
        dispatchCommand(target, what, data)
    }

    private fun sendConfigUpdate(builder: Bundle.() -> Unit) {
        val bundle = Bundle().apply(builder)
        sendCommand(ChatServiceProtocol.MSG_UPDATE_CONFIG, bundle)
    }

    private fun handleNewMessage(data: Bundle) {
        val content = data.getString(ChatServiceProtocol.EXTRA_MESSAGE_CONTENT) ?: return
        val id = data.getString(ChatServiceProtocol.EXTRA_MESSAGE_ID) ?: return
        val isFromUser = data.getBoolean(ChatServiceProtocol.EXTRA_MESSAGE_FROM_USER)
        val timestamp = data.getLong(ChatServiceProtocol.EXTRA_MESSAGE_TIMESTAMP)
        val snapshot = ChatMessageSnapshot(id, content, isFromUser, timestamp)
        _messages.update { current ->
            if (current.any { it.id == id }) current else current + snapshot
        }
        scope.launch { _newMessages.emit(snapshot) }
    }

    private fun handleStandardMessage(data: Bundle) {
        val json = data.getString(ChatServiceProtocol.EXTRA_STANDARD_MESSAGE_JSON) ?: return
        val message = runCatching { MessageBase.fromJsonString(json) }.getOrNull() ?: return
        val messageId = message.messageInfo.messageId
        _standardMessages.update { current ->
            if (messageId != null && current.any { it.messageInfo.messageId == messageId }) current
            else current + message
        }
    }

    private fun handleSnapshot(data: Bundle) {
        val messageBundles =
                data.getParcelableArrayList<Bundle>(ChatServiceProtocol.EXTRA_MESSAGE_BUNDLE_LIST)
                        ?: emptyList()
        val messages =
                messageBundles.mapNotNull { bundle ->
                    val id =
                            bundle.getString(ChatServiceProtocol.EXTRA_MESSAGE_ID)
                                    ?: return@mapNotNull null
                    val content =
                            bundle.getString(ChatServiceProtocol.EXTRA_MESSAGE_CONTENT)
                                    ?: return@mapNotNull null
                    val isFromUser = bundle.getBoolean(ChatServiceProtocol.EXTRA_MESSAGE_FROM_USER)
                    val timestamp = bundle.getLong(ChatServiceProtocol.EXTRA_MESSAGE_TIMESTAMP)
                    ChatMessageSnapshot(id, content, isFromUser, timestamp)
                }
        val standardJson =
                data.getStringArrayList(ChatServiceProtocol.EXTRA_STANDARD_MESSAGE_LIST)
                        ?: arrayListOf()
        val standardMessages =
                standardJson.mapNotNull { json ->
                    runCatching { MessageBase.fromJsonString(json) }.getOrNull()
                }
        _messages.value = messages
        _standardMessages.value = standardMessages
        scope.launch { _snapshot.emit(messages) }
    }

    private fun flushPendingCommands() {
        if (pendingCommands.isEmpty()) return
        val target = serviceMessenger ?: return
        Log.d(TAG, "Flushing ${pendingCommands.size} pending commands")
        val snapshot = ArrayList(pendingCommands)
        pendingCommands.clear()
        snapshot.forEach { (pendingWhat, pendingData) ->
            dispatchCommand(target, pendingWhat, pendingData)
        }
    }

    private fun queueCommand(what: Int, data: Bundle?) {
        val copy = data?.let { Bundle(it) }
        pendingCommands.add(what to copy)
        Log.d(
                TAG,
                "queueCommand ${commandName(what)} pending=${pendingCommands.size} bound=$isBound messengerReady=${serviceMessenger != null}"
        )
    }

    private fun dispatchCommand(target: Messenger, what: Int, data: Bundle?) {
        try {
            val msg =
                    Message.obtain(null, what).apply {
                        replyTo = messenger
                        data?.let { this.data = it }
                    }
            Log.d(TAG, "dispatchCommand ${commandName(what)} -> binder=$target")
            target.send(msg)
        } catch (e: RemoteException) {
            Log.e(TAG, "sendMessage failed", e)
        }
    }

    private fun commandName(what: Int): String =
            when (what) {
                ChatServiceProtocol.MSG_REGISTER_CLIENT -> "MSG_REGISTER_CLIENT"
                ChatServiceProtocol.MSG_UNREGISTER_CLIENT -> "MSG_UNREGISTER_CLIENT"
                ChatServiceProtocol.MSG_CONNECT -> "MSG_CONNECT"
                ChatServiceProtocol.MSG_DISCONNECT -> "MSG_DISCONNECT"
                ChatServiceProtocol.MSG_SEND_MESSAGE -> "MSG_SEND_MESSAGE"
                ChatServiceProtocol.MSG_UPDATE_CONFIG -> "MSG_UPDATE_CONFIG"
                ChatServiceProtocol.MSG_REQUEST_SNAPSHOT -> "MSG_REQUEST_SNAPSHOT"
                ChatServiceProtocol.MSG_CLEAR_MESSAGES -> "MSG_CLEAR_MESSAGES"
                ChatServiceProtocol.MSG_CLEAR_MESSAGES_EPHEMERAL -> "MSG_CLEAR_MESSAGES_EPHEMERAL"
                ChatServiceProtocol.MSG_EVENT_CONNECTION_STATE -> "MSG_EVENT_CONNECTION_STATE"
                ChatServiceProtocol.MSG_EVENT_NEW_MESSAGE -> "MSG_EVENT_NEW_MESSAGE"
                ChatServiceProtocol.MSG_EVENT_SNAPSHOT -> "MSG_EVENT_SNAPSHOT"
                ChatServiceProtocol.MSG_EVENT_ERROR -> "MSG_EVENT_ERROR"
                ChatServiceProtocol.MSG_EVENT_STANDARD_MESSAGE -> "MSG_EVENT_STANDARD_MESSAGE"
                ChatServiceProtocol.MSG_SET_ACTIVE_MODEL -> "MSG_SET_ACTIVE_MODEL"
                else -> "MSG_UNKNOWN_$what"
            }

    data class ChatMessageSnapshot(
            val id: String,
            val content: String,
            val isFromUser: Boolean,
            val timestamp: Long
    )

    enum class ChatConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR;

        companion object {
            fun fromOrdinal(ordinal: Int): ChatConnectionState =
                    values().getOrNull(ordinal) ?: DISCONNECTED
        }
    }

    companion object {
        private const val TAG = "ChatServiceClient"
        private const val CHAT_PREFS = "chat_prefs"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_PLATFORM = "platform"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_RECEIVER_ID = "receiver_user_id"
        private const val KEY_RECEIVER_NICKNAME = "receiver_user_nickname"
    }
}
