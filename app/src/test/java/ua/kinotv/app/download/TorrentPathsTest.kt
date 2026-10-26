package ua.kinotv.app.download

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrentPathsTest {
    private val root = Files.createTempDirectory("kinotv-torrents").toFile()
    private val name = "cab311f9-2aba-41d6-98f4-ac2aa91859ce.torrent"

    @After fun cleanUp() { root.deleteRecursively() }

    @Test fun `allows an existing UUID-named descriptor in the private directory`() {
        val file = File(root, name).apply { writeText("descriptor") }
        assertEquals(file.canonicalFile, TorrentPaths.fileFor(root, name))
    }

    @Test fun `does not return missing files`() {
        assertNull(TorrentPaths.fileFor(root, name))
    }

    @Test fun `rejects paths from corrupt or manipulated download history`() {
        listOf("../$name", "/tmp/$name", "subdir/$name", "film.torrent", "$name.exe").forEach {
            assertNull(TorrentPaths.fileFor(root, it))
        }
    }

    @Test fun `rejects a symbolic link that escapes the download directory`() {
        val outside = Files.createTempFile("kinotv-outside", ".torrent")
        try {
            val link = File(root, name).toPath()
            Files.createSymbolicLink(link, outside)
            assertNull(TorrentPaths.fileFor(root, name))
            Files.delete(link)
        } finally {
            Files.deleteIfExists(outside)
        }
    }
}
