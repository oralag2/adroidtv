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

    @Test fun `live source structure exposes the download page and player without the trailer`() {
        val sourcePage = "${base}filmy/genre-action/11-matrycia.html"
        val document = parser.document(fixture("matrix-source-details.html"), sourcePage)
        assertEquals("${base}filmy/genre-action/11-matrycia/download.html", parser.torrentPage(document, sourcePage))
        assertEquals("https://ashdi.vip/vod/12345", parser.details(document, Movie(sourcePage, "Матриця")).onlineUrl)
        assertTrue(parser.torrents(document, sourcePage).isEmpty())
    }

    @Test fun `observed download page supplies literal torrent POST fields`() {
        val downloadPage = "${base}filmy/genre-action/11-matrycia/download.html"
        val request = requireNotNull(parser.torrentRequest(parser.document(fixture("matrix-source-download.html"), downloadPage), downloadPage))
        assertEquals("${base}engine/ajax/toloka.php", request.url)
        assertEquals(mapOf("id" to "11", "cat" to "filmy/genre-action/", "title" to "Матриця", "theme" to "/templates/uakino"), request.fields)
    }

    @Test fun `torrent POST parser handles escaped quotes without running script expressions`() {
        val html = """<script>$.post(dle_root + 'engine/ajax/toloka.php', {id:'11', cat:'filmy/', title:'Він сказав \'так\'', theme:'/templates/uakino'}, done);</script>"""
        assertEquals("Він сказав 'так'", parser.torrentRequest(parser.document(html, page), page)?.fields?.get("title"))
        val dynamic = html.replace("id:'11'", "id:readMovieId()")
        assertNull(parser.torrentRequest(parser.document(dynamic, page), page))
    }

    @Test fun `live torrent table provides descriptor routes and release metadata`() {
        val releases = parser.torrents(parser.document(fixture("matrix-source-releases.html"), page), page)
        assertEquals(5, releases.size)
        assertEquals("${base}download/711542/torrent/", releases.first().downloadUrl)
        assertEquals("1080p", releases.first().quality)
        assertEquals("70.99 GB", releases.first().size)
        assertEquals("1", releases.first().seeders)
        assertEquals("3xUkr/Eng", releases.first().audio)
        assertEquals("2160p", releases.last().quality)
        assertEquals("39", releases.last().seeders)
    }

    @Test fun `explicit missing torrents is recognized separately from a changed response`() {
        assertTrue(parser.hasExplicitEmptyTorrents(parser.document("<center>Oppps роздач не виявлено зверніться до адміна</center>", page)))
        assertFalse(parser.hasExplicitEmptyTorrents(parser.document("<div>Новий формат відповіді</div>", page)))
    }

    @Test fun `live playlist widget declares its source request without accepting trailer as movie`() {
        val document = parser.document(fixture("matrix-playlist-details.html"), page)
        assertNull(parser.details(document, Movie(page, "Матриця")).onlineUrl)
        val request = requireNotNull(parser.playlistRequest(document, page))
        assertEquals("${base}engine/ajax/playlists.php", request.url)
        assertEquals(mapOf("news_id" to "11", "xfield" to "playlist", "time" to "1778492761"), request.fields)
        assertNull(parser.playlistRequest(parser.document("<div class='playlists-ajax' data-news_id='../11' data-xfname='playlist'></div>", page), page))
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/uakino/$name")).readText()
}
