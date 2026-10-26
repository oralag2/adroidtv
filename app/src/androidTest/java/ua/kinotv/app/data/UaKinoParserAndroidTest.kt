package ua.kinotv.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import ua.kinotv.app.data.parser.UaKinoParser

/** Android uses ICU regex rules; a successful JVM parser test cannot cover class initialization there. */
@RunWith(AndroidJUnit4::class)
class UaKinoParserAndroidTest {
    @Test fun sourceTorrentScriptParsesUsingAndroidRegexEngine() {
        val parser = UaKinoParser()
        val page = "https://uakino.best/filmy/genre-action/11-matrycia/download.html"
        val document = parser.document(
            """<script>
                ${'$'}.post(dle_root + 'engine/ajax/toloka.php', {
                    id: '11', cat: 'filmy/genre-action/', title: 'Матриця', theme: '/templates/uakino'
                }, function(data) {});
            </script>""".trimIndent(), page,
        )
        val request = parser.torrentRequest(document, page)
        assertNotNull(request)
        assertEquals("https://uakino.best/engine/ajax/toloka.php", request!!.url)
        assertEquals("11", request.fields["id"])
        assertEquals("Матриця", request.fields["title"])
        assertEquals("filmy/genre-action/", request.fields["cat"])
    }
}
