package ua.kinotv.app.ui

import ua.kinotv.app.data.model.Movie
import ua.kinotv.app.data.model.MovieDetails
import ua.kinotv.app.download.DownloadRecord

enum class Section(val title: String) {
    HOME("Головна"), MOVIES("Фільми"), SERIES("Серіали"),
    SEARCH("Пошук"), DOWNLOADS("Завантаження"), SETTINGS("Налаштування")
}

data class KinoUiState(
    val section: Section = Section.HOME,
    val baseUrl: String = "https://uakino.best/",
    val searchQuery: String = "",
    val movies: List<Movie> = emptyList(),
    val selectedMovie: Movie? = null,
    val details: MovieDetails? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val downloads: List<DownloadRecord> = emptyList(),
    val activeDownloads: Map<String, Int> = emptyMap(),
)
