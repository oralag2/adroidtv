package ua.kinotv.app.download

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentHttpTest {
    @Test fun `descriptor route without filename preserves source headers and valid body`() {
        MockWebServer().use { server ->
            server.start()
            val descriptor = descriptor()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/x-bittorrent")
                .setBody(Buffer().write(descriptor)))
            val referer = "https://uakino.best/filmy/genre-action/11-matrycia/download.html".toHttpUrl()
            val request = TorrentHttp.request(server.url("/download/711542/torrent/"), referer)
            OkHttpClient().newCall(request).execute().use { response ->
                TorrentHttp.validateResponse(response)
                TorrentValidator.validate(requireNotNull(response.body).bytes())
            }
            val sent = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/download/711542/torrent/", sent.path)
            assertEquals(referer.toString(), sent.getHeader("Referer"))
            assertTrue(sent.getHeader("User-Agent").orEmpty().contains("Mozilla/5.0"))
        }
    }

    @Test fun `browser challenge is distinct from a malformed torrent`() {
        val error = failure(MockResponse().setResponseCode(403)
            .setHeader("Content-Type", "text/html; charset=UTF-8")
            .setBody("<!doctype html><title>Just a moment...</title><script src='/cdn-cgi/challenge-platform/run'></script>"))
        assertTrue(error.message.orEmpty().contains("перевірку браузера"))
    }

    @Test fun `successful HTTP status cannot disguise a browser challenge`() {
        val error = failure(MockResponse().setHeader("Content-Type", "text/html")
            .setBody("<html><title>Checking your browser</title></html>"))
        assertTrue(error.message.orEmpty().contains("перевірку браузера"))
    }

    @Test fun `plain access denial preserves HTTP status`() {
        val error = failure(MockResponse().setResponseCode(403).setBody("Forbidden"))
        assertTrue(error.message.orEmpty().contains("HTTP 403"))
    }

    @Test fun `HTML instead of a descriptor is rejected before storage`() {
        val error = failure(MockResponse().setHeader("Content-Type", "text/html")
            .setBody("<html><body>File not available</body></html>"))
        assertTrue(error.message.orEmpty().contains("Замість торрент-файлу"))
    }

    @Test fun `oversized content length is rejected before reading body`() {
        val error = failure(MockResponse().setHeader("Content-Type", "application/x-bittorrent")
            .setHeader("Content-Length", TorrentValidator.MAX_BYTES + 1))
        assertTrue(error.message.orEmpty().contains("5 МіБ"))
    }

    private fun failure(reply: MockResponse): IOException = MockWebServer().use { server ->
        server.start()
        server.enqueue(reply)
        val request = TorrentHttp.request(server.url("/download/711542/torrent/"),
            "https://uakino.best/filmy/genre-action/11-matrycia/download.html".toHttpUrl())
        OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            assertThrows(IOException::class.java) { TorrentHttp.validateResponse(response) }
        }
    }

    private fun descriptor(): ByteArray =
        "d4:infod6:lengthi1e4:name8:film.mkv12:piece lengthi16384e6:pieces20:00000000000000000000ee".toByteArray()
}
