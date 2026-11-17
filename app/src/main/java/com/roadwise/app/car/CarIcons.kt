package com.roadwise.app.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.core.graphics.PathParser
import androidx.core.graphics.drawable.IconCompat
import com.roadwise.app.routing.Turn

/** Icons for Android Auto, drawn in code (Material icon paths) so no image files are needed. */
object CarIcons {
    const val SEARCH = "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"
    const val ZOOM_IN = "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"
    const val ZOOM_OUT = "M19,13H5v-2h14v2z"
    const val MY_LOCATION = "M12,8c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4 -1.79,-4 -4,-4zM20.94,11c-0.46,-4.17 -3.77,-7.48 -7.94,-7.94V1h-2v2.06C6.83,3.52 3.52,6.83 3.06,11H1v2h2.06c0.46,4.17 3.77,7.48 7.94,7.94V23h2v-2.06c4.17,-0.46 7.48,-3.77 7.94,-7.94H23v-2h-2.06zM12,19c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z"
    const val REPORT = "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z"
    const val HOME = "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z"
    const val WORK = "M20,6h-4V4c0,-1.11 -0.89,-2 -2,-2h-4c-1.11,0 -2,0.89 -2,2v2H4c-1.11,0 -1.99,0.89 -1.99,2L2,19c0,1.11 0.89,2 2,2h16c1.11,0 2,-0.89 2,-2V8c0,-1.11 -0.89,-2 -2,-2zM14,6h-4V4h4v2z"
    const val HISTORY = "M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08V8H12z"
    const val PLACE = "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7zM12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z"
    const val STAR = "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z"

    /** Tinted vector icon from a 24x24 Material path. */
    fun icon(path: String, tint: CarColor? = null): CarIcon {
        val b = CarIcon.Builder(IconCompat.createWithBitmap(pathBitmap(path, Color.WHITE)))
        if (tint != null) b.setTint(tint)
        return b.build()
    }

    private fun pathBitmap(data: String, color: Int, size: Int = 96): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = PathParser.createPathFromPathData(data)
        val m = Matrix().apply { setScale(size / 24f, size / 24f) }
        p.transform(m)
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        return bmp
    }

    /** Emoji in a coloured circle (used for report types). */
    fun emoji(e: String, bg: Int): CarIcon {
        val s = 128
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawCircle(s / 2f, s / 2f, s / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = s * 0.55f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
        val fm = tp.fontMetrics
        c.drawText(e, s / 2f, s / 2f - (fm.ascent + fm.descent) / 2, tp)
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }

    /** White turn arrow for the maneuver card (angle 0 = straight, negative = left). */
    fun turn(t: Turn): CarIcon {
        val s = 128
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = s * 0.11f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        when (t) {
            Turn.ARRIVE -> {
                c.drawLine(s * 0.35f, s * 0.92f, s * 0.35f, s * 0.12f, p)
                c.drawPath(Path().apply { moveTo(s * 0.35f, s * 0.12f); lineTo(s * 0.85f, s * 0.27f); lineTo(s * 0.35f, s * 0.45f); close() }, fill)
            }
            Turn.ROUNDABOUT -> {
                c.drawCircle(s * 0.5f, s * 0.45f, s * 0.2f, p)
                c.drawLine(s * 0.5f, s * 0.92f, s * 0.5f, s * 0.65f, p)
                arrowHead(c, fill, s * 0.5f + s * 0.2f * 0.7f, s * 0.45f - s * 0.2f * 0.7f, 45f, s)
            }
            Turn.UTURN -> {
                val path = Path().apply {
                    moveTo(s * 0.65f, s * 0.92f); lineTo(s * 0.65f, s * 0.4f)
                    arcTo(RectF(s * 0.3f, s * 0.2f, s * 0.65f, s * 0.55f), 0f, -180f)
                    lineTo(s * 0.3f, s * 0.62f)
                }
                c.drawPath(path, p)
                arrowHead(c, fill, s * 0.3f, s * 0.72f, 180f, s)
            }
            else -> {
                val angle = when (t) {
                    Turn.SLIGHT_LEFT, Turn.KEEP_LEFT, Turn.RAMP_LEFT, Turn.EXIT_LEFT -> -40f
                    Turn.LEFT -> -90f
                    Turn.SHARP_LEFT -> -135f
                    Turn.SLIGHT_RIGHT, Turn.KEEP_RIGHT, Turn.RAMP_RIGHT, Turn.EXIT_RIGHT -> 40f
                    Turn.RIGHT -> 90f
                    Turn.SHARP_RIGHT -> 135f
                    else -> 0f
                }
                // Stem up from the bottom to the junction, then a leg in the turn direction.
                val jx = s * 0.5f; val jy = s * 0.5f
                c.drawLine(jx, s * 0.92f, jx, jy, p)
                val rad = Math.toRadians(angle.toDouble())
                val len = s * 0.32f
                val ex = jx + (Math.sin(rad) * len).toFloat(); val ey = jy - (Math.cos(rad) * len).toFloat()
                c.drawLine(jx, jy, ex, ey, p)
                arrowHead(c, fill, ex, ey, angle, s)
            }
        }
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }

    private fun arrowHead(c: Canvas, p: Paint, x: Float, y: Float, angleDeg: Float, s: Int) {
        c.save()
        c.translate(x, y); c.rotate(angleDeg)
        c.drawPath(Path().apply { moveTo(0f, -s * 0.13f); lineTo(s * 0.13f, s * 0.04f); lineTo(-s * 0.13f, s * 0.04f); close() }, p)
        c.restore()
    }
}
