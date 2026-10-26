package ua.kinotv.app.playback

import okhttp3.HttpUrl.Companion.toHttpUrl
import ua.kinotv.app.data.network.SourceUrl

/** The source can provide either a playable file/playlist or an HTML player. */
data class PlaybackTarget private constructor(
    val url: String,
    val referer: String,
    val title: String,
    val kind: Kind,
) {
    enum class Kind { EMBED, HLS, FILE }

    /** A player page cannot send the TV to advertising sites or custom application schemes. */
    fun permitsNavigation(address: String): Boolean =
        SourceUrl.publicHttps(address, url, sameOrigin = true) != null

    companion object {
        fun create(url: String, referer: String, title: String): PlaybackTarget {
            val target = SourceUrl.publicHttps(url, url)
                ?: throw IllegalArgumentException("Джерело надало непідтримувану адресу програвача.")
            val source = SourceUrl.publicHttps(referer, referer)
                ?: throw IllegalArgumentException("Не вдалося визначити сторінку джерела відео.")
            val path = target.toHttpUrl().encodedPath.lowercase()
            val kind = when {
                path.endsWith(".m3u8") -> Kind.HLS
                listOf(".mp4", ".webm", ".mkv", ".m4v").any(path::endsWith) -> Kind.FILE
                else -> Kind.EMBED
            }
            return PlaybackTarget(target, source, title.ifBlank { "KinoTV" }, kind)
        }
    }
}
