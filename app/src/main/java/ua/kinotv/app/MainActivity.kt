package ua.kinotv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import ua.kinotv.app.playback.PlayerActivity
import ua.kinotv.app.ui.KinoTvApp

class MainActivity : ComponentActivity() {
    private val model: KinoViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                KinoViewModel(application as KinoApplication) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            BackHandler(enabled = state.selectedMovie != null) { model.back() }
            KinoTvApp(
                state = state,
                onSection = model::selectSection,
                onSearch = model::search,
                onSelectMovie = model::selectMovie,
                onBack = model::back,
                onRetry = model::retry,
                onLoadMore = model::loadMore,
                onDownload = model::download,
                onOpenDownload = model::openDownload,
                onSaveBaseUrl = model::saveBaseUrl,
                onDismissMessage = model::dismissMessage,
                onOpenOnline = {
                    val details = state.details
                    if (details == null || details.onlineUrl == null) {
                        model.showMessage("Джерело не надало підтримуване посилання для онлайн-перегляду.")
                    } else {
                        startActivity(PlayerActivity.intent(this, details.onlineUrl,
                            details.movie.pageUrl, details.movie.title))
                    }
                },
            )
        }
    }

}
