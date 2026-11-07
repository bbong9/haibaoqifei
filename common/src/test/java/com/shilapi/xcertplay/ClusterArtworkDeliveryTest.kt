package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.os.Looper
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterArtworkDeliveryTest {
    private val controller = mock(CarPlayController::class.java)
    private val context get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun call(name: String, vararg args: Any?) = CarPlayMediaKeys.javaClass.declaredMethods
        .single { it.name == name }.apply { isAccessible = true }.invoke(CarPlayMediaKeys, *args)
    private fun set(name: String, value: Any?) = ReflectionHelpers.setField(CarPlayMediaKeys, name, value)
    private fun <T> field(name: String): T = ReflectionHelpers.getField(CarPlayMediaKeys, name)

    @After fun clean() {
        CarPlayMediaKeys.detach(controller)
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        idle()
    }

    private fun seed(song: CarPlayNowPlaying): Bitmap {
        set("controller", controller)
        set("appContext", context)
        set("nowPlaying", song)
        set("artworkOwner", Any())
        val art = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        set("artwork", art)
        field<MutableMap<Int, Bitmap?>>("artworkCache")[song.artworkTransferId!!] = art
        ClusterMusicState.publish(song, art); idle()
        return art
    }

    @Test fun sameReferenceMetadataDoesNotCancelDecodeOrEraseCachedCover() {
        val song = CarPlayNowPlaying(title = "A", artist = "Artist", artworkTransferId = 7, elapsedMillis = 90_000)
        val art = seed(song)
        val owner = field<Any>("artworkOwner")
        // This title/position update used to start a new artwork session, dropping in-flight bytes.
        `when`(controller.nowPlayingSnapshot()).thenReturn(song.copy(title = "B", elapsedMillis = 0))
        call("onNowPlayingChanged", controller); idle()
        assertSame(owner, field<Any>("artworkOwner"))
        assertSame(art, field<MutableMap<Int, Bitmap?>>("artworkCache")[7])
        var shown: Bitmap? = null
        val stop = ClusterMusicState.observe { shown = it.artwork }
        assertSame(art, shown)
        stop()
    }

    @Test fun failedDecodeDoesNotOverwriteValidCoverAndLateOldOwnerCannotPublish() {
        val art = seed(CarPlayNowPlaying(title = "A", artworkTransferId = 7))
        call("onArtworkDecoded", field<Any>("artworkOwner"), 7, null)
        idle()
        assertSame(art, field<Bitmap>("artwork"))
        assertSame(art, field<MutableMap<Int, Bitmap?>>("artworkCache")[7])
        call("onArtworkDecoded", Any(), 7, null)
        assertSame(art, field<Bitmap>("artwork"))
    }

    @Test fun newReferenceKeepsPreviousCoverWhileItsTransferIsPending() {
        val song = CarPlayNowPlaying(title = "A", artworkTransferId = 7)
        val art = seed(song)
        `when`(controller.nowPlayingSnapshot()).thenReturn(song.copy(title = "B", artworkTransferId = 8))
        call("onNowPlayingChanged", controller); idle()
        var shown: Bitmap? = null
        val stop = ClusterMusicState.observe { shown = it.artwork }
        assertSame(art, shown)
        CarPlayMediaKeys.detach(controller); idle()
        assertNull(shown)
        stop()
    }

    @Test fun missingCoverRecoveryAlsoWorksWithOnlyClusterMusicEnabled() {
        seed(CarPlayNowPlaying(title = "A", artworkTransferId = 7))
        ClusterMusicSettings.saveEnabled(context, true)
        assertEquals(true, call("albumRecoveryEnabled"))
        ClusterMusicSettings.saveEnabled(context, false)
        assertEquals(false, call("albumRecoveryEnabled"))
    }

    @Test fun playbackOnlyUpdatesReachClusterWithoutChangingMediaMetadata() {
        val song = CarPlayNowPlaying(title = "A", artworkTransferId = 7,
            durationMillis = 180_000, elapsedMillis = 10_000, playing = true)
        val art = seed(song)
        val updates = mutableListOf<ClusterMusicState.Snapshot>()
        val stop = ClusterMusicState.observe { updates += it }
        try {
            val seek = song.copy(elapsedMillis = 45_000)
            assertFalse(CarPlayMediaKeys.metadataChanged(song, seek))
            `when`(controller.nowPlayingSnapshot()).thenReturn(seek)
            call("onNowPlayingChanged", controller); idle()
            assertEquals(45_000L, updates.last().positionMillis(updates.last().elapsedAt))
            assertSame(art, updates.last().artwork)

            val paused = seek.copy(playing = false)
            assertFalse(CarPlayMediaKeys.metadataChanged(seek, paused))
            `when`(controller.nowPlayingSnapshot()).thenReturn(paused)
            call("onNowPlayingChanged", controller); idle()
            assertFalse(updates.last().info.playing)
            assertEquals(updates.last().positionMillis(updates.last().elapsedAt),
                updates.last().positionMillis(updates.last().elapsedAt + 10_000))
        } finally { stop() }
    }
}
