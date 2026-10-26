package ua.kinotv.app.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("source_settings", Context.MODE_PRIVATE)
    val baseUrl: String
        get() = prefs.getString("base_url", null) ?: "https://uakino.best/"

    suspend fun saveBaseUrl(value: String): String = withContext(Dispatchers.IO) {
        val normalized = SourceAddress.normalize(value)
        check(prefs.edit().putString("base_url", normalized).commit()) {
            "Не вдалося зберегти адресу сайту."
        }
        normalized
    }
}
