package com.star.launcher.lyrics.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.star.launcher.hud.StarHudConfigStore
import com.star.launcher.hud.StarHudInputManager
import com.star.launcher.hud.StarTouchHudOverlay
import com.star.launcher.lyrics.config.AnimationIntensity
import com.star.launcher.lyrics.config.AnimationQuality
import com.star.launcher.lyrics.config.IslandDisplayMode
import com.star.launcher.lyrics.config.LiveLyricsConfig
import com.star.launcher.lyrics.config.LiveLyricsConfigStore
import com.star.launcher.lyrics.config.LiveLyricsDisplayMode
import com.star.launcher.lyrics.config.LiveLyricsSessionState
import com.star.launcher.lyrics.engine.LyricsFormat
import com.star.launcher.lyrics.engine.SyncConfidence
import com.star.launcher.lyrics.engine.SyncedLyricsFrame
import com.star.launcher.lyrics.media.MediaTrackInfo
import com.star.launcher.lyrics.media.UnifiedMediaState
import net.kdt.pojavlaunch.R
import net.kdt.pojavlaunch.customcontrols.ControlLayout
import net.kdt.pojavlaunch.customcontrols.mouse.HotbarView
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Activity-owned in-game Dynamic Island overlay for STAR Live Lyrics.
 *
 * Designed to live inside `MainActivity`'s `content_frame` above `MinecraftGLSurface` and
 * `ControlLayout` without requiring `SYSTEM_ALERT_WINDOW`, while keeping 100% of gameplay touch
 * areas unobstructed.
 */
class StarLiveLyricsOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onToggleExpandCollapse()
        fun onHideRequested()
        fun onShowRequested()
        fun onPlayPauseClicked()
        fun onPreviousClicked()
        fun onNextClicked()
        fun onAdjustTrackOffset(deltaMs: Long)
        fun onResetTrackOffset()
        fun onToggleSessionDisabled(disabled: Boolean)
        fun onModeTransitioned(mode: IslandDisplayMode)
        fun onSelectDisplayMode(mode: LiveLyricsDisplayMode)
        fun onPositionChanged(normalizedX: Float, normalizedY: Float) {}
        fun onPositionResetRequested() {}
        fun onPositionLockChanged(locked: Boolean) {}
        fun onPositionEditingModeChanged(editing: Boolean) {}
    }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val benchmark = LiveLyricsBenchmarkMonitor()

    private var listener: Listener? = null
    private var config = LiveLyricsConfig.defaults()
    private var sessionState = LiveLyricsSessionState()
    private var effectiveMode = IslandDisplayMode.HIDDEN
    private var lastMediaState = UnifiedMediaState()
    private var lastLyricsFrame = SyncedLyricsFrame()

    private var safeLeft = 0
    private var safeTop = 0
    private var safeRight = 0
    private var safeBottom = 0
    private var thermalThrottled = false

    // Morph state: 0f = Compact, 1f = Expanded
    private var morphFraction = 0f
    private var morphAnimator: ValueAnimator? = null
    private var visibilityAnimator: ValueAnimator? = null
    private var resetPositionAnimator: ValueAnimator? = null

    // Theme / dynamic artwork accent
    private var accentColor = MediaTrackInfo.DEFAULT_ACCENT_COLOR
    private var currentArtKey = ""
    private var displayedBitmap: Bitmap? = null

    // Diffed text / timer state to avoid redundant TextView layout checks on 55ms ticks
    private var lastAppliedTitle: String = "\u0000"
    private var lastAppliedArtist: String = "\u0000"
    private var lastAppliedSyncStatus: String = "\u0000"
    private var lastAppliedAttribution: String = "\u0000"
    private var lastAppliedElapsedSec: Long = -1L
    private var lastAppliedDurationSec: Long = -2L

    // UI nodes
    private val pillCard: AmbientGlassCard
    private val compactRow: LinearLayout
    private val compactArtGlowDot: View
    private val compactVisualizer: MiniVisualizerView
    private val compactTitleText: TextView
    private val compactLyricView: KineticLyricsView

    private val expandedColumn: LinearLayout
    private val artworkFrame: FrameLayout
    private val artworkBackdropBlur: ImageView
    private val artworkImagePrimary: ImageView
    private val artworkImageSecondary: ImageView
    private var usingPrimaryArtwork = true
    private val expandedTitleText: TextView
    private val expandedArtistText: TextView
    private val statusBadgeText: TextView
    private val collapseButton: TextView
    private val hideButton: TextView
    private val quickTuneButton: TextView

    private val expandedLyricsView: KineticLyricsView
    private val progressRow: LinearLayout
    private val elapsedTimeText: TextView
    private val progressTrack: ThinProgressView
    private val durationTimeText: TextView
    private val controlsRow: LinearLayout
    private val prevButton: TextView
    private val playPauseButton: MorphPlayPauseButton
    private val nextButton: TextView
    private val attributionText: TextView
    private val lockBadgeView: TextView

    // Unobtrusive quick-restore pill when temporarily hidden
    private val restoreChip: LinearLayout
    private val restoreChipLabel: TextView

    // In-game quick control panel (opened from drawer or expanded gear)
    private val quickPanelCard: LinearLayout
    private val quickPanelStatusText: TextView
    private val quickPanelFullBtn: TextView
    private val quickPanelCompactBtn: TextView
    private val quickPanelLyricsOnlyBtn: TextView
    private val quickPanelLockBtn: TextView
    private val quickPanelResetPosBtn: TextView
    private val quickPanelEditPosBtn: TextView
    private val quickPanelShowHideBtn: TextView
    private val quickPanelExpandBtn: TextView
    private val quickPanelSessionBtn: TextView
    private val quickPanelOffsetLabel: TextView
    private var quickPanelVisible = false

    // Subtle confirmation banner for position reset / lock actions
    private val confirmationBannerText: TextView
    private var lastConfirmationMessage: String = ""
    private val hideConfirmationRunnable = Runnable {
        confirmationBannerText.animate().alpha(0f).setDuration(160L).withEndAction {
            confirmationBannerText.visibility = View.GONE
        }.start()
    }

    // Cached HUD-safe placement so 55ms sync ticks do not trigger expensive View traversals
    private var lastPlacementKey: String = ""
    private var cachedPlacement: HudPlacementResult? = null
    private var lastTargetPillWidthPx: Float = 0f
    private var lastTargetPillHeightPx: Float = 0f

    // Free drag & position editing state on pillCard
    private var positionEditingMode = false
    private var isDraggingPill = false
    private var isPillTouchSelected = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downParentX = 0f
    private var downParentY = 0f
    private var dragStartLeftPx = 0f
    private var dragStartTopPx = 0f
    private var lastTrackOffsetMs = 0L

    private var thermalListener: Any? = null

    init {
        setWillNotDraw(true)
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
        translationZ = 14f * density

        pillCard = AmbientGlassCard(context).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = false
            pivotX = 0f
            pivotY = 0f
        }

        // --- Compact Row ---
        compactRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        compactArtGlowDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accentColor)
            }
        }
        compactVisualizer = MiniVisualizerView(context)
        compactTitleText = TextView(context).apply {
            setTextColor(0xFFEAF1FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        compactLyricView = KineticLyricsView(context).apply {
            configure(
                expanded = false,
                lyricsFontSizeSp = 12.5f,
                accent = accentColor,
                animEnabled = true,
                animQuality = AnimationQuality.HIGH,
                speed = 1.0f
            )
        }
        compactRow.addView(compactArtGlowDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply {
            marginEnd = dp(6)
        })
        compactRow.addView(compactVisualizer, LinearLayout.LayoutParams(dp(16), dp(14)).apply {
            marginEnd = dp(8)
        })
        compactRow.addView(compactTitleText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(8)
        })
        compactRow.addView(compactLyricView, LinearLayout.LayoutParams(0, dp(30), 1f))

        // --- Expanded Column ---
        expandedColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            visibility = View.GONE
            alpha = 0f
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        artworkFrame = FrameLayout(context).apply {
            background = roundedShape(0xFF1A2236.toInt(), dp(12).toFloat(), 0x33FFFFFF)
            clipToOutline = true
        }
        artworkBackdropBlur = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.28f
            visibility = View.GONE
        }
        artworkImagePrimary = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        artworkImageSecondary = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        artworkFrame.addView(artworkBackdropBlur, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        artworkFrame.addView(artworkImagePrimary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        artworkFrame.addView(artworkImageSecondary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val metaCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        expandedTitleText = TextView(context).apply {
            setTextColor(0xFFF5F8FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        expandedArtistText = TextView(context).apply {
            setTextColor(0xFF9EB0CC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        statusBadgeText = TextView(context).apply {
            setTextColor(0xFF5EE2FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        metaCol.addView(expandedTitleText)
        metaCol.addView(expandedArtistText)
        metaCol.addView(statusBadgeText)

        quickTuneButton = createHeaderIconButton("⚙") { toggleQuickControlPanel() }
        collapseButton = createHeaderIconButton("▾") { listener?.onToggleExpandCollapse() }
        hideButton = createHeaderIconButton("✕") { listener?.onHideRequested() }

        headerRow.addView(artworkFrame, LinearLayout.LayoutParams(dp(46), dp(46)).apply {
            marginEnd = dp(10)
        })
        headerRow.addView(metaCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(quickTuneButton, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(4) })
        headerRow.addView(collapseButton, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(4) })
        headerRow.addView(hideButton, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(4) })

        expandedLyricsView = KineticLyricsView(context).apply {
            configure(
                expanded = true,
                lyricsFontSizeSp = 13.5f,
                accent = accentColor,
                animEnabled = true,
                animQuality = AnimationQuality.HIGH,
                speed = 1.0f
            )
        }

        progressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        elapsedTimeText = TextView(context).apply {
            setTextColor(0xFF96A2BA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            text = "0:00"
        }
        progressTrack = ThinProgressView(context)
        durationTimeText = TextView(context).apply {
            setTextColor(0xFF96A2BA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            text = "0:00"
        }
        progressRow.addView(elapsedTimeText)
        progressRow.addView(progressTrack, LinearLayout.LayoutParams(0, dp(6), 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        progressRow.addView(durationTimeText)

        controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        prevButton = createControlButton("⏮") { listener?.onPreviousClicked() }
        playPauseButton = MorphPlayPauseButton(context).apply {
            setOnClickListener {
                pulseArtworkOnAction()
                listener?.onPlayPauseClicked()
            }
        }
        nextButton = createControlButton("⏭") { listener?.onNextClicked() }
        attributionText = TextView(context).apply {
            setTextColor(0x8896A2BA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
        }
        controlsRow.addView(prevButton, LinearLayout.LayoutParams(dp(36), dp(30)))
        controlsRow.addView(playPauseButton, LinearLayout.LayoutParams(dp(42), dp(32)).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        })
        controlsRow.addView(nextButton, LinearLayout.LayoutParams(dp(36), dp(30)))
        controlsRow.addView(attributionText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })

        expandedColumn.addView(headerRow)
        expandedColumn.addView(expandedLyricsView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(64)
        ).apply {
            topMargin = dp(8)
            bottomMargin = dp(4)
        })
        expandedColumn.addView(progressRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(6)
        })
        expandedColumn.addView(controlsRow)

        lockBadgeView = TextView(context).apply {
            text = "🔓"
            setTextColor(0xFFEAF1FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(1), dp(5), dp(1))
            background = roundedShape(0xCC0D1424.toInt(), dp(10).toFloat(), 0x555EE2FF)
            visibility = View.GONE
            setOnClickListener {
                if (positionEditingMode) {
                    togglePositionLocked()
                }
            }
        }

        pillCard.addView(compactRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        pillCard.addView(expandedColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        pillCard.addView(lockBadgeView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(3)
            rightMargin = dp(6)
        })
        setupPillTouchBehavior()
        addView(pillCard, LayoutParams(dp(286), dp(42), Gravity.TOP or Gravity.START))

        // --- Unobtrusive Quick Restore Chip ---
        restoreChip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = roundedShape(0xCC0D1322.toInt(), dp(16).toFloat(), 0x445EE2FF)
            visibility = View.GONE
            isClickable = true
            setOnClickListener { listener?.onShowRequested() }
        }
        restoreChipLabel = TextView(context).apply {
            text = "♪ Lyrics"
            setTextColor(0xFFDCEBFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        restoreChip.addView(restoreChipLabel)
        addView(restoreChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))

        // --- In-Game Quick Control Panel ---
        quickPanelCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedShape(0xEC0C111D.toInt(), dp(18).toFloat(), 0x445EE2FF)
            visibility = View.GONE
            isClickable = true
        }
        val qHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val qTitle = TextView(context).apply {
            text = "STAR Live Lyrics · Quick Control"
            setTextColor(0xFFF1F5FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val qClose = createHeaderIconButton("Done", widthDp = 48) {
            if (positionEditingMode) {
                exitPositionEditingMode()
            } else {
                hideQuickControlPanel()
            }
        }
        qHeader.addView(qTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        qHeader.addView(qClose)

        quickPanelStatusText = TextView(context).apply {
            setTextColor(0xFF96A2BA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4), 0, dp(8))
        }

        val qModeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        quickPanelFullBtn = createActionChip("Full Music") {
            selectDisplayModeInternal(LiveLyricsDisplayMode.FULL_MUSIC)
        }
        quickPanelCompactBtn = createActionChip("Compact") {
            selectDisplayModeInternal(LiveLyricsDisplayMode.COMPACT_MUSIC)
        }
        quickPanelLyricsOnlyBtn = createActionChip("Lyrics Only") {
            selectDisplayModeInternal(LiveLyricsDisplayMode.LYRICS_ONLY)
        }
        qModeRow.addView(quickPanelFullBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        qModeRow.addView(quickPanelCompactBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        qModeRow.addView(quickPanelLyricsOnlyBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Dedicated Position & Lock Quick Controls Row (Lock/Unlock, Reset Position, Enter/Exit Position Editing Mode)
        val qPositionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        quickPanelLockBtn = createActionChip("🔓 Unlocked") {
            togglePositionLocked()
        }
        quickPanelResetPosBtn = createActionChip("Reset Position") {
            resetPositionToDefault()
        }
        quickPanelEditPosBtn = createActionChip("Edit Position") {
            if (positionEditingMode) {
                exitPositionEditingMode()
            } else {
                enterPositionEditingMode()
            }
        }
        qPositionRow.addView(quickPanelLockBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        qPositionRow.addView(quickPanelResetPosBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        qPositionRow.addView(quickPanelEditPosBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val qBtnRow1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        quickPanelShowHideBtn = createActionChip("Hide Pill") {
            if (effectiveMode == IslandDisplayMode.HIDDEN) {
                listener?.onShowRequested()
            } else {
                listener?.onHideRequested()
            }
            refreshQuickPanelLabels(lastTrackOffsetMs)
        }
        quickPanelExpandBtn = createActionChip("Expand") {
            listener?.onToggleExpandCollapse()
            refreshQuickPanelLabels(lastTrackOffsetMs)
        }
        quickPanelSessionBtn = createActionChip("Disable Session") {
            listener?.onToggleSessionDisabled(!sessionState.sessionDisabled)
            refreshQuickPanelLabels(lastTrackOffsetMs)
        }
        qBtnRow1.addView(quickPanelShowHideBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        qBtnRow1.addView(quickPanelExpandBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        qBtnRow1.addView(quickPanelSessionBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val qOffsetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        quickPanelOffsetLabel = TextView(context).apply {
            text = "Sync Offset: 0 ms"
            setTextColor(0xFFD6E4FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
        }
        val minusBtn = createActionChip("-250ms") {
            listener?.onAdjustTrackOffset(-250L)
        }
        val resetBtn = createActionChip("0ms") {
            listener?.onResetTrackOffset()
        }
        val plusBtn = createActionChip("+250ms") {
            listener?.onAdjustTrackOffset(250L)
        }
        qOffsetRow.addView(quickPanelOffsetLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        qOffsetRow.addView(minusBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
        qOffsetRow.addView(resetBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
        qOffsetRow.addView(plusBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })

        quickPanelCard.addView(qHeader)
        quickPanelCard.addView(quickPanelStatusText)
        quickPanelCard.addView(qModeRow)
        quickPanelCard.addView(qPositionRow)
        quickPanelCard.addView(qBtnRow1)
        quickPanelCard.addView(qOffsetRow)
        addView(quickPanelCard, LayoutParams(dp(340), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        // --- Sleek Confirmation Toast Banner ---
        confirmationBannerText = TextView(context).apply {
            setTextColor(0xFFEAF6FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedShape(0xEE0D1626.toInt(), dp(14).toFloat(), 0x775EE2FF)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        addView(confirmationBannerText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(18)
        })
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun updateSafeInsets(left: Int, top: Int, right: Int, bottom: Int) {
        if (safeLeft == left && safeTop == top && safeRight == right && safeBottom == bottom) return
        safeLeft = left.coerceAtLeast(0)
        safeTop = top.coerceAtLeast(0)
        safeRight = right.coerceAtLeast(0)
        safeBottom = bottom.coerceAtLeast(0)
        invalidateHudPlacementCache()
        relayoutOverlay()
    }

    fun invalidateHudPlacementCache() {
        lastPlacementKey = ""
        cachedPlacement = null
    }

    fun relayoutOverlay() {
        invalidateHudPlacementCache()
        applyPlacementAndScale()
        requestLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            relayoutOverlay()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            onWindowFocusLost()
        }
    }

    fun onWindowFocusLost() {
        cancelActiveDrag()
    }

    fun getBenchmarkSnapshot(): LiveLyricsBenchmarkSnapshot = benchmark.snapshot()

    fun getCurrentEffectiveMode(): IslandDisplayMode = effectiveMode

    fun getCurrentConfig(): LiveLyricsConfig = config

    fun isPositionLocked(): Boolean = config.positionLocked

    fun getLockIndicatorSymbol(): String = if (config.positionLocked) "🔒" else "🔓"

    fun isPositionEditingMode(): Boolean = positionEditingMode

    fun isQuickControlPanelVisible(): Boolean = quickPanelVisible

    fun getLastConfirmationMessage(): String = lastConfirmationMessage

    fun getPillBoundsOnScreen(): RectF {
        val left = pillCard.left.toFloat()
        val top = pillCard.top.toFloat()
        val w = pillCard.width * pillCard.scaleX
        val h = pillCard.height * pillCard.scaleY
        return RectF(left, top, left + w, top + h)
    }

    fun setPositionLocked(locked: Boolean) {
        if (isDraggingPill && locked) {
            cancelActiveDrag()
        }
        config = LiveLyricsConfigStore.save(context, config.withPositionLocked(locked))
        refreshLockAndEditVisuals()
        refreshQuickPanelLabels(lastTrackOffsetMs)
        listener?.onPositionLockChanged(locked)
    }

    fun togglePositionLocked(): Boolean {
        val next = !config.positionLocked
        setPositionLocked(next)
        return next
    }

    fun enterPositionEditingMode() {
        setPositionEditingMode(true)
    }

    fun exitPositionEditingMode() {
        setPositionEditingMode(false)
    }

    fun setPositionEditingMode(editing: Boolean) {
        if (positionEditingMode == editing && (!editing || quickPanelVisible)) {
            refreshLockAndEditVisuals()
            return
        }
        StarHudInputManager.getInstance().releaseAllInputs(
            if (editing) "enter_lyrics_position_edit" else "exit_lyrics_position_edit"
        )
        if (!editing && isDraggingPill) {
            commitDraggedPosition()
        }
        positionEditingMode = editing
        if (editing) {
            if (config.featureEnabled && !sessionState.sessionDisabled && pillCard.visibility != View.VISIBLE) {
                pillCard.visibility = View.VISIBLE
                pillCard.alpha = config.overlayAlpha
            }
            quickPanelVisible = true
            quickPanelCard.visibility = View.VISIBLE
        } else {
            isPillTouchSelected = false
            isDraggingPill = false
            quickPanelVisible = false
            quickPanelCard.visibility = View.GONE
        }
        refreshLockAndEditVisuals()
        refreshQuickPanelLabels(lastTrackOffsetMs)
        applyPlacementAndScale()
        listener?.onPositionEditingModeChanged(editing)
    }

    fun resetPositionToDefault() {
        if (isDraggingPill) {
            cancelActiveDrag()
        }
        val oldLeft = pillCard.left
        val oldTop = pillCard.top
        val hadLaidOutPill = width > 0 && height > 0 && pillCard.width > 0 && pillCard.height > 0

        config = LiveLyricsConfigStore.save(context, config.withDefaultPosition())
        invalidateHudPlacementCache()
        applyPlacementAndScale()
        refreshLockAndEditVisuals()
        showConfirmationMessage(LiveLyricsConfig.RESET_POSITION_CONFIRMATION_MESSAGE)
        refreshQuickPanelLabels(lastTrackOffsetMs)
        listener?.onPositionResetRequested()

        val newLeft = pillCard.left
        val newTop = pillCard.top
        val animate = hadLaidOutPill &&
            config.animationsEnabled &&
            effectiveAnimationQuality() != AnimationQuality.BATTERY_SAVER &&
            (oldLeft != newLeft || oldTop != newTop)

        resetPositionAnimator?.cancel()
        if (animate) {
            val startDx = (oldLeft - newLeft).toFloat()
            val startDy = (oldTop - newTop).toFloat()
            pillCard.translationX = startDx
            pillCard.translationY = startDy
            resetPositionAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = (250L / config.animationSpeed).toLong().coerceIn(120L, 420L)
                interpolator = SpringLikeInterpolator(damping = 0.82f)
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    pillCard.translationX = startDx * f
                    pillCard.translationY = startDy * f
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        pillCard.translationX = 0f
                        pillCard.translationY = 0f
                    }
                })
                start()
            }
        } else {
            pillCard.translationX = 0f
            pillCard.translationY = 0f
        }
    }

    private fun showConfirmationMessage(message: String) {
        lastConfirmationMessage = message
        removeCallbacks(hideConfirmationRunnable)
        confirmationBannerText.text = message
        confirmationBannerText.visibility = View.VISIBLE
        if (config.animationsEnabled && effectiveAnimationQuality() != AnimationQuality.BATTERY_SAVER) {
            confirmationBannerText.alpha = 0f
            confirmationBannerText.animate().alpha(1f).setDuration(140L).start()
        } else {
            confirmationBannerText.alpha = 1f
        }
        postDelayed(hideConfirmationRunnable, 2200L)
    }

    private fun selectDisplayModeInternal(mode: LiveLyricsDisplayMode) {
        config = LiveLyricsConfigStore.save(context, config.copy(displayMode = mode))
        val nextIsland = when (mode) {
            LiveLyricsDisplayMode.FULL_MUSIC -> IslandDisplayMode.EXPANDED
            LiveLyricsDisplayMode.COMPACT_MUSIC -> IslandDisplayMode.COMPACT
            LiveLyricsDisplayMode.LYRICS_ONLY -> IslandDisplayMode.COMPACT
        }
        sessionState = sessionState.copy(
            temporarilyHidden = false,
            activeMode = nextIsland,
            activeDisplayMode = mode
        )
        invalidateHudPlacementCache()
        listener?.onSelectDisplayMode(mode)
        applyState(config, sessionState, lastMediaState, lastLyricsFrame, lastTrackOffsetMs)
        refreshQuickPanelLabels(lastTrackOffsetMs)
    }

    fun showQuickControlPanel() {
        quickPanelVisible = true
        quickPanelCard.visibility = View.VISIBLE
        refreshQuickPanelLabels(lastTrackOffsetMs)
        applyPlacementAndScale()
    }

    fun hideQuickControlPanel() {
        if (positionEditingMode) {
            exitPositionEditingMode()
            return
        }
        quickPanelVisible = false
        quickPanelCard.visibility = View.GONE
        refreshLockAndEditVisuals()
    }

    fun toggleQuickControlPanel() {
        if (quickPanelVisible) hideQuickControlPanel() else showQuickControlPanel()
    }

    fun applyState(
        cfg: LiveLyricsConfig,
        sess: LiveLyricsSessionState,
        media: UnifiedMediaState,
        frame: SyncedLyricsFrame,
        trackOffsetMs: Long
    ) {
        // If user is actively dragging the pill, preserve the in-flight manual position across sync ticks
        config = if (isDraggingPill) {
            cfg.copy(
                autoHudSafePositioning = false,
                manualPosXFraction = config.manualPosXFraction,
                manualPosYFraction = config.manualPosYFraction
            )
        } else {
            cfg
        }
        sessionState = sess
        lastMediaState = media
        lastLyricsFrame = frame
        lastTrackOffsetMs = trackOffsetMs

        val isLyricsOnly = sess.effectiveDisplayMode(cfg) == LiveLyricsDisplayMode.LYRICS_ONLY
        val quality = effectiveAnimationQuality()
        val nextMode = sess.effectiveMode(cfg)
        val modeChanged = nextMode != effectiveMode
        effectiveMode = nextMode
        benchmark.setMode(nextMode)

        if (!cfg.featureEnabled || sess.sessionDisabled) {
            stopAllAnimations()
            pillCard.visibility = View.GONE
            restoreChip.visibility = View.GONE
            displayedBitmap = null
            currentArtKey = ""
            if (modeChanged) listener?.onModeTransitioned(IslandDisplayMode.HIDDEN)
            if (quickPanelVisible) refreshQuickPanelLabels(trackOffsetMs)
            return
        }

        // Accent color & album art crossfade
        val nextAccent = media.track.accentColor
        if (accentColor != nextAccent) {
            accentColor = nextAccent
            pillCard.setAccentColor(accentColor)
            compactVisualizer.setAccentColor(accentColor)
            progressTrack.setAccentColor(accentColor)
            statusBadgeText.setTextColor(accentColor)
            (compactArtGlowDot.background as? GradientDrawable)?.setColor(accentColor)
        }

        updateArtworkIfChanged(media, cfg, isLyricsOnly)
        updateTextAndControls(cfg, media, frame, isLyricsOnly)

        compactVisualizer.setPlaybackAndQuality(
            playing = !isLyricsOnly && media.playback.isPlaying && cfg.decorativeVisualizerEnabled && nextMode == IslandDisplayMode.COMPACT,
            animEnabled = cfg.animationsEnabled,
            animQuality = quality,
            speed = cfg.animationSpeed
        )
        compactVisualizer.visibility = if (!isLyricsOnly && cfg.decorativeVisualizerEnabled) View.VISIBLE else View.GONE
        compactArtGlowDot.visibility = if (!isLyricsOnly && cfg.showAlbumArtwork && cfg.metadataEnabled) View.VISIBLE else View.GONE

        compactLyricView.configure(
            expanded = false,
            lyricsOnly = isLyricsOnly,
            showNote = cfg.showMusicalNote,
            lyricsFontSizeSp = if (isLyricsOnly) cfg.lyricsFontSizeSp else (cfg.lyricsFontSizeSp - 0.8f).coerceAtLeast(10.5f),
            accent = accentColor,
            animEnabled = cfg.animationsEnabled,
            animQuality = quality,
            animIntensity = cfg.animationIntensity,
            speed = cfg.animationSpeed,
            noteSpeed = cfg.noteAnimationSpeed
        )
        expandedLyricsView.configure(
            expanded = true,
            lyricsOnly = false,
            showNote = cfg.showMusicalNote,
            lyricsFontSizeSp = cfg.lyricsFontSizeSp,
            accent = accentColor,
            animEnabled = cfg.animationsEnabled,
            animQuality = quality,
            animIntensity = cfg.animationIntensity,
            speed = cfg.animationSpeed,
            noteSpeed = cfg.noteAnimationSpeed
        )

        if (isLyricsOnly || nextMode == IslandDisplayMode.COMPACT || modeChanged || morphFraction < 0.92f) {
            val compactDisplayLine = when {
                isLyricsOnly && !frame.currentLine.isNullOrBlank() -> frame.currentLine
                isLyricsOnly && media.hasActiveSession -> if (frame.statusLabel.isNotBlank()) frame.statusLabel else "No synchronized lyrics"
                isLyricsOnly -> "Waiting for music"
                !cfg.lyricsEnabled -> if (cfg.metadataEnabled && media.track.artist.isNotBlank()) media.track.artist else if (media.playback.isPlaying) "Playing" else "Paused"
                !frame.currentLine.isNullOrBlank() -> frame.currentLine
                media.hasActiveSession -> if (frame.statusLabel.isNotBlank()) frame.statusLabel else "No lyrics"
                else -> "Waiting for music"
            }
            compactLyricView.updateLyrics(
                previous = null,
                current = compactDisplayLine,
                next = null,
                activeWords = if (cfg.lyricsEnabled || isLyricsOnly) frame.activeWords else emptyList(),
                wordIndex = if (cfg.lyricsEnabled || isLyricsOnly) frame.activeWordIndex else -1,
                currentWordProgress = if (cfg.lyricsEnabled || isLyricsOnly) frame.currentWordProgress else 0f,
                currentLineProgress = if (cfg.lyricsEnabled || isLyricsOnly) frame.lineProgress else 0f,
                currentFormat = if (cfg.lyricsEnabled || isLyricsOnly) frame.format else LyricsFormat.NONE,
                currentConfidence = if (cfg.lyricsEnabled || isLyricsOnly) frame.syncConfidence else SyncConfidence.UNAVAILABLE,
                playing = media.playback.isPlaying
            )
        }

        if (!isLyricsOnly && (nextMode == IslandDisplayMode.EXPANDED || modeChanged || morphFraction > 0.08f)) {
            val expandedCurrent = when {
                !cfg.lyricsEnabled -> "Lyrics hidden by setting"
                !frame.currentLine.isNullOrBlank() -> frame.currentLine
                else -> "No synchronized lyrics available"
            }
            expandedLyricsView.updateLyrics(
                previous = if (cfg.lyricsEnabled) frame.previousLine else null,
                current = expandedCurrent,
                next = if (cfg.lyricsEnabled) frame.nextLine else null,
                activeWords = if (cfg.lyricsEnabled) frame.activeWords else emptyList(),
                wordIndex = if (cfg.lyricsEnabled) frame.activeWordIndex else -1,
                currentWordProgress = if (cfg.lyricsEnabled) frame.currentWordProgress else 0f,
                currentLineProgress = if (cfg.lyricsEnabled) frame.lineProgress else 0f,
                currentFormat = if (cfg.lyricsEnabled) frame.format else LyricsFormat.NONE,
                currentConfidence = if (cfg.lyricsEnabled) frame.syncConfidence else SyncConfidence.UNAVAILABLE,
                playing = media.playback.isPlaying
            )
        }

        if (modeChanged) {
            transitionToMode(nextMode, cfg)
            listener?.onModeTransitioned(nextMode)
        } else {
            if (isLyricsOnly && morphFraction > 0.01f) {
                applyMorphFraction(0f)
            }
            updateRestoreChipVisibility(cfg, sess, media)
        }

        applyPlacementAndScale()
        refreshLockAndEditVisuals()
        if (quickPanelVisible) {
            refreshQuickPanelLabels(trackOffsetMs)
        }
    }

    fun stopAllAnimations() {
        cancelActiveDrag()
        morphAnimator?.cancel()
        morphAnimator = null
        visibilityAnimator?.cancel()
        visibilityAnimator = null
        resetPositionAnimator?.cancel()
        resetPositionAnimator = null
        removeCallbacks(hideConfirmationRunnable)
        compactVisualizer.stopAnimations()
        compactLyricView.stopAnimations()
        expandedLyricsView.stopAnimations()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerThermalListener()
    }

    override fun onDetachedFromWindow() {
        stopAllAnimations()
        unregisterThermalListener()
        super.onDetachedFromWindow()
    }

    private fun effectiveAnimationQuality(): AnimationQuality {
        if (!config.animationsEnabled) return AnimationQuality.BATTERY_SAVER
        return if (thermalThrottled) AnimationQuality.BATTERY_SAVER else config.animationQuality
    }

    private fun transitionToMode(target: IslandDisplayMode, cfg: LiveLyricsConfig) {
        val quality = effectiveAnimationQuality()
        val animate = cfg.animationsEnabled && quality != AnimationQuality.BATTERY_SAVER

        if (target == IslandDisplayMode.HIDDEN) {
            compactVisualizer.stopAnimations()
            compactLyricView.stopAnimations()
            expandedLyricsView.stopAnimations()
            visibilityAnimator?.cancel()
            if (!animate || pillCard.visibility != View.VISIBLE) {
                pillCard.visibility = View.GONE
                pillCard.alpha = 0f
                updateRestoreChipVisibility(cfg, sessionState, lastMediaState)
            } else {
                visibilityAnimator = ValueAnimator.ofFloat(pillCard.alpha, 0f).apply {
                    duration = (180L / cfg.animationSpeed).toLong().coerceIn(90L, 320L)
                    addUpdateListener { anim ->
                        val v = anim.animatedValue as Float
                        pillCard.alpha = v * cfg.overlayAlpha
                        pillCard.scaleX = (0.86f + 0.14f * v) * cfg.overlayScale
                        pillCard.scaleY = (0.86f + 0.14f * v) * cfg.overlayScale
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (effectiveMode == IslandDisplayMode.HIDDEN) {
                                pillCard.visibility = View.GONE
                                updateRestoreChipVisibility(config, sessionState, lastMediaState)
                            }
                        }
                    })
                    start()
                }
            }
            return
        }

        restoreChip.visibility = View.GONE
        if (pillCard.visibility != View.VISIBLE) {
            pillCard.visibility = View.VISIBLE
            pillCard.alpha = if (animate) 0f else cfg.overlayAlpha
        }

        val targetMorph = if (target == IslandDisplayMode.EXPANDED) 1f else 0f
        if (pillCard.alpha < cfg.overlayAlpha * 0.9f) {
            visibilityAnimator?.cancel()
            if (animate) {
                visibilityAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = (220L / cfg.animationSpeed).toLong().coerceIn(110L, 400L)
                    interpolator = SpringLikeInterpolator(damping = 0.84f)
                    addUpdateListener { anim ->
                        val v = anim.animatedValue as Float
                        pillCard.alpha = v * cfg.overlayAlpha
                        val s = (0.90f + 0.10f * v) * cfg.overlayScale
                        pillCard.scaleX = s
                        pillCard.scaleY = s
                    }
                    start()
                }
            } else {
                pillCard.alpha = cfg.overlayAlpha
                pillCard.scaleX = cfg.overlayScale
                pillCard.scaleY = cfg.overlayScale
            }
        }

        morphAnimator?.cancel()
        if (!animate) {
            applyMorphFraction(targetMorph)
            return
        }
        val dur = ((if (quality == AnimationQuality.BALANCED) 220L else 340L) / cfg.animationSpeed)
            .toLong()
            .coerceIn(140L, 550L)
        morphAnimator = ValueAnimator.ofFloat(morphFraction, targetMorph).apply {
            duration = dur
            interpolator = SpringLikeInterpolator(damping = 0.80f)
            addUpdateListener {
                applyMorphFraction(it.animatedValue as Float)
            }
            start()
        }
    }

    private fun applyMorphFraction(fraction: Float) {
        val isLyricsOnly = sessionState.effectiveDisplayMode(config) == LiveLyricsDisplayMode.LYRICS_ONLY
        morphFraction = if (isLyricsOnly) 0f else fraction.coerceIn(0f, 1f)
        val compactW = if (isLyricsOnly) dp(248) else dp(286)
        val compactH = dp(42)
        val expandedW = dp(364)
        val expandedH = expandedTargetHeightPx()

        val curW = (compactW + (expandedW - compactW) * morphFraction).roundToInt()
        val curH = (compactH + (expandedH - compactH) * morphFraction).roundToInt()

        val lp = pillCard.layoutParams as? LayoutParams ?: return
        if (lp.width != curW || lp.height != curH) {
            lp.width = curW
            lp.height = curH
            pillCard.layoutParams = lp
            invalidateHudPlacementCache()
        }
        val radius = dp(20) + (dp(24) - dp(20)) * morphFraction
        pillCard.setCornerRadius(radius)

        compactRow.visibility = if (isLyricsOnly || morphFraction < 0.92f) View.VISIBLE else View.GONE
        compactRow.alpha = if (isLyricsOnly) 1f else (1f - morphFraction * 1.45f).coerceIn(0f, 1f)

        expandedColumn.visibility = if (!isLyricsOnly && morphFraction > 0.08f) View.VISIBLE else View.GONE
        expandedColumn.alpha = if (isLyricsOnly) 0f else ((morphFraction - 0.15f) / 0.85f).coerceIn(0f, 1f)
    }

    private fun expandedTargetHeightPx(): Int {
        val base = dp(130)
        val lyrics = if (config.lyricsEnabled) dp(68) else dp(28)
        val controls = if (config.showPlaybackControls) dp(34) else dp(6)
        return base + lyrics + controls - dp(32)
    }

    private fun updateArtworkIfChanged(
        media: UnifiedMediaState,
        cfg: LiveLyricsConfig,
        isLyricsOnly: Boolean
    ) {
        val showArt = !isLyricsOnly && cfg.showAlbumArtwork && cfg.metadataEnabled
        artworkFrame.visibility = if (showArt) View.VISIBLE else View.GONE
        compactArtGlowDot.visibility = if (showArt) View.VISIBLE else View.GONE
        if (!showArt) return

        val key = media.track.artworkKey
        val bmp = media.track.artworkBitmap
        if (key == currentArtKey && bmp === displayedBitmap) return

        currentArtKey = key
        displayedBitmap = bmp
        val quality = effectiveAnimationQuality()
        val animateArt = cfg.animationsEnabled && quality != AnimationQuality.BATTERY_SAVER

        if (bmp == null || bmp.isRecycled) {
            artworkImagePrimary.setImageDrawable(null)
            artworkImageSecondary.setImageDrawable(null)
            artworkBackdropBlur.visibility = View.GONE
            return
        }

        // Optional lightweight API 31+ backdrop blur only inside artwork container in HIGH quality
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && quality == AnimationQuality.HIGH) {
            artworkBackdropBlur.setImageBitmap(bmp)
            artworkBackdropBlur.setRenderEffect(RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP))
            artworkBackdropBlur.visibility = View.VISIBLE
        } else {
            artworkBackdropBlur.visibility = View.GONE
        }

        if (!animateArt) {
            artworkImagePrimary.setImageBitmap(bmp)
            artworkImagePrimary.alpha = 1f
            artworkImageSecondary.alpha = 0f
            usingPrimaryArtwork = true
            return
        }

        val incoming = if (usingPrimaryArtwork) artworkImageSecondary else artworkImagePrimary
        val outgoing = if (usingPrimaryArtwork) artworkImagePrimary else artworkImageSecondary
        incoming.setImageBitmap(bmp)
        incoming.alpha = 0f
        incoming.animate().alpha(1f).setDuration(220L).start()
        outgoing.animate().alpha(0f).setDuration(220L).start()
        usingPrimaryArtwork = !usingPrimaryArtwork
    }

    private fun updateTextAndControls(
        cfg: LiveLyricsConfig,
        media: UnifiedMediaState,
        frame: SyncedLyricsFrame,
        isLyricsOnly: Boolean
    ) {
        val hasTrack = media.hasActiveSession && media.track.hasTrack
        val title = when {
            isLyricsOnly -> ""
            !cfg.metadataEnabled -> "Music"
            hasTrack -> media.track.title
            !media.hasNotificationPermission -> "Grant Notification Access"
            else -> "No Active Track"
        }
        val artist = when {
            isLyricsOnly -> ""
            !cfg.metadataEnabled -> media.track.sourceAppLabel.ifBlank { "Live Lyrics" }
            hasTrack -> listOfNotNull(
                media.track.artist.takeIf { it.isNotBlank() },
                media.track.sourceAppLabel.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            !media.hasNotificationPermission -> "Open STAR Settings → Live Lyrics"
            else -> "Play music or use STAR Player"
        }

        val targetCompactVis = if (!isLyricsOnly && cfg.metadataEnabled) View.VISIBLE else View.GONE
        if (compactTitleText.visibility != targetCompactVis) {
            compactTitleText.visibility = targetCompactVis
        }
        if (title != lastAppliedTitle) {
            lastAppliedTitle = title
            compactTitleText.text = title
            expandedTitleText.text = title
        }
        if (artist != lastAppliedArtist) {
            lastAppliedArtist = artist
            expandedArtistText.text = artist
        }

        val syncStatus = when {
            !cfg.lyricsEnabled -> "Lyrics Off"
            frame.syncConfidence == SyncConfidence.PLAIN_UNSYNCED -> "Unsynced Plain Text"
            frame.syncConfidence == SyncConfidence.APPROXIMATE_CLOCK -> frame.statusLabel
            frame.format == LyricsFormat.WORD_SYNCED -> "Word Sync · ${frame.statusLabel}"
            else -> frame.statusLabel
        }
        if (syncStatus != lastAppliedSyncStatus) {
            lastAppliedSyncStatus = syncStatus
            statusBadgeText.text = syncStatus
        }

        val lyricsHeight = if (cfg.lyricsEnabled) dp(64) else dp(28)
        (expandedLyricsView.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            if (lp.height != lyricsHeight) {
                lp.height = lyricsHeight
                expandedLyricsView.layoutParams = lp
            }
        }

        val durMs = maxOf(media.playback.durationMs, frame.durationMs)
        val posMs = media.playback.currentPositionMs().coerceAtLeast(0L)
        val elapsedSec = posMs / 1000L
        if (elapsedSec != lastAppliedElapsedSec) {
            lastAppliedElapsedSec = elapsedSec
            elapsedTimeText.text = formatTime(posMs)
        }
        val durationSec = if (durMs > 0L) durMs / 1000L else -1L
        if (durationSec != lastAppliedDurationSec) {
            lastAppliedDurationSec = durationSec
            durationTimeText.text = if (durMs > 0L) formatTime(durMs) else "--:--"
        }
        if (expandedColumn.visibility == View.VISIBLE) {
            val fraction = if (durMs > 0L) (posMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f) else 0f
            progressTrack.setProgress(fraction)
        }

        val targetControlsVis = if (cfg.showPlaybackControls) View.VISIBLE else View.GONE
        if (controlsRow.visibility != targetControlsVis) {
            controlsRow.visibility = targetControlsVis
        }
        playPauseButton.setPlaying(media.playback.isPlaying, cfg.animationsEnabled && effectiveAnimationQuality() != AnimationQuality.BATTERY_SAVER)
        val caps = media.playback.capabilities
        val targetPrevAlpha = if (!hasTrack || caps.canPrevious) 1f else 0.4f
        if (prevButton.alpha != targetPrevAlpha) prevButton.alpha = targetPrevAlpha
        val targetNextAlpha = if (!hasTrack || caps.canNext) 1f else 0.4f
        if (nextButton.alpha != targetNextAlpha) nextButton.alpha = targetNextAlpha

        val nextAttribution = if (cfg.lyricsEnabled) frame.attribution else ""
        if (nextAttribution != lastAppliedAttribution) {
            lastAppliedAttribution = nextAttribution
            attributionText.text = nextAttribution
        }
    }

    private fun updateRestoreChipVisibility(
        cfg: LiveLyricsConfig,
        sess: LiveLyricsSessionState,
        media: UnifiedMediaState
    ) {
        val showRestore = cfg.featureEnabled &&
            !sess.sessionDisabled &&
            effectiveMode == IslandDisplayMode.HIDDEN &&
            (sess.temporarilyHidden || media.hasActiveSession)
        restoreChip.visibility = if (showRestore) View.VISIBLE else View.GONE
    }

    private fun applyPlacementAndScale() {
        val parentW = width
        val parentH = height
        if (parentW <= 0 || parentH <= 0) return

        val isLyricsOnly = sessionState.effectiveDisplayMode(config) == LiveLyricsDisplayMode.LYRICS_ONLY
        val targetW = when {
            effectiveMode == IslandDisplayMode.EXPANDED && !isLyricsOnly -> dp(364)
            isLyricsOnly -> dp(248)
            else -> dp(286)
        }
        val targetH = when {
            effectiveMode == IslandDisplayMode.EXPANDED && !isLyricsOnly -> expandedTargetHeightPx()
            else -> dp(42)
        }

        val scaledW = targetW * config.overlayScale
        val scaledH = targetH * config.overlayScale
        val activeDisplay = sessionState.effectiveDisplayMode(config)
        val cacheKey = "$parentW|$parentH|$safeLeft|$safeTop|$safeRight|$safeBottom|" +
            "${scaledW.roundToInt()}|${scaledH.roundToInt()}|${activeDisplay.name}|${effectiveMode.name}|" +
            "${config.autoHudSafePositioning}|${config.positionAnchor.name}|${config.offsetXFraction}|" +
            "${config.offsetYDp}|${config.manualPosXFraction}|${config.manualPosYFraction}"

        val placement = if (cacheKey == lastPlacementKey && cachedPlacement != null) {
            cachedPlacement!!
        } else {
            val starCfg = StarHudConfigStore.load(context)
            val liveRects = collectLiveHudExclusions()
            val req = HudPlacementRequest(
                containerWidthPx = parentW.toFloat(),
                containerHeightPx = parentH.toFloat(),
                density = density,
                safeLeftPx = safeLeft.toFloat(),
                safeTopPx = safeTop.toFloat(),
                safeRightPx = safeRight.toFloat(),
                safeBottomPx = safeBottom.toFloat(),
                desiredWidthPx = scaledW,
                desiredHeightPx = scaledH,
                config = config,
                starHudConfig = starCfg,
                liveControlRects = liveRects,
                includeVanillaBottomHud = true,
                includeDrawerPull = true,
                includeChatSafetyZone = false
            )
            LiveLyricsHudLayoutSolver.solve(req).also {
                lastPlacementKey = cacheKey
                cachedPlacement = it
                lastTargetPillWidthPx = scaledW
                lastTargetPillHeightPx = scaledH
            }
        }

        val finalScale = (config.overlayScale * placement.appliedScale).coerceIn(0.45f, 1.35f)
        val marginLeft = placement.leftPx.roundToInt().coerceAtLeast(0)
        val marginTop = placement.topPx.roundToInt().coerceAtLeast(0)

        pillCard.pivotX = 0f
        pillCard.pivotY = 0f

        (pillCard.layoutParams as? LayoutParams)?.let { lp ->
            val syncSize = morphAnimator?.isRunning != true
            val desiredLpW = if (syncSize) targetW else lp.width
            val desiredLpH = if (syncSize) targetH else lp.height
            val changed = lp.gravity != (Gravity.TOP or Gravity.START) ||
                lp.leftMargin != marginLeft ||
                lp.topMargin != marginTop ||
                lp.rightMargin != 0 ||
                lp.width != desiredLpW ||
                lp.height != desiredLpH
            if (changed) {
                lp.gravity = Gravity.TOP or Gravity.START
                lp.leftMargin = marginLeft
                lp.topMargin = marginTop
                lp.rightMargin = 0
                lp.width = desiredLpW
                lp.height = desiredLpH
                pillCard.layoutParams = lp
            }
            if (changed || pillCard.left != marginLeft || pillCard.top != marginTop || pillCard.width != desiredLpW || pillCard.height != desiredLpH) {
                if (pillCard.measuredWidth != desiredLpW || pillCard.measuredHeight != desiredLpH) {
                    pillCard.measure(
                        MeasureSpec.makeMeasureSpec(desiredLpW, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(desiredLpH, MeasureSpec.EXACTLY)
                    )
                }
                pillCard.layout(marginLeft, marginTop, marginLeft + desiredLpW, marginTop + desiredLpH)
            }
        }

        if (resetPositionAnimator?.isRunning != true) {
            pillCard.translationX = 0f
            pillCard.translationY = 0f
        }
        val dragScaleBoost = if (isDraggingPill) 1.02f else 1.0f
        pillCard.scaleX = finalScale * dragScaleBoost
        pillCard.scaleY = finalScale * dragScaleBoost

        if (placement.shouldTemporarilyHide && !positionEditingMode) {
            if (pillCard.visibility == View.VISIBLE) {
                pillCard.visibility = View.GONE
            }
        } else if (effectiveMode != IslandDisplayMode.HIDDEN || positionEditingMode) {
            pillCard.alpha = config.overlayAlpha
        }

        (restoreChip.layoutParams as? LayoutParams)?.let { lp ->
            lp.gravity = Gravity.TOP or Gravity.END
            lp.topMargin = safeTop + dp(56)
            lp.rightMargin = safeRight + dp(10)
            restoreChip.layoutParams = lp
        }

        (quickPanelCard.layoutParams as? LayoutParams)?.let { lp ->
            val pillCenterY = placement.topPx + placement.heightPx * 0.5f
            val placeAtBottom = positionEditingMode && pillCenterY < parentH * 0.48f
            if (placeAtBottom) {
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                lp.topMargin = 0
                lp.bottomMargin = safeBottom + dp(24)
            } else {
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.topMargin = safeTop + dp(54)
                lp.bottomMargin = 0
            }
            quickPanelCard.layoutParams = lp
        }
    }

    private fun collectLiveHudExclusions(): List<HudExclusionRect> {
        val parentView = parent as? ViewGroup ?: return emptyList()
        val list = ArrayList<HudExclusionRect>(12)

        val drawerPull = parentView.findViewById<View>(R.id.drawer_button)
        if (drawerPull != null && drawerPull.visibility == View.VISIBLE && drawerPull.width > 0 && drawerPull.height > 0) {
            list.add(
                HudExclusionRect(
                    id = "drawer_pull",
                    label = "Menu Handle",
                    category = HudZoneCategory.DRAWER_PULL,
                    left = drawerPull.left.toFloat(),
                    top = drawerPull.top.toFloat(),
                    right = drawerPull.right.toFloat(),
                    bottom = drawerPull.bottom.toFloat()
                )
            )
        }

        val hotbar = parentView.findViewById<HotbarView>(R.id.hotbar_view)
        if (hotbar != null && hotbar.visibility == View.VISIBLE && hotbar.width > 0 && hotbar.height > 0) {
            list.add(
                HudExclusionRect(
                    id = "hotbar_view",
                    label = "Hotbar & Health/Hunger",
                    category = HudZoneCategory.HOTBAR_AND_VITALS,
                    left = hotbar.left.toFloat(),
                    top = maxOf(0f, hotbar.top.toFloat() - 36f * density),
                    right = hotbar.right.toFloat(),
                    bottom = hotbar.bottom.toFloat()
                )
            )
        }

        val starHud = parentView.findViewById<StarTouchHudOverlay>(R.id.star_touch_hud_overlay)
        if (starHud != null && starHud.visibility == View.VISIBLE) {
            for ((id, rect) in starHud.currentRects.orEmpty()) {
                val hRect = HudExclusionRect(
                    id = "star_${id.name}",
                    label = id.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                    category = when (id.name) {
                        "JOYSTICK" -> HudZoneCategory.JOYSTICK
                        "HOTBAR" -> HudZoneCategory.HOTBAR_AND_VITALS
                        "INVENTORY", "CHAT", "THIRD_PERSON", "PAUSE" -> HudZoneCategory.TOP_HUD_ACTION
                        else -> HudZoneCategory.TOUCH_BUTTON
                    },
                    left = rect.left.toFloat(),
                    top = if (id.name == "HOTBAR") maxOf(0f, rect.top.toFloat() - 36f * density) else rect.top.toFloat(),
                    right = rect.right.toFloat(),
                    bottom = rect.bottom.toFloat()
                )
                list.add(hRect)
            }
        }

        val controlLayout = parentView.findViewById<ControlLayout>(R.id.main_control_layout)
        if (controlLayout != null) {
            for (i in 0 until controlLayout.childCount) {
                val child = controlLayout.getChildAt(i)
                if (child.visibility == View.VISIBLE && child.width > 0 && child.height > 0) {
                    val clsName = child.javaClass.simpleName
                    if (clsName.startsWith("Control")) {
                        list.add(
                            HudExclusionRect(
                                id = "legacy_ctrl_$i",
                                label = "Touch Control",
                                category = HudZoneCategory.LEGACY_CONTROL,
                                left = child.left.toFloat(),
                                top = child.top.toFloat(),
                                right = child.right.toFloat(),
                                bottom = child.bottom.toFloat()
                            )
                        )
                    }
                }
            }
        }
        return list
    }

    private fun setupPillTouchBehavior() {
        // Touch handling is managed directly inside AmbientGlassCard (dispatchTouchEvent /
        // onInterceptTouchEvent / onTouchEvent) so dragging works seamlessly across
        // LYRICS_ONLY, COMPACT_MUSIC, and FULL_MUSIC modes even when starting on child views.
    }

    private fun onPillTouchDown(event: MotionEvent) {
        resetPositionAnimator?.cancel()
        pillCard.translationX = 0f
        pillCard.translationY = 0f
        activePointerId = event.getPointerId(0)
        downParentX = pillCard.left + event.getX(0) * pillCard.scaleX
        downParentY = pillCard.top + event.getY(0) * pillCard.scaleY
        dragStartLeftPx = pillCard.left.toFloat()
        dragStartTopPx = pillCard.top.toFloat()
        isDraggingPill = false
        isPillTouchSelected = true
        parent?.requestDisallowInterceptTouchEvent(true)
        refreshLockAndEditVisuals()
    }

    private fun handlePillTouchEvent(event: MotionEvent): Boolean {
        if (config.positionLocked) {
            if (positionEditingMode) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    showConfirmationMessage("Position is locked — tap Unlock to move.")
                }
                return true
            }
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isPillTouchSelected || activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    onPillTouchDown(event)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Keep tracking the primary active pointer without jumping
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    if (newIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(newIndex)
                        downParentX = pillCard.left + event.getX(newIndex) * pillCard.scaleX
                        downParentY = pillCard.top + event.getY(newIndex) * pillCard.scaleY
                        dragStartLeftPx = pillCard.left.toFloat()
                        dragStartTopPx = pillCard.top.toFloat()
                    } else {
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                var idx = event.findPointerIndex(activePointerId)
                if (idx < 0) {
                    activePointerId = event.getPointerId(0)
                    idx = 0
                    downParentX = pillCard.left + event.getX(0) * pillCard.scaleX
                    downParentY = pillCard.top + event.getY(0) * pillCard.scaleY
                    dragStartLeftPx = pillCard.left.toFloat()
                    dragStartTopPx = pillCard.top.toFloat()
                }
                val curParentX = pillCard.left + event.getX(idx) * pillCard.scaleX
                val curParentY = pillCard.top + event.getY(idx) * pillCard.scaleY
                val dx = curParentX - downParentX
                val dy = curParentY - downParentY

                if (!isDraggingPill && hypot(dx, dy) > touchSlop) {
                    startDraggingPill()
                }
                if (isDraggingPill) {
                    updateDraggedPosition(dragStartLeftPx + dx, dragStartTopPx + dy)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                finishPillTouch(event)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelActiveDrag()
                return true
            }
            else -> return true
        }
    }

    private fun startDraggingPill() {
        isDraggingPill = true
        isPillTouchSelected = true
        parent?.requestDisallowInterceptTouchEvent(true)
        StarHudInputManager.getInstance().releaseAllInputs("lyrics_overlay_drag_start")
        if (quickPanelVisible) {
            quickPanelCard.alpha = 0.28f
        }
        refreshLockAndEditVisuals()
    }

    private fun updateDraggedPosition(desiredLeftPx: Float, desiredTopPx: Float) {
        val parentW = width
        val parentH = height
        if (parentW <= 0 || parentH <= 0) return

        val visualW = max(1f, pillCard.width * pillCard.scaleX)
        val visualH = max(1f, pillCard.height * pillCard.scaleY)
        val clampRes = LiveLyricsHudLayoutSolver.clampManualPosition(
            containerWidthPx = parentW.toFloat(),
            containerHeightPx = parentH.toFloat(),
            density = density,
            safeLeftPx = safeLeft.toFloat(),
            safeTopPx = safeTop.toFloat(),
            safeRightPx = safeRight.toFloat(),
            safeBottomPx = safeBottom.toFloat(),
            pillWidthPx = visualW,
            pillHeightPx = visualH,
            desiredLeftPx = desiredLeftPx,
            desiredTopPx = desiredTopPx
        )
        config = config.withManualPosition(clampRes.normalizedX, clampRes.normalizedY)
        invalidateHudPlacementCache()
        applyPlacementAndScale()
    }

    private fun finishPillTouch(event: MotionEvent) {
        val wasDragging = isDraggingPill
        isDraggingPill = false
        isPillTouchSelected = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        if (quickPanelVisible) {
            quickPanelCard.alpha = 1.0f
        }

        if (wasDragging) {
            commitDraggedPosition()
        } else if (!positionEditingMode) {
            val idx = event.actionIndex.coerceIn(0, max(0, event.pointerCount - 1))
            val upParentX = pillCard.left + event.getX(idx) * pillCard.scaleX
            val upParentY = pillCard.top + event.getY(idx) * pillCard.scaleY
            if (hypot(upParentX - downParentX, upParentY - downParentY) <= touchSlop) {
                if (sessionState.effectiveDisplayMode(config) == LiveLyricsDisplayMode.LYRICS_ONLY) {
                    toggleQuickControlPanel()
                } else {
                    listener?.onToggleExpandCollapse()
                }
            }
        }
        refreshLockAndEditVisuals()
        applyPlacementAndScale()
    }

    private fun commitDraggedPosition() {
        config = LiveLyricsConfigStore.save(
            context,
            config.withManualPosition(config.manualPosXFraction, config.manualPosYFraction)
        )
        invalidateHudPlacementCache()
        applyPlacementAndScale()
        refreshQuickPanelLabels(lastTrackOffsetMs)
        listener?.onPositionChanged(config.manualPosXFraction, config.manualPosYFraction)
    }

    fun cancelActiveDrag() {
        val hadActiveTouch = isDraggingPill || isPillTouchSelected
        val wasDragging = isDraggingPill
        isDraggingPill = false
        isPillTouchSelected = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        if (quickPanelVisible) {
            quickPanelCard.alpha = 1.0f
        }
        if (wasDragging) {
            commitDraggedPosition()
        }
        if (hadActiveTouch) {
            StarHudInputManager.getInstance().releaseAllInputs("lyrics_overlay_drag_cancel")
            refreshLockAndEditVisuals()
            applyPlacementAndScale()
        }
    }

    private fun refreshLockAndEditVisuals() {
        val showEditingChrome = positionEditingMode || isPillTouchSelected || isDraggingPill
        lockBadgeView.text = if (config.positionLocked) "🔒" else "🔓"
        lockBadgeView.visibility = if (showEditingChrome) View.VISIBLE else View.GONE
        pillCard.setEditingVisualState(
            editingOrSelected = showEditingChrome,
            dragging = isDraggingPill,
            locked = config.positionLocked
        )
    }

    private fun pulseArtworkOnAction() {
        if (!config.animationsEnabled || effectiveAnimationQuality() == AnimationQuality.BATTERY_SAVER) return
        artworkFrame.animate().scaleX(0.93f).scaleY(0.93f).setDuration(75L).withEndAction {
            artworkFrame.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
        }.start()
    }

    private fun refreshQuickPanelLabels(trackOffsetMs: Long = 0L) {
        val activeDisplay = sessionState.effectiveDisplayMode(config)
        val posModeLabel = if (config.autoHudSafePositioning) {
            "HUD-Safe Default"
        } else {
            "Custom (${(config.manualPosXFraction * 100).roundToInt()}%, ${(config.manualPosYFraction * 100).roundToInt()}%)"
        }
        val lockStatusLabel = if (config.positionLocked) "🔒 Locked" else "🔓 Unlocked"
        val placementNote = when {
            cachedPlacement?.shouldTemporarilyHide == true -> " · Auto-hidden (HUD tight)"
            cachedPlacement?.autoRepositioned == true -> " · Auto-shifted (${cachedPlacement?.selectedSlotName})"
            else -> ""
        }
        val modeLabel = if (sessionState.sessionDisabled) {
            "Disabled for this session"
        } else {
            "${activeDisplay.name.replace('_', ' ')} · $posModeLabel · $lockStatusLabel$placementNote"
        }
        val songLabel = if (lastMediaState.hasActiveSession) {
            "${lastMediaState.track.title} — ${lastMediaState.track.artist}".trim(' ', '—')
        } else {
            "No active track"
        }
        quickPanelStatusText.text = "$modeLabel · $songLabel"
        quickPanelFullBtn.alpha = if (activeDisplay == LiveLyricsDisplayMode.FULL_MUSIC) 1.0f else 0.62f
        quickPanelCompactBtn.alpha = if (activeDisplay == LiveLyricsDisplayMode.COMPACT_MUSIC) 1.0f else 0.62f
        quickPanelLyricsOnlyBtn.alpha = if (activeDisplay == LiveLyricsDisplayMode.LYRICS_ONLY) 1.0f else 0.62f

        quickPanelLockBtn.text = if (config.positionLocked) "🔒 Locked" else "🔓 Unlocked"
        quickPanelLockBtn.alpha = 1.0f
        quickPanelResetPosBtn.text = "Reset Position"
        quickPanelEditPosBtn.text = if (positionEditingMode) "Exit Edit Mode" else "Edit Position"
        quickPanelEditPosBtn.alpha = if (positionEditingMode) 1.0f else 0.85f

        quickPanelShowHideBtn.text = if (effectiveMode == IslandDisplayMode.HIDDEN) "Show Pill" else "Hide Pill"
        quickPanelExpandBtn.text = if (effectiveMode == IslandDisplayMode.EXPANDED) "Collapse" else "Expand"
        quickPanelExpandBtn.isEnabled = activeDisplay != LiveLyricsDisplayMode.LYRICS_ONLY
        quickPanelExpandBtn.alpha = if (activeDisplay == LiveLyricsDisplayMode.LYRICS_ONLY) 0.4f else 1f
        quickPanelSessionBtn.text = if (sessionState.sessionDisabled) "Re-Enable" else "Disable Session"
        quickPanelOffsetLabel.text = String.format(Locale.US, "Sync Offset: %+d ms", trackOffsetMs)
    }

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener == null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                val throttled = status >= PowerManager.THERMAL_STATUS_MODERATE
                if (thermalThrottled != throttled) {
                    thermalThrottled = throttled
                    post {
                        compactVisualizer.setPlaybackAndQuality(
                            playing = lastMediaState.playback.isPlaying && config.decorativeVisualizerEnabled && effectiveMode == IslandDisplayMode.COMPACT,
                            animEnabled = config.animationsEnabled,
                            animQuality = effectiveAnimationQuality(),
                            speed = config.animationSpeed
                        )
                    }
                }
            }
            pm.addThermalStatusListener(listener)
            thermalListener = listener
        }
    }

    private fun unregisterThermalListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val l = thermalListener as? PowerManager.OnThermalStatusChangedListener ?: return
            runCatching { pm.removeThermalStatusListener(l) }
            thermalListener = null
        }
    }

    private fun createHeaderIconButton(label: String, widthDp: Int = 28, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(0xFFD6E4FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 2) 11f else 12.5f)
            background = roundedShape(0x28FFFFFF, dp(14).toFloat(), 0x26FFFFFF)
            if (widthDp > 28) {
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createControlButton(symbol: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = symbol
            gravity = Gravity.CENTER
            setTextColor(0xFFEAF1FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = roundedShape(0x1EFFFFFF, dp(12).toFloat(), 0x1EFFFFFF)
            setOnClickListener { onClick() }
        }
    }

    private fun createActionChip(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(0xFFEAF1FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = roundedShape(0xFF1A2338.toInt(), dp(10).toFloat(), 0x445EE2FF)
            setOnClickListener { onClick() }
        }
    }

    private fun roundedShape(fillColor: Int, radiusPx: Float, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return String.format(Locale.US, "%d:%02d", min, sec)
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    /**
     * Damped harmonic spring curve for smooth Dynamic Island expansion/collapse without harsh bouncing.
     */
    private class SpringLikeInterpolator(private val damping: Float = 0.80f) : TimeInterpolator {
        private val omega = 12.5f
        override fun getInterpolation(t: Float): Float {
            if (t <= 0f) return 0f
            if (t >= 1f) return 1f
            val decay = exp(-damping * omega * t)
            val freq = omega * kotlin.math.sqrt((1f - damping * damping).coerceAtLeast(0.05f))
            val raw = 1f - decay * (cos(freq * t) + (damping * omega / freq) * sin(freq * t))
            return raw.coerceIn(0f, 1.06f)
        }
    }

    /**
     * Ambient translucent obsidian glass card with subtle dynamic album-art tint,
     * specular border, subtle editing outline, and full multi-mode drag interception.
     */
    private inner class AmbientGlassCard(context: Context) : FrameLayout(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.1f * density
        }
        private val editOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
            color = 0xCC5EE2FF.toInt()
            pathEffect = DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
        }
        private val editHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF5EE2FF.toInt()
        }
        private val cardRect = RectF()
        private val handleRect = RectF()
        private val clipPath = Path()
        private var cornerRadiusPx = 19f * density
        private var cardAccent = MediaTrackInfo.DEFAULT_ACCENT_COLOR
        private var showEditingOutline = false
        private var isActivelyDragging = false
        private var isCardLocked = false

        init {
            setWillNotDraw(false)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            rebuildShaders(100f, 40f)
        }

        fun setEditingVisualState(editingOrSelected: Boolean, dragging: Boolean, locked: Boolean) {
            if (showEditingOutline != editingOrSelected || isActivelyDragging != dragging || isCardLocked != locked) {
                showEditingOutline = editingOrSelected
                isActivelyDragging = dragging
                isCardLocked = locked
                invalidate()
            }
        }

        fun setAccentColor(accent: Int) {
            if (cardAccent != accent) {
                cardAccent = accent
                rebuildShaders(width.toFloat(), height.toFloat())
                invalidate()
            }
        }

        fun setCornerRadius(radiusPx: Float) {
            if (abs(cornerRadiusPx - radiusPx) >= 0.5f) {
                cornerRadiusPx = radiusPx
                invalidate()
            }
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (config.positionLocked && !positionEditingMode) {
                // When Locked outside of editing mode, completely ignore touch gestures on the overlay
                return false
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (config.positionLocked) {
                return positionEditingMode
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onPillTouchDown(ev)
                    return positionEditingMode
                }
                MotionEvent.ACTION_MOVE -> {
                    val idx = ev.findPointerIndex(activePointerId).let { if (it < 0) 0 else it }
                    val curX = left + ev.getX(idx) * scaleX
                    val curY = top + ev.getY(idx) * scaleY
                    if (hypot(curX - downParentX, curY - downParentY) > touchSlop) {
                        return true
                    }
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            return handlePillTouchEvent(event)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuildShaders(w.toFloat(), h.toFloat())
        }

        private fun rebuildShaders(w: Float, h: Float) {
            val safeW = w.coerceAtLeast(1f)
            val safeH = h.coerceAtLeast(1f)
            fillPaint.shader = LinearGradient(
                0f, 0f, 0f, safeH,
                intArrayOf(0xE60B101C.toInt(), 0xDE131B2E.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
            val accentSoft = Color.argb(44, Color.red(cardAccent), Color.green(cardAccent), Color.blue(cardAccent))
            tintPaint.shader = LinearGradient(
                0f, 0f, safeW * 0.75f, safeH,
                intArrayOf(accentSoft, 0x00000000),
                null,
                Shader.TileMode.CLAMP
            )
            val borderAccent = Color.argb(95, Color.red(cardAccent), Color.green(cardAccent), Color.blue(cardAccent))
            borderPaint.shader = LinearGradient(
                0f, 0f, safeW, safeH,
                intArrayOf(0x4AFFFFFF, borderAccent, 0x26FFFFFF),
                null,
                Shader.TileMode.CLAMP
            )
        }

        override fun dispatchDraw(canvas: Canvas) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val w = width.toFloat()
            val h = height.toFloat()
            if (w > 0f && h > 0f) {
                cardRect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
                clipPath.reset()
                clipPath.addRoundRect(cardRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)

                canvas.drawRoundRect(cardRect, cornerRadiusPx, cornerRadiusPx, fillPaint)
                canvas.drawRoundRect(cardRect, cornerRadiusPx, cornerRadiusPx, tintPaint)

                val save = canvas.save()
                canvas.clipPath(clipPath)
                super.dispatchDraw(canvas)
                canvas.restoreToCount(save)

                canvas.drawRoundRect(cardRect, cornerRadiusPx, cornerRadiusPx, borderPaint)

                if (showEditingOutline) {
                    editOutlinePaint.color = when {
                        isCardLocked -> 0xCCF59E0B.toInt()
                        isActivelyDragging -> 0xFF5EE2FF.toInt()
                        else -> 0xAA5EE2FF.toInt()
                    }
                    editOutlinePaint.pathEffect = if (isActivelyDragging) {
                        null
                    } else {
                        DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
                    }
                    cardRect.set(1.2f * density, 1.2f * density, w - 1.2f * density, h - 1.2f * density)
                    canvas.drawRoundRect(cardRect, cornerRadiusPx, cornerRadiusPx, editOutlinePaint)

                    if (positionEditingMode && !isCardLocked) {
                        val hs = 4.5f * density
                        editHandlePaint.color = 0xDD5EE2FF.toInt()
                        handleRect.set(2f * density, h * 0.5f - hs, 2f * density + hs * 0.7f, h * 0.5f + hs)
                        canvas.drawRoundRect(handleRect, hs, hs, editHandlePaint)
                        handleRect.set(w - 2f * density - hs * 0.7f, h * 0.5f - hs, w - 2f * density, h * 0.5f + hs)
                        canvas.drawRoundRect(handleRect, hs, hs, editHandlePaint)
                    }
                }
            } else {
                super.dispatchDraw(canvas)
            }
            val elapsedNs = SystemClock.elapsedRealtimeNanos() - t0
            benchmark.recordDrawDurationNs(elapsedNs)
            benchmark.recordFrameTimestampNs(t0)
        }
    }

    private class ThinProgressView(context: Context) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33FFFFFF
        }
        private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MediaTrackInfo.DEFAULT_ACCENT_COLOR
        }
        private val rect = RectF()
        private var progress = 0f

        fun setAccentColor(color: Int) {
            if (fgPaint.color != color) {
                fgPaint.color = color
                invalidate()
            }
        }

        fun setProgress(value: Float) {
            val clamped = value.coerceIn(0f, 1f)
            if (abs(clamped - progress) >= 0.005f) {
                progress = clamped
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            if (h <= 0f || w <= 0f) return
            val barH = min(h, 3.5f * resources.displayMetrics.density)
            val top = (h - barH) * 0.5f
            val r = barH * 0.5f
            rect.set(0f, top, w, top + barH)
            canvas.drawRoundRect(rect, r, r, bgPaint)
            if (progress > 0f) {
                rect.set(0f, top, (w * progress).coerceAtLeast(barH), top + barH)
                canvas.drawRoundRect(rect, r, r, fgPaint)
            }
        }
    }

    private class MorphPlayPauseButton(context: Context) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x2EFFFFFF
        }
        private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF5F8FF.toInt()
        }
        private val rect = RectF()
        private val playPath = Path()
        private var playing = false
        private var morph = 0f // 0 = play triangle, 1 = pause bars
        private var anim: ValueAnimator? = null

        fun setPlaying(isPlaying: Boolean, animate: Boolean) {
            if (playing == isPlaying) return
            playing = isPlaying
            val target = if (isPlaying) 1f else 0f
            anim?.cancel()
            if (!animate) {
                morph = target
                invalidate()
                return
            }
            anim = ValueAnimator.ofFloat(morph, target).apply {
                duration = 170L
                addUpdateListener {
                    morph = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, h * 0.45f, h * 0.45f, bgPaint)

            val cx = w * 0.5f
            val cy = h * 0.5f
            val size = min(w, h) * 0.36f

            if (morph < 0.5f) {
                val scale = 1f - morph * 0.4f
                val half = size * scale
                playPath.reset()
                playPath.moveTo(cx - half * 0.42f, cy - half * 0.65f)
                playPath.lineTo(cx + half * 0.72f, cy)
                playPath.lineTo(cx - half * 0.42f, cy + half * 0.65f)
                playPath.close()
                fgPaint.alpha = ((1f - morph * 1.6f).coerceIn(0f, 1f) * 255).toInt()
                canvas.drawPath(playPath, fgPaint)
            } else {
                val barW = size * 0.28f
                val gap = size * 0.22f
                val halfH = size * 0.58f * ((morph - 0.5f) * 0.4f + 0.8f)
                fgPaint.alpha = (((morph - 0.5f) * 2f).coerceIn(0f, 1f) * 255).toInt()
                rect.set(cx - gap * 0.5f - barW, cy - halfH, cx - gap * 0.5f, cy + halfH)
                canvas.drawRoundRect(rect, barW * 0.3f, barW * 0.3f, fgPaint)
                rect.set(cx + gap * 0.5f, cy - halfH, cx + gap * 0.5f + barW, cy + halfH)
                canvas.drawRoundRect(rect, barW * 0.3f, barW * 0.3f, fgPaint)
            }
        }
    }
}
