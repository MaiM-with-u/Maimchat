package com.l2dchat.chat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.l2dchat.logging.L2DLogger
import com.l2dchat.logging.LogModule
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class ChatWebSocketManager {
    companion object {
        private const val DEFAULT_PLATFORM = "live2d_chat"
    }
    private val logger = L2DLogger.module(LogModule.CHAT)
    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private val client =
            OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS)
                    .pingInterval(30, TimeUnit.SECONDS)
                    .build()
    private var platform: String = DEFAULT_PLATFORM
    private var authToken: String? = null
    private val messageHandler = Live2DChatMessageHandler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private val _errors =
            MutableSharedFlow<String>(
                    extraBufferCapacity = 8,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
    val errors: SharedFlow<String> = _errors.asSharedFlow()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _standardMessages = MutableStateFlow<List<MessageBase>>(emptyList())
    val standardMessages: StateFlow<List<MessageBase>> = _standardMessages.asStateFlow()
    private var lastServerMessageTime: Long = 0L
    private var onMotionTrigger: ((String, Int, Boolean) -> Unit)? = null
    private var userId: String = generateUserId()
    private var userNickname: String? = null
    private var userCardName: String? = null
    private var receiverModelName: String? = null
    private var activeModelKey: String? = null
    private var appContext: Context? = null
    private var receiverUserIdOverride: String? = null
    private var receiverUserNicknameOverride: String? = null
    // === Auto Reconnect Support ===
    private var lastConnectUrl: String? = null
    private var lastConnectPlatform: String? = null
    private var lastConnectAuth: String? = null
    private var retryCount: Int = 0
    private val maxRetries = 3
    private var userInitiatedDisconnect = false
    private var reconnectJobActive = false

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    data class ChatMessage(
            val id: String,
            val content: String,
            val isFromUser: Boolean,
            val timestamp: Long = System.currentTimeMillis(),
            val motionData: MotionData? = null
    )

    data class MotionData(val group: String, val index: Int, val loop: Boolean = false)

    fun setConnectionConfig(platform: String, authToken: String? = null) {
        applyPlatformPreference(platform)
        this.authToken = authToken
    }

    fun updatePlatformPreference(platform: String?) {
        applyPlatformPreference(platform)
    }

    private fun applyPlatformPreference(platformInput: String?) {
        val resolvedPlatform = resolvePlatform(platformInput)
        if (this.platform != resolvedPlatform) {
            this.platform = resolvedPlatform
            synchronizeCachedMessagePlatforms(resolvedPlatform)
        }
    }

    private fun resolvePlatform(input: String?): String {
        val trimmed = input?.trim().orEmpty()
        return if (trimmed.isEmpty()) DEFAULT_PLATFORM else trimmed
    }
    fun connect(url: String, platform: String? = null, authToken: String? = null) {
        if (_connectionState.value == ConnectionState.CONNECTED ||
                        _connectionState.value == ConnectionState.CONNECTING
        ) {
            return
        }
        lastConnectUrl = url
        if (platform != null) updatePlatformPreference(platform)
        if (authToken != null) this.authToken = authToken.takeIf { it.isNotBlank() }
        lastConnectPlatform = this.platform
        lastConnectAuth = this.authToken
        userInitiatedDisconnect = false
        _connectionState.value = ConnectionState.CONNECTING

        val activePlatform = this.platform
        val requestBuilder =
                Request.Builder()
                        .url(url)
                        .addHeader("platform", activePlatform)
                        .addHeader("Sec-WebSocket-Protocol", "chat")
        this.authToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        val request = requestBuilder.build()

        val authStatus =
                if (this.authToken.isNullOrBlank()) "未提供鉴权信息"
                else "已附带 Bearer Token（长度=${this.authToken!!.length}）"
        val headerPreview =
                request.headers.names().sorted().joinToString(separator = "; ") { name ->
                    val value =
                            if (name.equals("Authorization", ignoreCase = true)) "******"
                            else request.header(name).orEmpty()
                    "$name=$value"
                }
        logger.info("准备建立 WebSocket 连接：目标地址=$url，当前重试序号=$retryCount，平台=$activePlatform，$authStatus")
        if (headerPreview.isNotBlank()) {
            logger.debug(
                    "连接请求头：$headerPreview",
                    throttleMs = 2_000L,
                    throttleKey = "ws_request_headers"
            )
        }

        val listener =
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        logger.info("服务器握手成功：${summarizeResponse(response)}")
                        retryCount = 0
                        reconnectJobActive = false
                        _connectionState.value = ConnectionState.CONNECTED
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        scope.launch { handleIncomingMessage(text) }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        try {
                            webSocket.close(1000, null)
                        } catch (_: Exception) {}
                        _connectionState.value = ConnectionState.DISCONNECTED
                        val readableReason = if (reason.isBlank()) "无" else reason
                        logger.warn(
                                "服务器请求关闭连接：状态码=$code，原因=$readableReason，" +
                                        "是否用户主动断开=$userInitiatedDisconnect"
                        )
                        if (!userInitiatedDisconnect && code !in setOf(1000, 1001)) {
                            reportConnectionError("服务器异常断开：状态码=$code，原因=$readableReason")
                        }
                        attemptScheduleReconnect()
                    }

                    override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?
                    ) {
                        val baseMsg = t.message ?: "未知错误"
                        val summary = summarizeResponse(response)
                        reportConnectionError("建立连接失败：$baseMsg；服务器响应概览：$summary", t)
                        attemptScheduleReconnect()
                    }
                }

        try {
            webSocket = client.newWebSocket(request, listener)
        } catch (e: Exception) {
            reportConnectionError("创建 WebSocket 失败：${e.message ?: "未知错误"}", e)
        }
    }

    private fun reportConnectionError(message: String, throwable: Throwable? = null) {
        logger.error(message, throwable)
        if (_connectionState.value != ConnectionState.CONNECTED) {
            _connectionState.value = ConnectionState.ERROR
        }
        scope.launch { _errors.emit(message) }
    }

    private fun summarizeResponse(response: Response?, previewLimit: Long = 512L): String {
        if (response == null) return "无可用响应（response=null）"
        val statusLine = "HTTP ${response.code} ${response.message.ifBlank { "(无状态描述)" }}"
        val protocol = response.header("Sec-WebSocket-Protocol")?.let { "，协商协议=$it" } ?: ""
        val server = response.header("Server")?.let { "，Server=$it" } ?: ""
        val errorCode = response.header("X-Error-Code")?.let { "，服务器错误码=$it" } ?: ""
        val headersPreview =
                response.headers
                        .names()
                        .filterNot { it.equals("Set-Cookie", true) }
                        .sorted()
                        .take(5)
                        .joinToString(separator = "; ") { name ->
                            val value = response.header(name).orEmpty()
                            "$name=$value"
                        }
        val headerText = if (headersPreview.isBlank()) "" else "，首部信息={$headersPreview}"
        val bodyPreview =
                runCatching { response.peekBody(previewLimit).string().trim() }
                        .getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { "，响应体预览=${sanitizeForLog(it)}" }
                        ?: ""
        return statusLine + protocol + server + errorCode + headerText + bodyPreview
    }

    private fun sanitizeForLog(raw: String): String {
        return raw.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n")
    }

    private fun attemptScheduleReconnect() {
        if (userInitiatedDisconnect) {
            logger.info("用户主动断开，不自动重连")
            return
        }
        if (lastConnectUrl.isNullOrBlank()) {
            reportConnectionError("无法自动重连：缺少上次连接的服务器地址，请重新配置连接信息")
            return
        }
        if (retryCount >= maxRetries) {
            reportConnectionError("达到最大重试次数($maxRetries)，已停止自动重连，请检查服务器状态或网络")
            return
        }
        if (reconnectJobActive) {
            logger.debug("已有重连任务，跳过重复调度", throttleMs = 2_000L, throttleKey = "reconnect_skip")
            return
        }
        val delayMs = 1500L * (retryCount + 1)
        reconnectJobActive = true
        retryCount += 1
        logger.info("计划 ${delayMs}ms 后进行第 $retryCount 次重连 ...")
        scope.launch {
            try {
                kotlinx.coroutines.delay(delayMs)
                reconnectJobActive = false
                // 再次确认未被用户断开
                if (!userInitiatedDisconnect && _connectionState.value != ConnectionState.CONNECTED
                ) {
                    connect(lastConnectUrl!!, lastConnectPlatform, lastConnectAuth)
                }
            } catch (e: Exception) {
                reconnectJobActive = false
                reportConnectionError("重连调度失败：${e.message ?: "未知错误"}", e)
            }
        }
    }

    fun setUserProfile(nickname: String, cardName: String? = null, userId: String? = null) {
        userNickname = nickname.ifBlank { null }
        userCardName = cardName?.ifBlank { null }
        userId?.let { if (it.isNotBlank()) this.userId = it }
    }
    fun setActiveModel(context: Context, modelName: String?) {
        receiverModelName = modelName?.ifBlank { null }
        activeModelKey = modelName?.lowercase()?.replace(Regex("[^a-z0-9_-]+"), "_")
        appContext = context.applicationContext
        activeModelKey?.let { loadHistory(context, it) }
    }
    fun setReceiverInfo(userId: String?, userNickname: String?) {
        receiverUserIdOverride = userId?.ifBlank { null }
        receiverUserNicknameOverride = userNickname?.ifBlank { null }
    }
    fun hasUserNickname(): Boolean = !userNickname.isNullOrBlank()
    fun getUserNickname(): String? = userNickname
    private fun isSenderMe(sender: SenderInfo?): Boolean {
        if (sender == null) return false
        val sid = sender.userInfo?.userId
        if (!sid.isNullOrBlank() && sid == this.userId) return true
        val snick = sender.userInfo?.userNickname
        if (!snick.isNullOrBlank() && !userNickname.isNullOrBlank() && snick == userNickname)
                return true
        return false
    }

    private fun buildStandardMessage(
            segments: List<Seg>,
            messageType: String,
            raw: String? = null,
            additional: Map<String, Any> = emptyMap()
    ): MessageBase {
        val allTypes = segments.map { it.type }
        val rootSeg =
                if (segments.size == 1 && segments[0].type == "seglist") segments[0]
                else Seg("seglist", segments)
        val senderInfo =
                SenderInfo(
                        userInfo =
                                UserInfo(
                                        platform = platform,
                                        userId = userId,
                                        userNickname = userNickname,
                                        userCardname = userCardName
                                )
                )
        val receiverInfo =
                ReceiverInfo(
                        userInfo =
                                UserInfo(
                                        platform = platform,
                                        userId = receiverUserIdOverride ?: receiverModelName,
                                        userNickname = receiverUserNicknameOverride
                                                        ?: receiverModelName
                                )
                )
        val msgInfo =
                BaseMessageInfo(
                        platform = platform,
                        messageId = generateMessageId(),
                        time = System.currentTimeMillis() / 1000.0,
                        senderInfo = senderInfo,
                        receiverInfo = receiverInfo,
                        groupInfo = senderInfo.groupInfo,
                        userInfo = senderInfo.userInfo,
                        formatInfo =
                                FormatInfo(
                                        contentFormat = allTypes.distinct(),
                                        acceptFormat = listOf("text", "image", "emoji", "voice")
                                ),
                        templateInfo = null,
                        additionalConfig =
                                if (additional.isEmpty()) mapOf("message_type" to messageType)
                                else additional + mapOf("message_type" to messageType)
                )
        return MessageBase(msgInfo, rootSeg, raw)
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val standard = MessageBase.fromJsonString(text)
            addStandardMessage(standard)
            when (val result = messageHandler.handleStandardMessage(standard)) {
                is Live2DChatMessageHandler.ChatMessageResult.Success -> {
                    val fromUser = isSenderMe(standard.messageInfo.senderInfo)
                    val srvTs = ((standard.messageInfo.time ?: 0.0) * 1000).toLong()
                    val isHistorical =
                            srvTs > 0 &&
                                    lastServerMessageTime > 0 &&
                                    srvTs + 1000 < lastServerMessageTime
                    if (!isHistorical) {
                        val adjusted =
                                result.message.copy(
                                        isFromUser = fromUser,
                                        timestamp =
                                                if (srvTs > 0) srvTs else result.message.timestamp
                                )
                        addMessage(adjusted)
                        if (srvTs > 0 && srvTs > lastServerMessageTime)
                                lastServerMessageTime = srvTs
                    }
                }
                is Live2DChatMessageHandler.ChatMessageResult.VoiceProcessed -> {}
                is Live2DChatMessageHandler.ChatMessageResult.EmojiProcessed -> {}
                is Live2DChatMessageHandler.ChatMessageResult.Error ->
                        logger.error("消息处理错误: ${result.message}")
            }
        } catch (e: Exception) {
            logger.error("处理消息失败", e)
        }
    }

    fun sendUserMessage(content: String) {
        val message = buildStandardMessage(listOf(Seg("text", content)), "chat", raw = content)
        addMessage(
                ChatMessage(
                        id = message.messageInfo.messageId!!,
                        content = content,
                        isFromUser = true
                )
        )
        sendStandardMessage(message)
    }
    fun sendStandardMessage(message: MessageBase) {
        sendRawMessage(message.toJsonString())
    }
    private fun sendRawMessage(text: String) {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            webSocket?.send(text)
            logger.debug(
                    "发送: ${sanitizeForLog(text)}",
                    throttleMs = 200L,
                    throttleKey = "send_preview"
            )
        } else {
            logger.warn("未连接, 发送失败", throttleMs = 1_000L, throttleKey = "send_without_connection")
        }
    }
    fun sendMotionMessage(group: String, index: Int, loop: Boolean = false) {
        val m =
                buildStandardMessage(
                        listOf(Seg("text", "播放动作: $group[$index]")),
                        "motion",
                        additional =
                                mapOf(
                                        "motion" to
                                                mapOf(
                                                        "group" to group,
                                                        "index" to index,
                                                        "loop" to loop
                                                )
                                )
                )
        sendStandardMessage(m)
    }
    fun triggerModelMotion(group: String, index: Int, loop: Boolean = false) {
        onMotionTrigger?.invoke(group, index, loop)
        sendMotionMessage(group, index, loop)
    }
    fun setMotionTriggerCallback(callback: (String, Int, Boolean) -> Unit) {
        onMotionTrigger = callback
    }
    fun getMessageEvents() = messageHandler.messageEvents
    private fun addMessage(message: ChatMessage) {
        val list = _messages.value.toMutableList()
        if (list.any { it.id == message.id }) return
        list.add(message)
        _messages.value = list
        val key = activeModelKey
        val ctx = appContext
        if (key != null && ctx != null) saveHistory(ctx, key)
    }
    private fun addStandardMessage(message: MessageBase) {
        val list = _standardMessages.value.toMutableList()
        val mid = message.messageInfo.messageId
        if (!mid.isNullOrBlank() && list.any { it.messageInfo.messageId == mid }) return
        list.add(message)
        _standardMessages.value = list
        val key = activeModelKey
        val ctx = appContext
        if (key != null && ctx != null) saveHistory(ctx, key)
    }

    private fun synchronizeCachedMessagePlatforms(newPlatform: String) {
        val current = _standardMessages.value
        if (current.isEmpty()) return
        val updated = current.map { it.withPlatform(newPlatform) }
        _standardMessages.value = updated
    }

    private fun MessageBase.withPlatform(newPlatform: String): MessageBase {
        val updatedInfo = messageInfo.withPlatform(newPlatform)
        return if (updatedInfo === messageInfo) this else copy(messageInfo = updatedInfo)
    }

    private fun BaseMessageInfo.withPlatform(newPlatform: String): BaseMessageInfo {
        val updatedSender = senderInfo?.withPlatform(newPlatform)
        val updatedReceiver = receiverInfo?.withPlatform(newPlatform)
        val updatedGroup = groupInfo?.withPlatform(newPlatform)
        val updatedUser = userInfo?.withPlatform(newPlatform)
        if (platform == newPlatform &&
                        updatedSender === senderInfo &&
                        updatedReceiver === receiverInfo &&
                        updatedGroup === groupInfo &&
                        updatedUser === userInfo
        )
                return this
        return copy(
                platform = newPlatform,
                senderInfo = updatedSender,
                receiverInfo = updatedReceiver,
                groupInfo = updatedGroup,
                userInfo = updatedUser
        )
    }

    private fun SenderInfo.withPlatform(newPlatform: String): SenderInfo {
        val updatedGroup = groupInfo?.withPlatform(newPlatform)
        val updatedUser = userInfo?.withPlatform(newPlatform)
        if (updatedGroup === groupInfo && updatedUser === userInfo) return this
        return copy(groupInfo = updatedGroup, userInfo = updatedUser)
    }

    private fun ReceiverInfo.withPlatform(newPlatform: String): ReceiverInfo {
        val updatedGroup = groupInfo?.withPlatform(newPlatform)
        val updatedUser = userInfo?.withPlatform(newPlatform)
        if (updatedGroup === groupInfo && updatedUser === userInfo) return this
        return copy(groupInfo = updatedGroup, userInfo = updatedUser)
    }

    private fun GroupInfo.withPlatform(newPlatform: String): GroupInfo {
        if (platform == newPlatform) return this
        return copy(platform = newPlatform)
    }

    private fun UserInfo.withPlatform(newPlatform: String): UserInfo {
        if (platform == newPlatform) return this
        return copy(platform = newPlatform)
    }
    fun clearMessages() {
        _messages.value = emptyList()
        _standardMessages.value = emptyList()
        lastServerMessageTime = 0L
        val key = activeModelKey
        val ctx = appContext
        if (key != null && ctx != null) saveHistory(ctx, key)
    }
    fun clearMessagesEphemeral() {
        _messages.value = emptyList()
        _standardMessages.value = emptyList()
        lastServerMessageTime = 0L
    }
    fun loadHistory(context: Context, modelKey: String) {
        try {
            val sp = context.getSharedPreferences("chat_history", Context.MODE_PRIVATE)
            val msgsStr = sp.getString("messages_" + modelKey, null)
            val stdStr = sp.getString("standard_" + modelKey, null)
            val loadedMsgs = mutableListOf<ChatMessage>()
            val loadedStd = mutableListOf<MessageBase>()
            if (!msgsStr.isNullOrBlank()) {
                try {
                    val arr = gson.fromJson(msgsStr, JsonArray::class.java)
                    arr?.forEach { el ->
                        if (el.isJsonObject) {
                            val o = el.asJsonObject
                            val id = o.get("id")?.asString ?: return@forEach
                            val content = o.get("content")?.asString ?: ""
                            val isFromUser = o.get("isFromUser")?.asBoolean ?: false
                            val ts = o.get("timestamp")?.asLong ?: System.currentTimeMillis()
                            loadedMsgs.add(ChatMessage(id, content, isFromUser, ts))
                        }
                    }
                } catch (_: Exception) {}
            }
            if (!stdStr.isNullOrBlank()) {
                try {
                    val arr = gson.fromJson(stdStr, JsonArray::class.java)
                    arr?.forEach { el ->
                        if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
                            try {
                                loadedStd.add(MessageBase.fromJsonString(el.asString))
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
            _messages.value = loadedMsgs
            _standardMessages.value = loadedStd
            synchronizeCachedMessagePlatforms(this.platform)
            val lastTs = loadedStd.maxOfOrNull { (((it.messageInfo.time) ?: 0.0) * 1000).toLong() }
            if (lastTs != null && lastTs > 0) lastServerMessageTime = lastTs
        } catch (e: Exception) {
            logger.error("加载历史失败", e)
        }
    }
    fun saveHistory(context: Context, modelKey: String) {
        try {
            val sp = context.getSharedPreferences("chat_history", Context.MODE_PRIVATE)
            val editor = sp.edit()
            val msgs = _messages.value.takeLast(200)
            val arrMsgs = JsonArray()
            msgs.forEach { m ->
                val o = JsonObject()
                o.addProperty("id", m.id)
                o.addProperty("content", m.content)
                o.addProperty("isFromUser", m.isFromUser)
                o.addProperty("timestamp", m.timestamp)
                arrMsgs.add(o)
            }
            val stds = _standardMessages.value.takeLast(200)
            val arrStd = JsonArray()
            stds.forEach { s -> arrStd.add(s.toJsonString()) }
            editor.putString("messages_" + modelKey, gson.toJson(arrMsgs))
            editor.putString("standard_" + modelKey, gson.toJson(arrStd))
            editor.apply()
        } catch (e: Exception) {
            logger.error("保存历史失败", e)
        }
    }
    private fun generateMessageId(): String =
            "msg_${System.currentTimeMillis()}_${(Math.random()*1000).toInt()}"
    private fun generateUserId(): String =
            "u_${System.currentTimeMillis()}_${(Math.random()*1000).toInt()}"
    fun disconnect() {
        userInitiatedDisconnect = true
        try {
            webSocket?.close(1000, "用户断开")
        } catch (_: Exception) {}
        _connectionState.value = ConnectionState.DISCONNECTED
    }
    fun getConnectionStateDescription(): String =
            when (_connectionState.value) {
                ConnectionState.DISCONNECTED -> "未连接"
                ConnectionState.CONNECTING -> "连接中..."
                ConnectionState.CONNECTED -> "已连接"
                ConnectionState.ERROR -> "连接错误"
            }

    fun getPlatform(): String = platform
}
