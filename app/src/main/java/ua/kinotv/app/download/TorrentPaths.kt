package ua.kinotv.app.download

import java.io.File
import java.io.IOException

internal object TorrentPaths {
    private val descriptorName = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.torrent")

    fun fileFor(directory: File, name: String): File? {
        if (!descriptorName.matches(name)) return null
        return try {
            val root = directory.canonicalFile
            val file = File(root, name)
            if (file.canonicalFile.parentFile != root || !file.isFile) null else file
        } catch (_: IOException) {
            null
        }
    }
}
