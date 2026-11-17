package com.roadwise.app.routing

import com.roadwise.app.core.Geo
import com.roadwise.app.core.LatLon

enum class TravelMode(val label: String, val valhalla: String, val brouterProfile: String) {
    DRIVE("Drive", "auto", "car-vario"),
    BIKE("Bike", "bicycle", "trekking"),
    WALK("Walk", "pedestrian", "hiking-mountain"),
}

enum class Turn {
    DEPART, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
    UTURN, KEEP_LEFT, KEEP_RIGHT, EXIT_LEFT, EXIT_RIGHT, RAMP_LEFT, RAMP_RIGHT,
    MERGE, ROUNDABOUT, FERRY, ARRIVE
}

data class Maneuver(
    val turn: Turn,
    /** Short text for the banner, e.g. "Turn right onto Main St". */
    val text: String,
    /** Sentence to speak (without the distance prefix). */
    val spoken: String,
    val street: String?,
    val pointIndex: Int,
    val exit: Int = 0,
)

class Route(
    val points: List<LatLon>,
    val maneuvers: List<Maneuver>,
    val durationS: Double,
    /** Seconds elapsed at each point (same size as points). */
    val cumTime: DoubleArray,
    /** Speed limit in km/h for the segment that starts at point i, 0 = unknown. */
    var speedLimits: IntArray,
    val source: String,       // "online" or "offline"
    val label: String,
    val hasTolls: Boolean = false,
    val hasHighways: Boolean = false,
    val hasFerry: Boolean = false,
    val mode: TravelMode = TravelMode.DRIVE,
    val destination: LatLon = points.last(),
) {
    val cumDist: DoubleArray = Geo.cumulative(points)
    val distanceM: Double get() = cumDist.last()

    fun timeAt(segIndex: Int, t: Double): Double {
        if (segIndex >= points.size - 1) return cumTime.last()
        return cumTime[segIndex] + (cumTime[segIndex + 1] - cumTime[segIndex]) * t
    }
}

class RoutingException(msg: String) : Exception(msg)

fun turnFromBearings(delta: Double): Turn {
    // delta: signed change in heading, -180..180 (negative = left)
    val a = kotlin.math.abs(delta)
    return when {
        a < 20 -> Turn.STRAIGHT
        a < 50 -> if (delta < 0) Turn.SLIGHT_LEFT else Turn.SLIGHT_RIGHT
        a < 130 -> if (delta < 0) Turn.LEFT else Turn.RIGHT
        a < 165 -> if (delta < 0) Turn.SHARP_LEFT else Turn.SHARP_RIGHT
        else -> Turn.UTURN
    }
}

fun ordinal(n: Int) = when {
    n % 100 in 11..13 -> "${n}th"
    n % 10 == 1 -> "${n}st"
    n % 10 == 2 -> "${n}nd"
    n % 10 == 3 -> "${n}rd"
    else -> "${n}th"
}

/** Generic wording for a turn when no instruction text is supplied (offline routes). */
fun genericInstruction(turn: Turn, exit: Int, street: String?): String {
    val onto = if (!street.isNullOrBlank()) " onto $street" else ""
    return when (turn) {
        Turn.DEPART -> if (!street.isNullOrBlank()) "Head out on $street" else "Head out"
        Turn.STRAIGHT -> "Continue straight$onto"
        Turn.SLIGHT_LEFT -> "Bear left$onto"
        Turn.LEFT -> "Turn left$onto"
        Turn.SHARP_LEFT -> "Make a sharp left$onto"
        Turn.SLIGHT_RIGHT -> "Bear right$onto"
        Turn.RIGHT -> "Turn right$onto"
        Turn.SHARP_RIGHT -> "Make a sharp right$onto"
        Turn.UTURN -> "Make a U-turn"
        Turn.KEEP_LEFT -> "Keep left$onto"
        Turn.KEEP_RIGHT -> "Keep right$onto"
        Turn.EXIT_LEFT -> "Take the exit on the left"
        Turn.EXIT_RIGHT -> "Take the exit on the right"
        Turn.RAMP_LEFT -> "Take the ramp on the left"
        Turn.RAMP_RIGHT -> "Take the ramp on the right"
        Turn.MERGE -> "Merge$onto"
        Turn.ROUNDABOUT -> if (exit > 0) "At the roundabout, take the ${ordinal(exit)} exit" else "Enter the roundabout"
        Turn.FERRY -> "Board the ferry"
        Turn.ARRIVE -> "Arrive at your destination"
    }
}
