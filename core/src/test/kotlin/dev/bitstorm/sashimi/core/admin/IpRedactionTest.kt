package dev.bitstorm.sashimi.core.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpRedactionTest {
    @Test
    fun `an IPv4 address keeps only its first octet`() {
        assertEquals("IP address: 185.x.x.x", IpRedaction.redact("IP address: 185.22.103.7"))
    }

    @Test
    fun `every address in the text is redacted`() {
        assertEquals("from 10.x.x.x via 72.x.x.x", IpRedaction.redact("from 10.0.0.2 via 72.14.9.200"))
    }

    @Test
    fun `an IPv6 address keeps only its first group`() {
        assertEquals("IP address: 2001:…", IpRedaction.redact("IP address: 2001:db8:85a3::8a2e:370:7334"))
        assertEquals("from fe80:…", IpRedaction.redact("from fe80::1"))
    }

    @Test
    fun `text without an address is unchanged, times included`() {
        assertEquals("mom is playing 300 at 12:30:45", IpRedaction.redact("mom is playing 300 at 12:30:45"))
        assertEquals("Version 12.2.0", IpRedaction.redact("Version 12.2.0"))
        assertNull(IpRedaction.redact(null))
    }
}
