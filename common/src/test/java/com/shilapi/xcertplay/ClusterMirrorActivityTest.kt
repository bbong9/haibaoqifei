package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
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
        ClusterActivityOutput.stopForSettings()
        controller.destroy()
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
        assertEquals(0f, overlays.alpha, 0f)
        assertEquals(Color.TRANSPARENT, (content.background as ColorDrawable).color)
        assertEquals(Color.TRANSPARENT, (activity.window.decorView.background as ColorDrawable).color)
    }
}
