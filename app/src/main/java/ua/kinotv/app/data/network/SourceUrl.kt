package ua.kinotv.app.data.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** URL rules shared by settings and HTML extraction. No credentials or local network URLs. */
object SourceUrl {
    fun normalizeBaseUrl(value: String): String {
        val url = value.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Вкажіть повну HTTPS-адресу сайту, наприклад https://uakino.best/")
        require(isPublicHttps(url)) { "Потрібен публічний HTTPS-домен без логіна, пароля чи нестандартного порту." }
        require(url.encodedPath == "/" && url.query == null && url.fragment == null) {
            "Вкажіть лише базову адресу сайту, без шляху, параметрів чи фрагмента."
        }
        return url.toString()
    }

    fun publicHttps(raw: String?, base: String, sameOrigin: Boolean = false): String? {
        if (raw.isNullOrBlank() || raw.length > 8192) return null
        val baseUrl = base.toHttpUrlOrNull() ?: return null
        val url = baseUrl.resolve(raw.trim()) ?: return null
        if (!isPublicHttps(url) || (sameOrigin && !sameOrigin(baseUrl, url))) return null
        return url.newBuilder().fragment(null).build().toString()
    }

    fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
        first.scheme == second.scheme && first.host == second.host && first.port == second.port

    private fun isPublicHttps(url: HttpUrl): Boolean {
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        val host = url.host.lowercase()
        // Reject literal IP addresses, single-label hosts and common private/local DNS suffixes.
        if (':' in host || host.all { it.isDigit() || it == '.' } || '.' !in host) return false
        if (host == "localhost" || listOf(".localhost", ".local", ".internal", ".lan", ".home", ".onion").any(host::endsWith)) return false
        return true
    }
}
