package ua.kinotv.app.download

import java.io.IOException

/** Parses the descriptor itself; an HTML response with a .torrent filename is never accepted. */
object TorrentValidator {
    const val MAX_BYTES: Long = 5L * 1024L * 1024L

    fun validate(bytes: ByteArray) {
        requireTorrent(bytes.isNotEmpty() && bytes.size <= MAX_BYTES, "Некоректний розмір торрент-файлу.")
        val root = Decoder(bytes).decode() as? Dict
            ?: invalid("Відповідь не є торрент-файлом: очікувався словник bencode.")
        val info = root.values["info"] as? Dict ?: invalid("У торрент-файлі відсутні метадані info.")
        val name = (info.values["name.utf-8"] ?: info.values["name"]) as? Bytes
            ?: invalid("У торрент-файлі відсутня назва.")
        safePathPart(name.value)
        val pieceLength = (info.values["piece length"] as? Number)?.value
            ?: invalid("У торрент-файлі відсутній розмір частини.")
        requireTorrent(pieceLength in 1..(64L * 1024 * 1024), "Некоректний розмір частини торренту.")
        val pieces = info.values["pieces"] as? Bytes
            ?: invalid("Цей формат торренту не підтримується: потрібні метадані BitTorrent v1.")

        val singleLength = info.values["length"]
        val files = info.values["files"]
        requireTorrent((singleLength == null) != (files == null), "Некоректний список файлів торренту.")
        val total = if (singleLength != null) {
            val length = (singleLength as? Number)?.value ?: invalid("Некоректний розмір файлу.")
            requireTorrent(length >= 0, "Від’ємний розмір файлу в торренті.")
            length
        } else {
            val entries = (files as? Sequence)?.values ?: invalid("Некоректний список файлів торренту.")
            requireTorrent(entries.isNotEmpty() && entries.size <= 10_000, "Некоректна кількість файлів у торренті.")
            var totalLength = 0L
            entries.forEach { entry ->
                val file = entry as? Dict ?: invalid("Некоректний опис файлу в торренті.")
                val length = (file.values["length"] as? Number)?.value ?: invalid("Не вказано розмір файлу.")
                requireTorrent(length >= 0 && Long.MAX_VALUE - totalLength >= length, "Некоректний розмір файлів.")
                totalLength += length
                val path = (file.values["path.utf-8"] ?: file.values["path"]) as? Sequence
                    ?: invalid("Не вказано шлях файлу.")
                requireTorrent(path.values.isNotEmpty(), "Порожній шлях файлу в торренті.")
                path.values.forEach { component ->
                    safePathPart((component as? Bytes)?.value ?: invalid("Некоректний шлях файлу."))
                }
            }
            totalLength
        }
        requireTorrent(pieces.value.size % 20 == 0, "Некоректна довжина хешів частин торренту.")
        val expectedParts = total / pieceLength + if (total % pieceLength == 0L) 0 else 1
        requireTorrent(expectedParts == pieces.value.size.toLong() / 20, "Кількість частин не відповідає розміру файлів.")
    }

    private fun safePathPart(bytes: ByteArray) {
        requireTorrent(bytes.isNotEmpty() && bytes.size <= 255, "Некоректна назва файлу в торренті.")
        val value = bytes.toString(Charsets.UTF_8)
        requireTorrent(value != "." && value != ".." && bytes.none {
            it == 0.toByte() || it == '/'.code.toByte() || it == '\\'.code.toByte()
        } && !value.contains(':'), "Небезпечний шлях у торрент-файлі.")
    }

    private sealed interface Value
    private data class Bytes(val value: ByteArray) : Value
    private data class Number(val value: Long) : Value
    private data class Sequence(val values: List<Value>) : Value
    private data class Dict(val values: Map<String, Value>) : Value

    private class Decoder(private val input: ByteArray) {
        private var position = 0
        private var nodes = 0

        fun decode(): Value {
            val value = value(0)
            requireTorrent(position == input.size, "Зайві дані після торрент-файлу.")
            return value
        }

        private fun value(depth: Int): Value {
            requireTorrent(depth <= 64 && ++nodes <= 100_000, "Надто складний торрент-файл.")
            return when (peek()) {
                'd' -> {
                    position++
                    val entries = linkedMapOf<String, Value>()
                    while (peek() != 'e') {
                        val key = string().value.toString(Charsets.ISO_8859_1)
                        requireTorrent(!entries.containsKey(key), "Повторений ключ у торрент-файлі.")
                        entries[key] = value(depth + 1)
                    }
                    position++
                    Dict(entries)
                }
                'l' -> {
                    position++
                    val items = mutableListOf<Value>()
                    while (peek() != 'e') items += value(depth + 1)
                    position++
                    Sequence(items)
                }
                'i' -> {
                    position++
                    val start = position
                    while (peek() != 'e') {
                        val c = peek()
                        requireTorrent(c in '0'..'9' || (position == start && c == '-'), "Некоректне число у торрент-файлі.")
                        position++
                        requireTorrent(position - start <= 20, "Завелике число в торрент-файлі.")
                    }
                    val text = input.copyOfRange(start, position).toString(Charsets.US_ASCII)
                    position++
                    requireTorrent(text.isNotEmpty() && text != "-0" && !text.startsWith("00") &&
                        !(text.length > 1 && text[0] == '0') && !text.startsWith("-0"), "Некоректне число у торрент-файлі.")
                    Number(text.toLongOrNull() ?: invalid("Некоректне число у торрент-файлі."))
                }
                in '0'..'9' -> string()
                else -> invalid("Некоректні дані bencode; сервер міг повернути HTML замість торренту.")
            }
        }

        private fun string(): Bytes {
            val start = position
            while (peek() != ':') {
                requireTorrent(peek() in '0'..'9' && position - start < 8, "Некоректна довжина рядка bencode.")
                position++
            }
            val rawLength = input.copyOfRange(start, position).toString(Charsets.US_ASCII)
            requireTorrent(rawLength.isNotEmpty() && (rawLength.length == 1 || rawLength[0] != '0'), "Некоректна довжина рядка bencode.")
            val length = rawLength.toIntOrNull() ?: invalid("Некоректна довжина рядка bencode.")
            position++
            requireTorrent(length <= input.size - position, "Торрент-файл завантажено не повністю.")
            val data = input.copyOfRange(position, position + length)
            position += length
            return Bytes(data)
        }

        private fun peek(): Char {
            requireTorrent(position < input.size, "Торрент-файл завантажено не повністю.")
            return input[position].toInt().toChar()
        }
    }

    private fun requireTorrent(condition: Boolean, message: String) {
        if (!condition) invalid(message)
    }

    private fun invalid(message: String): Nothing = throw TorrentValidationException(message)
}

class TorrentValidationException(message: String) : IOException(message)
