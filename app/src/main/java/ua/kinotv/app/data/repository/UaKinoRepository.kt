package ua.kinotv.app.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.json.JSONException
import org.json.JSONObject
import ua.kinotv.app.data.model.MediaKind
import ua.kinotv.app.data.model.Movie
import ua.kinotv.app.data.model.MovieDetails
import ua.kinotv.app.data.network.SourceUrl
import ua.kinotv.app.data.parser.SourceFormatException
import ua.kinotv.app.data.parser.UaKinoParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class UaKinoRepository(client: OkHttpClient, private val baseUrl: () -> String) : MediaRepository {
    // Inspect every redirect before the next request, including client instances injected by callers.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val parser = UaKinoParser()

    override suspend fun catalog(kind: MediaKind, page: Int): List<Movie> {
        require(page > 0) { "Номер сторінки має бути додатним." }
        val origin = sourceOrigin()
        val category = if (kind == MediaKind.SERIES) "seriesss" else "filmy"
        val path = if (page == 1) "$category/" else "$category/page/$page/"
        val url = origin.resolve(path) ?: throw IOException("Некоректна адреса каталогу.")
        val loaded = html(request(url), origin)
        val movies = parser.catalog(loaded.document, loaded.url.toString())
        if (movies.isEmpty() && !parser.hasExplicitEmptyResult(loaded.document)) {
            throw SourceFormatException("Не вдалося знайти картки фільмів. Сайт міг змінити розмітку або обмежити доступ.")
        }
        return movies
    }

    override suspend fun search(query: String): List<Movie> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()
        require(normalized.length <= 200) { "Пошуковий запит задовгий." }
        val origin = sourceOrigin()
        val url = origin.resolve("ua/") ?: throw IOException("Некоректна адреса пошуку.")
        val body = FormBody.Builder(Charsets.UTF_8)
            .add("do", "search")
            .add("subaction", "search")
            .add("story", normalized)
            .build()
        val loaded = html(request(url).newBuilder().post(body).build(), origin)
        val results = parser.catalog(loaded.document, loaded.url.toString())
        if (results.isEmpty() && !parser.hasExplicitEmptyResult(loaded.document)) {
            throw SourceFormatException("Сайт не повернув впізнавану відповідь пошуку. Спробуйте пізніше або перевірте домен.")
        }
        return results
    }

    override suspend fun details(movie: Movie): MovieDetails {
        val origin = sourceOrigin()
        val url = SourceUrl.publicHttps(movie.pageUrl, origin.toString(), sameOrigin = true)?.toHttpUrl()
            ?: throw IOException("Адреса фільму не належить поточному домену. Оновіть каталог.")
        val loaded = html(request(url), origin)
        var details = parser.details(loaded.document, movie.copy(pageUrl = loaded.url.toString()))
        if (details.onlineUrl == null) {
            val playlist = parser.playlistRequest(loaded.document, loaded.url.toString())
            if (playlist != null) {
                try {
                    val playlistUrl = playlist.url.toHttpUrl().newBuilder()
                    playlist.fields.forEach { (name, value) -> playlistUrl.addQueryParameter(name, value) }
                    val player = html(request(playlistUrl.build()).newBuilder()
                        .header("Referer", loaded.url.toString())
                        .header("X-Requested-With", "XMLHttpRequest").build(), origin, unwrapPlaylist = true)
                    details = details.copy(onlineUrl = parser.onlineUrl(player.document, player.url.toString()))
                } catch (_: IOException) {
                    // Film metadata and torrents remain usable when this independent player endpoint fails.
                    currentCoroutineContext().ensureActive()
                }
            }
        }
        if (details.torrents.isNotEmpty()) return details.copy(torrents = details.torrents.map { it.copy(referer = loaded.url.toString()) })
        return try {
            val torrentPageUrl = parser.torrentPage(loaded.document, loaded.url.toString())
            val torrentPage = if (torrentPageUrl != null) {
                html(request(torrentPageUrl.toHttpUrl()).newBuilder()
                    .header("Referer", loaded.url.toString()).build(), origin)
            } else loaded
            val directReleases = parser.torrents(torrentPage.document, torrentPage.url.toString())
            if (directReleases.isNotEmpty()) return details.copy(torrents = directReleases.map { it.copy(referer = torrentPage.url.toString()) }, torrentNotice = null)
            val form = parser.torrentRequest(torrentPage.document, torrentPage.url.toString())
            val endpoint = form?.url ?: parser.torrentEndpoint(torrentPage.document, torrentPage.url.toString())
                ?: return details.copy(torrentNotice = if (parser.hasExplicitEmptyTorrents(torrentPage.document)) NO_TORRENTS else details.torrentNotice)
            val ajaxBuilder = request(endpoint.toHttpUrl()).newBuilder()
                .header("Referer", torrentPage.url.toString())
                .header("X-Requested-With", "XMLHttpRequest")
            if (form != null) {
                val body = FormBody.Builder(Charsets.UTF_8)
                form.fields.forEach { (name, value) -> body.add(name, value) }
                ajaxBuilder.post(body.build())
            }
            val ajaxRequest = ajaxBuilder.build()
            val torrentHtml = html(ajaxRequest, origin)
            val releases = parser.torrents(torrentHtml.document, torrentHtml.url.toString())
                .map { it.copy(referer = torrentPage.url.toString()) }
            details.copy(torrents = releases, torrentNotice = when {
                releases.isNotEmpty() -> null
                parser.hasExplicitEmptyTorrents(torrentHtml.document) -> NO_TORRENTS
                else -> "Не вдалося прочитати список торрент-роздач. Спробуйте повторити запит пізніше."
            })
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            details.copy(torrentNotice = "Не вдалося завантажити роздачі: ${error.message ?: "мережева помилка"}")
        }
    }

    private fun sourceOrigin(): HttpUrl = SourceUrl.normalizeBaseUrl(baseUrl()).toHttpUrl()

    private fun request(url: HttpUrl): Request = Request.Builder().url(url)
        .header("User-Agent", BROWSER_USER_AGENT)
        .header("Accept", "text/html,application/xhtml+xml;q=0.9")
        .header("Accept-Language", "uk-UA,uk;q=0.9,en;q=0.5")
        .build()

    private data class LoadedHtml(val document: Document, val url: HttpUrl)

    private suspend fun html(initialRequest: Request, origin: HttpUrl, unwrapPlaylist: Boolean = false): LoadedHtml = withContext(Dispatchers.IO) {
        var nextRequest = initialRequest
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            currentCoroutineContext().ensureActive()
            if (!SourceUrl.sameOrigin(origin, nextRequest.url)) throw IOException("Заборонено запит до іншого домену.")
            client.newCall(nextRequest).await().use { response ->
                if (response.code in REDIRECT_CODES) {
                    if (redirectCount == MAX_REDIRECTS) throw IOException("Забагато перенаправлень на сайті.")
                    val location = response.header("Location") ?: throw IOException("Сайт повернув перенаправлення без адреси.")
                    val nextUrl = SourceUrl.publicHttps(location, nextRequest.url.toString(), sameOrigin = true)
                        ?.toHttpUrl() ?: throw IOException("Сайт перенаправляє на інший або небезпечний домен. Перевірте адресу в налаштуваннях.")
                    val builder = nextRequest.newBuilder().url(nextUrl)
                    if (response.code == 303 || ((response.code == 301 || response.code == 302) && nextRequest.method == "POST")) {
                        builder.get().removeHeader("Content-Type").removeHeader("Content-Length")
                    }
                    nextRequest = builder.build()
                } else {
                    if (!response.isSuccessful) {
                        val reason = when (response.code) {
                            401, 403 -> "Сайт відхилив доступ (HTTP ${response.code}). Можлива перевірка браузера або обмеження мережі."
                            404 -> "Сторінку не знайдено (HTTP 404). Перевірте домен або оновіть каталог."
                            429 -> "Забагато запитів. Зачекайте й повторіть спробу."
                            else -> "Сайт повернув помилку HTTP ${response.code}."
                        }
                        throw IOException(reason)
                    }
                    val body = response.body ?: throw IOException("Сайт повернув порожню відповідь.")
                    val mime = body.contentType()
                    if (mime != null && mime.type != "text" && !(mime.type == "application" &&
                        (mime.subtype == "xhtml+xml" || (unwrapPlaylist && mime.subtype == "json")))) {
                        throw SourceFormatException("Замість HTML сайт повернув непідтримуваний формат даних.")
                    }
                    if (body.contentLength() > MAX_HTML_BYTES) throw IOException("Сторінка джерела перевищує допустимий розмір.")
                    val bytes = ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_HTML_BYTES) throw IOException("Сторінка джерела перевищує допустимий розмір.")
                            bytes.write(buffer, 0, read)
                        }
                    }
                    val charset = mime?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    var text = bytes.toString(charset.name())
                    if (unwrapPlaylist) {
                        val data = try { JSONObject(text) } catch (_: JSONException) {
                            throw SourceFormatException("Джерело повернуло некоректні дані програвача.")
                        }
                        if (!data.optBoolean("success") || data.optString("response").isBlank()) {
                            throw SourceFormatException("Джерело не надало доступного програвача.")
                        }
                        text = data.optString("response")
                    }
                    return@withContext LoadedHtml(parser.document(text, nextRequest.url.toString()), nextRequest.url)
                }
            }
        }
        throw IOException("Не вдалося отримати відповідь джерела.")
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, abandonedResponse, _ -> abandonedResponse.close() }
            }
        })
    }

    companion object {
        const val BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val NO_TORRENTS = "Джерело не знайшло торрент-роздач для цього фільму."
        private const val MAX_HTML_BYTES = 4 * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
