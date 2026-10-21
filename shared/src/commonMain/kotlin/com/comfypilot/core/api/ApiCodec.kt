package com.comfypilot.core.api

import kotlinx.serialization.json.Json

object ApiCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    fun encodePrompt(body: PromptRequestBody): String = json.encodeToString(PromptRequestBody.serializer(), body)

    fun decodePromptResponse(text: String): PromptResponse =
        json.decodeFromString(PromptResponse.serializer(), text)

    fun decodeQueue(text: String): QueueSnapshot =
        QueueSnapshot.from(json.decodeFromString(QueueResponse.serializer(), text))

    /** 未知 prompt 返回 `{}`，因此结果可空。 */
    fun decodeHistory(text: String, promptId: String): HistoryEntry? {
        val all: Map<String, HistoryEntry> = json.decodeFromString(text)
        return all[promptId] ?: all.values.firstOrNull()
    }

    fun decodeUploadImage(text: String): UploadImageResponse =
        json.decodeFromString(UploadImageResponse.serializer(), text)
}
