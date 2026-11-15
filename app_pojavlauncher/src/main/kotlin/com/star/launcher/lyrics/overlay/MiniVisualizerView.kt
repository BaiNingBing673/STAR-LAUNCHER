package com.star.launcher.lyrics.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.star.launcher.lyrics.config.AnimationQuality
import kotlin.math.PI
import kotlin.math.sin

/**
 * Lightweight 4-bar decorative waveform indicator.
 * Does not use microphone permissions; gently animates only while visible, playing, and allowed by quality settings.
 */
class MiniVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF5EE2FF.toInt()
    }
    private val barRect = RectF()
    private val barHeights = floatArrayOf(0.35f, 0.72f, 0.50f, 0.85f)
    private val phaseOffsets = floatArrayOf(0f, 1.4f, 2.7f, 4.1f)
    private val frequencies = floatArrayOf(1.0f, 1.35f, 1.15f, 1.55f)

    private var animator: ValueAnimator? = null
    private var phase = 0f
    private var lastFrameMs = 0L
    private var isPlaying = false
    private var animationsEnabled = true
    private var quality = AnimationQuality.HIGH
    private var speedMultiplier = 1.0f

    fun setAccentColor(color: Int) {
        if (barPaint.color != color) {
            barPaint.color = color
            invalidate()
        }
    }

    fun setPlaybackAndQuality(
        playing: Boolean,
        animEnabled: Boolean,
        animQuality: AnimationQuality,
        speed: Float
    ) {
        isPlaying = playing
        animationsEnabled = animEnabled
        quality = animQuality
        speedMultiplier = speed.coerceIn(0.5f, 2.0f)
        syncAnimatorState()
    }

    fun stopAnimations() {
        animator?.cancel()
        animator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimatorState()
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncAnimatorState()
    }

    private fun syncAnimatorState() {
        val shouldAnimate = isAttachedToWindow &&
            visibility == VISIBLE &&
            isPlaying &&
            animationsEnabled &&
            quality != AnimationQuality.BATTERY_SAVER

        if (!shouldAnimate) {
            stopAnimations()
            if (!isPlaying) {
                barHeights[0] = 0.28f
                barHeights[1] = 0.42f
                barHeights[2] = 0.34f
                barHeights[3] = 0.25f
            } else {
                barHeights[0] = 0.45f
                barHeights[1] = 0.85f
                barHeights[2] = 0.62f
                barHeights[3] = 0.74f
            }
            invalidate()
            return
        }

        if (animator?.isRunning == true) return
        val minFrameIntervalMs = if (quality == AnimationQuality.BALANCED) 33L else 16L
        lastFrameMs = 0L
        animator = ValueAnimator.ofFloat(0f, (2.0 * PI).toFloat()).apply {
            duration = (1_400L / speedMultiplier).toLong().coerceIn(500L, 2_800L)
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val now = anim.currentPlayTime
                if (lastFrameMs != 0L && (now - lastFrameMs) in 0 until minFrameIntervalMs) {
                    return@addUpdateListener
                }
                lastFrameMs = now
                phase = anim.animatedValue as Float
                for (i in 0 until 4) {
                    val wave = sin(phase * frequencies[i] + phaseOffsets[i])
                    barHeights[i] = 0.26f + 0.72f * ((wave + 1f) * 0.5f)
                }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return

        val barCount = 4
        val gap = (w * 0.14f).coerceAtLeast(1.5f)
        val barWidth = ((w - gap * (barCount - 1)) / barCount).coerceAtLeast(2f)
        val radius = barWidth * 0.5f
        val baseBottom = (height - paddingBottom).toFloat()

        for (i in 0 until barCount) {
            val left = paddingLeft + i * (barWidth + gap)
            val bh = (h * barHeights[i]).coerceAtLeast(barWidth)
            barRect.set(left, baseBottom - bh, left + barWidth, baseBottom)
            canvas.drawRoundRect(barRect, radius, radius, barPaint)
        }
    }
}
