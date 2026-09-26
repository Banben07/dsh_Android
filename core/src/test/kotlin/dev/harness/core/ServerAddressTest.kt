package dev.harness.core

import kotlin.test.*
import org.junit.Test

class ServerAddressTest {
    @Test fun `tailnet address defaults to HTTP and keeps port`() {
        assertEquals("http://100.80.20.10:3000", ServerAddress.parse("100.80.20.10:3000").origin)
    }
    @Test fun `pasted token is separated from the saved origin`() {
        val address = ServerAddress.parse("http://100.80.20.10:3000/?token=a%2Bb#session")
        assertEquals("a+b", address.launchToken)
        assertEquals("http://100.80.20.10:3000", address.origin)
        assertNull(address.base.query)
    }
    @Test fun `supports HTTPS MagicDNS and IPv6`() {
        assertEquals("https://host.tail.example", ServerAddress.parse("https://host.tail.example/").origin)
        assertEquals("http://[fd7a:115c:a1e0::1]:3000", ServerAddress.parse("http://[fd7a:115c:a1e0::1]:3000").origin)
    }
    @Test fun `does not accept credentials or API paths in endpoint`() {
        assertFailsWith<IllegalArgumentException> { ServerAddress.parse("http://user:secret@example.com") }
        assertFailsWith<IllegalArgumentException> { ServerAddress.parse("http://100.80.20.10:3000/api") }
        assertFailsWith<IllegalArgumentException> { ServerAddress.parse("") }
    }
}
