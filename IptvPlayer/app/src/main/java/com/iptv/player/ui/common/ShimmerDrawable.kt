/*
 * ShimmerDrawable.kt
 * A lightweight, dependency-free animated "skeleton" placeholder. It paints the
 * card color and sweeps a diagonal highlight gradient across it to signal that
 * content is loading. Pure Android (ValueAnimator + LinearGradient) so it works
 * on minSdk 21. Use ShimmerDrawable() as a View background, or via SkeletonHelper.
 */
package com.iptv.player.ui.common

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

class ShimmerDrawable(
    private val baseColor: Int = 0xFF1C232D.toInt(),      // matches @color/bg_card
    private val highlightColor: Int = 0xFF2A3340.toInt()  // matches @color/bg_card_focused
) : Drawable() {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = baseColor }
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.drawRect(b, basePaint)

        val width = b.width().toFloat()
        // Sweep the highlight band from off-screen left to off-screen right.
        val translate = -width + (2f * width * progress)
        val gradient = LinearGradient(
            translate, 0f, translate + width, 0f,
            intArrayOf(baseColor, highlightColor, baseColor),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        shimmerPaint.shader = gradient
        canvas.drawRect(b, shimmerPaint)
    }

    fun start() {
        if (!animator.isStarted) animator.start()
    }

    fun stop() {
        if (animator.isStarted) animator.cancel()
    }

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        shimmerPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        shimmerPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Convenience for a transparent-friendly variant. */
        fun default() = ShimmerDrawable(
            baseColor = Color.parseColor("#1C232D"),
            highlightColor = Color.parseColor("#2A3340")
        )
    }
}
