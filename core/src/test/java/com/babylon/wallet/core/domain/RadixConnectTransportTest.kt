package com.babylon.wallet.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import rdx.works.core.domain.RadixConnectTransport

class RadixConnectTransportTest {

    @Test
    fun `the tester build uses Automatic by default`() {
        assertEquals(RadixConnectTransport.AUTOMATIC, RadixConnectTransport.DEFAULT)
    }

    @Test
    fun `every transport round trips through its literal`() {
        RadixConnectTransport.entries.forEach { transport ->
            assertEquals(transport, RadixConnectTransport.fromLiteral(transport.toLiteral()))
        }
    }

    @Test
    fun `literals are stable`() {
        assertEquals("automatic", RadixConnectTransport.AUTOMATIC.toLiteral())
        assertEquals("webrtc", RadixConnectTransport.WEBRTC.toLiteral())
        assertEquals("iroh", RadixConnectTransport.IROH.toLiteral())
    }

    @Test
    fun `missing or unknown literals fall back to the default`() {
        assertEquals(RadixConnectTransport.DEFAULT, RadixConnectTransport.fromLiteral(null))
        assertEquals(RadixConnectTransport.DEFAULT, RadixConnectTransport.fromLiteral(""))
        assertEquals(RadixConnectTransport.DEFAULT, RadixConnectTransport.fromLiteral("carrier_pigeon"))
    }
}
