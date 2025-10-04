package com.l2dchat.chat

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

// Helper to safely read strings
private fun jsonGetString(obj: JsonObject?, key: String): String? {
    val el = obj?.get(key) ?: return null
    if (el.isJsonNull) return null
    return if (el.isJsonPrimitive) {
        val p = el.asJsonPrimitive
        when {
            p.isString -> p.asString
            p.isNumber || p.isBoolean -> p.toString()
            else -> null
        }
    } else null
}

private fun UserInfo?.takeIfMeaningful(): UserInfo? {
    if (this == null) return null
    return if (platform.isNullOrBlank() &&
                    userId.isNullOrBlank() &&
                    userNickname.isNullOrBlank() &&
                    userCardname.isNullOrBlank()
    ) {
        null
    } else this
}

data class Seg(@SerializedName("type") val type: String, @SerializedName("data") val data: Any) {
    companion object {
        fun fromJson(json: JsonObject): Seg {
            val type = json.get("type").asString
            val dataElement = json.get("data")
            val data: Any =
                    when (type) {
                        "seglist" -> dataElement.asJsonArray.map { fromJson(it.asJsonObject) }
                        else -> dataElement.asString
                    }
            return Seg(type, data)
        }
    }
    fun toJson(): JsonObject {
        val json = JsonObject()
        json.addProperty("type", type)
        when (type) {
            "seglist" -> {
                @Suppress("UNCHECKED_CAST") val segList = data as List<Seg>
                val array = com.google.gson.JsonArray()
                segList.forEach { seg -> array.add(seg.toJson()) }
                json.add("data", array)
            }
            else -> json.addProperty("data", data.toString())
        }
        return json
    }
}

data class GroupInfo(
        @SerializedName("platform") val platform: String? = null,
        @SerializedName("group_id") val groupId: String? = null,
        @SerializedName("group_name") val groupName: String? = null
) {
    fun toJson(): JsonObject {
        val json = JsonObject()
        platform?.let { json.addProperty("platform", it) }
        groupId?.let { json.addProperty("group_id", it) }
        groupName?.let { json.addProperty("group_name", it) }
        return json
    }
    companion object {
        fun fromJson(json: JsonObject?): GroupInfo? {
            if (json == null) return null
            val gid = jsonGetString(json, "group_id") ?: return null
            return GroupInfo(
                    jsonGetString(json, "platform"),
                    gid,
                    jsonGetString(json, "group_name")
            )
        }
    }
}

data class UserInfo(
        @SerializedName("platform") val platform: String? = null,
        @SerializedName("user_id") val userId: String? = null,
        @SerializedName("user_nickname") val userNickname: String? = null,
        @SerializedName("user_cardname") val userCardname: String? = null
) {
    fun toJson(): JsonObject {
        val json = JsonObject()
        platform?.let { json.addProperty("platform", it) }
        userId?.let { json.addProperty("user_id", it) }
        userNickname?.let { json.addProperty("user_nickname", it) }
        userCardname?.let { json.addProperty("user_cardname", it) }
        return json
    }
    companion object {
        fun fromJson(json: JsonObject?): UserInfo =
                UserInfo(
                        jsonGetString(json, "platform"),
                        jsonGetString(json, "user_id"),
                        jsonGetString(json, "user_nickname"),
                        jsonGetString(json, "user_cardname")
                )
    }
}

data class SenderInfo(
        @SerializedName("group_info") val groupInfo: GroupInfo? = null,
        @SerializedName("user_info") val userInfo: UserInfo? = null
) {
    fun toJson(): JsonObject {
        val json = JsonObject()
        groupInfo?.let { json.add("group_info", it.toJson()) }
        userInfo?.let { json.add("user_info", it.toJson()) }
        return json
    }
    companion object {
        fun fromJson(json: JsonObject?): SenderInfo? {
            if (json == null) return null
            val giObj =
                    json.get("group_info")?.let { if (it.isJsonObject) it.asJsonObject else null }
            val uiObj =
                    json.get("user_info")?.let { if (it.isJsonObject) it.asJsonObject else null }
            return SenderInfo(GroupInfo.fromJson(giObj), UserInfo.fromJson(uiObj))
        }
    }
}

