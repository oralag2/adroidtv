package ua.kinotv.app.download

data class DownloadRecord(
    val id: String,
    val title: String,
    val fileName: String,
    val downloadedAt: Long,
    val bytes: Long,
)
