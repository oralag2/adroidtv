package ua.kinotv.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
                onOpenOnline = { openOnline(state.details?.onlineUrl) },
            )
        }
    }

    private fun openOnline(address: String?) {
        val url = address?.toHttpUrlOrNull()
        if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            model.showMessage("Джерело не надало підтримуване HTTPS-посилання для онлайн-перегляду.")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())))
        } catch (_: ActivityNotFoundException) {
            model.showMessage("На пристрої немає застосунку для відкриття цього онлайн-посилання.")
        } catch (_: SecurityException) {
            model.showMessage("Пристрій заборонив відкриття цього онлайн-посилання.")
        }
    }
}