data class ReceiverInfo(
        @SerializedName("group_info") val groupInfo: GroupInfo? = null,
        @SerializedName("user_info") val userInfo: UserInfo? = null
) {
    fun toJson(): JsonObject {
        val json = JsonObject()
        groupInfo?.let { json.add("group_info", it.toJson()) }
        userInfo?.let { json.add("user_info", it.toJson()) }
        return json
    }
    companion object {
        fun fromJson(json: JsonObject?): ReceiverInfo? {
            if (json == null) return null
            val giObj =
                    json.get("group_info")?.let { if (it.isJsonObject) it.asJsonObject else null }
            val uiObj =
                    json.get("user_info")?.let { if (it.isJsonObject) it.asJsonObject else null }
            return ReceiverInfo(GroupInfo.fromJson(giObj), UserInfo.fromJson(uiObj))
        }
    }
}

data class FormatInfo(
        @SerializedName("content_format") val contentFormat: List<String>? = null,
        @SerializedName("accept_format") val acceptFormat: List<String>? = null
) {
    fun toJson(): JsonObject {
        val json = JsonObject()
        contentFormat?.let {
            val arr = com.google.gson.JsonArray()
            it.forEach { f -> arr.add(f) }
            json.add("content_format", arr)
        }
        acceptFormat?.let {
            val arr = com.google.gson.JsonArray()
            it.forEach { f -> arr.add(f) }
            json.add("accept_format", arr)
        }
        return json
    }
    companion object {
        fun fromJson(json: JsonObject?): FormatInfo =
                FormatInfo(
                        json?.getAsJsonArray("content_format")?.map { it.asString },
                        json?.getAsJsonArray("accept_format")?.map { it.asString }
                )
    }
}

data class TemplateInfo(
        @SerializedName("template_items") val templateItems: Map<String, String>? = null,
        @SerializedName("template_name") val templateName: Map<String, String>? = null,
        @SerializedName("template_default") val templateDefault: Boolean = true
) {
    fun toJson(): JsonObject {
        val json = JsonObject()
        templateItems?.let {
            val obj = JsonObject()
            it.forEach { (k, v) -> obj.addProperty(k, v) }
            json.add("template_items", obj)
        }
        templateName?.let {
            val obj = JsonObject()
            it.forEach { (k, v) -> obj.addProperty(k, v) }
            json.add("template_name", obj)
        }
        json.addProperty("template_default", templateDefault)
        return json
    }
    companion object {
        fun fromJson(json: JsonObject?): TemplateInfo =
                TemplateInfo(
                        json?.getAsJsonObject("template_items")?.entrySet()?.associate {
                            it.key to it.value.asString
                        },
                        json?.getAsJsonObject("template_name")?.entrySet()?.associate {
                            it.key to it.value.asString
                        },
                        json?.get("template_default")?.asBoolean ?: true
                )
    }
}

