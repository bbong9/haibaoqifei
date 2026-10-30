package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
class ClusterTurnCardLaneTest {
    private val guidance = ClusterTurnGuidance(2, 0, 350, "Main Street",
        remainingMeters = 4200L, remainingSeconds = 630L)
    private val four = listOf(
        LaneGuidanceLane(0, 0, 0, null), LaneGuidanceLane(1, 0, 90, null),
        LaneGuidanceLane(2, 2, 0, 90), LaneGuidanceLane(3, 0, 0, null),
    )

    private fun view(width: Int = 1920, height: Int = 720): ClusterTurnCardView =
        ClusterTurnCardView(RuntimeEnvironment.getApplication()).apply {
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            layout(0, 0, width, height)
            setGuidance(guidance)
        }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun noLanesShowOnlyTheExpectedCapsule() {
        val view = view()
        assertNull(view.laneRowRect())
        val expected = ClusterTurnCardOverlay.card(1920, 720, 76, 30, 55)
        assertEquals(expected, view.cardRect())
        val image = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        val centerX = expected.left + expected.width / 2
        assertNotEquals(Color.TRANSPARENT, image.getPixel(centerX, expected.top + expected.height - 3))
        assertEquals(Color.TRANSPARENT, image.getPixel(centerX, expected.top - 3))
        assertEquals(Color.TRANSPARENT, image.getPixel(centerX, expected.top + expected.height + 3))
        image.recycle()
    }

    @Test fun fourLanesFitImmediatelyBelowTheCapsule() {
        val view = view()
        view.setLanes(four)
        val card = ClusterTurnCardOverlay.card(1920, 720, 76, 30, 55)
        val row = view.laneRowRect()!!
        assertEquals(card.top + card.height + card.height * ClusterTurnCardView.LANE_GAP_FRACTION, row.top, 0.001f)
        assertEquals(card.height * 0.42f, row.height(), 0.001f)
        assertTrue(row.top >= card.top + card.height)
        assertTrue(row.bottom <= view.height)
    }

    @Test fun largeCardAtBottomKeepsTheLaneStripBelowTheCapsule() {
        val view = view()
        view.setLayout(76, 72, 95)
        val card = ClusterTurnCardOverlay.card(1920, 720, 76, 72, 95)
        view.setLanes(four)
        val row = view.laneRowRect()!!
        assertEquals(card.top + card.height + card.height * ClusterTurnCardView.LANE_GAP_FRACTION, row.top, 0.001f)
        assertTrue(row.bottom <= view.height)
    }

    @Test fun onlyTheFirstEightLanesAreDrawnAndGuidanceIsRequired() {
        val view = view()
        view.setLanes((0..9).map { LaneGuidanceLane(it, 0, 0, null) })
        assertEquals(8, view.displayedLaneCount())
        assertNotNull(view.laneRowRect())
        view.setGuidance(null)
        assertEquals(0, view.displayedLaneCount())
        assertNull(view.laneRowRect())
    }

    @Test fun aNarrowPanelClampsTheLaneRowToThePanelEdges() {
        val view = view(180, 720)
        view.setLanes((0..7).map { LaneGuidanceLane(it, 0, 0, null) })
        val row = view.laneRowRect()!!
        assertTrue(row.left >= 0f)
        assertTrue(row.right <= 180f)
    }

    @Test fun extremelyNarrowPanelsKeepTheLaneRowInsideThePanel() {
        for (width in 1..8) {
            val view = view(width, 720)
            view.setLanes(four)
            val row = view.laneRowRect()!!
            assertTrue("width=$width left=${row.left}", row.left >= 0f)
            assertTrue("width=$width right=${row.right}", row.right <= width.toFloat())
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun laneStripScreenshots() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val project = if (root.name == "common") root.parentFile else root
        val directory = File(project, "build/lane-strip").apply { mkdirs() }
        val view = view()
        fun capture(name: String, night: Boolean, lanes: List<LaneGuidanceLane>) {
            view.setNightMode(night)
            view.setLanes(lanes)
            val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (night) Color.rgb(29, 34, 41) else Color.rgb(210, 221, 230))
            view.draw(canvas)
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        capture("night-four-lanes", true, four)
        capture("day-four-lanes", false, four)
        capture("eight-lanes", true, (0..7).map { LaneGuidanceLane(it, if (it == 3) 2 else 0, (it - 3) * 30, null) })
        capture("no-lanes", true, emptyList())
    }
}
