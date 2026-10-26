package ua.kinotv.app.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Session cookies stay in memory and are sent only where OkHttp's domain/path rules allow. */
class SessionCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        this.cookies.removeAll { existing ->
            existing.expiresAt <= now || cookies.any {
                it.name == existing.name && it.domain == existing.domain && it.path == existing.path
            }
        }
        this.cookies.addAll(cookies.filter { it.expiresAt > now })
        while (this.cookies.size > 128) this.cookies.removeAt(0)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }
}
