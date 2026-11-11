package com.ivanmalison.akuvoxwear.protocol

import java.nio.charset.StandardCharsets
import java.util.UUID

object WireProtocol {
    const val UNLOCK_REQUEST_PATH = "/akuvox/unlock/request"
    const val UNLOCK_RESULT_PATH = "/akuvox/unlock/result"

    data class UnlockRequest(
        val requestId: String,
        /** Ask the phone to pin a reminder once the door has opened. */
        val armPin: Boolean,
    )

    enum class PinOutcome(val wire: String) {
        NOT_REQUESTED("none"),
        ARMED("armed"),
        FAILED("failed"),
    }

    data class UnlockResult(
        val requestId: String,
        val successful: Boolean,
        val pin: PinOutcome,
        val message: String,
    )

    fun newRequestId(): String = UUID.randomUUID().toString()

    fun encodeRequest(request: UnlockRequest): ByteArray {
        requireValidRequestId(request.requestId)
        return listOf(request.requestId, request.armPin.toString())
            .joinToString(SEPARATOR.toString())
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun decodeRequest(payload: ByteArray): UnlockRequest {
        val parts = payload.toString(StandardCharsets.UTF_8).split(SEPARATOR)
        require(parts.size == 2) { "Malformed unlock request" }
        requireValidRequestId(parts[0])
        return UnlockRequest(parts[0], decodeBoolean(parts[1]))
    }

    fun encodeResult(result: UnlockResult): ByteArray {
        requireValidRequestId(result.requestId)
        val safeMessage = result.message.replace(SEPARATOR, ' ').take(MAX_MESSAGE_LENGTH)
        return listOf(result.requestId, result.successful.toString(), result.pin.wire, safeMessage)
            .joinToString(SEPARATOR.toString())
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun decodeResult(payload: ByteArray): UnlockResult {
        val parts = payload.toString(StandardCharsets.UTF_8).split(SEPARATOR, limit = 4)
        require(parts.size == 4) { "Malformed unlock result" }
        requireValidRequestId(parts[0])
        val pin = requireNotNull(PinOutcome.entries.firstOrNull { it.wire == parts[2] }) { "Malformed pin outcome" }
        return UnlockResult(parts[0], decodeBoolean(parts[1]), pin, parts[3])
    }

    private fun decodeBoolean(value: String): Boolean {
        require(value == "true" || value == "false") { "Malformed boolean" }
        return value.toBoolean()
    }

    private fun requireValidRequestId(requestId: String) {
        require(runCatching { UUID.fromString(requestId) }.isSuccess) { "Invalid request ID" }
    }

    private const val SEPARATOR = '\u0000'
    private const val MAX_MESSAGE_LENGTH = 240
}
