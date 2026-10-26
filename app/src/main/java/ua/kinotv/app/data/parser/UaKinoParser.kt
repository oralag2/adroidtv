package ua.kinotv.app.data.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import ua.kinotv.app.data.model.Movie
import ua.kinotv.app.data.model.MovieDetails
import ua.kinotv.app.data.model.TorrentRelease
import ua.kinotv.app.data.network.SourceUrl
import java.io.IOException
import java.util.Locale

class SourceFormatException(message: String) : IOException(message)

data class SourceTorrentRequest(val url: String, val fields: Map<String, String>)
data class SourcePlaylistRequest(val url: String, val fields: Map<String, String>)

/**
 * Parses source HTML without executing scripts or inventing releases.
 * UA Kino's markup is not a versioned API; failures must stay visible to the user.
 */
class UaKinoParser {
    fun document(html: String, pageUrl: String): Document {
        val document = Jsoup.parse(html, pageUrl)
        val title = document.title().lowercase(Locale.ROOT)
        if (document.selectFirst("#challenge-running, #challenge-form, #cf-challenge-running") != null ||
            title.contains("just a moment") || title.contains("attention required") || title.contains("checking your browser")
        ) {
            throw SourceFormatException("Сайт вимагає перевірку браузера. Автоматичний доступ зараз недоступний.")
        }
        return document
    }

    fun catalog(document: Document, pageUrl: String): List<Movie> {
        val cards = document.select(".movie-item, .owl-item, .search-result-link")
        return cards.mapNotNull { card ->
            val link = when {
                card.`is`("a.search-result-link") -> card
                else -> card.selectFirst("a.movie-title[href], a.full-movie[href], a.search-result-link[href]")
            } ?: return@mapNotNull null
            val page = SourceUrl.publicHttps(link.attr("href"), pageUrl, sameOrigin = true)
                ?: return@mapNotNull null
            if (!ARTICLE_PATH.containsMatchIn(page) || page.contains("/news/") || page.contains("/franchise/")) return@mapNotNull null
            val title = card.selectFirst(".movie-title, .full-movie-title, .search-result-title")?.text()
                ?.takeIf(String::isNotBlank) ?: link.attr("title").takeIf(String::isNotBlank)
                ?: link.text().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val image = card.selectFirst(".movie-img img, .movie-poster img, .search-result-poster img, img")
            val poster = image?.let { imageUrl(it, pageUrl) }
            val yearText = card.selectFirst(".movie-year, .search-result-year, [itemprop=dateCreated]")?.text().orEmpty()
            Movie(page, title.trim(), poster, YEAR.find(yearText)?.value)
        }.distinctBy(Movie::pageUrl)
    }

    fun hasExplicitEmptyResult(document: Document): Boolean {
        val message = document.select(".berrors, .search-result-empty, .search-results-empty, #dle-content, .no-results").text().lowercase(Locale.ROOT)
        return listOf("нічого не знайдено", "не знайдено жодного", "за вашим запитом нічого", "пошук не дав", "немає матеріалів", "на жаль, пошук", "поиск не дал", "ничего не найдено").any(message::contains)
    }

