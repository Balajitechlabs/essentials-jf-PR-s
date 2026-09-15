/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities - Overlays
 * File: IslandOverlayView.kt
 * Description: Dynamic island notification pill overlay view expanding from camera cutout.
 */

package com.sameerasw.essentials.utils

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.sameerasw.essentials.R
import com.sameerasw.essentials.domain.model.ActiveNotificationAlert
import com.sameerasw.essentials.domain.model.NotificationActionItem
import com.sameerasw.essentials.services.handlers.IslandTouchHandler
import com.sameerasw.essentials.utils.island.AnimatedFloatProperty
import com.sameerasw.essentials.utils.island.IslandBubbleIconShape
import com.sameerasw.essentials.utils.island.IslandBubbleRow
import com.sameerasw.essentials.utils.island.IslandBubbleSide
import com.sameerasw.essentials.utils.island.IslandBubbleSpec
import com.sameerasw.essentials.utils.island.IslandTransitionSpec
import com.sameerasw.essentials.utils.island.MarqueeController
import kotlin.math.abs

class IslandOverlayView(context: Context) : View(context) {
    enum class DragCollapseTarget {
        CAMERA,
        COMPACT,
    }

    private companion object {
        private const val MEDIA_BUBBLE_KEY = "media"
        private const val TOTAL_BUBBLE_BUDGET = 2
    }

    private val density = resources.displayMetrics.density

