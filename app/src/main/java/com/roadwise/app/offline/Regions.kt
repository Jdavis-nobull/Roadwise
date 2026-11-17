package com.roadwise.app.offline

import com.roadwise.app.core.BBox
import com.roadwise.app.core.LatLon

/** A ready-made download area (whole state), so offline maps are one tap. */
data class Region(val code: String, val name: String, val bbox: BBox)

private fun r(code: String, name: String, minLon: Double, minLat: Double, maxLon: Double, maxLat: Double) =
    Region(code, name, BBox(minLat, minLon, maxLat, maxLon).expand(3_000.0))

val US_STATES = listOf(
    r("AL", "Alabama", -88.47, 30.22, -84.89, 35.01),
    r("AK", "Alaska (mainland)", -165.0, 54.5, -129.98, 71.39),
    r("AZ", "Arizona", -114.82, 31.33, -109.04, 37.00),
    r("AR", "Arkansas", -94.62, 33.00, -89.64, 36.50),
    r("CA", "California", -124.41, 32.53, -114.13, 42.01),
    r("CO", "Colorado", -109.06, 36.99, -102.04, 41.00),
    r("CT", "Connecticut", -73.73, 40.98, -71.79, 42.05),
    r("DE", "Delaware", -75.79, 38.45, -75.05, 39.84),
    r("DC", "District of Columbia", -77.12, 38.79, -76.91, 39.00),
    r("FL", "Florida", -87.63, 24.52, -80.03, 31.00),
    r("GA", "Georgia", -85.61, 30.36, -80.84, 35.00),
    r("HI", "Hawaii", -160.24, 18.91, -154.81, 22.24),
    r("ID", "Idaho", -117.24, 41.99, -111.04, 49.00),
    r("IL", "Illinois", -91.51, 36.97, -87.49, 42.51),
    r("IN", "Indiana", -88.10, 37.77, -84.78, 41.76),
    r("IA", "Iowa", -96.64, 40.38, -90.14, 43.50),
    r("KS", "Kansas", -102.05, 36.99, -94.59, 40.00),
    r("KY", "Kentucky", -89.57, 36.50, -81.96, 39.15),
    r("LA", "Louisiana", -94.04, 28.93, -88.82, 33.02),
    r("ME", "Maine", -71.08, 43.06, -66.95, 47.46),
    r("MD", "Maryland", -79.49, 37.91, -75.05, 39.72),
    r("MA", "Massachusetts", -73.51, 41.24, -69.93, 42.89),
    r("MI", "Michigan", -90.42, 41.70, -82.41, 48.31),
    r("MN", "Minnesota", -97.24, 43.50, -89.49, 49.38),
    r("MS", "Mississippi", -91.66, 30.17, -88.10, 35.00),
    r("MO", "Missouri", -95.77, 35.99, -89.10, 40.61),
    r("MT", "Montana", -116.05, 44.36, -104.04, 49.00),
    r("NE", "Nebraska", -104.05, 40.00, -95.31, 43.00),
    r("NV", "Nevada", -120.01, 35.00, -114.04, 42.00),
    r("NH", "New Hampshire", -72.56, 42.70, -70.61, 45.31),
    r("NJ", "New Jersey", -75.56, 38.93, -73.89, 41.36),
    r("NM", "New Mexico", -109.05, 31.33, -103.00, 37.00),
    r("NY", "New York", -79.76, 40.50, -71.86, 45.02),
    r("NC", "North Carolina", -84.32, 33.84, -75.46, 36.59),
    r("ND", "North Dakota", -104.05, 45.94, -96.55, 49.00),
    r("OH", "Ohio", -84.82, 38.40, -80.52, 41.98),
    r("OK", "Oklahoma", -103.00, 33.62, -94.43, 37.00),
    r("OR", "Oregon", -124.57, 41.99, -116.46, 46.29),
    r("PA", "Pennsylvania", -80.52, 39.72, -74.69, 42.27),
    r("RI", "Rhode Island", -71.86, 41.15, -71.12, 42.02),
    r("SC", "South Carolina", -83.35, 32.03, -78.54, 35.22),
    r("SD", "South Dakota", -104.06, 42.48, -96.44, 45.95),
    r("TN", "Tennessee", -90.31, 34.98, -81.65, 36.68),
    r("TX", "Texas", -106.65, 25.84, -93.51, 36.50),
    r("UT", "Utah", -114.05, 37.00, -109.04, 42.00),
    r("VT", "Vermont", -73.44, 42.73, -71.46, 45.02),
    r("VA", "Virginia", -83.68, 36.54, -75.24, 39.47),
    r("WA", "Washington", -124.85, 45.54, -116.92, 49.00),
    r("WV", "West Virginia", -82.64, 37.20, -77.72, 40.64),
    r("WI", "Wisconsin", -92.89, 42.49, -86.25, 47.31),
    r("WY", "Wyoming", -111.06, 40.99, -104.05, 45.01),
)

/** The state you're in (smallest box containing you), or null outside the US. */
fun stateAt(p: LatLon): Region? = US_STATES.filter { it.bbox.contains(p) }
    .minByOrNull { (it.bbox.maxLat - it.bbox.minLat) * (it.bbox.maxLon - it.bbox.minLon) }
