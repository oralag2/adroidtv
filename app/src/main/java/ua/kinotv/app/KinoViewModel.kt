package ua.kinotv.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import ua.kinotv.app.data.model.MediaKind
import ua.kinotv.app.data.model.Movie
import ua.kinotv.app.data.model.TorrentRelease
import ua.kinotv.app.download.DownloadRecord
import ua.kinotv.app.ui.KinoUiState
import ua.kinotv.app.ui.Section

class KinoViewModel(private val app: KinoApplication) : ViewModel() {
    private val mutableState = MutableStateFlow(KinoUiState(baseUrl = app.settings.baseUrl))
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var page = 1
    private val downloadJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch { refreshDownloads() }
        loadCatalog()
    }

    fun selectSection(section: Section) {
        request?.cancel()
        mutableState.update {
            it.copy(section = section, selectedMovie = null, details = null, error = null,
                isLoading = false, isLoadingMore = false)
        }
        when (section) {
            Section.HOME, Section.MOVIES, Section.SERIES -> loadCatalog()
            Section.SEARCH -> {
                mutableState.update { it.copy(movies = emptyList(), hasMore = false) }
                if (state.value.searchQuery.isNotBlank()) search(state.value.searchQuery)
            }
            Section.DOWNLOADS -> viewModelScope.launch { refreshDownloads() }
            Section.SETTINGS -> Unit
        }
    }

    private fun loadCatalog(more: Boolean = false) {
        if (more && (state.value.isLoading || state.value.isLoadingMore || !state.value.hasMore)) return
        request?.cancel()
        val section = state.value.section
        val nextPage = if (more) page + 1 else 1
        mutableState.update {
            it.copy(isLoading = !more, isLoadingMore = more, error = null,
                movies = if (more) it.movies else emptyList())
        }
        request = viewModelScope.launch {
            try {
                val kind = if (section == Section.SERIES) MediaKind.SERIES else MediaKind.MOVIE
                val result = app.repository.catalog(kind, nextPage)
                mutableState.update { old ->
                    val movies = if (more) (old.movies + result).distinctBy { it.pageUrl } else result
                    old.copy(movies = movies, hasMore = result.isNotEmpty() && (!more || movies.size > old.movies.size))
                }
                page = nextPage
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportRequestError(e) }
            finally { if (isActive) mutableState.update { it.copy(isLoading = false, isLoadingMore = false) } }
        }
    }

    fun loadMore() = loadCatalog(more = true)

    fun search(query: String) {
        request?.cancel()
        mutableState.update { it.copy(searchQuery = query.trim(), isLoading = query.isNotBlank(),
            movies = emptyList(), error = null, hasMore = false) }
        if (query.isBlank()) return
        request = viewModelScope.launch {
            try {
                val result = app.repository.search(query.trim())
                mutableState.update { it.copy(movies = result) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportRequestError(e) }
            finally { if (isActive) mutableState.update { it.copy(isLoading = false) } }
        }
    }

    fun selectMovie(movie: Movie) {
        request?.cancel()
        mutableState.update { it.copy(selectedMovie = movie, details = null, isLoading = true,
            isLoadingMore = false, error = null) }
        request = viewModelScope.launch {
            try {
                val details = app.repository.details(movie)
                mutableState.update { it.copy(details = details) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportRequestError(e) }
            finally { if (isActive) mutableState.update { it.copy(isLoading = false) } }
        }
    }

    fun back() {
        request?.cancel()
        mutableState.update { it.copy(selectedMovie = null, details = null,
            isLoading = false, isLoadingMore = false, error = null) }
    }

    fun retry() {
        val current = state.value
        when {
            current.selectedMovie != null -> selectMovie(current.selectedMovie)
            current.section == Section.SEARCH -> search(current.searchQuery)
            else -> loadCatalog()
        }
    }

    fun saveBaseUrl(value: String) {
        request?.cancel()
        request = viewModelScope.launch {
            try {
                val normalized = app.settings.saveBaseUrl(value)
                mutableState.update { it.copy(baseUrl = normalized, movies = emptyList(),
                    selectedMovie = null, details = null, message = "Адресу збережено.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showMessage(e.message ?: "Не вдалося зберегти адресу.") }
        }
    }

    fun download(release: TorrentRelease) {
        if (downloadJobs[release.id]?.isActive == true) return
        val referer = state.value.selectedMovie?.pageUrl ?: return
        mutableState.update { it.copy(activeDownloads = it.activeDownloads + (release.id to -1)) }
        downloadJobs[release.id] = viewModelScope.launch {
            try {
                app.downloader.download(release, referer) { progress ->
                    mutableState.update { it.copy(activeDownloads = it.activeDownloads + (release.id to progress)) }
                }
                refreshDownloads()
                showMessage("Торрент-файл збережено. Відкрийте його в розділі «Завантаження».")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showMessage(e.message ?: "Не вдалося завантажити торрент-файл.") }
            finally {
                mutableState.update { it.copy(activeDownloads = it.activeDownloads - release.id) }
                downloadJobs.remove(release.id)
            }
        }
    }

    fun openDownload(record: DownloadRecord) {
        try {
            val file = app.downloader.fileFor(record)
                ?: error("Торрент-файл більше не існує. Завантажте його повторно.")
            app.torrentLauncher.open(record, file)
        } catch (e: Exception) { showMessage(e.message ?: "Не вдалося відкрити торрент-файл.") }
    }

    private suspend fun refreshDownloads() {
        try {
            val records = withContext(Dispatchers.IO) { app.downloader.records() }
            mutableState.update { it.copy(downloads = records) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { showMessage(e.message ?: "Не вдалося прочитати історію завантажень.") }
    }

    private fun reportRequestError(e: Exception) {
        mutableState.update { it.copy(error = e.message ?: "Не вдалося отримати дані. Перевірте мережу та адресу сайту.") }
    }

    fun showMessage(message: String) { mutableState.update { it.copy(message = message) } }
    fun dismissMessage() { mutableState.update { it.copy(message = null) } }
}
