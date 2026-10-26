package ua.kinotv.app.settings

import ua.kinotv.app.data.network.SourceUrl

object SourceAddress {
    fun normalize(value: String): String = SourceUrl.normalizeBaseUrl(value)
}
