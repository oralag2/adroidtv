package ua.kinotv.app.download

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import ua.kinotv.app.data.model.TorrentRelease
import ua.kinotv.app.data.network.SourceUrl

class TorrentDownloader(context: Context, private val client: OkHttpClient) {
    private val directory = File(context.applicationContext.filesDir, "torrents")
    private val index = AtomicFile(File(directory, "downloads.json"))
    private val indexLock = Any()

    /** Downloads only the small torrent descriptor, not the video it describes. */
    suspend fun download(
        release: TorrentRelease,
        referer: String,
        onProgress: (Int) -> Unit,
    ): DownloadRecord = withContext(Dispatchers.IO) {
        val origin = secureUrl(referer)
        var url = secureUrl(release.downloadUrl)
        requireSameOrigin(origin, url)
        val transport = safeTransport(origin)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Не вдалося створити теку завантажень.")
        val id = UUID.randomUUID().toString()
        val part = File(directory, ".$id.part")
        val target = File(directory, "$id.torrent")
        if (!part.createNewFile()) throw IOException("Не вдалося створити файл завантаження.")
        var retained = false
        try {
            var redirects = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val request = TorrentHttp.request(url, origin)
                val call = transport.newCall(request)
                val next = withCancellation(call) {
                    call.execute().use { response ->
                        if (response.code in listOf(301, 302, 303, 307, 308)) {
                            if (++redirects > 5) throw IOException("Забагато перенаправлень під час завантаження.")
                            val location = response.header("Location") ?: throw IOException("Сервер не вказав адресу перенаправлення.")
                            val redirect = url.resolve(location) ?: throw IOException("Некоректна адреса перенаправлення.")
                            secureUrl(redirect.toString())
                            requireSameOrigin(origin, redirect)
                            redirect
                        } else {
                            saveResponse(response, part, onProgress)
                            null
                        }
                    }
                }
                if (next == null) break
                url = next
            }
            currentCoroutineContext().ensureActive()
            TorrentValidator.validate(part.readBytes())
            val record = DownloadRecord(id, release.title, target.name, System.currentTimeMillis(), part.length())
            synchronized(indexLock) {
                val previous = readRecordsUnlocked()
                if (target.exists()) throw IOException("Файл із таким ім’ям уже існує.")
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                try {
                    writeRecordsUnlocked(listOf(record) + previous)
                } catch (failure: Exception) {
                    target.delete()
                    throw failure
                }
            }
            retained = true
            onProgress(100)
            record
        } finally {
            part.delete()
            if (!retained) target.delete()
        }
    }

    fun records(): List<DownloadRecord> = synchronized(indexLock) {
        readRecordsUnlocked().filter { fileFor(it) != null }.sortedByDescending { it.downloadedAt }
    }

    fun fileFor(record: DownloadRecord): File? = TorrentPaths.fileFor(directory, record.fileName)

    private suspend fun saveResponse(response: Response, part: File, onProgress: (Int) -> Unit) {
        TorrentHttp.validateResponse(response)
        val body = response.body ?: throw IOException("Сервер повернув порожню відповідь.")
        val length = body.contentLength()
        onProgress(if (length > 0) 0 else -1)
        var received = 0L
        var previousProgress = Int.MIN_VALUE
        body.byteStream().use { source ->
            part.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read == -1) break
                    received += read
                    if (received > TorrentValidator.MAX_BYTES) throw IOException("Торрент-файл перевищує обмеження 5 МіБ.")
                    output.write(buffer, 0, read)
                    val progress = if (length > 0) (received * 100 / length).toInt().coerceIn(0, 99) else -1
                    if (progress != previousProgress) {
                        onProgress(progress)
                        previousProgress = progress
                    }
                }
                output.fd.sync()
            }
        }
        if (length >= 0 && received != length) throw IOException("Торрент-файл завантажено не повністю.")
    }

    private suspend fun <T> withCancellation(call: Call, block: suspend () -> T): T = coroutineScope {
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            block()
        } finally {
            cancellation.cancel()
        }
    }

    private fun safeTransport(origin: HttpUrl): OkHttpClient {
        val cookieJar = client.cookieJar
        return client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    if (sameOrigin(origin, url)) cookieJar.saveFromResponse(url, cookies)
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    if (sameOrigin(origin, url)) cookieJar.loadForRequest(url) else emptyList()
            })
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    client.dns.lookup(hostname).also { addresses ->
                        if (addresses.isEmpty() || addresses.any(::isPrivateAddress)) {
                            throw IOException("Адреса джерела не повинна вести до локальної мережі.")
                        }
                    }
            })
            .build()
    }

    private fun secureUrl(value: String): HttpUrl {
        val validated = SourceUrl.publicHttps(value, value)
            ?: throw IOException("Дозволені лише публічні HTTPS-адреси без пароля в посиланні.")
        return validated.toHttpUrl()
    }

    private fun requireSameOrigin(origin: HttpUrl, destination: HttpUrl) {
        if (!sameOrigin(origin, destination)) {
            throw IOException("Торрент-посилання веде на інший домен. Завантаження сторонніх джерел поки не підтримується.")
        }
    }

    private fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
        first.scheme == second.scheme && first.host == second.host && first.port == second.port

    private fun isPrivateAddress(address: InetAddress): Boolean =
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress ||
            (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)

    private fun readRecordsUnlocked(): List<DownloadRecord> {
        val contents = try {
            index.readFully()
        } catch (_: java.io.FileNotFoundException) {
            return emptyList()
        }
        return try {
            val items = JSONArray(contents.toString(Charsets.UTF_8))
            (0 until items.length()).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                val fileName = item.optString("fileName")
                val id = item.optString("id")
                if (id.isEmpty() || fileName != "$id.torrent" || TorrentPaths.fileFor(directory, fileName) == null) {
                    return@mapNotNull null
                }
                DownloadRecord(id, item.optString("title", "Торрент"), fileName,
                    item.optLong("downloadedAt"), item.optLong("bytes"))
            }
        } catch (_: org.json.JSONException) {
            // Preserve corrupt user data before writing a fresh index on the next successful download.
            val digest = MessageDigest.getInstance("SHA-256").digest(contents).take(8)
                .joinToString("") { "%02x".format(it) }
            val backup = File(directory, "downloads-corrupt-$digest.json")
            if (!backup.exists()) backup.outputStream().use { it.write(contents) }
            emptyList()
        }
    }

    private fun writeRecordsUnlocked(records: List<DownloadRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject().apply {
                put("id", record.id)
                put("title", record.title)
                put("fileName", record.fileName)
                put("downloadedAt", record.downloadedAt)
                put("bytes", record.bytes)
            })
        }
        val output = index.startWrite()
        try {
            output.write(array.toString().toByteArray(Charsets.UTF_8))
            index.finishWrite(output)
        } catch (failure: Exception) {
            index.failWrite(output)
            throw failure
        }
    }
}
