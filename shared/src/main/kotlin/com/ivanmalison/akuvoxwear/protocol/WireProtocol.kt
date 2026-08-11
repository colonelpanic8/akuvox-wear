package com.ivanmalison.akuvoxwear.protocol

import java.nio.charset.StandardCharsets
import java.util.UUID

object WireProtocol {
    const val UNLOCK_REQUEST_PATH = "/akuvox/unlock/request"
    const val UNLOCK_RESULT_PATH = "/akuvox/unlock/result"

    data class UnlockResult(
        val requestId: String,
        val successful: Boolean,
        val message: String,
    )

    fun newRequestId(): String = UUID.randomUUID().toString()

    fun encodeRequest(requestId: String): ByteArray {
        requireValidRequestId(requestId)
        return requestId.toByteArray(StandardCharsets.UTF_8)
    }

    fun decodeRequest(payload: ByteArray): String {
        val requestId = payload.toString(StandardCharsets.UTF_8)
        requireValidRequestId(requestId)
        return requestId
    }

    fun encodeResult(result: UnlockResult): ByteArray {
        requireValidRequestId(result.requestId)
        val safeMessage = result.message.replace(SEPARATOR, ' ').take(MAX_MESSAGE_LENGTH)
        return listOf(result.requestId, result.successful.toString(), safeMessage)
            .joinToString(SEPARATOR.toString())
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun decodeResult(payload: ByteArray): UnlockResult {
        val parts = payload.toString(StandardCharsets.UTF_8).split(SEPARATOR, limit = 3)
        require(parts.size == 3) { "Malformed unlock result" }
        requireValidRequestId(parts[0])
        require(parts[1] == "true" || parts[1] == "false") { "Malformed result status" }
        return UnlockResult(parts[0], parts[1].toBoolean(), parts[2])
    }

    private fun requireValidRequestId(requestId: String) {
        require(runCatching { UUID.fromString(requestId) }.isSuccess) { "Invalid request ID" }
    }

    private const val SEPARATOR = '\u0000'
    private const val MAX_MESSAGE_LENGTH = 240
}