data class BaseMessageInfo(
        @SerializedName("platform") val platform: String? = null,
        @SerializedName("message_id") val messageId: String? = null,
        @SerializedName("time") val time: Double? = null,
        @SerializedName("sender_info") val senderInfo: SenderInfo? = null,
        @SerializedName("receiver_info") val receiverInfo: ReceiverInfo? = null,
        @SerializedName("group_info") val groupInfo: GroupInfo? = null,
        @SerializedName("user_info") val userInfo: UserInfo? = null,
        @SerializedName("format_info") val formatInfo: FormatInfo? = null,
        @SerializedName("template_info") val templateInfo: TemplateInfo? = null,
        @SerializedName("additional_config") val additionalConfig: Map<String, Any>? = null
) {
    fun toJson(): JsonObject {
        val json = JsonObject()
        platform?.let { json.addProperty("platform", it) }
        messageId?.let { json.addProperty("message_id", it) }
        time?.let { json.addProperty("time", it) }
        senderInfo?.let { json.add("sender_info", it.toJson()) }
        receiverInfo?.let { json.add("receiver_info", it.toJson()) }
        val legacyGroupInfo = groupInfo ?: senderInfo?.groupInfo ?: receiverInfo?.groupInfo
        val legacyUserInfo = userInfo ?: senderInfo?.userInfo ?: receiverInfo?.userInfo
        legacyGroupInfo?.let { json.add("group_info", it.toJson()) }
        legacyUserInfo?.let { json.add("user_info", it.toJson()) }
        formatInfo?.let { json.add("format_info", it.toJson()) }
        templateInfo?.let { json.add("template_info", it.toJson()) }
        additionalConfig?.let {
            val obj = JsonObject()
            it.forEach { (k, v) ->
                when (v) {
                    is String -> obj.addProperty(k, v)
                    is Number -> obj.addProperty(k, v)
                    is Boolean -> obj.addProperty(k, v)
                    else -> obj.addProperty(k, v.toString())
                }
            }
            json.add("additional_config", obj)
        }
        return json
    }
    companion object {
        fun fromJson(json: JsonObject?): BaseMessageInfo {
            val senderInfo =
                    SenderInfo.fromJson(
                            json?.get("sender_info")?.let {
                                if (it.isJsonObject) it.asJsonObject else null
                            }
                    )
            val receiverInfo =
                    ReceiverInfo.fromJson(
                            json?.get("receiver_info")?.let {
                                if (it.isJsonObject) it.asJsonObject else null
                            }
                    )
            val legacyGroupInfo =
                    json?.get("group_info")
                            ?.let { if (it.isJsonObject) it.asJsonObject else null }
                            ?.let { GroupInfo.fromJson(it) }
            val legacyUserInfoRaw =
                    json?.get("user_info")
                            ?.let { if (it.isJsonObject) it.asJsonObject else null }
                            ?.let { UserInfo.fromJson(it) }
            val effectiveGroupInfo = legacyGroupInfo ?: receiverInfo?.groupInfo
            val effectiveUserInfo = (legacyUserInfoRaw ?: receiverInfo?.userInfo).takeIfMeaningful()
            val additionalConfig =
                    json?.getAsJsonObject("additional_config")?.entrySet()?.associate { e ->
                        e.key to
                                if (e.value.isJsonPrimitive) {
                                    val p = e.value.asJsonPrimitive
                                    when {
                                        p.isString -> p.asString
                                        p.isNumber -> p.asNumber
                                        p.isBoolean -> p.asBoolean
                                        else -> p.asString
                                    }
                                } else e.value.toString()
                    }

            return BaseMessageInfo(
                    platform = json?.get("platform")?.asString,
                    messageId = json?.get("message_id")?.asString,
                    time = json?.get("time")?.asDouble,
                    senderInfo = senderInfo,
                    receiverInfo = receiverInfo,
                    groupInfo = effectiveGroupInfo,
                    userInfo = effectiveUserInfo,
                    formatInfo = FormatInfo.fromJson(json?.getAsJsonObject("format_info")),
                    templateInfo = TemplateInfo.fromJson(json?.getAsJsonObject("template_info")),
                    additionalConfig = additionalConfig
            )
        }
    }
}

data class MessageBase(
        @SerializedName("message_info") val messageInfo: BaseMessageInfo,
        @SerializedName("message_segment") val messageSegment: Seg,
        @SerializedName("raw_message") val rawMessage: String? = null
) {
    fun toJson(): JsonObject {
        val json = JsonObject()
        json.add("message_info", messageInfo.toJson())
        json.add("message_segment", messageSegment.toJson())
        rawMessage?.let { json.addProperty("raw_message", it) }
        return json
    }
    fun toJsonString(): String = Gson().toJson(toJson())
    companion object {
        fun fromJson(json: JsonObject): MessageBase =
                MessageBase(
                        BaseMessageInfo.fromJson(json.getAsJsonObject("message_info")),
                        Seg.fromJson(json.getAsJsonObject("message_segment")),
                        json.get("raw_message")?.asString
                )
        fun fromJsonString(jsonString: String): MessageBase {
            val json = Gson().fromJson(jsonString, JsonObject::class.java)
            return fromJson(json)
        }
    }
}
