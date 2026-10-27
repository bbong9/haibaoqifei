package com.shilapi.xcertplay

internal enum class LaneArrowShape {
    STRAIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, LEFT, RIGHT, U_TURN_LEFT, U_TURN_RIGHT;

    companion object {
        fun of(angle: Int): LaneArrowShape = when (angle) {
            in -20..20 -> STRAIGHT
            in -60..-21 -> SLIGHT_LEFT
            in 21..60 -> SLIGHT_RIGHT
            in -135..-61 -> LEFT
            in 61..135 -> RIGHT
            in Int.MIN_VALUE..-136 -> U_TURN_LEFT
            else -> U_TURN_RIGHT
        }
    }
}
