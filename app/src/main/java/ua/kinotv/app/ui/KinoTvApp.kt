package ua.kinotv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import ua.kinotv.app.data.model.Movie
import ua.kinotv.app.data.model.MovieDetails
import ua.kinotv.app.data.model.TorrentRelease
import ua.kinotv.app.download.DownloadRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Background = Color(0xFF0B1018)
private val Panel = Color(0xFF182332)
private val Accent = Color(0xFF82DFC7)
private val Muted = Color(0xFFB2BFCD)

/** TV controls use native focus traversal; no touch input is required. */
@Composable
fun KinoTvApp(
    state: KinoUiState,
    onSection: (Section) -> Unit,
    onSearch: (String) -> Unit,
    onSelectMovie: (Movie) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onDownload: (TorrentRelease) -> Unit,
    onOpenDownload: (DownloadRecord) -> Unit,
    onSaveBaseUrl: (String) -> Unit,
    onDismissMessage: () -> Unit,
    onOpenOnline: () -> Unit,
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Background,
            background = Background,
            surface = Panel,
            onSurface = Color.White,
        ),
    ) {
        // Mobile text inputs retain native Android keyboard support on Google TV.
        androidx.compose.material3.MaterialTheme(
            colorScheme = androidx.compose.material3.darkColorScheme(
                primary = Accent,
                surface = Panel,
                background = Background,
            ),
        ) {
            val navigationFocus = remember { FocusRequester() }
            val gridState = rememberLazyGridState()
            var previousMovie by rememberSaveable { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) { navigationFocus.requestFocus() }
            LaunchedEffect(state.section) {
                previousMovie = null
                gridState.scrollToItem(0)
            }

            Row(
                modifier = Modifier.fillMaxSize().background(Background).padding(28.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Column(
                    modifier = Modifier.width(150.dp).fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("KinoTV", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Accent)
                    Text("Кіно українською", fontSize = 12.sp, color = Muted)
                    Spacer(Modifier.height(16.dp))
                    Section.entries.forEach { section ->
                        TvAction(
                            text = section.title,
                            selected = section == state.section,
                            modifier = Modifier.fillMaxWidth().then(
                                if (section == Section.HOME) Modifier.focusRequester(navigationFocus)
                                else Modifier,
                            ),
                            onClick = { onSection(section) },
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (state.selectedMovie != null) {
                        MovieScreen(state, onBack, onRetry, onDownload, onOpenOnline)
                    } else {
                        Text(state.section.title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        when (state.section) {
                            Section.SETTINGS -> SettingsScreen(state.baseUrl, onSaveBaseUrl)
                            Section.DOWNLOADS -> DownloadsScreen(state.downloads, onOpenDownload)
                            else -> {
                                if (state.section == Section.SEARCH) {
                                    SearchBar(state.searchQuery, onSearch)
                                }
                                if (state.error != null) ErrorPanel(state.error, onRetry)
                                if (state.isLoading) LoadingIndicator()
                                if (!state.isLoading && state.movies.isEmpty() && state.error == null) {
                                    EmptyPanel(
                                        if (state.section == Section.SEARCH && state.searchQuery.isBlank())
                                            "Введіть назву фільму або серіалу."
                                        else "Нічого не знайдено.",
                                    )
                                }
                                MovieGrid(
                                    movies = state.movies,
                                    restoreMovieUrl = previousMovie,
                                    hasMore = state.hasMore && state.section != Section.SEARCH,
                                    loadingMore = state.isLoadingMore,
                                    onSelectMovie = { movie ->
                                        previousMovie = movie.pageUrl
                                        onSelectMovie(movie)
                                    },
                                    onLoadMore = onLoadMore,
                                    gridState = gridState,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            state.message?.let { MessageDialog(it, onDismissMessage) }
        }
    }
}

@Composable
private fun TvAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Color(0xFF294C4A) else Panel,
            contentColor = Color.White,
            focusedContainerColor = Accent,
            focusedContentColor = Background,
        ),
    ) {
        Text(text, fontSize = 14.sp, maxLines = 2)
    }
}

@Composable
private fun SearchBar(initialQuery: String, onSearch: (String) -> Unit) {
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { androidx.compose.material3.Text("Назва фільму або серіалу") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query.trim()) }),
        )
        TvAction("Знайти", { onSearch(query.trim()) }, enabled = query.isNotBlank())
    }
}

@Composable
private fun MovieGrid(
    movies: List<Movie>,
    restoreMovieUrl: String?,
    hasMore: Boolean,
    loadingMore: Boolean,
    onSelectMovie: (Movie) -> Unit,
    onLoadMore: () -> Unit,
    gridState: LazyGridState,
    modifier: Modifier,
) {
    val restoredFocus = remember { FocusRequester() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        state = gridState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(movies, key = { it.pageUrl }) { movie ->
            val restore = movie.pageUrl == restoreMovieUrl
            MovieCard(
                movie,
                { onSelectMovie(movie) },
                Modifier.then(if (restore) Modifier.focusRequester(restoredFocus) else Modifier),
            )
            if (restore) LaunchedEffect(movie.pageUrl) { restoredFocus.requestFocus() }
        }
        if (movies.isNotEmpty() && hasMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                    TvAction(
                        if (loadingMore) "Завантаження…" else "Наступна сторінка",
                        onLoadMore,
                        enabled = !loadingMore,
                    )
                }
            }
        }
    }
}

