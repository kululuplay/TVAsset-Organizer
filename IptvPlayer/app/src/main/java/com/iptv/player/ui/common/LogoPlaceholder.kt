/*
 * LogoPlaceholder.kt
 * Generates a colored box with the channel's first initial as a fallback when a
 * logo/poster is missing or fails to load. Never show a broken-image icon.
 */
package com.iptv.player.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.abs

object LogoPlaceholder {

    // Muted palette that fits a dark theme and stays easy on the eyes.
    private val COLORS = intArrayOf(
        0xFF2A3B4C.toInt(), 0xFF3A2C4C.toInt(), 0xFF2C4C3A.toInt(),
        0xFF4C3A2C.toInt(), 0xFF2C3C4C.toInt(), 0xFF44384C.toInt()
    )

    fun forName(context: Context, name: String): Drawable {
        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val color = COLORS[abs(name.hashCode()) % COLORS.size]
        return InitialDrawable(initial, color)
    }

    private class InitialDrawable(
        private val initial: String,
        private val bgColor: Int
    ) : Drawable() {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val textBounds = Rect()

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawRect(b, bgPaint)
            textPaint.textSize = b.height() * 0.5f
            textPaint.getTextBounds(initial, 0, initial.length, textBounds)
            val x = b.exactCenterX()
            val y = b.exactCenterY() - textBounds.exactCenterY()
            canvas.drawText(initial, x, y, textPaint)
        }

        override fun setAlpha(alpha: Int) { bgPaint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            bgPaint.colorFilter = colorFilter
        }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }
}
