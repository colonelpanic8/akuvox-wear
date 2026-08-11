package com.ivanmalison.akuvoxwear.protocol

import com.ivanmalison.akuvoxwear.protocol.WireProtocol.UnlockResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WireProtocolTest {
    @Test
    fun requestRoundTrips() {
        val requestId = WireProtocol.newRequestId()

        assertEquals(requestId, WireProtocol.decodeRequest(WireProtocol.encodeRequest(requestId)))
    }

    @Test
    fun resultRoundTripsAndSanitizesMessage() {
        val result = UnlockResult(WireProtocol.newRequestId(), true, "Door\u0000opened")

        assertEquals(
            result.copy(message = "Door opened"),
            WireProtocol.decodeResult(WireProtocol.encodeResult(result)),
        )
    }

    @Test
    fun malformedRequestIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            WireProtocol.decodeRequest("not-a-uuid".toByteArray())
        }
    }
}
