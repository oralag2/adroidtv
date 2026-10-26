package ua.kinotv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTargetTest {
    private val referer = "https://uakino.best/filmy/101-film.html"

    @Test fun handlesRealEmbedShapeWithoutTreatingItAsMedia() {
        val target = PlaybackTarget.create("https://ashdi.vip/vod/298014", referer, "Фільм")
        assertEquals(PlaybackTarget.Kind.EMBED, target.kind)
        assertEquals(referer, target.referer)
    }

    @Test fun classifiesSignedHlsAndMediaFilesByPath() {
        assertEquals(PlaybackTarget.Kind.HLS,
            PlaybackTarget.create("https://cdn.example/video/MASTER.M3U8?token=example", referer, "").kind)
        listOf("mp4", "mkv", "webm", "m4v").forEach { extension ->
            assertEquals(PlaybackTarget.Kind.FILE,
                PlaybackTarget.create("https://cdn.example/video.$extension?key=example", referer, "Фільм").kind)
        }
        assertEquals(PlaybackTarget.Kind.EMBED,
            PlaybackTarget.create("https://cdn.example/player?file=video.mp4", referer, "Фільм").kind)
    }

    @Test fun refusesUnsafePlayerAndRefererAddresses() {
        listOf("http://ashdi.vip/vod/1", "javascript:alert(1)", "intent://player", "file:///tmp/file",
            "https://user:pass@ashdi.vip/vod/1", "https://127.0.0.1/video.mp4", "https://host.local/video.mp4").forEach { address ->
            assertThrows(address, IllegalArgumentException::class.java) {
                PlaybackTarget.create(address, referer, "Фільм")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackTarget.create("https://ashdi.vip/vod/1", "file:///secret", "Фільм")
        }
    }

    @Test fun embedNavigationCannotLaunchAdsOrOtherApplications() {
        val target = PlaybackTarget.create("https://ashdi.vip/vod/298014", referer, "Фільм")
        assertTrue(target.permitsNavigation("/vod/298015"))
        assertFalse(target.permitsNavigation("https://unrelated.example/ads"))
        assertFalse(target.permitsNavigation("intent://application"))
        assertFalse(target.permitsNavigation("http://ashdi.vip/vod/298015"))
    }
}
