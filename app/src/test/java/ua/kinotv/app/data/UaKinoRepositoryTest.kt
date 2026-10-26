package ua.kinotv.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ua.kinotv.app.data.model.MediaKind
import ua.kinotv.app.data.model.Movie
import ua.kinotv.app.data.parser.SourceFormatException
import ua.kinotv.app.data.repository.UaKinoRepository
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class UaKinoRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: UaKinoRepository

    @Before fun start() {
        server.start()
        // Rewrite transport only in tests. Production sees and validates real HTTPS source URLs.
        val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).addInterceptor { chain ->
            val original = chain.request()
            val transportUrl = server.url("/").newBuilder()
                .encodedPath(original.url.encodedPath).encodedQuery(original.url.encodedQuery).build()
            chain.proceed(original.newBuilder().url(transportUrl).build()).newBuilder().request(original).build()
        }.build()
        repository = UaKinoRepository(client) { "https://uakino.best/" }
    }

    @After fun stop() { server.shutdown() }

    @Test fun `search submits UTF-8 form data without replacing spaces or plus characters`() = runBlocking {
        server.enqueue(html("<div class='berrors'>На жаль, пошук не дав результатів</div>"))
        assertTrue(repository.search("Фільм + друга частина").isEmpty())
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/ua/", request.path)
        val fields = request.body.readUtf8().split('&').associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals("search", fields["do"])
        assertEquals("search", fields["subaction"])
        assertEquals("Фільм + друга частина", fields["story"])
    }

    @Test fun `catalog uses observed category routes and parses a same-origin redirect`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/filmy/page/1/"))
        server.enqueue(html(card()))
        assertEquals("Фільм", repository.catalog(MediaKind.MOVIE).single().title)
        assertEquals("/filmy/", server.takeRequest().path)
        assertEquals("/filmy/page/1/", server.takeRequest().path)
        server.enqueue(html(card()))
        repository.catalog(MediaKind.SERIES, 2)
        assertEquals("/seriesss/page/2/", server.takeRequest().path)
    }

    @Test fun `cross-origin redirect stops before a second request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://other.example/filmy/"))
        try {
            repository.catalog(MediaKind.MOVIE)
            fail("Cross-origin redirect accepted")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("домен"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `HTTP denial stays a failure rather than a successful empty catalog`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        try {
            repository.catalog(MediaKind.MOVIE)
            fail("HTTP error hidden")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("403"))
        }
    }

    @Test fun `unrecognized successful HTML remains a source format failure`() = runBlocking {
        server.enqueue(html("<html><body>Changed layout</body></html>"))
        try {
            repository.catalog(MediaKind.MOVIE)
            fail("Unknown HTML accepted")
        } catch (expected: SourceFormatException) {
            assertTrue(expected.message.orEmpty().contains("картки"))
        }
    }

    @Test fun `details follow explicit torrent HTML endpoint and preserve source metadata`() = runBlocking {
        server.enqueue(html("<h1>Фільм</h1><div itemprop='description'>Опис</div><div class='torrents' data-torrents-url='/releases/101'></div>"))
        server.enqueue(html("<a href='/file/101.torrent'>Фільм 1080p</a>"))
        val details = repository.details(Movie("https://uakino.best/filmy/101-film.html", "Фільм"))
        assertEquals("Опис", details.description)
        assertEquals("https://uakino.best/file/101.torrent", details.torrents.single().downloadUrl)
        assertNull(details.torrentNotice)
        server.takeRequest()
        val ajax = server.takeRequest()
        assertEquals("/releases/101", ajax.path)
        assertEquals("XMLHttpRequest", ajax.getHeader("X-Requested-With"))
        assertEquals("https://uakino.best/filmy/101-film.html", ajax.getHeader("Referer"))
    }

    @Test fun `actual source flow follows download page then submits declared torrent POST`() = runBlocking {
        server.enqueue(html(fixture("matrix-source-details.html")))
        server.enqueue(html(fixture("matrix-source-download.html")))
        server.enqueue(html(fixture("matrix-source-releases.html")))
        val filmPage = "https://uakino.best/filmy/genre-action/11-matrycia.html"
        val downloadPage = "https://uakino.best/filmy/genre-action/11-matrycia/download.html"
        val details = repository.details(Movie(filmPage, "Матриця"))
        assertEquals(5, details.torrents.size)
        assertEquals(downloadPage, details.torrents.first().referer)
        assertNull(details.torrentNotice)
        assertEquals("/filmy/genre-action/11-matrycia.html", server.takeRequest().path)
        val downloadRequest = server.takeRequest()
        assertEquals("/filmy/genre-action/11-matrycia/download.html", downloadRequest.path)
        assertEquals(filmPage, downloadRequest.getHeader("Referer"))
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/engine/ajax/toloka.php", post.path)
        assertEquals(downloadPage, post.getHeader("Referer"))
        val fields = post.body.readUtf8().split('&').associate { field ->
            val pair = field.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals("11", fields["id"])
        assertEquals("Матриця", fields["title"])
        assertEquals("filmy/genre-action/", fields["cat"])
    }

    @Test fun `source no-release result remains a useful film detail response`() = runBlocking {
        server.enqueue(html(fixture("matrix-source-details.html")))
        server.enqueue(html(fixture("matrix-source-download.html")))
        server.enqueue(html("<center>Oppps роздач не виявлено зверніться до адміна</center>"))
        val details = repository.details(Movie("https://uakino.best/filmy/genre-action/11-matrycia.html", "Матриця"))
        assertTrue(details.torrents.isEmpty())
        assertEquals("Джерело не знайшло торрент-роздач для цього фільму.", details.torrentNotice)
    }

    @Test fun `unavailable torrent page preserves playable film details with a visible error`() = runBlocking {
        server.enqueue(html(fixture("matrix-source-details.html")))
        server.enqueue(MockResponse().setResponseCode(503))
        val details = repository.details(Movie("https://uakino.best/filmy/genre-action/11-matrycia.html", "Матриця"))
        assertNotNull(details.onlineUrl)
        assertTrue(details.torrents.isEmpty())
        assertTrue(details.torrentNotice.orEmpty().contains("503"))
    }

    @Test fun `actual JSON playlist response resolves a playable movie before loading torrents`() = runBlocking {
        server.enqueue(html(fixture("matrix-playlist-details.html")))
        server.enqueue(MockResponse().addHeader("Content-Type", "application/json; charset=UTF-8").setBody(fixture("matrix-source-playlist.json")))
        server.enqueue(html(fixture("matrix-source-download.html")))
        server.enqueue(html(fixture("matrix-source-releases.html")))
        val filmPage = "https://uakino.best/filmy/genre-action/11-matrycia.html"
        val details = repository.details(Movie(filmPage, "Матриця"))
        assertEquals("https://ashdi.vip/vod/146072", details.onlineUrl)
        assertEquals(5, details.torrents.size)
        server.takeRequest()
        val playlistRequest = server.takeRequest()
        assertEquals("GET", playlistRequest.method)
        assertEquals("/engine/ajax/playlists.php?news_id=11&xfield=playlist&time=1778492761", playlistRequest.path)
        assertEquals(filmPage, playlistRequest.getHeader("Referer"))
        assertEquals("XMLHttpRequest", playlistRequest.getHeader("X-Requested-With"))
    }

    @Test fun `failed player JSON response keeps torrent access and does not expose a trailer`() = runBlocking {
        server.enqueue(html(fixture("matrix-playlist-details.html")))
        server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("{\"success\":false}"))
        server.enqueue(html(fixture("matrix-source-download.html")))
        server.enqueue(html(fixture("matrix-source-releases.html")))
        val details = repository.details(Movie("https://uakino.best/filmy/genre-action/11-matrycia.html", "Матриця"))
        assertNull(details.onlineUrl)
        assertEquals(5, details.torrents.size)
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/uakino/$name")).readText()

    private fun card() = "<div class='movie-item'><a class='movie-title' href='/filmy/101-film.html'>Фільм</a></div>"
    private fun html(body: String) = MockResponse().addHeader("Content-Type", "text/html; charset=UTF-8").setBody(body)
}
