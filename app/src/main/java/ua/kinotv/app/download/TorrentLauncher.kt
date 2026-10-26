package ua.kinotv.app.download

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

class TorrentLauncher(context: Context) {
    private val context = context.applicationContext

    fun open(record: DownloadRecord, file: File) {
        if (!file.isFile || file.name != record.fileName) throw IOException("Торрент-файл більше не доступний. Завантажте його ще раз.")
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        } catch (_: IllegalArgumentException) {
            throw IOException("Торрент-файл не належить до теки завантажень KinoTV.")
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/x-bittorrent")
            clipData = ClipData.newRawUri(record.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (context.packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) {
            throw IOException("Не встановлено застосунок для торрент-файлів. Установіть сумісний торрент-клієнт.")
        }
        val chooser = Intent.createChooser(view, "Відкрити торрент у застосунку").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            // Do not restrict to a hard-coded torrent client: users choose a compatible installed app.
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            throw IOException("Не встановлено застосунок для торрент-файлів. Установіть сумісний торрент-клієнт.")
        } catch (_: SecurityException) {
            throw IOException("Торрент-застосунок не отримав доступ до файлу.")
        }
    }
}
