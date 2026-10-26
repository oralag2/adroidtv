package ua.kinotv.app.data.model

enum class MediaKind { MOVIE, SERIES }

data class Movie(
    val pageUrl: String,
    val title: String,
    val posterUrl: String? = null,
    val year: String? = null,
)

data class TorrentRelease(
    val id: String,
    val title: String,
    val downloadUrl: String,
    val quality: String? = null,
    val size: String? = null,
    val videoCodec: String? = null,
    val audio: String? = null,
    val seeders: String? = null,
)

data class MovieDetails(
    val movie: Movie,
    val description: String,
    val genres: List<String> = emptyList(),
    val rating: String? = null,
    val torrents: List<TorrentRelease> = emptyList(),
    val onlineUrl: String? = null,
    val torrentNotice: String? = null,
)
