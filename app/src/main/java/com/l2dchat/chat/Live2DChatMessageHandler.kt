package com.l2dchat.chat

import com.l2dchat.logging.L2DLogger
import com.l2dchat.logging.LogModule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private val chatLogger = L2DLogger.module(LogModule.CHAT)

class Live2DChatMessageHandler {
    private val _messageEvents: MutableSharedFlow<MessageEvent> = MutableSharedFlow()
    val messageEvents: SharedFlow<MessageEvent> = _messageEvents.asSharedFlow()

    fun handleStandardMessage(message: MessageBase): ChatMessageResult {
        return try {
            val parsed: ParsedMessageContent = parseMessageSegment(message.messageSegment)
            when {
                parsed.hasVoice() -> handleVoiceMessage(message, parsed)
                parsed.hasEmoji() -> handleEmojiMessage(message, parsed)
                else -> handleTextMessage(message, parsed)
            }
        } catch (e: Exception) {
            chatLogger.error("处理消息失败", e)
            ChatMessageResult.Error("消息处理失败: ${e.message}")
        }
    }

    private fun parseMessageSegment(segment: Seg): ParsedMessageContent {
        val content = ParsedMessageContent()
        parseSegmentRecursive(segment, content)
        return content
    }

    // 显式使用代码块并指定返回 Unit，避免编译器对表达式主体推断产生递归类型问题
    private fun parseSegmentRecursive(segment: Seg, content: ParsedMessageContent): Unit {
        when (segment.type) {
            "text" -> content.addText(segment.data.toString())
            "emoji" -> content.addEmoji(segment.data.toString())
            "voice" -> content.addVoice(segment.data.toString())
            "seglist" -> {
                @Suppress("UNCHECKED_CAST")
                val segList = segment.data as List<Seg>
                for (child in segList) {
                    parseSegmentRecursive(child, content)
                }
            }
            else -> {
                chatLogger.warn(
                        message = "未知段类型: ${segment.type}",
                        throttleMs = 5_000L,
                        throttleKey = "unknown_segment_${segment.type}"
                )
                content.addUnknown(segment.type, segment.data.toString())
            }
        }
    }

    private fun handleTextMessage(
            message: MessageBase,
            content: ParsedMessageContent
    ): ChatMessageResult {
        val chat =
                ChatWebSocketManager.ChatMessage(
                        id = message.messageInfo.messageId ?: genId(),
                        content = content.getText(),
                        isFromUser = false,
                        timestamp = ((message.messageInfo.time ?: 0.0) * 1000).toLong()
                )
        emit(MessageEvent.ChatReceived(chat))
        return ChatMessageResult.Success(chat)
    }
    private fun handleVoiceMessage(
            message: MessageBase,
            content: ParsedMessageContent
    ): ChatMessageResult {
        val voice = content.voiceData.firstOrNull() ?: return ChatMessageResult.Error("语音数据为空")
        emit(MessageEvent.VoiceReceived(voice))
        val text = content.getText()
        if (text.isNotBlank()) {
            val chat =
                    ChatWebSocketManager.ChatMessage(
                            id = message.messageInfo.messageId ?: genId(),
                            content = text,
                            isFromUser = false,
                            timestamp = ((message.messageInfo.time ?: 0.0) * 1000).toLong()
                    )
            emit(MessageEvent.ChatReceived(chat))
            return ChatMessageResult.Success(chat)
        }
        return ChatMessageResult.VoiceProcessed(voice)
    }
    private fun handleEmojiMessage(
            message: MessageBase,
            content: ParsedMessageContent
    ): ChatMessageResult {
        val emoji = content.emojiData.firstOrNull() ?: return ChatMessageResult.Error("表情数据为空")
        emit(MessageEvent.EmojiReceived(emoji))
        val text = content.getText()
        val display = if (text.isNotBlank()) text else "[表情]"
        val chat =
                ChatWebSocketManager.ChatMessage(
                        id = message.messageInfo.messageId ?: genId(),
                        content = display,
                        isFromUser = false,
                        timestamp = ((message.messageInfo.time ?: 0.0) * 1000).toLong()
                )
        emit(MessageEvent.ChatReceived(chat))
        return ChatMessageResult.Success(chat)
    }
    private fun emit(event: MessageEvent) {
        _messageEvents.tryEmit(event)
    }
    private fun genId(): String =
            "msg_${System.currentTimeMillis()}_${(Math.random()*1000).toInt()}"

    sealed class ChatMessageResult {
        data class Success(val message: ChatWebSocketManager.ChatMessage) : ChatMessageResult()

        data class VoiceProcessed(val voiceData: String) : ChatMessageResult()

        data class EmojiProcessed(val emojiData: String) : ChatMessageResult()

        data class Error(val message: String) : ChatMessageResult()
    }
    sealed class MessageEvent {
        data class ChatReceived(val message: ChatWebSocketManager.ChatMessage) : MessageEvent()

        data class VoiceReceived(val voiceData: String) : MessageEvent()

        data class EmojiReceived(val emojiData: String) : MessageEvent()

        data class Error(val message: String, val exception: Throwable?) : MessageEvent()
    }
}

class ParsedMessageContent {
    val textData: MutableList<String> = mutableListOf()
    val emojiData: MutableList<String> = mutableListOf()
    val voiceData: MutableList<String> = mutableListOf()
    fun addText(t: String): Unit { textData.add(t) }
    fun addEmoji(e: String): Unit { emojiData.add(e) }
    fun addVoice(v: String): Unit { voiceData.add(v) }
    fun addUnknown(type: String, data: String): Unit {
        chatLogger.warn(
                message = "未知类型当作文本: $type $data",
                throttleMs = 5_000L,
                throttleKey = "parsed_unknown_$type"
        )
        addText("[$type]$data")
    }
    fun getText(): String = textData.joinToString(" ")
    fun hasVoice(): Boolean = voiceData.isNotEmpty()
    fun hasEmoji(): Boolean = emojiData.isNotEmpty()
}
