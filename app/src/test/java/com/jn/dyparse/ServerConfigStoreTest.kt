package com.jn.dyparse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ServerConfigStore 的纯函数部分测试。
 * （涉及 SharedPreferences 的部分需要 Android 环境，这里不覆盖。）
 */
class ServerConfigStoreTest {

    @Test
    fun normalizeUrlTrimsWhitespaceAndTrailingSlash() {
        assertEquals(
            "https://example.com/api/data.php",
            ServerConfigStore.normalizeUrl("  https://example.com/api/data.php/  ")
        )
    }

    @Test
    fun normalizeUrlAcceptsHttp() {
        assertEquals("http://192.168.1.10/api/data.php", ServerConfigStore.normalizeUrl("http://192.168.1.10/api/data.php"))
    }

    @Test
    fun normalizeUrlRejectsMissingScheme() {
        assertNull(ServerConfigStore.normalizeUrl("example.com/api/data.php"))
        assertNull(ServerConfigStore.normalizeUrl("ftp://example.com/x"))
    }

    @Test
    fun normalizeUrlRejectsBlank() {
        assertNull(ServerConfigStore.normalizeUrl(""))
        assertNull(ServerConfigStore.normalizeUrl("   "))
    }

    @Test
    fun placeholderIsDetectedSoUiCanGuideTheUser() {
        assertTrue(ServerConfigStore.isPlaceholder("https://your-server.example.com/api/data.php"))
        assertTrue(ServerConfigStore.isPlaceholder(""))
        assertFalse(ServerConfigStore.isPlaceholder("https://my-own-server.cn/api/data.php"))
    }
}