    fun details(document: Document, fallback: Movie): MovieDetails {
        val title = document.selectFirst("h1 .solototle, h1[itemprop=name], .film-title h1, h1")?.text()?.trim()
            ?.takeIf(String::isNotBlank)
            ?: throw SourceFormatException("Не вдалося прочитати сторінку фільму. Можливо, структура сайту змінилася.")
        val poster = document.selectFirst(".film-poster img, .full-poster img")?.let { imageUrl(it, fallback.pageUrl) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.let { SourceUrl.publicHttps(it, fallback.pageUrl) }
            ?: fallback.posterUrl
        val description = document.selectFirst("[itemprop=description], .film-desc, .full-description, .full-text")
            ?.text()?.trim().orEmpty()
        val metadata = document.select(".fi-item-s, .fi-item")
        val year = metadata.firstOrNull { it.select(".fi-label").text().contains("Рік", true) }
            ?.let { YEAR.find(it.select(".fi-desc, .deck-value").text())?.value } ?: fallback.year
        val genreRow = metadata.firstOrNull { it.select(".fi-label").text().contains("Жанр", true) }
        val genres = genreRow?.select(".fi-desc a, .deck-value a")?.map(Element::text)
            ?.takeIf(List<String>::isNotEmpty)
            ?: genreRow?.select(".fi-desc, .deck-value")?.text()?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val rating = document.selectFirst("[itemprop=ratingValue]")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: metadata.firstOrNull {
                it.select(".fi-label").text().contains("IMDb", true) || it.selectFirst(".fi-label img[alt*=IMDb], .fi-label img[src*=imdb]") != null
            }?.select(".fi-desc")?.text()?.trim()?.takeIf(String::isNotBlank)
        val releases = torrents(document, fallback.pageUrl)
        val notice = if (releases.isEmpty()) {
            "Не знайдено підтримуваних торрент-посилань. Список може бути порожнім або " +
                "завантажуватися способом, який застосунок поки не підтримує."
        } else null
        return MovieDetails(
            movie = fallback.copy(title = title, posterUrl = poster, year = year),
            description = description,
            genres = genres,
            rating = rating,
            torrents = releases,
            onlineUrl = onlineUrl(document, fallback.pageUrl),
            torrentNotice = notice,
        )
    }

    fun torrents(document: Document, pageUrl: String): List<TorrentRelease> =
        document.select("a[href]").mapNotNull { link ->
            val href = link.attr("href")
            val isTorrent = TORRENT_EXTENSION.containsMatchIn(href) ||
                SITE_TORRENT_DOWNLOAD.containsMatchIn(href) ||
                link.attr("type").equals("application/x-bittorrent", true) ||
                link.attr("download").endsWith(".torrent", true) ||
                (href.contains("/engine/download.php", true) && link.parents().any { isTorrentContainer(it) })
            if (!isTorrent) return@mapNotNull null
            val url = SourceUrl.publicHttps(href, pageUrl) ?: return@mapNotNull null
            val row = link.parents().firstOrNull { it.`is`("tr, .torrent-item, .torrent-row, .torrent-release, .release-item, .tor-item") } ?: link
            val text = row.text()
            val tableMetadata = row.select("th.torrent-sp, td.torrent-sp")
            val releaseTitle = row.selectFirst(".torrent-title, .release-title, .tor-title")?.text()?.takeIf(String::isNotBlank)
                ?: link.attr("data-title").takeIf(String::isNotBlank)
                ?: link.attr("title").takeIf(String::isNotBlank)
                ?: text.takeIf { it.isNotBlank() && it.length <= 500 }
                ?: "Торрент-файл"
            TorrentRelease(
                id = url,
                title = releaseTitle,
                downloadUrl = url,
                quality = row.attr("data-quality").takeIf(String::isNotBlank) ?: QUALITY.find(text)?.value,
                size = row.selectFirst(".torrent-size, .release-size, .tor-size")?.text()?.takeIf(String::isNotBlank)
                    ?: tableMetadata.getOrNull(0)?.text()?.takeIf(String::isNotBlank) ?: SIZE.find(text)?.value,
                videoCodec = CODEC.find(text)?.value,
                audio = row.selectFirst(".torrent-audio, .release-audio, .tor-audio")?.text()?.takeIf(String::isNotBlank)
                    ?: AUDIO.find(releaseTitle)?.value,
                seeders = row.selectFirst(".seeders, .torrent-seeds, .tor-seeds")?.text()?.takeIf(String::isNotBlank)
                    ?: tableMetadata.getOrNull(1)?.text()?.takeIf(String::isNotBlank),
            )
        }.distinctBy(TorrentRelease::downloadUrl)

    /** UA Kino declares a separate download page on the film, rather than listing files there. */
    fun torrentPage(document: Document, pageUrl: String): String? =
        document.select("a.torentdownload[href], a.torrentdownload[href]").firstNotNullOfOrNull { link ->
            SourceUrl.publicHttps(link.attr("href"), pageUrl, sameOrigin = true)
        }

    /**
     * Read the literal POST fields emitted by UA Kino's download page. This does not
     * execute JavaScript, infer endpoints, or send the site's unrelated analytics calls.
     */
    fun torrentRequest(document: Document, pageUrl: String): SourceTorrentRequest? {
        for (script in document.select("script:not([src])")) {
            val match = TORRENT_POST.find(script.data()) ?: continue
            val endpoint = SourceUrl.publicHttps("/" + match.groupValues[2], pageUrl, sameOrigin = true) ?: continue
            val fields = mutableMapOf<String, String>()
            for (field in LITERAL_FIELD.findAll(match.groupValues[3])) {
                val key = field.groupValues[1]
                if (key !in TORRENT_FIELDS) continue
                val decoded = decodeJavaScriptString(field.groupValues[3]) ?: return null
                if (decoded.length > 1000 || fields.put(key, decoded) != null) return null
            }
            if (fields.keys != TORRENT_FIELDS || !fields.getValue("id").matches(Regex("[0-9]{1,12}"))) continue
            return SourceTorrentRequest(endpoint, fields)
        }
        return null
    }

    fun hasExplicitEmptyTorrents(document: Document): Boolean {
        val text = document.text().lowercase(Locale.ROOT)
        return listOf("роздач не виявлено", "роздач не знайдено", "торрентів не знайдено", "торентів не знайдено").any(text::contains)
    }

    /** The current source player.js requests this endpoint for its declared playlist widget. */
    fun playlistRequest(document: Document, pageUrl: String): SourcePlaylistRequest? {
        val widget = document.selectFirst(".playlists-ajax[data-news_id][data-xfname]") ?: return null
        val id = widget.attr("data-news_id")
        val field = widget.attr("data-xfname")
        if (!id.matches(Regex("[0-9]{1,12}")) || !field.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,79}"))) return null
        val endpoint = SourceUrl.publicHttps("/engine/ajax/playlists.php", pageUrl, sameOrigin = true) ?: return null
        val time = document.select("script:not([src])").firstNotNullOfOrNull { script ->
            EDIT_TIME.find(script.data())?.groupValues?.get(1)
        }.orEmpty()
        return SourcePlaylistRequest(endpoint, mapOf("news_id" to id, "xfield" to field, "time" to time))
    }

    private fun decodeJavaScriptString(value: String): String? {
        val decoded = StringBuilder()
        var index = 0
        while (index < value.length) {
            val current = value[index++]
            if (current != '\\') { decoded.append(current); continue }
            if (index >= value.length) return null
            when (val escape = value[index++]) {
                '\\', '\'', '"', '/' -> decoded.append(escape)
                'n' -> decoded.append('\n')
                'r' -> decoded.append('\r')
                't' -> decoded.append('\t')
                'u', 'x' -> {
                    val length = if (escape == 'u') 4 else 2
                    if (index + length > value.length) return null
                    val code = value.substring(index, index + length).toIntOrNull(16) ?: return null
                    decoded.append(code.toChar())
                    index += length
                }
                else -> return null
            }
        }
        return decoded.toString()
    }

    /** Follow only an endpoint declared by source markup, never a guessed JavaScript API. */
    fun torrentEndpoint(document: Document, pageUrl: String): String? {
        val containers = document.select("[data-torrents-url], [data-torrent-url], .torrent[data-url], .torrents[data-url], #torrent[data-url], #torrents[data-url]")
        return containers.firstNotNullOfOrNull { container ->
            listOf("data-torrents-url", "data-torrent-url", "data-url").firstNotNullOfOrNull { attribute ->
                container.attr(attribute).takeIf(String::isNotBlank)?.let { SourceUrl.publicHttps(it, pageUrl, sameOrigin = true) }
            }
        }
    }

    private fun imageUrl(image: Element, pageUrl: String): String? =
        listOf("data-src", "data-original", "src").firstNotNullOfOrNull { attribute ->
            image.attr(attribute).takeIf(String::isNotBlank)?.let { SourceUrl.publicHttps(it, pageUrl) }
        }

    private fun isTorrentContainer(element: Element): Boolean =
        (element.id() + " " + element.className()).contains("torrent", true) || element.hasAttr("data-torrent-url")

    fun onlineUrl(document: Document, pageUrl: String): String? {
        val candidates = document.select("video[src], video source[src], [itemprop=embedUrl], [itemprop=contentUrl], [itemprop=video], iframe#pre, #player iframe, .player iframe, .players-section iframe, .full-text iframe, .playlists-videos [data-file]")
        return candidates.firstNotNullOfOrNull { element ->
            if (element.parents().any {
                    val marker = it.id() + " " + it.className()
                    marker.contains("trailer", true) || marker.contains("overroll", true)
                }) return@firstNotNullOfOrNull null
            listOf("content", "value", "src", "data-src", "data-file", "href").firstNotNullOfOrNull { attribute ->
                SourceUrl.publicHttps(element.attr(attribute).takeIf(String::isNotBlank), pageUrl)
                    ?.takeUnless { it.contains("youtube.com", true) || it.contains("youtu.be", true) }
            }
        }
    }

    companion object {
        private val ARTICLE_PATH = Regex("/\\d+-[^/?#]+\\.html(?:$|[?#])", RegexOption.IGNORE_CASE)
        private val YEAR = Regex("\\b(?:19|20)\\d{2}\\b")
        private val TORRENT_EXTENSION = Regex("\\.torrent(?:$|[?#])", RegexOption.IGNORE_CASE)
        private val SITE_TORRENT_DOWNLOAD = Regex("(?:^|/)download/[0-9]+/torrent/(?:$|[?#])", RegexOption.IGNORE_CASE)
        private val TORRENT_POST = Regex("""\${'$'}\s*\.\s*post\s*\(\s*dle_root\s*\+\s*(['"])(engine/ajax/toloka\.php)\1\s*,\s*\{([\s\S]*?)}""")
        private val LITERAL_FIELD = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(['"])((?:\\.|(?!\2)[^\\])*)\2""")
        private val TORRENT_FIELDS = setOf("id", "cat", "title", "theme")
        private val EDIT_TIME = Regex("""\bvar\s+dle_edittime\s*=\s*['"]([0-9]{0,20})['"]""")
        private val QUALITY = Regex("\\b(?:2160|1080|720|480)p\\b|\\b4K\\b", RegexOption.IGNORE_CASE)
        private val SIZE = Regex("\\b\\d+(?:[.,]\\d+)?\\s*(?:GiB|MiB|GB|MB|ГБ|МБ)\\b", RegexOption.IGNORE_CASE)
        private val CODEC = Regex("\\b(?:x26[45]|h[.]?26[45]|HEVC|AV1)(?:[ /+-]+HDR(?:10[+]?)?)?\\b", RegexOption.IGNORE_CASE)
        private val AUDIO = Regex("\\b(?:[0-9]+x)?(?:Ukr|Українська)(?:/(?:Eng|[0-9]+xUkr))*\\b", RegexOption.IGNORE_CASE)
    }
}
