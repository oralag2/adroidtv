package ua.kinotv.app.download

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class TorrentValidatorTest {
    @Test fun `accepts a complete single-file descriptor`() {
        TorrentValidator.validate(torrent())
    }

    @Test fun `accepts valid multi-file torrent with matching piece count`() {
        TorrentValidator.validate(encode(mapOf("info" to mapOf(
            "name" to "film",
            "piece length" to 16L,
            "pieces" to ByteArray(40),
            "files" to listOf(
                mapOf("length" to 12L, "path" to listOf("video.mkv")),
                mapOf("length" to 5L, "path" to listOf("subtitles", "uk.srt")),
            ),
        ))))
    }

    @Test fun `rejects an HTML error masquerading as a torrent`() {
        rejects("<html><body>Access denied</body></html>".toByteArray())
    }

    @Test fun `rejects missing info dictionary`() {
        rejects(encode(mapOf("announce" to "https://tracker.example/announce")))
    }

    @Test fun `rejects a partial download`() {
        rejects(torrent().dropLast(1).toByteArray())
    }

    @Test fun `rejects garbage after a valid descriptor`() {
        rejects(torrent() + "garbage".toByteArray())
    }

    @Test fun `rejects incorrect piece count`() {
        rejects(torrent(pieces = ByteArray(20)))
    }

    @Test fun `rejects non-SHA1-sized piece hashes`() {
        rejects(torrent(pieces = ByteArray(39)))
    }

    @Test fun `rejects traversal in the torrent name`() {
        rejects(torrent(name = "../film.mkv"))
        rejects(torrent(name = ".."))
    }

    @Test fun `rejects traversal components in a multi-file descriptor`() {
        rejects(encode(mapOf("info" to mapOf(
            "name" to "film",
            "piece length" to 16L,
            "pieces" to ByteArray(20),
            "files" to listOf(mapOf("length" to 1L, "path" to listOf("..", "video.mkv"))),
        ))))
    }

    @Test fun `rejects a duplicate dictionary key`() {
        rejects("d1:ai1e1:ai2ee".toByteArray())
    }

    @Test fun `rejects invalid or overflowing integers`() {
        rejects("d4:infoi-0ee".toByteArray())
        rejects("d4:infoi9223372036854775808ee".toByteArray())
    }

    @Test fun `rejects negative file sizes and zero piece lengths`() {
        rejects(torrent(length = -1L))
        rejects(torrent(pieceLength = 0L))
    }

    @Test fun `rejects impossibly large bencode string lengths before allocating`() {
        rejects("d9999999999999999999999:hello".toByteArray())
    }

    @Test fun `rejects descriptors larger than five mebibytes`() {
        rejects(ByteArray(TorrentValidator.MAX_BYTES.toInt() + 1))
    }

    @Test fun `rejects excessively nested bencode`() {
        rejects(("l".repeat(100) + "i1e" + "e".repeat(100)).toByteArray())
    }

    private fun rejects(bytes: ByteArray) {
        assertThrows(TorrentValidationException::class.java) { TorrentValidator.validate(bytes) }
    }

    private fun torrent(
        name: String = "film.mkv",
        length: Long = 16_385L,
        pieceLength: Long = 16_384L,
        pieces: ByteArray = ByteArray(40),
    ): ByteArray = encode(mapOf("info" to mapOf(
        "length" to length, "name" to name,
        "piece length" to pieceLength, "pieces" to pieces,
    )))

    private fun encode(value: Any): ByteArray = ByteArrayOutputStream().use { output ->
        fun write(item: Any) {
            when (item) {
                is String -> write(item.toByteArray(Charsets.UTF_8))
                is ByteArray -> {
                    output.write("${item.size}:".toByteArray())
                    output.write(item)
                }
                is Long -> output.write("i${item}e".toByteArray())
                is List<*> -> {
                    output.write('l'.code)
                    item.forEach { write(requireNotNull(it)) }
                    output.write('e'.code)
                }
                is Map<*, *> -> {
                    output.write('d'.code)
                    item.entries.sortedBy { it.key as String }.forEach { (key, v) ->
                        write(key as String)
                        write(requireNotNull(v))
                    }
                    output.write('e'.code)
                }
                else -> error("Unsupported test value")
            }
        }
        write(value)
        output.toByteArray()
    }
}
