package ua.kinotv.app

import android.app.Application
import okhttp3.OkHttpClient
import ua.kinotv.app.data.repository.UaKinoRepository
import ua.kinotv.app.download.TorrentDownloader
import ua.kinotv.app.download.TorrentLauncher
import ua.kinotv.app.network.SessionCookieJar
import ua.kinotv.app.settings.SettingsStore
import java.util.concurrent.TimeUnit

class KinoApplication : Application() {
    val settings by lazy { SettingsStore(this) }
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(SessionCookieJar())
            .build()
    }
    val repository by lazy { UaKinoRepository(client) { settings.baseUrl } }
    val downloader by lazy { TorrentDownloader(this, client) }
    val torrentLauncher by lazy { TorrentLauncher(this) }
}
