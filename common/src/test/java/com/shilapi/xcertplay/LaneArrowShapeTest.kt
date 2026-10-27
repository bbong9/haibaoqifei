package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class LaneArrowShapeTest {
    @Test fun signedAnglesAndTheirBoundariesSelectTheExpectedShape() {
        val cases = mapOf(
            0 to LaneArrowShape.STRAIGHT,
            -20 to LaneArrowShape.STRAIGHT, 20 to LaneArrowShape.STRAIGHT,
            -21 to LaneArrowShape.SLIGHT_LEFT, 21 to LaneArrowShape.SLIGHT_RIGHT,
            -60 to LaneArrowShape.SLIGHT_LEFT, 60 to LaneArrowShape.SLIGHT_RIGHT,
            -61 to LaneArrowShape.LEFT, 61 to LaneArrowShape.RIGHT,
            -135 to LaneArrowShape.LEFT, 135 to LaneArrowShape.RIGHT,
            -136 to LaneArrowShape.U_TURN_LEFT, 136 to LaneArrowShape.U_TURN_RIGHT,
            -180 to LaneArrowShape.U_TURN_LEFT, 180 to LaneArrowShape.U_TURN_RIGHT,
            Int.MIN_VALUE to LaneArrowShape.U_TURN_LEFT, Int.MAX_VALUE to LaneArrowShape.U_TURN_RIGHT,
        )
        cases.forEach { (angle, expected) -> assertEquals("angle=$angle", expected, LaneArrowShape.of(angle)) }
    }
}
