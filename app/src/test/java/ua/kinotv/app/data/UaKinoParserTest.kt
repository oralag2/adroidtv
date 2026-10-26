package ua.kinotv.app.data

import org.junit.Assert.*
import org.junit.Test
import ua.kinotv.app.data.model.Movie
import ua.kinotv.app.data.network.SourceUrl
import ua.kinotv.app.data.parser.SourceFormatException
import ua.kinotv.app.data.parser.UaKinoParser

class UaKinoParserTest {
    private val parser = UaKinoParser()
    private val base = "https://uakino.best/"
    private val page = "${base}filmy/drama/101-test-film.html"

    @Test fun `catalog deduplicates movie URLs and rejects cross-origin or executable links`() {
        val movies = parser.catalog(parser.document(fixture("catalog.html"), base), base)
        assertEquals(1, movies.size)
        assertEquals("Тестовий фільм", movies.single().title)
        assertEquals(page, movies.single().pageUrl)
        assertEquals("${base}uploads/poster.jpg", movies.single().posterUrl)
        assertEquals("2025", movies.single().year)
    }

    @Test fun `details reads metadata and actual torrent links without manufacturing unavailable fields`() {
        val details = parser.details(parser.document(fixture("details.html"), page), Movie(page, "Стара назва"))
        assertEquals("Тестовий фільм", details.movie.title)
        assertEquals(listOf("Драма", "Пригоди"), details.genres)
        assertEquals("7.8 / 10", details.rating)
        assertEquals("Опис фільму. Другий абзац.", details.description)
        val torrent = details.torrents.single()
        assertEquals("${base}uploads/torrents/101.torrent?token=example", torrent.downloadUrl)
        assertEquals("1080p", torrent.quality)
        assertEquals("4.2 GB", torrent.size)
        assertEquals("Українська", torrent.audio)
        assertEquals("12", torrent.seeders)
        assertNull(details.torrentNotice)
        assertEquals("https://ashdi.vip/vod/123", details.onlineUrl)
    }

    @Test fun `script-only torrent UI produces an explicit unsupported notice and no fake releases`() {
        val html = """<h1>Фільм</h1><button onclick="loadTorrent(101)">Торренти</button><script>loadTorrent=function(){}</script>"""
        val details = parser.details(parser.document(html, page), Movie(page, "Фільм"))
        assertTrue(details.torrents.isEmpty())
        assertTrue(details.torrentNotice.orEmpty().contains("поки не підтримує"))
        assertNull(parser.torrentEndpoint(parser.document(html, page), page))
    }

    @Test fun `torrent endpoint is accepted only when explicitly supplied by same-origin markup`() {
        val good = parser.document("""<div class="torrents" data-torrents-url="/source/release/101"></div>""", page)
        assertEquals("${base}source/release/101", parser.torrentEndpoint(good, page))
        val bad = parser.document("""<div class="torrents" data-torrents-url="https://other.example/token"></div>""", page)
        assertNull(parser.torrentEndpoint(bad, page))
    }

    @Test fun `general download endpoint outside a torrent section is not reported as a torrent`() {
        val document = parser.document("""<a href="/engine/download.php?id=99">Інший файл</a><div class="torrents"><a download="movie.torrent" href="/engine/download.php?id=101">Торрент</a></div>""", page)
        assertEquals(listOf("${base}engine/download.php?id=101"), parser.torrents(document, page).map { it.downloadUrl })
    }

    @Test fun `unsafe torrent and poster URLs are not exposed`() {
        val document = parser.document("""<h1>Фільм</h1><div class="film-poster"><img src="https://127.0.0.1/private"></div><a href="http://example.com/test.torrent">Файл</a><a href="https://router.local/test.torrent">Файл</a>""", page)
        val details = parser.details(document, Movie(page, "Фільм"))
        assertNull(details.movie.posterUrl)
        assertTrue(details.torrents.isEmpty())
    }

    @Test(expected = SourceFormatException::class)
    fun `anti-bot response is not mistaken for an empty catalog`() {
        parser.document("<html><head><title>Just a moment...</title></head><body id='challenge-running'></body></html>", base)
    }

    @Test fun `explicit no-results message differs from unrecognized HTML`() {
        assertTrue(parser.hasExplicitEmptyResult(parser.document("<div id='dle-content'><div class='berrors'>На жаль, пошук не дав жодного результату</div></div>", base)))
        assertFalse(parser.hasExplicitEmptyResult(parser.document("<html><p>Новий дизайн</p></html>", base)))
    }

    @Test fun `base URL validation rejects credentials private hosts and paths`() {
        assertEquals(base, SourceUrl.normalizeBaseUrl(" https://uakino.best "))
        for (url in listOf("http://uakino.best", "https://user:pass@uakino.best", "https://uakino.best:8443/", "https://uakino.best/filmy/", "https://127.0.0.1", "https://192.168.1.1", "https://localhost", "https://internal.lan", "https://[::1]/", "https://uakino.best/?token=x")) {
            assertThrows("Accepted unsafe URL: $url", IllegalArgumentException::class.java) { SourceUrl.normalizeBaseUrl(url) }
        }
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/uakino/$name")).readText()
}
