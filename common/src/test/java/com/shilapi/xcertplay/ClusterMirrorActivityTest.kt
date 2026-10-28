package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import java.lang.ref.WeakReference
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ClusterMirrorActivityTest {
    private val owner = Any()
    private val token = "01234567-89ab-cdef-0123-456789abcdef"
    private lateinit var controller: ActivityController<AdbClusterActivity>
    private lateinit var activity: AdbClusterActivity

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveAdbClusterEnabled(context, true)
        AirPlayPersistence.saveLegacyClusterEnabled(context, false)
        ClusterActivityOutput.stopForSettings()
        ClusterActivityOutput.bind(owner, 1) { }
        ReflectionHelpers.setStaticField(ClusterActivityOutput::class.java, "launchToken", token)
        val intent = Intent(context, AdbClusterActivity::class.java).putExtra("cluster_launch_token", token)
        controller = Robolectric.buildActivity(AdbClusterActivity::class.java, intent).create()
        activity = controller.get()
        ClusterActivityOutput.activity = WeakReference(activity)
        val root = activity.findViewById<View>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 720)
    }

    @After fun tearDown() {
        AirPlayPersistence.overlaySettingsListener = null
        ClusterActivityOutput.stopForSettings()
        controller.destroy()
        ClusterMusicState.publish(CarPlayNowPlaying(), null)
        shadowOf(Looper.getMainLooper()).idle()
        RuntimeEnvironment.getApplication().getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun mirrorUsesTargetBoundsAndRestoresMatchParentWhenDisabled() {
        ClusterActivityOutput.setMirrorState(true, true, 1940, 1092)
        val video = ReflectionHelpers.getField<ClusterVideoTexture>(activity, "videoTexture")
        val waiting = ReflectionHelpers.getField<TextView>(activity, "waiting")
        val expected = checkNotNull(ClusterActivityOutput.mirrorRect)
        val params = video.layoutParams as FrameLayout.LayoutParams
        assertEquals(expected.left.roundToInt(), params.leftMargin)
        assertEquals(expected.top.roundToInt(), params.topMargin)
        assertEquals(expected.right.roundToInt() - params.leftMargin, params.width)
        assertEquals(expected.bottom.roundToInt() - params.topMargin, params.height)
        val waitingParams = waiting.layoutParams as FrameLayout.LayoutParams
        assertEquals(params.width, waitingParams.width)
        assertEquals(params.height, waitingParams.height)
        assertEquals(params.leftMargin, waitingParams.leftMargin)
        assertEquals(params.topMargin, waitingParams.topMargin)
        ClusterActivityOutput.setMirrorState(false, false, 1940, 1092)
        val restored = video.layoutParams as FrameLayout.LayoutParams
        assertEquals(-1, restored.width)
        assertEquals(-1, restored.height)
        assertEquals(0, restored.leftMargin)
        assertEquals(0, restored.topMargin)
        assertTrue(video.getTransform(android.graphics.Matrix()).isIdentity)
    }

    @Test fun inactiveNavigationLeavesTheWindowTransparentWithoutAWaitingLayer() {
        ClusterActivityOutput.setMirrorState(true, false, 1940, 1092)
        val video = ReflectionHelpers.getField<ClusterVideoTexture>(activity, "videoTexture")
        val waiting = ReflectionHelpers.getField<TextView>(activity, "waiting")
        val overlays = ReflectionHelpers.getField<FrameLayout>(activity, "overlayRegion")
        val content = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0)
        assertEquals(View.GONE, waiting.visibility)
        assertEquals(0f, video.alpha, 0f)
        assertEquals(1f, overlays.alpha, 0f)
        assertEquals(Color.TRANSPARENT, (content.background as ColorDrawable).color)
        assertEquals(Color.TRANSPARENT, (activity.window.decorView.background as ColorDrawable).color)
    }

    @Test fun inactiveNavigationKeepsEnabledMusicAndSpeedCardsVisible() {
        controller.start().resume().visible()
        ClusterMusicSettings.saveEnabled(activity, true)
        ClusterMusicState.publish(CarPlayNowPlaying(title = "Track", artist = "Artist"), null)
        shadowOf(Looper.getMainLooper()).idle()
        ClusterActivityOutput.setMirrorState(true, false, 1940, 1092)
        val overlays = ReflectionHelpers.getField<FrameLayout>(activity, "overlayRegion")
        val music = (0 until overlays.childCount).map(overlays::getChildAt).filterIsInstance<ClusterMusicView>().single()
        val speed = (0 until overlays.childCount).map(overlays::getChildAt).filterIsInstance<ClusterSpeedometerView>().single()
        val waiting = ReflectionHelpers.getField<TextView>(activity, "waiting")
        speed.setSpeedKmh(42)
        assertEquals(View.VISIBLE, music.visibility)
        assertEquals(View.VISIBLE, speed.visibility)
        assertEquals(1f, overlays.alpha, 0f)
        assertEquals(View.GONE, waiting.visibility)
    }

    @Test fun customMirrorLayoutRefreshesImmediatelyWhenSettingsChange() {
        AirPlayPersistence.saveClusterMirrorSize(activity, ClusterMirrorSize.CUSTOM)
        ClusterActivityOutput.setMirrorState(true, true, 1940, 1092)
        val video = ReflectionHelpers.getField<ClusterVideoTexture>(activity, "videoTexture")
        val initial = video.layoutParams as FrameLayout.LayoutParams
        assertEquals(317, initial.leftMargin)
        assertEquals(0, initial.topMargin)
        assertEquals(1286, initial.width)
        assertEquals(720, initial.height)
        AirPlayPersistence.overlaySettingsListener = { ClusterActivityOutput.refreshMirrorLayout() }
        AirPlayPersistence.saveClusterMirrorCustom(activity, ClusterMirrorCustomRect(0, 100, 50, 50))
        val changed = video.layoutParams as FrameLayout.LayoutParams
        assertEquals(0, changed.leftMargin)
        assertEquals(360, changed.topMargin)
        assertEquals(960, changed.width)
        assertEquals(360, changed.height)
        assertFalse(PendingReconnect.isPending(null))
        val expectedKeep = ClusterMirrorCrop.keep(1940, 1092, null, false, 7)
        val displayed = ClusterMirrorCrop.cover(expectedKeep, 960f / 360f)
        val actual = ReflectionHelpers.getField<android.graphics.RectF>(video, "contentRect")
        assertEquals(displayed.left / 1940f, actual.left, .0001f)
        assertEquals(displayed.top / 1092f, actual.top, .0001f)
        assertEquals(displayed.right / 1940f, actual.right, .0001f)
        assertEquals(displayed.bottom / 1092f, actual.bottom, .0001f)
    }

    @Test fun customSliderRefreshesMirrorLayoutWithoutReconnect() {
        AirPlayPersistence.saveClusterMirrorSize(activity, ClusterMirrorSize.CUSTOM)
        ClusterActivityOutput.setMirrorState(true, true, 1940, 1092)
        val settingsController = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(activity, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        try {
            val settings = settingsController.get()
            val card = LinearLayout(settings)
            ReflectionHelpers.callInstanceMethod<Unit>(settings, "clusterMirrorCustomControls",
                ReflectionHelpers.ClassParameter(LinearLayout::class.java, card))
            val slider = (card.getChildAt(0) as LinearLayout).getChildAt(1) as SeekBar
            val listener = SeekBar::class.java.getDeclaredField("mOnSeekBarChangeListener")
                .apply { isAccessible = true }.get(slider) as SeekBar.OnSeekBarChangeListener
            AirPlayPersistence.overlaySettingsListener = { ClusterActivityOutput.refreshMirrorLayout() }
            listener.onProgressChanged(slider, 100, true)
            val video = ReflectionHelpers.getField<ClusterVideoTexture>(activity, "videoTexture")
            val params = video.layoutParams as FrameLayout.LayoutParams
            assertEquals(634, params.leftMargin)
            assertEquals(1286, params.width)
            assertEquals(100, AirPlayPersistence.loadClusterMirrorCustom(activity).x)
            assertFalse(PendingReconnect.isPending(null))
        } finally {
            AirPlayPersistence.overlaySettingsListener = null
            settingsController.pause().stop().destroy()
        }
    }

    @Test fun customFrameUpdatesContentRectImmediatelyWithoutReconnect() {
        AirPlayPersistence.saveClusterMirrorSize(activity, ClusterMirrorSize.CUSTOM)
        AirPlayPersistence.saveClusterMirrorCustom(activity, ClusterMirrorCustomRect(50, 50, 50, 50))
        ClusterActivityOutput.setMirrorState(true, true, 1940, 1092)
        AirPlayPersistence.overlaySettingsListener = { ClusterActivityOutput.refreshMirrorLayout() }
        AirPlayPersistence.saveClusterMirrorView(activity, ClusterMirrorView(200, 20, 100))
        val video = ReflectionHelpers.getField<ClusterVideoTexture>(activity, "videoTexture")
        val params = video.layoutParams as FrameLayout.LayoutParams
        val keep = ClusterMirrorCrop.keep(1940, 1092, null, false, 7)
        val frame = ClusterMirrorCrop.frame(keep, params.width.toFloat() / params.height, 200, 20, 100)
        val actual = ReflectionHelpers.getField<android.graphics.RectF>(video, "contentRect")
        assertEquals(frame.left / 1940f, actual.left, .0001f)
        assertEquals(frame.top / 1092f, actual.top, .0001f)
        assertEquals(frame.right / 1940f, actual.right, .0001f)
        assertEquals(frame.bottom / 1092f, actual.bottom, .0001f)
        assertTrue(ClusterActivityOutput.mirrorAttached)
        assertFalse(PendingReconnect.isPending(null))
    }

    @Test fun mirrorTurnCardAndLanesFollowAttachmentAndSettingsAboveVideo() {
        val guidance = ClusterTurnGuidance(2, 0, 120, "")
        ClusterActivityOutput.setTurnCard(guidance, 50, 40, 100, 85, false)
        ClusterActivityOutput.setLanes(listOf(LaneGuidanceLane(0, 2, 0, null)))
        shadowOf(Looper.getMainLooper()).idle()
        AirPlayPersistence.saveClusterLaneStrip(activity, true)
        ClusterActivityOutput.setMirrorState(true, true, 1940, 1092)
        val card = ReflectionHelpers.getField<ClusterTurnCardView>(activity, "turnCard")
        val video = ReflectionHelpers.getField<ClusterVideoTexture>(activity, "videoTexture")
        val overlays = ReflectionHelpers.getField<FrameLayout>(activity, "overlayRegion")
        val root = overlays.parent as FrameLayout
        assertSame(overlays, card.parent)
        assertTrue(root.indexOfChild(overlays) > root.indexOfChild(video.parent as View))
        assertEquals(View.GONE, card.visibility)
        assertEquals(0, card.displayedLaneCount())

        AirPlayPersistence.overlaySettingsListener = { activity.updateTurnCard() }
        AirPlayPersistence.saveClusterMirrorTurnCard(activity, true)
        assertEquals(View.VISIBLE, card.visibility)
        assertEquals(guidance, ReflectionHelpers.getField<ClusterTurnGuidance>(card, "guidance"))
        assertEquals(1, card.displayedLaneCount())
        AirPlayPersistence.saveClusterLaneStrip(activity, false)
        assertEquals(0, card.displayedLaneCount())
        AirPlayPersistence.saveClusterLaneStrip(activity, true)
        assertEquals(1, card.displayedLaneCount())
        AirPlayPersistence.saveClusterMirrorTurnCard(activity, false)
        assertEquals(View.GONE, card.visibility)
        assertEquals(0, card.displayedLaneCount())
        AirPlayPersistence.saveClusterMirrorTurnCard(activity, true)
        assertEquals(1, card.displayedLaneCount())
        ClusterActivityOutput.setMirrorState(true, false, 1940, 1092)
        assertEquals(View.GONE, card.visibility)
        assertEquals(0, card.displayedLaneCount())
        assertFalse(PendingReconnect.isPending(null))
    }
}
