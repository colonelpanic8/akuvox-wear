package com.ivanmalison.akuvoxwear.protocol

import com.ivanmalison.akuvoxwear.protocol.WireProtocol.PinOutcome
import com.ivanmalison.akuvoxwear.protocol.WireProtocol.UnlockRequest
import com.ivanmalison.akuvoxwear.protocol.WireProtocol.UnlockResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WireProtocolTest {
    @Test
    fun requestRoundTripsWithAndWithoutPin() {
        for (armPin in listOf(false, true)) {
            val request = UnlockRequest(WireProtocol.newRequestId(), armPin)

            assertEquals(request, WireProtocol.decodeRequest(WireProtocol.encodeRequest(request)))
        }
    }

    @Test
    fun requestMatchesTheLayoutThePhoneBridgeParses() {
        val requestId = "123e4567-e89b-12d3-a456-426614174000"

        assertEquals(
            "$requestId\u0000true",
            WireProtocol.encodeRequest(UnlockRequest(requestId, armPin = true)).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun resultRoundTripsAndSanitizesMessage() {
        val result = UnlockResult(WireProtocol.newRequestId(), true, PinOutcome.ARMED, "Door\u0000opened")

        assertEquals(
            result.copy(message = "Door opened"),
            WireProtocol.decodeResult(WireProtocol.encodeResult(result)),
        )
    }

    @Test
    fun resultDecodesThePayloadThePhoneBridgeSends() {
        val requestId = "123e4567-e89b-12d3-a456-426614174000"

        assertEquals(
            UnlockResult(requestId, true, PinOutcome.FAILED, "Door opened, reminder not set"),
            WireProtocol.decodeResult(
                "$requestId\u0000true\u0000failed\u0000Door opened, reminder not set".toByteArray(),
            ),
        )
    }

    @Test
    fun malformedRequestIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            WireProtocol.decodeRequest("not-a-uuid\u0000false".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            WireProtocol.decodeRequest(WireProtocol.newRequestId().toByteArray())
        }
    }
}