    var touchHandler: IslandTouchHandler? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isNotificationAlertActive && !isMediaPlaybackActive) return false
        val x = event.x
        val y = event.y

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val totalBounds = getTotalAlertsBounds()
            val pad = 16f * density
            val expandedRect = RectF(
                totalBounds.left - pad,
                totalBounds.top - pad,
                totalBounds.right + pad,
                totalBounds.bottom + pad,
            )
            if (!expandedRect.contains(x, y)) {
                return false
            }
        }

        return touchHandler?.onTouchEvent(event) ?: super.onTouchEvent(event)
    }

    var cameraCenterX: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var cameraCenterY: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var cameraRadiusPx: Float = 36f
        set(value) {
            field = value
            invalidate()
        }

    var maxWidthDp: Float = 360f
        set(value) {
            field = value
            invalidate()
        }

    var expandedWidthDp: Float = 360f
        set(value) {
            field = value
            invalidate()
        }

    var expandedCornerRadiusDp: Float = 24f
        set(value) {
            field = value
            invalidate()
        }

    var expandedPaddingDp: Float = 16f
        set(value) {
            field = value
            invalidate()
        }

    var expandedTopPaddingDp: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var isIslandEnabled: Boolean = true
    var isShowGlow: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var isCatchUpMode: Boolean = false
        private set
    var canEnterCatchUp: Boolean = true
    var catchUpFraction: Float = 0f
        private set
    private val catchUpAnimator = AnimatedFloatProperty()
    var onCatchUpModeChanged: ((Boolean) -> Unit)? = null

    private val unreadIconBitmap: Bitmap? by lazy {
        try {
            val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.rounded_notifications_unread_24)
            if (d != null) {
                AppUtil.drawableToBitmap(d, (24f * density).toInt())
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private val musicIconBitmap: Bitmap? by lazy {
        try {
            val drawable = ContextCompat.getDrawable(context, R.drawable.rounded_music_note_24)
            drawable?.let { AppUtil.drawableToBitmap(it, (24f * density).toInt()) }
        } catch (_: Exception) {
            null
        }
    }

    private var activeNotificationAlert: ActiveNotificationAlert? = null
    var isNotificationAlertActive: Boolean = false
        private set
    var isMediaPlaybackActive: Boolean = false
        private set
    var isMediaCompact: Boolean = false
        private set
    private var mediaTitle: String = ""
    private var mediaArtist: String = ""
    private var mediaArtwork: Bitmap? = null
    private val mediaAnimator = AnimatedFloatProperty()
    private val mediaCompactAnimator = AnimatedFloatProperty()
    private val mediaBubbleAnimator = AnimatedFloatProperty()
    private var mediaFraction: Float = 0f
    private var mediaCompactFraction: Float = 0f
    private var mediaBubbleFraction: Float = 0f
    private val mediaPillRect = RectF()
    var animatedNotificationFraction: Float = 0f
        private set
    private val notificationAnimator = AnimatedFloatProperty()
    private val notificationPillRect = RectF()
    private val notificationIconClipPath = Path()
    private val notificationContentClipPath = Path()
    private val catchUpIconClipPath = Path()

    private val queuedNotificationAlerts = mutableListOf<ActiveNotificationAlert>()
    private val bubbleFractions = floatArrayOf(0f, 0f)
    private val bubbleAnimators = arrayOf(AnimatedFloatProperty(), AnimatedFloatProperty())
    private val catchUpUnreadIconRect = RectF()

    private val leadingBubbleRects = mutableMapOf<Any, RectF>()
    private val trailingBubbleRects = mutableMapOf<Any, RectF>()

    // Media takes one of the TOTAL_BUBBLE_BUDGET slots when docked; the queue gets the rest.
    private val isMediaBubbleOccupyingSlot: Boolean
        get() = isMediaPlaybackActive && !isCatchUpMode
    private val maxQueuedBubbleSlots: Int
        get() = TOTAL_BUBBLE_BUDGET - (if (isMediaBubbleOccupyingSlot) 1 else 0)

    private fun trimQueueTo(maxSize: Int) {
        while (queuedNotificationAlerts.size > maxSize) {
            queuedNotificationAlerts.removeAt(0)
            bubbleFractions[0] = bubbleFractions[1]
            bubbleFractions[1] = 0f
        }
    }

    private var isMerging: Boolean = false
    private var mergeFraction: Float = 1.0f
    private val mergeAnimator = AnimatedFloatProperty()
    private var previousAlert: ActiveNotificationAlert? = null
    private var mergeSourceBubbleLeft: Float = 0f
    private var mergeSourceBubbleCenterX: Float = 0f
    private var mergeSourceBubbleCenterY: Float = 0f
    private var mergeSourcePillLeft: Float = 0f
    private var mergeSourcePillRight: Float = 0f

    private val leftMarquee = MarqueeController()
    private val rightMarquee = MarqueeController()
    private val marqueeFadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    var dragTranslationX: Float = 0f
        private set
    var dragTranslationY: Float = 0f
        private set
    var dragScale: Float = 1.0f
        private set

    var dragCollapseFraction: Float = 0f
        private set
    private var dragCollapseTarget = DragCollapseTarget.CAMERA

    fun updateDragCollapseFraction(
        fraction: Float,
        target: DragCollapseTarget = DragCollapseTarget.CAMERA,
    ) {
        dragCollapseTarget = target
        dragCollapseFraction = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    fun updateDragTranslation(dx: Float, dy: Float = 0f) {
        dragTranslationX = dx
        dragTranslationY = dy
        invalidate()
    }

    fun resetDragOffset() {
        dragCollapseFraction = 0f
        dragTranslationX = 0f
        dragTranslationY = 0f
        dragScale = 1.0f
        invalidate()
    }

    private val dragAnimator = AnimatedFloatProperty()

    fun animateHorizontalSwipeDismiss(direction: Float, onEnd: () -> Unit) {
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val startX = dragTranslationX
        val targetX = if (direction > 0f) (screenWidth * 0.85f) else (-screenWidth * 0.85f)
        val startNotif = animatedNotificationFraction
        dragAnimator.animateTo(
            from = 0f,
            to = 1.0f,
            spec = IslandTransitionSpec.SwipeDismiss,
            onUpdate = { f ->
                dragTranslationX = startX + (targetX - startX) * f
                animatedNotificationFraction = startNotif * (1f - f)
                invalidate()
            },
            onEnd = {
                resetDragOffset()
                onEnd()
            },
        )
    }

    fun animateDragDismissCollapse(
        target: DragCollapseTarget = DragCollapseTarget.CAMERA,
        onEnd: () -> Unit,
    ) {
        dragCollapseTarget = target
        val startVal = dragCollapseFraction
        dragAnimator.animateTo(
            from = startVal,
            to = 1.0f,
            spec = IslandTransitionSpec.DragCollapse,
            onUpdate = {
                dragCollapseFraction = it
                invalidate()
            },
            onEnd = {
                resetDragOffset()
                onEnd()
            },
        )
    }

    fun animateDragSnapBack() {
        val startVal = dragCollapseFraction
        val startX = dragTranslationX
        val startY = dragTranslationY
        val startScale = dragScale

        dragAnimator.animateTo(
            from = 0f,
            to = 1f,
            spec = IslandTransitionSpec.DragSnapBack,
            onUpdate = { f ->
                dragCollapseFraction = startVal * (1f - f)
                dragTranslationX = startX * (1f - f)
                dragTranslationY = startY * (1f - f)
                dragScale = startScale + (1.0f - startScale) * f
                invalidate()
            },
            onEnd = {
                dragCollapseTarget = DragCollapseTarget.CAMERA
            },
        )
    }

    var onDismissAnimationEnd: (() -> Unit)? = null
    var onAlertsChanged: (() -> Unit)? = null

    private val notificationPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    private val googleSansFlexTypeface: Typeface? by lazy {
        try {
            ResourcesCompat.getFont(context, R.font.google_sans_flex)
        } catch (_: Exception) {
            null
        }
    }

    private val notificationSenderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val notificationBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val catchUpUnreadPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val actionButtonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(40, 255, 255, 255)
    }

    private val actionButtonTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val actionButtonPath = Path()
    private val actionButtonRects = mutableMapOf<NotificationActionItem, RectF>()
    private var highlightedAction: NotificationActionItem? = null
    private var actionExecutionFraction: Float = 0f
    private val actionExecutionAnimator = AnimatedFloatProperty()

    fun animateActionExecution(action: NotificationActionItem, onComplete: () -> Unit) {
        highlightedAction = action
        actionExecutionFraction = 0f
        actionExecutionAnimator.animateTo(
            from = 0f,
            to = 1f,
            spec = IslandTransitionSpec.ActionExecution,
            onUpdate = {
                actionExecutionFraction = it
                invalidate()
            },
            onEnd = {
                highlightedAction = null
                actionExecutionFraction = 0f
                invalidate()
                onComplete()
            },
        )
    }

    fun showNotificationAlert(alert: ActiveNotificationAlert) {
        if (!isIslandEnabled) return

        if (isMediaPlaybackActive) setMediaBubbleVisible(true)
        canEnterCatchUp = true

        if (isCatchUpMode) {
            if (activeNotificationAlert?.key == alert.key) {
                activeNotificationAlert = alert
                invalidate()
            } else {
                dismissCatchUpAndShow(alert)
            }
            return
        }

        if (!isNotificationAlertActive || activeNotificationAlert == null) {
            activeNotificationAlert = alert
            isNotificationAlertActive = true

            val startVal = animatedNotificationFraction
            notificationAnimator.animateTo(
                from = startVal,
                to = 1.0f,
                spec = IslandTransitionSpec.ContentShow,
                onUpdate = {
                    animatedNotificationFraction = it
                    invalidate()
                },
            )
            onAlertsChanged?.invoke()
            return
        }

        if (activeNotificationAlert?.key == alert.key) {
            activeNotificationAlert = alert
            invalidate()
            return
        }

        val existingIdx = queuedNotificationAlerts.indexOfFirst { it.key == alert.key }
        if (existingIdx >= 0) {
            queuedNotificationAlerts[existingIdx] = alert
            invalidate()
            return
        }

        trimQueueTo((maxQueuedBubbleSlots - 1).coerceAtLeast(0))
        queuedNotificationAlerts.add(alert)
        val bubbleIdx = queuedNotificationAlerts.size - 1
        animateBubbleIn(bubbleIdx)
        onAlertsChanged?.invoke()
    }

    fun showMediaPlayback(title: String, artist: String, artwork: Bitmap?) {
        if (!isIslandEnabled) return

        val wasActive = isMediaPlaybackActive
        isMediaPlaybackActive = true
        mediaTitle = title
        mediaArtist = artist
        mediaArtwork = artwork
        if (isNotificationAlertActive) setMediaBubbleVisible(true)
        if (!wasActive) {
            trimQueueTo(maxQueuedBubbleSlots)
            isMediaCompact = false
            mediaCompactFraction = 0f
            mediaBubbleFraction = if (isNotificationAlertActive) 1f else 0f
            mediaAnimator.animateTo(
                from = mediaFraction,
                to = 1f,
                spec = IslandTransitionSpec.ContentShow,
                onUpdate = {
                    mediaFraction = it
                    invalidate()
                },
            )
            onAlertsChanged?.invoke()
        } else {
            invalidate()
        }
    }

    fun setMediaCompact(compact: Boolean) {
        if (!isMediaPlaybackActive || isMediaCompact == compact) return
        isMediaCompact = compact
        mediaCompactAnimator.animateTo(
            from = mediaCompactFraction,
            to = if (compact) 1f else 0f,
            spec = IslandTransitionSpec.ModeChange,
            onUpdate = {
                mediaCompactFraction = it
                invalidate()
            },
        )
        onAlertsChanged?.invoke()
    }

    fun dismissMediaPlayback() {
        if (!isMediaPlaybackActive) return
        mediaCompactAnimator.cancel()
        mediaBubbleAnimator.cancel()
        mediaAnimator.animateTo(
            from = mediaFraction,
            to = 0f,
            spec = IslandTransitionSpec.MediaDismiss,
            onUpdate = {
                mediaFraction = it
                invalidate()
            },
            onEnd = {
                isMediaPlaybackActive = false
                isMediaCompact = false
                mediaFraction = 0f
                mediaCompactFraction = 0f
                mediaBubbleFraction = 0f
                mediaTitle = ""
                mediaArtist = ""
                mediaArtwork = null
                onDismissAnimationEnd?.invoke()
                onAlertsChanged?.invoke()
            },
        )
    }

    private fun setMediaBubbleVisible(visible: Boolean) {
        if (!isMediaPlaybackActive) return
        val target = if (visible) 1f else 0f
        mediaBubbleAnimator.animateTo(
            from = mediaBubbleFraction,
            to = target,
            spec = IslandTransitionSpec.ModeChange,
            onUpdate = {
                mediaBubbleFraction = it
                invalidate()
                onAlertsChanged?.invoke()
            },
        )
    }

    private fun animateBubbleIn(index: Int) {
        if (index !in 0..1) return
        val startVal = bubbleFractions[index]
        bubbleAnimators[index].animateTo(
            from = startVal,
            to = 1.0f,
            spec = IslandTransitionSpec.BubbleIn,
            onUpdate = {
                bubbleFractions[index] = it
                invalidate()
            },
        )
    }

    fun getQueuedAlertIndexAt(x: Float, y: Float): Int {
        if (expandedFraction > 0.1f) return -1
        for (i in queuedNotificationAlerts.indices) {
            val rect = leadingBubbleRects[i] ?: continue
            if (i in 0..1 && bubbleFractions[i] > 0.3f) {
                val pad = 6f * density
                val touchRect = RectF(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)
                if (touchRect.contains(x, y)) {
                    return i
                }
            }
        }
        return -1
    }

    fun switchToQueuedNotification(index: Int): Boolean {
        if (index !in queuedNotificationAlerts.indices) return false
        stopAllMarquees()

        val outgoingAlert = activeNotificationAlert
        val incomingAlert = queuedNotificationAlerts.removeAt(index)

        mergeSourcePillLeft = notificationPillRect.left
        mergeSourcePillRight = notificationPillRect.right
        val sourceBubbleRect = leadingBubbleRects[index]
        mergeSourceBubbleLeft = sourceBubbleRect?.left?.takeIf { it > 0f } ?: (notificationPillRect.left - 40f * density)
        mergeSourceBubbleCenterX = sourceBubbleRect?.centerX()?.takeIf { it > 0f } ?: (mergeSourceBubbleLeft + 20f * density)
        mergeSourceBubbleCenterY = sourceBubbleRect?.centerY()?.takeIf { it > 0f } ?: notificationPillRect.centerY()

        previousAlert = outgoingAlert
        activeNotificationAlert = incomingAlert
        isMerging = true
        mergeFraction = 0f

        if (index == 0 && queuedNotificationAlerts.isNotEmpty()) {
            bubbleFractions[0] = bubbleFractions[1]
            bubbleFractions[1] = 0f
        } else {
            bubbleFractions[index] = 0f
            if (queuedNotificationAlerts.isEmpty()) {
                bubbleFractions[0] = 0f
                bubbleFractions[1] = 0f
            }
        }

        mergeAnimator.animateTo(
            from = 0f,
            to = 1.0f,
            spec = IslandTransitionSpec.Merge,
            onUpdate = {
                mergeFraction = it
                invalidate()
            },
            onEnd = {
                isMerging = false
                previousAlert = null
                mergeFraction = 1.0f
                invalidate()
            },
        )

        onAlertsChanged?.invoke()
        return true
    }

    var isExpanded: Boolean = false
        private set
    var expandedFraction: Float = 0f
        private set
    private val expansionAnimator = AnimatedFloatProperty()

    private var onExpandedStateChanged: ((Boolean) -> Unit)? = null

    fun setOnExpandedStateChangedListener(listener: ((Boolean) -> Unit)?) {
        onExpandedStateChanged = listener
    }

    fun toggleExpansion(): Boolean {
        if (!isNotificationAlertActive && !isMediaPlaybackActive) return false
        setExpandedState(!isExpanded)
        return isExpanded
    }

    fun setExpandedState(expand: Boolean) {
        if (isExpanded == expand) return
        isExpanded = expand
        stopAllMarquees()

        val startVal = expandedFraction
        val targetVal = if (expand) 1.0f else 0.0f
        expansionAnimator.animateTo(
            from = startVal,
            to = targetVal,
            spec = if (expand) IslandTransitionSpec.ExpandOpen else IslandTransitionSpec.ModeChange,
            onUpdate = {
                expandedFraction = it
                invalidate()
            },
            onEnd = {
                onAlertsChanged?.invoke()
            },
        )
        onExpandedStateChanged?.invoke(expand)
        onAlertsChanged?.invoke()
    }

    fun enterCatchUpMode() {
        if (!isNotificationAlertActive || activeNotificationAlert == null || isCatchUpMode) return
        isCatchUpMode = true
        if (isMediaPlaybackActive) setMediaBubbleVisible(false)
        if (isExpanded) {
            isExpanded = false
            expandedFraction = 0f
            expansionAnimator.cancel()
        }
        stopAllMarquees()
        val startVal = catchUpFraction
        catchUpAnimator.animateTo(
            from = startVal,
            to = 1.0f,
            spec = IslandTransitionSpec.ModeChange,
            onUpdate = {
                catchUpFraction = it
                invalidate()
            },
            onEnd = {
                onAlertsChanged?.invoke()
            },
        )
        onCatchUpModeChanged?.invoke(true)
        onAlertsChanged?.invoke()
    }

    fun exitCatchUpMode() {
        if (!isCatchUpMode) return
        isCatchUpMode = false
        if (isMediaPlaybackActive) setMediaBubbleVisible(true)
        val startVal = catchUpFraction
        catchUpFraction = 0f
        catchUpAnimator.animateTo(
            from = startVal,
            to = 0.0f,
            spec = IslandTransitionSpec.ExpandOpen,
            onUpdate = {
                catchUpFraction = it
                invalidate()
            },
            onEnd = {
                onAlertsChanged?.invoke()
            },
        )
        onCatchUpModeChanged?.invoke(false)
        onAlertsChanged?.invoke()
    }

    private fun dismissCatchUpAndShow(newAlert: ActiveNotificationAlert) {
        stopAllMarquees()
        isCatchUpMode = false
        canEnterCatchUp = true
        catchUpAnimator.cancel()

        val startNotif = animatedNotificationFraction
        val startCatchUp = catchUpFraction
        notificationAnimator.animateTo(
            from = 1.0f,
            to = 0.0f,
            spec = IslandTransitionSpec.notificationDismiss(wasExpanded = false),
            onUpdate = { f ->
                animatedNotificationFraction = startNotif * f
                catchUpFraction = startCatchUp * f
                invalidate()
            },
            onEnd = {
                animatedNotificationFraction = 0f
                catchUpFraction = 0f
                activeNotificationAlert = null
                isNotificationAlertActive = false
                showNotificationAlert(newAlert)
            },
        )
        onCatchUpModeChanged?.invoke(false)
        onAlertsChanged?.invoke()
    }

    private fun computeExpandedHeight(alert: ActiveNotificationAlert, width: Float): Float {
        val basePillHeight = cameraRadiusPx * 2f + 14f * density
        val (_, message) = computeSenderAndMessage(alert)
        if (message.isBlank()) {
            return basePillHeight
        }

        val textSize = (basePillHeight * 0.38f).coerceIn(14f * density, 18f * density)
        val bodyTextPaint = TextPaint().apply {
            set(notificationBodyPaint)
            typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif", Typeface.NORMAL)
            setTextSize(textSize)
        }
        val cornerExtraPadding = (expandedCornerRadiusDp * 0.35f * density)
        val innerPadding = (expandedPaddingDp * density) + cornerExtraPadding
        val textWidth = (width - innerPadding * 2).toInt().coerceAtLeast(50)

        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(message, 0, message.length, bodyTextPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f * density, 1.0f)
                .setMaxLines(7)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                message,
                bodyTextPaint,
                textWidth,
                Layout.Alignment.ALIGN_NORMAL,
                1.0f,
                2f * density,
                true,
            )
        }

        val textHeight = layout.height.toFloat()
        val topPad = expandedTopPaddingDp * density
        val hasActions = alert.actions.isNotEmpty()
        val headerSpacing = if (hasActions) 0f else (4f * density)
        val actionsHeight = if (hasActions) 46f * density else 0f
        val bottomPad = if (hasActions) {
            (expandedPaddingDp * 0.70f * density) + cornerExtraPadding * 0.3f
        } else {
            (expandedPaddingDp * 0.90f * density) + cornerExtraPadding * 0.3f
        }
        val totalHeight = basePillHeight + topPad + headerSpacing + textHeight + actionsHeight + bottomPad
        return totalHeight.coerceAtLeast(basePillHeight)
    }

    private fun computeCollapsedTargetBounds(alert: ActiveNotificationAlert): RectF {
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val targetPillHeight = cameraRadiusPx * 2f + 14f * density
        val targetTop = cameraCenterY - targetPillHeight / 2f
        val targetBottom = cameraCenterY + targetPillHeight / 2f
        val iconSize = (targetPillHeight - 14f * density).coerceAtLeast(16f * density)
        val verticalPadding = (targetPillHeight - iconSize) / 2f

        val textSize = (targetPillHeight * 0.38f).coerceIn(13f * density, 20f * density)
        notificationSenderPaint.typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        notificationSenderPaint.textSize = textSize
        notificationBodyPaint.typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif", Typeface.NORMAL)
        notificationBodyPaint.textSize = textSize

        val isCenterCamera = abs(cameraCenterX - screenWidth / 2f) < 50f * density

        if (isCenterCamera) {
            val minHalfWidth = cameraRadiusPx + iconSize + 24f * density
            val maxScreenHalfWidth = minOf(
                cameraCenterX - 8f * density,
                screenWidth - cameraCenterX - 8f * density,
            ).coerceAtLeast(minHalfWidth)
            val halfWidth = (maxWidthDp * density / 2f).coerceAtMost(maxScreenHalfWidth).coerceAtLeast(minHalfWidth)

            val targetLeft = cameraCenterX - halfWidth
            val targetRight = cameraCenterX + halfWidth

            return RectF(targetLeft, targetTop, targetRight, targetBottom)
        } else {
            val targetLeft = (cameraCenterX - cameraRadiusPx - verticalPadding).coerceAtLeast(8f * density)
            val maxAllowedWidthPx = (maxWidthDp * density).coerceAtMost(screenWidth - 16f * density)
            val targetRight = (targetLeft + maxAllowedWidthPx).coerceAtMost(screenWidth - 8f * density).coerceAtLeast(targetLeft + 80f * density)

            return RectF(targetLeft, targetTop, targetRight, targetBottom)
        }
    }

    private fun computeCatchUpTargetBounds(alert: ActiveNotificationAlert): RectF {
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val targetPillHeight = cameraRadiusPx * 2f + 14f * density
        val targetTop = cameraCenterY - targetPillHeight / 2f
        val targetBottom = cameraCenterY + targetPillHeight / 2f
        val iconSize = (targetPillHeight - 14f * density).coerceAtLeast(16f * density)
        val verticalPadding = (targetPillHeight - iconSize) / 2f
        val isCenterCamera = abs(cameraCenterX - screenWidth / 2f) < 50f * density
        val trailingSize = if (isMediaPlaybackActive) iconSize else (18f * density)

        if (isCenterCamera) {
            val leftDist = cameraRadiusPx + 10f * density + iconSize + verticalPadding + 4f * density
            val rightDist = cameraRadiusPx + 10f * density + trailingSize + verticalPadding + 4f * density
            return RectF(cameraCenterX - leftDist, targetTop, cameraCenterX + rightDist, targetBottom)
        } else {
            val targetLeft = (cameraCenterX - cameraRadiusPx - verticalPadding).coerceAtLeast(8f * density)
            val iconLeft = cameraCenterX + cameraRadiusPx + 12f * density
            val targetRight = (iconLeft + iconSize + 14f * density + trailingSize + verticalPadding + 6f * density).coerceAtMost(screenWidth - 8f * density)
            return RectF(targetLeft, targetTop, targetRight, targetBottom)
        }
    }

    private fun computeNotificationTargetBounds(alert: ActiveNotificationAlert): RectF {
        val normalBounds = computeCollapsedTargetBounds(alert)
        val collapsedBounds = if (catchUpFraction > 0f) {
            val catchUpBounds = computeCatchUpTargetBounds(alert)
            RectF(
                normalBounds.left + (catchUpBounds.left - normalBounds.left) * catchUpFraction,
                normalBounds.top + (catchUpBounds.top - normalBounds.top) * catchUpFraction,
                normalBounds.right + (catchUpBounds.right - normalBounds.right) * catchUpFraction,
                normalBounds.bottom + (catchUpBounds.bottom - normalBounds.bottom) * catchUpFraction,
            )
        } else {
            normalBounds
        }

        if (expandedFraction <= 0.001f && !isExpanded) {
            return collapsedBounds
        }

        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val isCenterCamera = abs(cameraCenterX - screenWidth / 2f) < 50f * density

        val maxAllowedWidthPx = (expandedWidthDp * density).coerceAtMost(screenWidth - 16f * density)
        val expTargetLeft: Float
        val expTargetRight: Float

        if (isCenterCamera) {
            val expHalfWidth = (maxAllowedWidthPx / 2f).coerceAtMost(minOf(cameraCenterX - 8f * density, screenWidth - cameraCenterX - 8f * density))
            expTargetLeft = cameraCenterX - expHalfWidth
            expTargetRight = cameraCenterX + expHalfWidth
        } else {
            val basePillHeight = cameraRadiusPx * 2f + 14f * density
            val iconSize = (basePillHeight - 14f * density).coerceAtLeast(16f * density)
            val verticalPadding = (basePillHeight - iconSize) / 2f
            expTargetLeft = (cameraCenterX - cameraRadiusPx - verticalPadding).coerceAtLeast(8f * density)
            expTargetRight = (expTargetLeft + maxAllowedWidthPx).coerceAtMost(screenWidth - 8f * density)
        }

        val expandedWidth = expTargetRight - expTargetLeft
        val expandedHeight = computeExpandedHeight(alert, expandedWidth)
        val expTargetTop = collapsedBounds.top
        val expTargetBottom = expTargetTop + expandedHeight

        val expLeft = collapsedBounds.left + (expTargetLeft - collapsedBounds.left) * expandedFraction
        val expRight = collapsedBounds.right + (expTargetRight - collapsedBounds.right) * expandedFraction
        val expBottom = collapsedBounds.bottom + (expTargetBottom - collapsedBounds.bottom) * expandedFraction

        return RectF(expLeft, expTargetTop, expRight, expBottom)
    }

    fun advanceToNextNotification(): Boolean {
        if (queuedNotificationAlerts.isNotEmpty()) {
            return switchToQueuedNotification(0)
        } else {
            if (isNotificationAlertActive) {
                dismissNotificationAlert()
            }
            return false
        }
    }

    fun removeNotificationByKey(key: String): Boolean {
        if (activeNotificationAlert?.key == key) {
            return advanceToNextNotification()
        }
        val idx = queuedNotificationAlerts.indexOfFirst { it.key == key }
        if (idx >= 0) {
            queuedNotificationAlerts.removeAt(idx)
            if (idx == 0 && queuedNotificationAlerts.isNotEmpty()) {
                bubbleFractions[0] = bubbleFractions[1]
                bubbleFractions[1] = 0f
            } else {
                bubbleFractions[idx] = 0f
            }
            invalidate()
            onAlertsChanged?.invoke()
            return true
        }
        return isNotificationAlertActive
    }

    fun dismissNotificationAlert() {
        if (!isNotificationAlertActive && activeNotificationAlert == null) return
        stopAllMarquees()
        isMerging = false
        previousAlert = null
        mergeFraction = 1.0f
        mergeAnimator.cancel()
        queuedNotificationAlerts.clear()
        bubbleFractions[0] = 0f
        bubbleFractions[1] = 0f
        for (i in 0..1) {
            bubbleAnimators[i].cancel()
        }

        expansionAnimator.cancel()
        catchUpAnimator.cancel()

        val startExpanded = expandedFraction
        val startNotif = animatedNotificationFraction

        notificationAnimator.animateTo(
            from = 1.0f,
            to = 0.0f,
            spec = IslandTransitionSpec.notificationDismiss(wasExpanded = startExpanded > 0.05f),
            onUpdate = { f ->
                animatedNotificationFraction = startNotif * f
                expandedFraction = startExpanded * f
                invalidate()
            },
            onEnd = {
                isExpanded = false
                expandedFraction = 0f
                isCatchUpMode = false
                catchUpFraction = 0f
                isNotificationAlertActive = false
                activeNotificationAlert = null
                animatedNotificationFraction = 0f
                if (isMediaPlaybackActive) setMediaBubbleVisible(false)
                invalidate()
                onDismissAnimationEnd?.invoke()
                onAlertsChanged?.invoke()
            },
        )
    }

    private fun stopAllMarquees() {
        leftMarquee.stop()
        rightMarquee.stop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        notificationAnimator.cancel()
        mergeAnimator.cancel()
        expansionAnimator.cancel()
        mediaBubbleAnimator.cancel()
        for (i in 0..1) {
            bubbleAnimators[i].cancel()
        }
        stopAllMarquees()
    }

    fun getActiveNotificationAlert(): ActiveNotificationAlert? = activeNotificationAlert

    fun getQueuedAlerts(): List<ActiveNotificationAlert> = queuedNotificationAlerts

    fun getNotificationPillBounds(): RectF = notificationPillRect

    fun getTotalAlertsBounds(): RectF {
        val alert = activeNotificationAlert
        if (alert == null) return if (isMediaPlaybackActive) computeMediaTargetBounds() else notificationPillRect
        val mainBounds = computeNotificationTargetBounds(alert)
        val numBubbles = queuedNotificationAlerts.size
        if (numBubbles == 0 || expandedFraction >= 0.99f) return unionWithMediaBounds(mainBounds)

        val basePillHeight = cameraRadiusPx * 2f + 14f * density
        val bubbleSize = basePillHeight
        val bubbleGap = 8f * density
        val totalBubblesWidth = numBubbles * (bubbleSize + bubbleGap) * (1f - expandedFraction)

        return unionWithMediaBounds(RectF(
            mainBounds.left - totalBubblesWidth,
            mainBounds.top,
            mainBounds.right,
            mainBounds.bottom,
        ))
    }

    fun getAlertAt(x: Float, y: Float): ActiveNotificationAlert? {
        if (expandedFraction < 0.5f) {
            for (i in queuedNotificationAlerts.indices) {
                if (i in 0..1 && bubbleFractions[i] > 0.5f) {
                    if (leadingBubbleRects[i]?.contains(x, y) == true) {
                        return queuedNotificationAlerts[i]
                    }
                }
            }
        }
        if (notificationPillRect.contains(x, y)) {
            return activeNotificationAlert
        }
        return null
    }

    fun isPointInsideActiveAlert(x: Float, y: Float): Boolean {
        if (!isNotificationAlertActive) return false
        val pad = 12f * density
        val bounds = getTotalAlertsBounds()
        val touchRect = RectF(bounds.left - pad, bounds.top - pad, bounds.right + pad, bounds.bottom + pad)
        return touchRect.contains(x, y)
    }

    fun getActionAt(x: Float, y: Float): NotificationActionItem? {
        if (expandedFraction < 0.6f) return null
        for ((action, rect) in actionButtonRects) {
            val pad = 6f * density
            val expandedRect = RectF(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)
            if (expandedRect.contains(x, y)) {
                return action
            }
        }
        return null
    }

    fun getNotificationTargetBounds(): RectF {
        return getTotalAlertsBounds()
    }

    fun isPointInsideMedia(x: Float, y: Float): Boolean {
        if (!isMediaPlaybackActive) return false
        val pad = 12f * density
        if (isNotificationAlertActive) {
            if (isCatchUpMode) {
                val rect = RectF(notificationPillRect.left - pad, notificationPillRect.top - pad, notificationPillRect.right + pad, notificationPillRect.bottom + pad)
                return rect.contains(x, y) && x >= notificationPillRect.centerX()
            }
            val mediaBubbleRect = trailingBubbleRects[MEDIA_BUBBLE_KEY] ?: return false
            return RectF(mediaBubbleRect.left - pad, mediaBubbleRect.top - pad, mediaBubbleRect.right + pad, mediaBubbleRect.bottom + pad).contains(x, y)
        }
        val bounds = currentMediaBounds()
        return RectF(bounds.left - pad, bounds.top - pad, bounds.right + pad, bounds.bottom + pad).contains(x, y)
    }

    private fun unionWithMediaBounds(bounds: RectF): RectF {
        if (!isMediaPlaybackActive || isCatchUpMode || mediaBubbleFraction <= 0.01f) return bounds
        val mediaBounds = currentMediaBounds()
        return RectF(
            minOf(bounds.left, mediaBounds.left),
            minOf(bounds.top, mediaBounds.top),
            maxOf(bounds.right, mediaBounds.right),
            maxOf(bounds.bottom, mediaBounds.bottom),
        )
    }

    private fun computeMediaBubbleBounds(): RectF {
        trailingBubbleRects[MEDIA_BUBBLE_KEY]?.let { return RectF(it) }
        val size = cameraRadiusPx * 2f + 14f * density
        val top = cameraCenterY - size / 2f
        val left = cameraCenterX + cameraRadiusPx + 8f * density
        val screenRight = resources.displayMetrics.widthPixels.toFloat() - 8f * density
        return RectF(left, top, minOf(left + size, screenRight), top + size)
    }

    private fun currentMediaBounds(): RectF {
        val fullBounds = computeMediaTargetBounds()
        if (!isNotificationAlertActive || mediaBubbleFraction <= 0.001f) return fullBounds
        val bubbleBounds = computeMediaBubbleBounds()
        return RectF(
            fullBounds.left + (bubbleBounds.left - fullBounds.left) * mediaBubbleFraction,
            fullBounds.top + (bubbleBounds.top - fullBounds.top) * mediaBubbleFraction,
            fullBounds.right + (bubbleBounds.right - fullBounds.right) * mediaBubbleFraction,
            fullBounds.bottom + (bubbleBounds.bottom - fullBounds.bottom) * mediaBubbleFraction,
        )
    }

    private fun computeMediaBounds(compactFraction: Float): RectF {
        val height = cameraRadiusPx * 2f + 14f * density
        val top = cameraCenterY - height / 2f
        val bottom = cameraCenterY + height / 2f
        val iconSize = (height - 14f * density).coerceAtLeast(16f * density)
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val isCenterCamera = abs(cameraCenterX - screenWidth / 2f) < 50f * density
        val maxWidth = (maxWidthDp * density).coerceAtMost(screenWidth - 16f * density)
        val normalHalfWidth = (maxWidth / 2f).coerceAtMost(
            minOf(cameraCenterX - 8f * density, screenWidth - cameraCenterX - 8f * density),
        )
        val normalBounds = if (isCenterCamera) {
            RectF(cameraCenterX - normalHalfWidth, top, cameraCenterX + normalHalfWidth, bottom)
        } else {
            val left = (cameraCenterX - cameraRadiusPx - 10f * density).coerceAtLeast(8f * density)
            val right = (left + maxWidth).coerceAtMost(screenWidth - 8f * density)
            RectF(left, top, right, bottom)
        }

        val compactBounds = if (isCenterCamera) {
            val sideWidth = cameraRadiusPx + 10f * density + iconSize + 12f * density
            RectF(cameraCenterX - sideWidth, top, cameraCenterX + sideWidth, bottom)
        } else {
            val left = (cameraCenterX - cameraRadiusPx - 10f * density).coerceAtLeast(8f * density)
            val right = (cameraCenterX + cameraRadiusPx + 12f * density + iconSize + 18f * density)
                .coerceAtMost(screenWidth - 8f * density)
            RectF(left, top, right, bottom)
        }

        return RectF(
            normalBounds.left + (compactBounds.left - normalBounds.left) * compactFraction,
            top,
            normalBounds.right + (compactBounds.right - normalBounds.right) * compactFraction,
            bottom,
        )
    }

    private fun computeMediaTargetBounds(): RectF =
        computeMediaBounds(mediaCompactFraction.coerceIn(0f, 1f))

    private fun computeSenderAndMessage(alert: ActiveNotificationAlert): Pair<String, String> {
        val appName = alert.appName?.trim() ?: try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(alert.packageName, 0)
            pm.getApplicationLabel(appInfo).toString().trim()
        } catch (_: Exception) {
            ""
        }

        var sender = alert.senderName?.trim() ?: ""
        if (sender.isBlank() || sender.equals("You", ignoreCase = true)) {
            val cleanTitle = when {
                appName.isNotBlank() && alert.title.startsWith("$appName: ", ignoreCase = true) ->
                    alert.title.substring(appName.length + 2).trim()
                appName.isNotBlank() && alert.title.startsWith("$appName - ", ignoreCase = true) ->
                    alert.title.substring(appName.length + 3).trim()
                appName.isNotBlank() && alert.title.startsWith("$appName • ", ignoreCase = true) ->
                    alert.title.substring(appName.length + 3).trim()
                else -> alert.title.trim()
            }

            val isTitleAppName = cleanTitle.isBlank() ||
                cleanTitle.equals("You", ignoreCase = true) ||
                (appName.isNotBlank() && cleanTitle.equals(appName, ignoreCase = true)) ||
                cleanTitle.equals(alert.packageName, ignoreCase = true) ||
                cleanTitle.equals("WhatsApp", ignoreCase = true) ||
                cleanTitle.equals("Messages", ignoreCase = true) ||
                cleanTitle.equals("Telegram", ignoreCase = true) ||
                cleanTitle.equals("Gmail", ignoreCase = true) ||
                cleanTitle.equals("Instagram", ignoreCase = true) ||
                cleanTitle.equals("Slack", ignoreCase = true) ||
                cleanTitle.equals("Discord", ignoreCase = true) ||
                cleanTitle.equals("Essentials", ignoreCase = true)

            sender = if (isTitleAppName) {
                if (appName.isNotBlank()) appName else cleanTitle
            } else {
                cleanTitle
            }
        }

        if (sender.isBlank() || sender.equals("You", ignoreCase = true)) {
            sender = if (appName.isNotBlank()) appName else "Notification"
        }

        var message = alert.text.trim()
        if (message.isBlank()) {
            val cleanTitle = alert.title.trim()
            if (!cleanTitle.equals(sender, ignoreCase = true) && !cleanTitle.equals(appName, ignoreCase = true) && !cleanTitle.equals("You", ignoreCase = true)) {
                message = cleanTitle
            }
        } else {
            if (sender.isNotBlank() && message.startsWith("$sender: ", ignoreCase = true)) {
                message = message.substring(sender.length + 2).trim()
            }
        }

        return Pair(sender, message)
    }

    private fun pillTextRightEdge(pillRight: Float, cornerExtraPad: Float = 0f, reservedRight: Float = 0f): Float =
        pillRight - cornerExtraPad - reservedRight

    private fun drawCatchUpTrailingIndicator(
        canvas: Canvas,
        currentRight: Float,
        currentTop: Float,
        currentBottom: Float,
        verticalPadding: Float,
        cornerExtraPad: Float,
        iconSize: Float,
        catchUpIndicatorAlpha: Int,
        catchUpTintColor: Int,
        unreadBmp: Bitmap?,
    ) {
        if (catchUpIndicatorAlpha <= 0) return

        if (isMediaPlaybackActive) {
            val artRight = currentRight - verticalPadding - cornerExtraPad - 2f * density
            val artLeft = artRight - iconSize
            val artTop = (currentTop + currentBottom) / 2f - iconSize / 2f
            val artRect = RectF(artLeft, artTop, artRight, artTop + iconSize)
            val art = mediaArtwork
            if (art != null) {
                catchUpIconClipPath.reset()
                catchUpIconClipPath.addCircle(artRect.centerX(), artRect.centerY(), iconSize / 2f, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(catchUpIconClipPath)
                iconPaint.alpha = catchUpIndicatorAlpha
                canvas.drawBitmap(art, null, artRect, iconPaint)
                canvas.restore()
            } else {
                musicIconBitmap?.let {
                    catchUpUnreadPaint.alpha = catchUpIndicatorAlpha
                    catchUpUnreadPaint.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                    canvas.drawBitmap(it, null, artRect, catchUpUnreadPaint)
                }
            }
            return
        }

        if (unreadBmp == null) return
        val unreadSize = (16f * density)
        val unreadRight = currentRight - verticalPadding - cornerExtraPad - 2f * density
        val unreadLeft = unreadRight - unreadSize
        val unreadTop = (currentTop + currentBottom) / 2f - unreadSize / 2f
        catchUpUnreadIconRect.set(unreadLeft, unreadTop, unreadRight, unreadTop + unreadSize)

        catchUpUnreadPaint.colorFilter = PorterDuffColorFilter(catchUpTintColor, PorterDuff.Mode.SRC_IN)
        catchUpUnreadPaint.alpha = catchUpIndicatorAlpha
        canvas.drawBitmap(unreadBmp, null, catchUpUnreadIconRect, catchUpUnreadPaint)
    }

    private fun drawMediaPlayback(canvas: Canvas) {
        val height = cameraRadiusPx * 2f + 14f * density
        val initial = RectF(
            cameraCenterX - cameraRadiusPx,
            cameraCenterY - cameraRadiusPx,
            cameraCenterX + cameraRadiusPx,
            cameraCenterY + cameraRadiusPx,
        )
        val collapseFraction = when (dragCollapseTarget) {
            DragCollapseTarget.CAMERA -> 0f
            DragCollapseTarget.COMPACT -> 1f
        }
        val target = computeMediaBounds(
            (mediaCompactFraction + (1f - mediaCompactFraction) * dragCollapseFraction * collapseFraction)
                .coerceIn(0f, 1f),
        )
        val targetLeft = target.left
        val targetRight = target.right
        val targetTop = target.top
        val targetBottom = target.bottom
        val left = initial.left + (targetLeft - initial.left) * mediaFraction
        val right = initial.right + (targetRight - initial.right) * mediaFraction
        val top = initial.top + (targetTop - initial.top) * mediaFraction
        val bottom = initial.bottom + (targetBottom - initial.bottom) * mediaFraction
        mediaPillRect.set(left, top, right, bottom)

        notificationPillPaint.color = Color.BLACK
        notificationPillPaint.alpha = 255
        canvas.drawRoundRect(mediaPillRect, height / 2f, height / 2f, notificationPillPaint)

        val alpha = (mediaFraction * 255f).toInt().coerceIn(0, 255)
        val iconSize = (height - 14f * density).coerceAtLeast(16f * density)
        val pad = (height - iconSize) / 2f
        val artRect = RectF(mediaPillRect.left + pad, mediaPillRect.top + pad, mediaPillRect.left + pad + iconSize, mediaPillRect.top + pad + iconSize)
        mediaArtwork?.let {
            iconPaint.alpha = alpha
            canvas.save()
            notificationIconClipPath.reset()
            notificationIconClipPath.addCircle(artRect.centerX(), artRect.centerY(), iconSize / 2f, Path.Direction.CW)
            canvas.clipPath(notificationIconClipPath)
            canvas.drawBitmap(it, null, artRect, iconPaint)
            canvas.restore()
        }

        val compactIconRect = RectF(mediaPillRect.right - pad - iconSize, mediaPillRect.top + pad, mediaPillRect.right - pad, mediaPillRect.top + pad + iconSize)
        musicIconBitmap?.let {
            catchUpUnreadPaint.alpha = (alpha * mediaCompactFraction).toInt().coerceIn(0, 255)
            catchUpUnreadPaint.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(it, null, compactIconRect, catchUpUnreadPaint)
        }

        val textAlpha = (alpha * (1f - mediaCompactFraction)).toInt().coerceIn(0, 255)
        if (textAlpha > 0) {
            notificationSenderPaint.alpha = textAlpha
            notificationBodyPaint.alpha = textAlpha
            notificationSenderPaint.textSize = (height * 0.34f).coerceIn(12f * density, 18f * density)
            notificationBodyPaint.textSize = notificationSenderPaint.textSize
            val artistLeft = artRect.right + 8f * density
            val artistRight = cameraCenterX - cameraRadiusPx - 8f * density
            val titleLeft = cameraCenterX + cameraRadiusPx + 10f * density
            val compactIconReserve = mediaPillRect.right - (compactIconRect.left - 8f * density)
            val titleRight = pillTextRightEdge(mediaPillRect.right, reservedRight = compactIconReserve * mediaCompactFraction)
            val mediaRevealFraction = mediaFraction * (1f - mediaCompactFraction)
            drawMarqueeText(canvas, mediaArtist, notificationSenderPaint, leftMarquee, artistLeft, artistRight, mediaPillRect.top, mediaPillRect.bottom, false, height / 2f, mediaRevealFraction)
            drawMarqueeText(canvas, mediaTitle, notificationBodyPaint, rightMarquee, titleLeft, titleRight, mediaPillRect.top, mediaPillRect.bottom, true, height / 2f, mediaRevealFraction)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val alert: ActiveNotificationAlert = activeNotificationAlert ?: run {
            if (isMediaPlaybackActive && mediaFraction > 0.001f) drawMediaPlayback(canvas)
            return
        }
        if (animatedNotificationFraction > 0.001f) {
            val fraction = animatedNotificationFraction
            val screenWidth = resources.displayMetrics.widthPixels.toFloat()

            val canvasSaveCount = canvas.save()
            if (dragTranslationX != 0f || dragTranslationY != 0f || dragScale != 1.0f) {
                val pivotX = notificationPillRect.centerX().takeIf { it > 0f } ?: cameraCenterX
                val pivotY = notificationPillRect.centerY().takeIf { it > 0f } ?: cameraCenterY
                canvas.translate(dragTranslationX, dragTranslationY)
                canvas.scale(dragScale, dragScale, pivotX, pivotY)
            }

            val targetBounds = computeNotificationTargetBounds(alert)
            val targetTop = targetBounds.top
            val targetBottom = targetBounds.bottom
            val targetRight = targetBounds.right

            val basePillHeight = cameraRadiusPx * 2f + 14f * density
            val bubbleSize = basePillHeight
            val bubbleGap = 8f * density

            val queueVisibleFraction = (1f - expandedFraction).coerceIn(0f, 1f)
            val leadingBubbleSpecs = queuedNotificationAlerts.indices.filter { it in 0..1 }.map { i ->
                val queuedAlert = queuedNotificationAlerts[i]
                IslandBubbleSpec(
                    key = i,
                    visibleFraction = bubbleFractions[i] * queueVisibleFraction,
                    icon = queuedAlert.icon ?: queuedAlert.appIcon,
                )
            }
            val trailingBubbleSpecs = if (isMediaPlaybackActive && !isCatchUpMode) {
                listOf(
                    IslandBubbleSpec(
                        key = MEDIA_BUBBLE_KEY,
                        visibleFraction = mediaBubbleFraction * queueVisibleFraction,
                        icon = mediaArtwork ?: musicIconBitmap,
                        iconShape = if (mediaArtwork != null) IslandBubbleIconShape.CIRCLE else IslandBubbleIconShape.ROUNDED_SQUARE,
                        iconTint = if (mediaArtwork == null) Color.WHITE else null,
                    ),
                )
            } else {
                emptyList()
            }
            val leadingBubblesWidth = IslandBubbleRow.reservedWidth(leadingBubbleSpecs, bubbleSize, bubbleGap)
            val trailingBubblesWidth = IslandBubbleRow.reservedWidth(trailingBubbleSpecs, bubbleSize, bubbleGap)

            val fullTargetLeft = targetBounds.left
            val adjustedTargetLeft = (fullTargetLeft + leadingBubblesWidth).coerceAtMost(cameraCenterX - cameraRadiusPx - 20f * density)
            val adjustedTargetRight = (targetRight - trailingBubblesWidth).coerceAtLeast(cameraCenterX + cameraRadiusPx + 20f * density)

            val initialLeft = cameraCenterX - cameraRadiusPx
            val initialRight = cameraCenterX + cameraRadiusPx

            val normalLeft = initialLeft + (adjustedTargetLeft - initialLeft) * fraction
            val baseLeft = if (isMerging && mergeSourcePillLeft > 0f) {
                mergeSourcePillLeft + (adjustedTargetLeft - mergeSourcePillLeft) * mergeFraction
            } else {
                normalLeft
            }
            val currentLeft = baseLeft + (initialLeft - baseLeft) * dragCollapseFraction

            val normalRight = initialRight + (adjustedTargetRight - initialRight) * fraction
            val baseRight = if (isMerging && mergeSourcePillRight > 0f) {
                mergeSourcePillRight + (adjustedTargetRight - mergeSourcePillRight) * mergeFraction
            } else {
                normalRight
            }
            val currentRight = baseRight - (baseRight - initialRight) * dragCollapseFraction

            val currentTop = targetTop
            val currentBottom = if (dragCollapseFraction > 0f && expandedFraction > 0f) {
                val initialBottom = cameraCenterY + cameraRadiusPx
                targetBottom - (targetBottom - initialBottom) * (dragCollapseFraction * expandedFraction)
            } else {
                targetBottom
            }

            notificationPillRect.set(currentLeft, currentTop, currentRight, currentBottom)
            val pillRadius = basePillHeight / 2f
            val targetExpCornerRadius = expandedCornerRadiusDp * density
            val expRadius = pillRadius + (targetExpCornerRadius - pillRadius) * expandedFraction
            val cornerRadius = if (dragCollapseFraction > 0f && expandedFraction > 0f) {
                cameraRadiusPx + (expRadius - cameraRadiusPx) * (1f - dragCollapseFraction * expandedFraction)
            } else {
                expRadius
            }

            notificationPillPaint.color = Color.BLACK
            notificationPillPaint.alpha = 255

            canvas.drawRoundRect(notificationPillRect, cornerRadius, cornerRadius, notificationPillPaint)

            val inAlpha = ((fraction - 0.20f) / 0.80f).coerceIn(0f, 1f)
            val dragAlpha = (1f - dragCollapseFraction * 2.5f).coerceIn(0f, 1f)
            val baseGlowAlpha = (130f * expandedFraction) * inAlpha * dragAlpha
            val glowAlpha = if (isShowGlow) baseGlowAlpha.toInt().coerceIn(0, 255) else 0

            if (glowAlpha > 0) {
                val accentColor = alert.appColor ?: Color.WHITE
                val r = Color.red(accentColor)
                val g = Color.green(accentColor)
                val b = Color.blue(accentColor)

                val glowStartColor = Color.argb(glowAlpha, r, g, b)
                val glowMidColor = Color.argb((glowAlpha * 0.48f).toInt(), r, g, b)
                val glowEndColor = Color.argb(0, r, g, b)

                val cameraAreaBottom = cameraCenterY + cameraRadiusPx + 4f * density
                val maxAllowedGlowHeight = (currentBottom - cameraAreaBottom).coerceAtLeast(basePillHeight * 0.45f)
                val requestedGlowHeight = (basePillHeight * 0.55f) + (65f * expandedFraction * density)
                val glowHeight = requestedGlowHeight.coerceAtMost(maxAllowedGlowHeight)
                val glowTop = currentBottom - glowHeight

                val glowSave = canvas.save()
                notificationContentClipPath.reset()
                notificationContentClipPath.addRoundRect(notificationPillRect, cornerRadius, cornerRadius, Path.Direction.CW)
                canvas.clipPath(notificationContentClipPath)

                glowPaint.shader = LinearGradient(
                    currentLeft, currentBottom,
                    currentLeft, glowTop,
                    intArrayOf(glowStartColor, glowMidColor, glowEndColor),
                    floatArrayOf(0f, 0.42f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(currentLeft, currentTop, currentRight, currentBottom, glowPaint)
                canvas.restoreToCount(glowSave)
            }

            val contentAlphaProgress = ((fraction - 0.15f) / 0.65f).coerceIn(0f, 1f)
            val baseContentAlpha = (contentAlphaProgress * 255).toInt()

            if (baseContentAlpha > 0) {
                val contentSaveCount = canvas.save()
                notificationContentClipPath.reset()
                notificationContentClipPath.addRoundRect(notificationPillRect, cornerRadius, cornerRadius, Path.Direction.CW)
                canvas.clipPath(notificationContentClipPath)

                val textSize = (basePillHeight * 0.38f).coerceIn(13f * density, 20f * density)
                notificationSenderPaint.typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
                notificationSenderPaint.textSize = textSize
                notificationBodyPaint.typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif", Typeface.NORMAL)
                notificationBodyPaint.textSize = textSize

                val isCenterCamera = abs(cameraCenterX - screenWidth / 2f) < 50f * density
                val iconSize = (basePillHeight - 14f * density).coerceAtLeast(16f * density)
                val verticalPadding = (basePillHeight - iconSize) / 2f
                val topPad = (expandedTopPaddingDp * density) * expandedFraction
                val topRowExtraPad = (expandedPaddingDp * 0.25f * density) * expandedFraction
                val cornerExtraPad = (expandedCornerRadiusDp * 0.35f * density) * expandedFraction + ((expandedPaddingDp - 16f).coerceAtLeast(0f) * 0.4f * density) * expandedFraction + topRowExtraPad

                val prev: ActiveNotificationAlert? = previousAlert
                if (isMerging && prev != null) {
                    val outAlphaProgress = (1f - mergeFraction * 2.2f).coerceIn(0f, 1f)
                    val outAlpha = (outAlphaProgress * 255).toInt()
                    val slideOutX = mergeFraction * 32f * density

                    if (outAlpha > 0) {
                        val (prevSender, prevMessage) = computeSenderAndMessage(prev)
                        notificationSenderPaint.alpha = outAlpha
                        notificationBodyPaint.alpha = outAlpha

                        if (isCenterCamera) {
                            val prevIconLeft = currentLeft + verticalPadding + slideOutX
                            val prevIconTop = currentTop + verticalPadding + topPad
                            val prevIconRect = RectF(prevIconLeft, prevIconTop, prevIconLeft + iconSize, prevIconTop + iconSize)
                            val prevDisplayIcon = prev.icon ?: prev.appIcon

                            if (prevDisplayIcon != null) {
                                iconPaint.alpha = outAlpha
                                canvas.save()
                                notificationIconClipPath.reset()
                                notificationIconClipPath.addRoundRect(prevIconRect, iconSize * 0.28f, iconSize * 0.28f, Path.Direction.CW)
                                canvas.clipPath(notificationIconClipPath)
                                canvas.drawBitmap(prevDisplayIcon, null, prevIconRect, iconPaint)
                                canvas.restore()
                            }

                            val prevSenderStart = prevIconLeft + iconSize + 8f * density
                            val prevSenderY = cameraCenterY + topPad + notificationSenderPaint.textSize * 0.35f
                            canvas.drawText(prevSender, prevSenderStart, prevSenderY, notificationSenderPaint)

                            val prevMsgStart = cameraCenterX + cameraRadiusPx + 10f * density + slideOutX
                            val prevMsgY = cameraCenterY + topPad + notificationBodyPaint.textSize * 0.35f
                            if (prevMessage.isNotBlank()) {
                                canvas.drawText(prevMessage, prevMsgStart, prevMsgY, notificationBodyPaint)
                            }
                        }
                    }
                }

                val (sender, message) = computeSenderAndMessage(alert)
                val inAlphaProgress = if (isMerging) {
                    ((mergeFraction - 0.25f) / 0.65f).coerceIn(0f, 1f)
                } else {
                    ((fraction - 0.25f) / 0.60f).coerceIn(0f, 1f)
                }
                val inSlideX = if (isMerging) {
                    (1f - mergeFraction) * -28f * density
                } else {
                    0f
                }

                val textAlpha = (inAlphaProgress * 255 * (1f - catchUpFraction)).toInt().coerceIn(0, 255)
                notificationSenderPaint.alpha = textAlpha
                notificationBodyPaint.alpha = textAlpha

                val catchUpIndicatorAlpha = (catchUpFraction * 255).toInt().coerceIn(0, 255)
                val unreadBmp = unreadIconBitmap

                val dynamicMaterialYouColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        ContextCompat.getColor(context, android.R.color.system_accent1_200)
                    } catch (_: Exception) {
                        0xFF80D8FF.toInt()
                    }
                } else {
                    0xFF80D8FF.toInt()
                }

                val catchUpTintColor = dynamicMaterialYouColor

                if (isCenterCamera) {
                    val iconLeft = currentLeft + verticalPadding + cornerExtraPad + inSlideX
                    val iconTop = currentTop + verticalPadding + topPad
                    val iconRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

                    val displayIcon = alert.icon ?: alert.appIcon
                    if (displayIcon != null) {
                        iconPaint.alpha = (inAlphaProgress * 255).toInt().coerceIn(0, 255)
                        val iconScale = 0.85f + 0.15f * inAlphaProgress
                        val iconCenterX = iconRect.centerX()
                        val iconCenterY = iconRect.centerY()

                        canvas.save()
                        canvas.scale(iconScale, iconScale, iconCenterX, iconCenterY)
                        notificationIconClipPath.reset()
                        notificationIconClipPath.addRoundRect(iconRect, iconSize * 0.28f, iconSize * 0.28f, Path.Direction.CW)
                        canvas.clipPath(notificationIconClipPath)
                        canvas.drawBitmap(displayIcon, null, iconRect, iconPaint)
                        canvas.restore()
                    }

                    drawCatchUpTrailingIndicator(
                        canvas, currentRight, currentTop, currentBottom,
                        verticalPadding, cornerExtraPad, iconSize,
                        catchUpIndicatorAlpha, catchUpTintColor, unreadBmp,
                    )

                    if (textAlpha > 0) {
                        val senderStart = iconLeft + iconSize + 8f * density
                        val senderEnd = cameraCenterX - cameraRadiusPx - 8f * density
                        if (senderEnd > senderStart + 10f * density) {
                            drawMarqueeText(
                                canvas = canvas,
                                text = sender,
                                paint = notificationSenderPaint,
                                marqueeController = leftMarquee,
                                clipLeft = senderStart,
                                clipRight = senderEnd,
                                currentTop = currentTop + topPad,
                                currentBottom = currentTop + topPad + basePillHeight,
                                isRightPillEdge = false,
                                cornerRadius = cornerRadius,
                            )
                        }

                        val collapsedRightAlpha = (textAlpha * (1f - expandedFraction * 2.5f).coerceIn(0f, 1f)).toInt()
                        if (collapsedRightAlpha > 0) {
                            notificationBodyPaint.alpha = collapsedRightAlpha
                            val msgStart = cameraCenterX + cameraRadiusPx + 10f * density + inSlideX
                            val msgEnd = pillTextRightEdge(currentRight, cornerExtraPad)
                            if (msgEnd > msgStart + 10f * density && message.isNotBlank()) {
                                drawMarqueeText(
                                    canvas = canvas,
                                    text = message,
                                    paint = notificationBodyPaint,
                                    marqueeController = rightMarquee,
                                    clipLeft = msgStart,
                                    clipRight = msgEnd,
                                    currentTop = currentTop + topPad,
                                    currentBottom = currentTop + topPad + basePillHeight,
                                    isRightPillEdge = true,
                                    cornerRadius = cornerRadius,
                                    )
                            }
                        }
                    }
                } else {
                    val spaceFromCutout = 14f * density
                    val iconLeft = cameraCenterX + cameraRadiusPx + spaceFromCutout + cornerExtraPad + inSlideX
                    val iconTop = currentTop + verticalPadding + topPad
                    val iconRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

                    val displayIcon = alert.icon ?: alert.appIcon
                    if (displayIcon != null) {
                        iconPaint.alpha = (inAlphaProgress * 255).toInt().coerceIn(0, 255)
                        val iconScale = 0.85f + 0.15f * inAlphaProgress
                        val iconCenterX = iconRect.centerX()
                        val iconCenterY = iconRect.centerY()

                        canvas.save()
                        canvas.scale(iconScale, iconScale, iconCenterX, iconCenterY)
                        notificationIconClipPath.reset()
                        notificationIconClipPath.addRoundRect(iconRect, iconSize * 0.28f, iconSize * 0.28f, Path.Direction.CW)
                        canvas.clipPath(notificationIconClipPath)
                        canvas.drawBitmap(displayIcon, null, iconRect, iconPaint)
                        canvas.restore()
                    }

                    drawCatchUpTrailingIndicator(
                        canvas, currentRight, currentTop, currentBottom,
                        verticalPadding, cornerExtraPad, iconSize,
                        catchUpIndicatorAlpha, catchUpTintColor, unreadBmp,
                    )

                    if (textAlpha > 0) {
                        val textStart = iconLeft + iconSize + 8f * density
                        val textEnd = pillTextRightEdge(currentRight, cornerExtraPad)
                        val collapsedRightAlpha = (textAlpha * (1f - expandedFraction * 2.5f).coerceIn(0f, 1f)).toInt()

                        if (collapsedRightAlpha > 0) {
                            notificationSenderPaint.alpha = collapsedRightAlpha
                            val combinedText = if (message.isNotBlank()) "$sender • $message" else sender
                            if (textEnd > textStart + 10f * density) {
                                drawMarqueeText(
                                    canvas = canvas,
                                    text = combinedText,
                                    paint = notificationSenderPaint,
                                    marqueeController = rightMarquee,
                                    clipLeft = textStart,
                                    clipRight = textEnd,
                                    currentTop = currentTop,
                                    currentBottom = currentTop + basePillHeight,
                                    isRightPillEdge = true,
                                    cornerRadius = cornerRadius,
                                )
                            }
                        }
                    }
                }

                if (expandedFraction > 0.01f && message.isNotBlank()) {
                    val expandedContentAlpha = ((expandedFraction - 0.20f) / 0.80f).coerceIn(0f, 1f)
                    val expAlpha = (expandedContentAlpha * 255).toInt()
                    if (expAlpha > 0) {
                        val textSize = (basePillHeight * 0.38f).coerceIn(14f * density, 18f * density)
                        val bodyTextPaint = TextPaint().apply {
                            set(notificationBodyPaint)
                            typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif", Typeface.NORMAL)
                            setTextSize(textSize)
                            setAlpha(expAlpha)
                        }
                        val middlePadding = (expandedPaddingDp * density) + (expandedCornerRadiusDp * 0.35f * density) * expandedFraction
                        val bodyLeft = currentLeft + middlePadding
                        val hasActions = alert.actions.isNotEmpty()
                        val headerSpacing = if (hasActions) 0f else (4f * density)
                        val bodyTop = currentTop + basePillHeight + topPad + headerSpacing + (1f - expandedFraction) * -8f * density
                        val bodyWidth = (currentRight - currentLeft - middlePadding * 2).toInt().coerceAtLeast(50)

                        val bodyLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            StaticLayout.Builder.obtain(message, 0, message.length, bodyTextPaint, bodyWidth)
                                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                                .setLineSpacing(2f * density, 1.0f)
                                .setMaxLines(7)
                                .setEllipsize(TextUtils.TruncateAt.END)
                                .build()
                        } else {
                            @Suppress("DEPRECATION")
                            StaticLayout(
                                message,
                                bodyTextPaint,
                                bodyWidth,
                                Layout.Alignment.ALIGN_NORMAL,
                                1.0f,
                                2f * density,
                                true,
                            )
                        }

                        canvas.save()
                        canvas.translate(bodyLeft, bodyTop)
                        bodyLayout.draw(canvas)
                        canvas.restore()

                        if (alert.actions.isNotEmpty()) {
                            actionButtonRects.clear()

                            val buttonRowTop = bodyTop + bodyLayout.height + 8f * density
                            val buttonHeight = 36f * density
                            val actions = alert.actions
                            val count = actions.size
                            val spaceBetween = 4f * density
                            val totalSpace = spaceBetween * (count - 1)
                            val buttonWidth = ((bodyWidth - totalSpace) / count).coerceAtLeast(40f * density)

                            actionButtonTextPaint.textSize = (buttonHeight * 0.38f).coerceIn(12f * density, 15f * density)
                            val fontMetrics = actionButtonTextPaint.fontMetrics
                            val textBaselineOffset = (buttonHeight - (fontMetrics.descent + fontMetrics.ascent)) / 2f

                            val appColor = alert.appColor
                            val (r, g, b) = if (appColor != null && appColor != 0 && appColor != Color.TRANSPARENT) {
                                Triple(Color.red(appColor), Color.green(appColor), Color.blue(appColor))
                            } else {
                                Triple(255, 255, 255)
                            }
                            val isBgLight = (0.299 * r + 0.587 * g + 0.114 * b) > 180

                            val outerRadius = 18f * density
                            val innerRadius = 4f * density

                            for (i in 0 until count) {
                                val action = actions[i]
                                val bLeft = bodyLeft + i * (buttonWidth + spaceBetween)
                                val bRight = bLeft + buttonWidth
                                val bBottom = buttonRowTop + buttonHeight
                                val bRect = RectF(bLeft, buttonRowTop, bRight, bBottom)
                                actionButtonRects[action] = bRect

                                val isActionHighlighted = (highlightedAction != null && (action === highlightedAction || (action.actionKey.isNotEmpty() && action.actionKey == highlightedAction?.actionKey && action.title == highlightedAction?.title)))

                                val (btnBgColor, btnTextColor, btnTextAlpha) = if (isActionHighlighted) {
                                    val pulseAlpha = (220 - (30 * actionExecutionFraction)).toInt().coerceIn(160, 240)
                                    val textColor = if (isBgLight) Color.BLACK else Color.WHITE
                                    Triple(Color.argb(pulseAlpha, r, g, b), textColor, 255)
                                } else if (highlightedAction != null) {
                                    val fadeOutFraction = (1f - actionExecutionFraction * 1.5f).coerceIn(0f, 1f)
                                    val currentExpAlpha = (fadeOutFraction * expAlpha).toInt()
                                    val bgA = (50 * fadeOutFraction * (expAlpha / 255f)).toInt()
                                    Triple(Color.argb(bgA, 255, 255, 255), Color.WHITE, currentExpAlpha)
                                } else {
                                    val bgA = (50 * (expAlpha / 255f)).toInt()
                                    Triple(Color.argb(bgA, 255, 255, 255), Color.WHITE, expAlpha)
                                }

                                actionButtonBgPaint.color = btnBgColor
                                actionButtonTextPaint.color = btnTextColor
                                actionButtonTextPaint.alpha = btnTextAlpha

                                actionButtonPath.reset()
                                val radii = when {
                                    count == 1 -> floatArrayOf(
                                        outerRadius, outerRadius,
                                        outerRadius, outerRadius,
                                        outerRadius, outerRadius,
                                        outerRadius, outerRadius
                                    )
                                    i == 0 -> floatArrayOf(
                                        outerRadius, outerRadius,
                                        innerRadius, innerRadius,
                                        innerRadius, innerRadius,
                                        outerRadius, outerRadius
                                    )
                                    i == count - 1 -> floatArrayOf(
                                        innerRadius, innerRadius,
                                        outerRadius, outerRadius,
                                        outerRadius, outerRadius,
                                        innerRadius, innerRadius
                                    )
                                    else -> floatArrayOf(
                                        innerRadius, innerRadius,
                                        innerRadius, innerRadius,
                                        innerRadius, innerRadius,
                                        innerRadius, innerRadius
                                    )
                                }
                                actionButtonPath.addRoundRect(bRect, radii, Path.Direction.CW)
                                canvas.drawPath(actionButtonPath, actionButtonBgPaint)

                                val title = if (action.isQuickReply) "${action.title} ↩" else action.title
                                val truncatedTitle = TextUtils.ellipsize(
                                    title,
                                    actionButtonTextPaint,
                                    buttonWidth - 16f * density,
                                    TextUtils.TruncateAt.END
                                ).toString()

                                val titleWidth = actionButtonTextPaint.measureText(truncatedTitle)
                                val textX = bLeft + (buttonWidth - titleWidth) / 2f
                                val textY = buttonRowTop + textBaselineOffset
                                canvas.drawText(truncatedTitle, textX, textY, actionButtonTextPaint)
                            }
                        }
                    }
                } else {
                    actionButtonRects.clear()
                }

                canvas.restoreToCount(contentSaveCount)
            }

            if (isMerging) {
                val mergeBubbleAlphaProgress = (1f - mergeFraction * 1.8f).coerceIn(0f, 1f)
                val mergeBubbleAlpha = (mergeBubbleAlphaProgress * 255).toInt()

                if (mergeBubbleAlpha > 0) {
                    val startBubbleLeft = mergeSourceBubbleLeft
                    val targetMergeLeft = currentLeft - bubbleSize / 2f
                    val bLeft = startBubbleLeft + (targetMergeLeft - startBubbleLeft) * mergeFraction
                    val bRight = bLeft + bubbleSize
                    val bRadius = bubbleSize / 2f
                    val bRect = RectF(bLeft, currentTop, bRight, currentBottom)

                    val bSaveCount = canvas.save()
                    val bScale = 1f - 0.2f * mergeFraction
                    canvas.scale(bScale, bScale, bRect.centerX(), bRect.centerY())

                    notificationPillPaint.color = Color.BLACK
                    notificationPillPaint.alpha = mergeBubbleAlpha
                    canvas.drawRoundRect(bRect, bRadius, bRadius, notificationPillPaint)

                    val mIcon = alert.icon ?: alert.appIcon
                    if (mIcon != null) {
                        val bIconSize = (bubbleSize - 12f * density).coerceAtLeast(14f * density)
                        val bIconPad = (bubbleSize - bIconSize) / 2f
                        val bIconRect = RectF(
                            bLeft + bIconPad,
                            currentTop + bIconPad,
                            bLeft + bIconPad + bIconSize,
                            currentTop + bIconPad + bIconSize,
                        )

                        val bClipPath = Path().apply {
                            addRoundRect(bIconRect, bIconSize * 0.28f, bIconSize * 0.28f, Path.Direction.CW)
                        }
                        canvas.save()
                        canvas.clipPath(bClipPath)
                        iconPaint.alpha = mergeBubbleAlpha
                        canvas.drawBitmap(mIcon, null, bIconRect, iconPaint)
                        canvas.restore()
                    }

                    canvas.restoreToCount(bSaveCount)
                }
            }

            val mergeSlideGap = if (isMerging) (bubbleSize + bubbleGap) * (1f - mergeFraction) else 0f
            notificationPillPaint.color = Color.BLACK
            IslandBubbleRow.draw(
                canvas = canvas,
                side = IslandBubbleSide.LEADING,
                bubbles = leadingBubbleSpecs,
                anchorEdge = currentLeft,
                top = currentTop,
                bottom = currentBottom,
                bubbleSize = bubbleSize,
                bubbleGap = bubbleGap,
                density = density,
                pillPaint = notificationPillPaint,
                iconPaint = iconPaint,
                tintPaint = catchUpUnreadPaint,
                outRects = leadingBubbleRects,
                extraGap = mergeSlideGap,
            )

            notificationPillPaint.color = Color.BLACK
            IslandBubbleRow.draw(
                canvas = canvas,
                side = IslandBubbleSide.TRAILING,
                bubbles = trailingBubbleSpecs,
                anchorEdge = currentRight,
                top = currentTop,
                bottom = currentBottom,
                bubbleSize = bubbleSize,
                bubbleGap = bubbleGap,
                density = density,
                pillPaint = notificationPillPaint,
                iconPaint = iconPaint,
                tintPaint = catchUpUnreadPaint,
                outRects = trailingBubbleRects,
            )

            canvas.restoreToCount(canvasSaveCount)
        }
    }

    private fun drawMarqueeText(
        canvas: Canvas,
        text: String,
        paint: Paint,
        marqueeController: MarqueeController,
        clipLeft: Float,
        clipRight: Float,
        currentTop: Float,
        currentBottom: Float,
        isRightPillEdge: Boolean,
        cornerRadius: Float,
        revealFraction: Float = animatedNotificationFraction,
    ) {
        val availableWidth = (clipRight - clipLeft).coerceAtLeast(10f * density)
        if (revealFraction >= 0.98f) {
            marqueeController.update(text, availableWidth, paint, density) {
                invalidate()
            }
        }

        val textY = (currentTop + currentBottom) / 2f + paint.textSize * 0.35f

        if (marqueeController.isNeeded && revealFraction >= 0.98f) {
            val marqueeBounds = RectF(clipLeft, currentTop, clipRight, currentBottom)
            val saveLayerCount = canvas.saveLayer(marqueeBounds, null)

            val marqueeClipPath = Path()
            if (isRightPillEdge) {
                val radii = floatArrayOf(
                    0f, 0f,
                    cornerRadius, cornerRadius,
                    cornerRadius, cornerRadius,
                    0f, 0f,
                )
                marqueeClipPath.addRoundRect(marqueeBounds, radii, Path.Direction.CW)
            } else {
                marqueeClipPath.addRect(marqueeBounds, Path.Direction.CW)
            }
            canvas.clipPath(marqueeClipPath)

            val marqueeGap = 28f * density
            val textWidthMeasure = paint.measureText(text)
            val x1 = clipLeft - marqueeController.offset
            val x2 = x1 + textWidthMeasure + marqueeGap
            canvas.drawText(text, x1, textY, paint)
            canvas.drawText(text, x2, textY, paint)

            val totalWidth = clipRight - clipLeft
            val fadeWidth = 14f * density
            if (totalWidth > fadeWidth * 2f) {
                val fLeft = (fadeWidth / totalWidth).coerceIn(0f, 0.4f)
                val fRight = 1f - fLeft
                if (!isRightPillEdge) {
                    marqueeFadePaint.shader = LinearGradient(
                        clipLeft, 0f, clipRight, 0f,
                        intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                        floatArrayOf(0f, fLeft, fRight, 1f),
                        Shader.TileMode.CLAMP,
                    )
                } else {
                    marqueeFadePaint.shader = LinearGradient(
                        clipLeft, 0f, clipRight, 0f,
                        intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK),
                        floatArrayOf(0f, fLeft, 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRect(marqueeBounds, marqueeFadePaint)
            }

            canvas.restoreToCount(saveLayerCount)
        } else {
            canvas.save()
            val textClipRect = RectF(clipLeft, currentTop, clipRight, currentBottom)
            canvas.clipRect(textClipRect)
            canvas.drawText(text, clipLeft, textY, paint)
            canvas.restore()
        }
    }
}