@Composable
private fun MovieCard(movie: Movie, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }
            .border(if (focused) 3.dp else 0.dp, Accent, RoundedCornerShape(12.dp)),
        contentPadding = PaddingValues(8.dp),
        colors = ButtonDefaults.colors(
            containerColor = Panel,
            contentColor = Color.White,
            focusedContainerColor = Color(0xFF28413F),
            focusedContentColor = Color.White,
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Poster(movie, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            Text(movie.title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            movie.year?.let { Text(it, fontSize = 12.sp, color = Muted) }
        }
    }
}

@Composable
private fun Poster(movie: Movie, modifier: Modifier) {
    Box(modifier.background(Color(0xFF253043)), contentAlignment = Alignment.Center) {
        Text("KINO", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Muted)
        AsyncImage(
            model = movie.posterUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun MovieScreen(
    state: KinoUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDownload: (TorrentRelease) -> Unit,
    onOpenOnline: () -> Unit,
) {
    val movie = state.selectedMovie ?: return
    val displayMovie = state.details?.movie ?: movie
    var showTorrents by rememberSaveable(movie.pageUrl) { mutableStateOf(false) }
    val backFocus = remember(movie.pageUrl) { FocusRequester() }
    LaunchedEffect(movie.pageUrl) { backFocus.requestFocus() }
    BackHandler(enabled = showTorrents) { showTorrents = false }
    TvAction(
        if (showTorrents) "‹ До фільму" else "‹ До каталогу",
        { if (showTorrents) showTorrents = false else onBack() },
        Modifier.focusRequester(backFocus),
    )
    if (state.isLoading) LoadingIndicator()
    state.error?.let { ErrorPanel(it, onRetry) }
    if (showTorrents && state.details != null) {
        TorrentList(state.details, state.activeDownloads, onDownload)
    } else {
        val detailScroll = rememberScrollState()
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(detailScroll),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Poster(displayMovie, Modifier.width(154.dp).aspectRatio(2f / 3f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(displayMovie.title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    displayMovie.year?.let { Text(it, color = Muted) }
                    state.details?.let { details ->
                        if (details.genres.isNotEmpty()) Text(details.genres.joinToString(" • "), color = Muted)
                        details.rating?.let { Text("Рейтинг: $it", color = Accent) }
                        TvAction("Торенти (${details.torrents.size})", { showTorrents = true })
                        if (details.onlineUrl != null) {
                            TvAction("Дивитися онлайн у зовнішньому застосунку", onOpenOnline)
                        } else {
                            Text("Онлайн-перегляд для цієї сторінки поки недоступний.", color = Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
            state.details?.description?.takeIf { it.isNotBlank() }?.let {
                ReadingText(it, detailScroll)
            }
        }
    }
}

@Composable
private fun TorrentList(
    details: MovieDetails,
    activeDownloads: Map<String, Int>,
    onDownload: (TorrentRelease) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(8.dp)) {
        item {
            Text("Торент-роздачі", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Завантажується лише .torrent-файл. Відео завантажує зовнішній торрент-застосунок.", color = Muted, fontSize = 13.sp)
        }
        details.torrentNotice?.let { notice -> item { EmptyPanel(notice) } }
        if (details.torrents.isEmpty() && details.torrentNotice == null) {
            item { EmptyPanel("Сайт не надав доступних торрент-файлів для цього фільму.") }
        }
        items(details.torrents, key = { it.id }) { release ->
            Column(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(release.title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                val metadata = listOfNotNull(release.quality, release.size, release.videoCodec, release.audio)
                if (metadata.isNotEmpty()) Text(metadata.joinToString(" • "), color = Muted, fontSize = 14.sp)
                release.seeders?.let { Text("Сідери: $it", color = Muted, fontSize = 13.sp) }
                val progress = activeDownloads[release.id]
                if (progress != null) {
                    if (progress in 0..100) {
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = Accent)
                    } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent)
                }
                TvAction(
                    if (progress == null) "Завантажити .torrent"
                    else if (progress >= 0) "Завантаження: $progress%" else "Завантаження…",
                    { onDownload(release) },
                    enabled = progress == null,
                )
            }
        }
    }
}

@Composable
private fun DownloadsScreen(records: List<DownloadRecord>, onOpen: (DownloadRecord) -> Unit) {
    if (records.isEmpty()) {
        EmptyPanel("Тут з’являться збережені .torrent-файли. Виберіть фільм і відкрийте розділ «Торенти».")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(10.dp)) {
        item { Text("Збережені торрент-файли. Для відкриття потрібен сумісний встановлений застосунок.", fontSize = 14.sp, color = Muted) }
        items(records, key = { it.id }) { record ->
            Column(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(record.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                val date = remember(record.downloadedAt) {
                    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                        .format(Instant.ofEpochMilli(record.downloadedAt).atZone(ZoneId.systemDefault()))
                }
                Text("$date • ${String.format(Locale.ROOT, "%.1f", record.bytes / 1024.0)} КБ • Збережено", color = Muted, fontSize = 13.sp)
                TvAction("Відкрити у торрент-застосунку", { onOpen(record) })
            }
        }
    }
}

@Composable
private fun SettingsScreen(baseUrl: String, onSave: (String) -> Unit) {
    var domain by rememberSaveable(baseUrl) { mutableStateOf(baseUrl) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Джерело каталогу", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Базова HTTPS-адреса сайту використовується для каталогу, пошуку й сторінок фільмів. Зберігається після перезапуску.", color = Muted, fontSize = 15.sp)
        OutlinedTextField(
            value = domain,
            onValueChange = { domain = it },
            modifier = Modifier.fillMaxWidth(),
            label = { androidx.compose.material3.Text("Адреса сайту") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(domain.trim()) }),
        )
        TvAction("Зберегти адресу", { onSave(domain.trim()) }, enabled = domain.isNotBlank())
        Text("Зовнішній торрент-застосунок", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Після завантаження .torrent відкрийте його в розділі «Завантаження». Android покаже сумісні застосунки; клієнт має бути встановлений на телевізорі.", color = Muted, fontSize = 15.sp)
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF3B232B), RoundedCornerShape(12.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, color = Color(0xFFFFD8DC), fontSize = 15.sp)
        TvAction("Повторити", onRetry)
    }
}

@Composable
private fun LoadingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp), color = Accent, strokeWidth = 2.dp)
        Text("Завантаження…", color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun EmptyPanel(message: String) {
    Text(
        message,
        modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(20.dp),
        color = Muted,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    )
}

@Composable
private fun MessageDialog(message: String, onDismiss: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 400.dp)
                .background(Panel, RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ReadingText(message, scroll, Modifier.weight(1f, fill = false).verticalScroll(scroll))
            TvAction("Зрозуміло", onDismiss, Modifier.focusRequester(closeFocus))
        }
        LaunchedEffect(Unit) { closeFocus.requestFocus() }
    }
}

/** Long descriptions remain readable with Up/Down on a remote, including text below the fold. */
@Composable
private fun ReadingText(text: String, scroll: ScrollState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    Text(
        text,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .border(if (focused) 2.dp else 0.dp, Accent, RoundedCornerShape(6.dp))
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false
                else when {
                    event.key == Key.DirectionDown && scroll.canScrollForward -> {
                        scope.launch { scroll.animateScrollBy(100f) }
                        true
                    }
                    event.key == Key.DirectionUp && scroll.canScrollBackward -> {
                        scope.launch { scroll.animateScrollBy(-100f) }
                        true
                    }
                    else -> false
                }
            }
            .focusable().padding(8.dp),
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )
}
