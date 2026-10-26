package ua.kinotv.app.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCookieJarTest {
    private val site = "https://uakino.best/filmy/".toHttpUrl()

    @Test fun keepsSecureSessionWithinItsHostAndPath() {
        val jar = SessionCookieJar()
        val cookie = Cookie.Builder().name("session").value("example").hostOnlyDomain(site.host)
            .path("/filmy").secure().build()
        jar.saveFromResponse(site, listOf(cookie))
        assertEquals(listOf(cookie), jar.loadForRequest(site))
        assertTrue(jar.loadForRequest("https://unrelated.example/filmy/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://uakino.best/filmy/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://uakino.best/other/".toHttpUrl()).isEmpty())
    }

    @Test fun replacesUpdatedCookieAndRemovesExpiredCookie() {
        val jar = SessionCookieJar()
        val old = Cookie.Builder().name("session").value("old").hostOnlyDomain(site.host).build()
        val replacement = old.newBuilderForTest("new")
        jar.saveFromResponse(site, listOf(old))
        jar.saveFromResponse(site, listOf(replacement))
        assertEquals(listOf(replacement), jar.loadForRequest(site))
        jar.saveFromResponse(site, listOf(Cookie.Builder().name("session").value("")
            .hostOnlyDomain(site.host).expiresAt(0).build()))
        assertTrue(jar.loadForRequest(site).isEmpty())
    }

    private fun Cookie.newBuilderForTest(value: String): Cookie = Cookie.Builder()
        .name(name).value(value).hostOnlyDomain(domain).path(path).build()
}
