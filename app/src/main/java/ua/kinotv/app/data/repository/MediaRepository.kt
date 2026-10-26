package ua.kinotv.app.data.repository

import ua.kinotv.app.data.model.MediaKind
import ua.kinotv.app.data.model.Movie
import ua.kinotv.app.data.model.MovieDetails

interface MediaRepository {
    suspend fun catalog(kind: MediaKind, page: Int = 1): List<Movie>
    suspend fun search(query: String): List<Movie>
    suspend fun details(movie: Movie): MovieDetails
}
