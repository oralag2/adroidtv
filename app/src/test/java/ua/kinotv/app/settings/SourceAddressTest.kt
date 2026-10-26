package ua.kinotv.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceAddressTest {
    @Test fun normalizesWebsiteAddress() {
        assertEquals("https://uakino.best/", SourceAddress.normalize("  https://UAkino.best  "))
    }

    @Test fun rejectsUnsafeOrNonRootAddresses() {
        listOf("http://uakino.best", "https://user:pass@uakino.best", "https://uakino.best/filmy/",
            "https://uakino.best/?q=1", "https://uakino.best/#fragment", "https://localhost",
            "https://127.0.0.1", "https://192.168.1.1", "https://[::1]", "https://host.internal",
            "https://uakino.best:8443", "file:///tmp/file").forEach { address ->
            assertThrows(address, IllegalArgumentException::class.java) { SourceAddress.normalize(address) }
        }
    }
}
