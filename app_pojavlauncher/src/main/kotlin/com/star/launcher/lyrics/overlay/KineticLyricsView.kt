package com.star.launcher.lyrics.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.PathInterpolator
import com.star.launcher.lyrics.config.AnimationIntensity
import com.star.launcher.lyrics.config.AnimationQuality
import com.star.launcher.lyrics.engine.LyricWordSpan
import com.star.launcher.lyrics.engine.LyricsFormat
import com.star.launcher.lyrics.engine.SyncConfidence
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lightweight kinetic lyrics renderer supporting compact single-line transitions,
 * lyrics-only minimal mode, and expanded 3-line (previous / current / next) views
 * plus a synchronized moving musical note (`♪`) indicator.
 *
 * Pre-allocates Paint objects and performs zero per-frame allocations in [onDraw].
 */
class KineticLyricsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val prevPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x7A96A2BA
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val currPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F7FF.toInt()
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val nextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99B4C0D8.toInt()
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val outgoingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F7FF.toInt()
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val wordActivePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5EE2FF.toInt()
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val noteTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5EE2FF.toInt()
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val noteGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x335EE2FF
    }
    private val noteRailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x28FFFFFF
    }
    private val noteFillRailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x995EE2FF.toInt()
    }
    private val noteUnsyncedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB8C7D2E5.toInt()
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val railRect = RectF()
    private val fillRailRect = RectF()

    private var expandedMode = false
    private var lyricsOnlyMode = false
    private var showMusicalNote = true
    private var fontSizeSp = 13.5f
    private var accentColor = 0xFF5EE2FF.toInt()
    private var animationsEnabled = true
    private var quality = AnimationQuality.HIGH
    private var intensity = AnimationIntensity.NORMAL
    private var speedMultiplier = 1.0f
    private var noteSpeedMultiplier = 1.0f

    private var previousLineText: String? = null
    private var currentLineText: String = ""
    private var nextLineText: String? = null
    private var outgoingLineText: String? = null

    private var lyricsFormat: LyricsFormat = LyricsFormat.NONE
    private var syncConfidence: SyncConfidence = SyncConfidence.UNAVAILABLE
    private var words: List<LyricWordSpan> = emptyList()
    private var activeWordIndex: Int = -1
    private var wordProgress: Float = 0f
    private var lineProgress: Float = 0f

    private var transitionProgress = 1f
    private var targetNoteFraction = 0f
    private var smoothedNoteFraction = 0f
    private var decorativePhase = 0f
    private var isActivelyPlaying = false
    private var transitionAnimator: ValueAnimator? = null
    private var noteGlideAnimator: ValueAnimator? = null
    private val easeCurve = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    init {
        applyFontSizes()
    }

    fun configure(
        expanded: Boolean,
        lyricsOnly: Boolean = false,
        showNote: Boolean = true,
        lyricsFontSizeSp: Float,
        accent: Int,
        animEnabled: Boolean,
        animQuality: AnimationQuality,
        animIntensity: AnimationIntensity = AnimationIntensity.NORMAL,
        speed: Float,
        noteSpeed: Float = 1.0f
    ) {
        var layoutDirty = false
        if (expandedMode != expanded) {
            expandedMode = expanded
            layoutDirty = true
        }
        if (lyricsOnlyMode != lyricsOnly) {
            lyricsOnlyMode = lyricsOnly
            layoutDirty = true
        }
        if (showMusicalNote != showNote) {
            showMusicalNote = showNote
            layoutDirty = true
        }
        val clampedSp = lyricsFontSizeSp.coerceIn(10.5f, 22f)
        if (fontSizeSp != clampedSp) {
            fontSizeSp = clampedSp
            applyFontSizes()
            layoutDirty = true
        }
        if (accentColor != accent) {
            accentColor = accent
            wordActivePaint.color = accent
            noteTextPaint.color = accent
            noteGlowPaint.color = (accent and 0x00FFFFFF) or 0x38000000
            noteFillRailPaint.color = (accent and 0x00FFFFFF) or 0xA8000000.toInt()
            layoutDirty = true
        }
        animationsEnabled = animEnabled
        quality = animQuality
        intensity = animIntensity
        speedMultiplier = speed.coerceIn(0.5f, 2.0f)
        noteSpeedMultiplier = noteSpeed.coerceIn(0.4f, 2.2f)
        if (!showMusicalNote || !animationsEnabled || quality == AnimationQuality.BATTERY_SAVER) {
            noteGlideAnimator?.cancel()
            smoothedNoteFraction = targetNoteFraction
        }
        if (layoutDirty) {
            invalidate()
        }
    }

    fun updateLyrics(
        previous: String?,
        current: String?,
        next: String?,
        activeWords: List<LyricWordSpan>,
        wordIndex: Int,
        currentWordProgress: Float,
        currentLineProgress: Float = 0f,
        currentFormat: LyricsFormat = LyricsFormat.NONE,
        currentConfidence: SyncConfidence = SyncConfidence.UNAVAILABLE,
        playing: Boolean = false
    ) {
        val cleanCurrent = current.orEmpty()
        val clampedLineProg = currentLineProgress.coerceIn(0f, 1f)
        val lineChanged = cleanCurrent != currentLineText ||
            previous != previousLineText ||
            next != nextLineText

        val wordChanged = activeWords !== words ||
            wordIndex != activeWordIndex ||
            abs(currentWordProgress - wordProgress) >= 0.03f

        val progressChanged = abs(clampedLineProg - lineProgress) >= 0.015f ||
            currentFormat != lyricsFormat ||
            currentConfidence != syncConfidence ||
            playing != isActivelyPlaying

        if (!lineChanged && !wordChanged && !progressChanged) return

        val textSwitched = cleanCurrent != currentLineText
        previousLineText = previous
        nextLineText = next
        words = activeWords
        activeWordIndex = wordIndex
        wordProgress = currentWordProgress.coerceIn(0f, 1f)
        lineProgress = clampedLineProg
        lyricsFormat = currentFormat
        syncConfidence = currentConfidence
        isActivelyPlaying = playing

        val computedFraction = computeTargetNoteFraction(
            format = currentFormat,
            confidence = currentConfidence,
            words = activeWords,
            activeWordIndex = wordIndex,
            wordProgress = wordProgress,
            lineProgress = clampedLineProg
        )

        if ((currentConfidence == SyncConfidence.PLAIN_UNSYNCED || currentFormat == LyricsFormat.PLAIN) && playing) {
            decorativePhase = (decorativePhase + 0.14f * noteSpeedMultiplier) % (2f * Math.PI.toFloat())
        }

        if (textSwitched) {
            outgoingLineText = currentLineText.takeIf { it.isNotEmpty() }
            currentLineText = cleanCurrent
            smoothedNoteFraction = if (animationsEnabled && quality != AnimationQuality.BATTERY_SAVER) 0f else computedFraction
            targetNoteFraction = computedFraction
            startLineTransition()
            animateNoteToward(computedFraction, isLineReset = true)
        } else {
            targetNoteFraction = computedFraction
            animateNoteToward(computedFraction, isLineReset = false)
            invalidate()
        }
    }

    fun stopAnimations() {
        transitionAnimator?.cancel()
        transitionAnimator = null
        noteGlideAnimator?.cancel()
        noteGlideAnimator = null
        transitionProgress = 1f
        smoothedNoteFraction = targetNoteFraction
        outgoingLineText = null
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }

    private fun startLineTransition() {
        transitionAnimator?.cancel()
        val shouldAnimate = isAttachedToWindow &&
            visibility == VISIBLE &&
            animationsEnabled &&
            quality != AnimationQuality.BATTERY_SAVER

        if (!shouldAnimate) {
            transitionProgress = 1f
            outgoingLineText = null
            invalidate()
            return
        }

        val intensityScale = when (intensity) {
            AnimationIntensity.SUBTLE -> 0.8f
            AnimationIntensity.NORMAL -> 1.0f
            AnimationIntensity.DYNAMIC -> 1.15f
        }
        val dur = ((if (quality == AnimationQuality.BALANCED) 180L else 260L) / speedMultiplier * intensityScale)
            .toLong()
            .coerceIn(90L, 480L)

        transitionProgress = 0f
        transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = easeCurve
            addUpdateListener {
                transitionProgress = it.animatedValue as Float
                if (transitionProgress >= 0.999f) {
                    outgoingLineText = null
                }
                invalidate()
            }
            start()
        }
    }

    private fun animateNoteToward(destination: Float, isLineReset: Boolean) {
        if (!showMusicalNote || !isAttachedToWindow || visibility != VISIBLE ||
            !animationsEnabled || quality == AnimationQuality.BATTERY_SAVER
        ) {
            noteGlideAnimator?.cancel()
            smoothedNoteFraction = destination
            return
        }
        val delta = abs(destination - smoothedNoteFraction)
        if (delta < 0.01f && !isLineReset) {
            smoothedNoteFraction = destination
            return
        }
        noteGlideAnimator?.cancel()
        val startVal = smoothedNoteFraction
        val baseDur = if (isLineReset) 210L else 120L
        val dur = (baseDur / noteSpeedMultiplier).toLong().coerceIn(55L, 380L)
        noteGlideAnimator = ValueAnimator.ofFloat(startVal, destination).apply {
            duration = dur
            interpolator = easeCurve
            addUpdateListener {
                smoothedNoteFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun applyFontSizes() {
        val dm = resources.displayMetrics
        val currPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, dm)
        val subPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, (fontSizeSp - 2.0f).coerceAtLeast(9.5f), dm)
        val notePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, (fontSizeSp * 0.95f).coerceIn(10f, 19f), dm)
        currPaint.textSize = currPx
        outgoingPaint.textSize = currPx
        wordActivePaint.textSize = currPx
        prevPaint.textSize = subPx
        nextPaint.textSize = subPx
        noteTextPaint.textSize = notePx
        noteUnsyncedPaint.textSize = notePx
    }

    override fun onDraw(canvas: Canvas) {
        val availWidth = (width - paddingLeft - paddingRight).toFloat()
        val availHeight = (height - paddingTop - paddingBottom).toFloat()
        if (availWidth <= 0f || availHeight <= 0f) return

        val x = paddingLeft.toFloat()
        val motionFactor = when {
            !animationsEnabled || quality == AnimationQuality.BATTERY_SAVER -> 0f
            intensity == AnimationIntensity.SUBTLE -> 0.55f
            intensity == AnimationIntensity.DYNAMIC -> 1.3f
            else -> 1.0f
        }
        val slideDist = currPaint.textSize * 0.36f * motionFactor * (1f - transitionProgress)

        if (!expandedMode) {
            drawSingleLineStage(canvas, x, availWidth, availHeight, slideDist, motionFactor)
            return
        }

        // Expanded mode: 3-line kinetic stack (Previous / Current / Next)
        val noteBandPx = if (showMusicalNote) (11f * resources.displayMetrics.density) else 0f
        val stackHeight = max(1f, availHeight - noteBandPx)
        val rowHeight = stackHeight / 3f
        val prevBase = paddingTop + rowHeight * 0.5f - (prevPaint.descent() + prevPaint.ascent()) * 0.5f
        val currBase = paddingTop + rowHeight * 1.5f - (currPaint.descent() + currPaint.ascent()) * 0.5f
        val nextBase = paddingTop + noteBandPx + rowHeight * 2.5f - (nextPaint.descent() + nextPaint.ascent()) * 0.5f

        previousLineText?.takeIf { it.isNotEmpty() }?.let { prev ->
            prevPaint.alpha = (85 + (transitionProgress * 55f)).toInt().coerceIn(0, 150)
            val ellip = TextUtils.ellipsize(prev, prevPaint, availWidth, TextUtils.TruncateAt.END)
            canvas.drawText(ellip, 0, ellip.length, x, prevBase + slideDist * 0.4f, prevPaint)
        }

        val out = outgoingLineText
        if (out != null && transitionProgress < 0.95f) {
            outgoingPaint.alpha = ((1f - transitionProgress) * 120f).toInt().coerceIn(0, 255)
            val outEllip = TextUtils.ellipsize(out, outgoingPaint, availWidth, TextUtils.TruncateAt.END)
            canvas.drawText(outEllip, 0, outEllip.length, x, currBase - rowHeight * 0.35f * transitionProgress, outgoingPaint)
        }

        var drawnLineWidth = availWidth
        if (currentLineText.isNotEmpty()) {
            currPaint.alpha = (90 + transitionProgress * 165f).toInt().coerceIn(0, 255)
            drawnLineWidth = drawCurrentLine(canvas, x, currBase + slideDist, availWidth, centered = false)
        }

        if (showMusicalNote && currentLineText.isNotEmpty()) {
            val laneY = paddingTop + rowHeight * 2.0f + noteBandPx * 0.45f
            drawMovingMusicalNote(
                canvas = canvas,
                spanStartX = x,
                spanWidth = max(24f, min(availWidth, drawnLineWidth)),
                laneCenterY = laneY,
                motionFactor = motionFactor
            )
        }

        nextLineText?.takeIf { it.isNotEmpty() }?.let { nxt ->
            nextPaint.alpha = (115 + transitionProgress * 55f).toInt().coerceIn(0, 180)
            val ellip = TextUtils.ellipsize(nxt, nextPaint, availWidth, TextUtils.TruncateAt.END)
            canvas.drawText(ellip, 0, ellip.length, x, nextBase + slideDist * 0.6f, nextPaint)
        }
    }

    private fun drawSingleLineStage(
        canvas: Canvas,
        x: Float,
        availWidth: Float,
        availHeight: Float,
        slideDist: Float,
        motionFactor: Float
    ) {
        val density = resources.displayMetrics.density
        val reserveNoteLane = showMusicalNote && currentLineText.isNotEmpty()
        val noteLaneH = if (reserveNoteLane) 11f * density else 0f
        val textAreaH = max(1f, availHeight - noteLaneH)
        val centerBaseline = paddingTop + textAreaH * 0.5f - (currPaint.descent() + currPaint.ascent()) * 0.5f

        val out = outgoingLineText
        if (out != null && transitionProgress < 0.99f) {
            outgoingPaint.alpha = ((1f - transitionProgress) * 175f).toInt().coerceIn(0, 255)
            val outEllip = TextUtils.ellipsize(out, outgoingPaint, availWidth, TextUtils.TruncateAt.END)
            val outW = outgoingPaint.measureText(outEllip, 0, outEllip.length)
            val outX = if (lyricsOnlyMode) x + max(0f, (availWidth - outW) * 0.5f) else x
            val outY = centerBaseline - currPaint.textSize * 0.34f * motionFactor * transitionProgress
            canvas.drawText(outEllip, 0, outEllip.length, outX, outY, outgoingPaint)
        }

        var drawnStartX = x
        var drawnWidth = availWidth * 0.6f
        if (currentLineText.isNotEmpty()) {
            currPaint.alpha = (transitionProgress * 255f).toInt().coerceIn(0, 255)
            val measured = min(availWidth, currPaint.measureText(currentLineText))
            drawnWidth = measured
            if (lyricsOnlyMode) {
                drawnStartX = x + max(0f, (availWidth - measured) * 0.5f)
            }
            drawCurrentLine(
                canvas = canvas,
                x = x,
                baseline = centerBaseline + slideDist,
                availWidth = availWidth,
                centered = lyricsOnlyMode
            )
        }

        if (reserveNoteLane) {
            val laneCenterY = paddingTop + textAreaH + noteLaneH * 0.52f
            drawMovingMusicalNote(
                canvas = canvas,
                spanStartX = drawnStartX,
                spanWidth = max(32f * density, drawnWidth),
                laneCenterY = laneCenterY,
                motionFactor = motionFactor
            )
        }
    }

    private fun drawMovingMusicalNote(
        canvas: Canvas,
        spanStartX: Float,
        spanWidth: Float,
        laneCenterY: Float,
        motionFactor: Float
    ) {
        val density = resources.displayMetrics.density
        val entryScale = (0.35f + 0.65f * transitionProgress).coerceIn(0f, 1f)
        val enterOffset = -(10f * density) * motionFactor * (1f - transitionProgress)
        val verticalFloat = sin(decorativePhase) * (1.2f * density) * motionFactor

        val isUnsynced = syncConfidence == SyncConfidence.PLAIN_UNSYNCED ||
            syncConfidence == SyncConfidence.UNAVAILABLE ||
            lyricsFormat == LyricsFormat.PLAIN ||
            lyricsFormat == LyricsFormat.NONE

        if (isUnsynced) {
            // Clearly distinct decorative ambient mode: stays gently anchored near start without faking sync progress.
            val drift = if (isActivelyPlaying) sin(decorativePhase) * (3.5f * density) * motionFactor else 0f
            val noteX = spanStartX + 7f * density + drift + enterOffset
            val noteAlpha = ((135 + 45 * sin(decorativePhase)) * transitionProgress).toInt().coerceIn(60, 200)
            noteUnsyncedPaint.alpha = noteAlpha
            val glyphBaseline = laneCenterY - (noteUnsyncedPaint.descent() + noteUnsyncedPaint.ascent()) * 0.5f + verticalFloat * 0.5f
            canvas.drawText("♪", noteX, glyphBaseline, noteUnsyncedPaint)
            return
        }

        val trackLeft = spanStartX
        val trackRight = spanStartX + spanWidth
        val trackHalfH = 1.0f * density
        railRect.set(trackLeft, laneCenterY - trackHalfH, trackRight, laneCenterY + trackHalfH)
        noteRailPaint.alpha = (55 * transitionProgress).toInt().coerceIn(0, 75)
        canvas.drawRoundRect(railRect, trackHalfH, trackHalfH, noteRailPaint)

        val progress = smoothedNoteFraction.coerceIn(0f, 1f)
        val noteX = (trackLeft + spanWidth * progress + enterOffset).coerceIn(trackLeft, trackRight)

        fillRailRect.set(trackLeft, laneCenterY - trackHalfH, max(trackLeft, noteX), laneCenterY + trackHalfH)
        noteFillRailPaint.alpha = (165 * transitionProgress).toInt().coerceIn(0, 210)
        canvas.drawRoundRect(fillRailRect, trackHalfH, trackHalfH, noteFillRailPaint)

        if (quality != AnimationQuality.BATTERY_SAVER && animationsEnabled) {
            val glowRadius = (6.5f * density) * entryScale * (0.9f + 0.12f * motionFactor)
            noteGlowPaint.alpha = (54 * transitionProgress).toInt().coerceIn(0, 72)
            canvas.drawCircle(noteX, laneCenterY, glowRadius, noteGlowPaint)
        }

        noteTextPaint.alpha = (235 * transitionProgress).toInt().coerceIn(0, 255)
        val baseline = laneCenterY - (noteTextPaint.descent() + noteTextPaint.ascent()) * 0.5f +
            (if (isActivelyPlaying) verticalFloat else 0f)
        canvas.drawText("♪", noteX, baseline, noteTextPaint)
    }

    private fun drawCurrentLine(
        canvas: Canvas,
        x: Float,
        baseline: Float,
        availWidth: Float,
        centered: Boolean
    ): Float {
        if (words.isEmpty() || activeWordIndex < 0 || lyricsFormat != LyricsFormat.WORD_SYNCED) {
            val ellip = TextUtils.ellipsize(currentLineText, currPaint, availWidth, TextUtils.TruncateAt.END)
            val measured = currPaint.measureText(ellip, 0, ellip.length)
            val drawX = if (centered) x + max(0f, (availWidth - measured) * 0.5f) else x
            canvas.drawText(ellip, 0, ellip.length, drawX, baseline, currPaint)
            return measured
        }

        // Word-level highlighting sweep without allocating per-frame arrays
        val totalWidth = currPaint.measureText(currentLineText)
        if (totalWidth > availWidth) {
            val ellip = TextUtils.ellipsize(currentLineText, currPaint, availWidth, TextUtils.TruncateAt.END)
            val measured = currPaint.measureText(ellip, 0, ellip.length)
            val drawX = if (centered) x + max(0f, (availWidth - measured) * 0.5f) else x
            canvas.drawText(ellip, 0, ellip.length, drawX, baseline, currPaint)
            return measured
        }

        var cursorX = if (centered) x + (availWidth - totalWidth) * 0.5f else x
        for (i in words.indices) {
            val seg = words[i].text
            val segW = currPaint.measureText(seg)
            when {
                i < activeWordIndex -> {
                    wordActivePaint.alpha = currPaint.alpha
                    canvas.drawText(seg, cursorX, baseline, wordActivePaint)
                }
                i == activeWordIndex -> {
                    val litWidth = (segW * wordProgress.coerceIn(0f, 1f))
                    val baseAlpha = currPaint.alpha
                    currPaint.alpha = (baseAlpha * 0.72f).toInt()
                    canvas.drawText(seg, cursorX, baseline, currPaint)
                    currPaint.alpha = baseAlpha
                    if (litWidth > 0.5f) {
                        val saveCount = canvas.save()
                        canvas.clipRect(cursorX, baseline + currPaint.ascent(), cursorX + litWidth, baseline + currPaint.descent())
                        wordActivePaint.alpha = baseAlpha
                        canvas.drawText(seg, cursorX, baseline, wordActivePaint)
                        canvas.restoreToCount(saveCount)
                    }
                }
                else -> {
                    val savedAlpha = currPaint.alpha
                    currPaint.alpha = (savedAlpha * 0.65f).toInt()
                    canvas.drawText(seg, cursorX, baseline, currPaint)
                    currPaint.alpha = savedAlpha
                }
            }
            cursorX += segW
        }
        return totalWidth
    }

    companion object {
        /**
         * Pure progression calculation used by [KineticLyricsView] and verified in unit tests:
         * - [LyricsFormat.WORD_SYNCED]: Tracks exact word position smoothly across total words.
         * - [LyricsFormat.LINE_SYNCED]: Tracks [lineProgress] accurately without fabricating word splits.
         * - [LyricsFormat.PLAIN] / [LyricsFormat.NONE] / [SyncConfidence.PLAIN_UNSYNCED]: Returns 0f (no fake timeline progression).
         */
        fun computeTargetNoteFraction(
            format: LyricsFormat,
            confidence: SyncConfidence,
            words: List<LyricWordSpan>,
            activeWordIndex: Int,
            wordProgress: Float,
            lineProgress: Float
        ): Float {
            if (confidence == SyncConfidence.PLAIN_UNSYNCED || confidence == SyncConfidence.UNAVAILABLE) {
                return 0f
            }
            return when (format) {
                LyricsFormat.WORD_SYNCED -> {
                    if (words.isEmpty() || activeWordIndex < 0) {
                        lineProgress.coerceIn(0f, 1f)
                    } else {
                        val totalChars = words.sumOf { it.text.length }.coerceAtLeast(1)
                        var charsBefore = 0
                        for (i in 0 until min(activeWordIndex, words.size)) {
                            charsBefore += words[i].text.length
                        }
                        val activeLen = words.getOrNull(activeWordIndex)?.text?.length ?: 0
                        val frac = (charsBefore + activeLen * wordProgress.coerceIn(0f, 1f)) / totalChars.toFloat()
                        frac.coerceIn(0f, 1f)
                    }
                }
                LyricsFormat.LINE_SYNCED -> lineProgress.coerceIn(0f, 1f)
                LyricsFormat.PLAIN, LyricsFormat.NONE -> 0f
            }
        }
    }
}
