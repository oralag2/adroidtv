package ua.kinotv.app.download

import java.io.IOException
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import ua.kinotv.app.data.repository.UaKinoRepository

/** HTTP policy for a descriptor request. URL/origin validation happens before this is called. */
internal object TorrentHttp {
    fun request(url: HttpUrl, referer: HttpUrl): Request = Request.Builder().url(url)
        .header("User-Agent", UaKinoRepository.BROWSER_USER_AGENT)
        .header("Accept", "application/x-bittorrent, application/octet-stream;q=0.9, */*;q=0.1")
        .header("Accept-Language", "uk-UA,uk;q=0.9,en;q=0.5")
        .header("Referer", referer.newBuilder().fragment(null).build().toString())
        .build()

    fun validateResponse(response: Response) {
        val body = response.body ?: throw IOException("Сервер повернув порожню відповідь.")
        val contentType = body.contentType()?.toString().orEmpty().lowercase(Locale.ROOT)
        if (response.code == 403 || response.code == 503 || "text/html" in contentType) {
            // Inspect only a small prefix without consuming the descriptor stream.
            val prefix = response.peekBody(8192).string().lowercase(Locale.ROOT)
            if ("just a moment" in prefix || "cf-chl-" in prefix || "challenge-platform" in prefix ||
                "checking your browser" in prefix
            ) {
                throw IOException("Сайт вимагає перевірку браузера. Автоматичне завантаження цього торрент-файлу поки недоступне.")
            }
        }
        if (!response.isSuccessful) {
            val message = when (response.code) {
                401 -> "Для завантаження цього торренту сайт вимагає авторизацію (HTTP 401)."
                403 -> "Сайт відхилив доступ до торрент-файлу (HTTP 403)."
                429 -> "Сайт обмежив кількість запитів. Зачекайте й повторіть завантаження."
                else -> "Сервер повернув HTTP ${response.code} під час завантаження торренту."
            }
            throw IOException(message)
        }
        if ("text/html" in contentType || "application/json" in contentType) {
            throw IOException("Замість торрент-файлу сервер повернув сторінку або повідомлення про помилку.")
        }
        if (body.contentLength() > TorrentValidator.MAX_BYTES) {
            throw IOException("Торрент-файл перевищує обмеження 5 МіБ.")
        }
    }
}
