package com.l2dchat.chat.service

object ChatServiceProtocol {
    // Client -> Service commands
    const val MSG_REGISTER_CLIENT = 1
    const val MSG_UNREGISTER_CLIENT = 2
    const val MSG_CONNECT = 3
    const val MSG_DISCONNECT = 4
    const val MSG_SEND_MESSAGE = 5
    const val MSG_UPDATE_CONFIG = 6
    const val MSG_REQUEST_SNAPSHOT = 7
    const val MSG_CLEAR_MESSAGES = 8
    const val MSG_SET_ACTIVE_MODEL = 9
    const val MSG_CLEAR_MESSAGES_EPHEMERAL = 10

    // Service -> Client events
    const val MSG_EVENT_CONNECTION_STATE = 101
    const val MSG_EVENT_NEW_MESSAGE = 102
    const val MSG_EVENT_SNAPSHOT = 103
    const val MSG_EVENT_ERROR = 104
    const val MSG_EVENT_STANDARD_MESSAGE = 105

    // Common extras
    const val EXTRA_URL = "extra_url"
    const val EXTRA_PLATFORM = "extra_platform"
    const val EXTRA_AUTH_TOKEN = "extra_auth_token"
    const val EXTRA_MESSAGE_TEXT = "extra_message_text"
    const val EXTRA_NICKNAME = "extra_nickname"
    const val EXTRA_RECEIVER_ID = "extra_receiver_id"
    const val EXTRA_RECEIVER_NICKNAME = "extra_receiver_nickname"
    const val EXTRA_MODEL_NAME = "extra_model_name"

    // Event extras
    const val EXTRA_CONNECTION_STATE = "extra_connection_state"
    const val EXTRA_CONNECTION_LABEL = "extra_connection_label"
    const val EXTRA_MESSAGE_ID = "extra_message_id"
    const val EXTRA_MESSAGE_CONTENT = "extra_message_content"
    const val EXTRA_MESSAGE_FROM_USER = "extra_message_from_user"
    const val EXTRA_MESSAGE_TIMESTAMP = "extra_message_timestamp"
    const val EXTRA_MESSAGE_BUNDLE_LIST = "extra_message_bundle_list"
    const val EXTRA_STANDARD_MESSAGE_LIST = "extra_standard_message_list"
    const val EXTRA_STANDARD_MESSAGE_JSON = "extra_standard_message_json"
    const val EXTRA_ERROR_MESSAGE = "extra_error_message"
}
