/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities - Overlays
 * File: IslandOverlayView.kt
 * Description: Dynamic island notification pill overlay view expanding from camera cutout.
 */

package com.sameerasw.essentials.utils

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
import android.view.animation.LinearInterpolator
import androidx.core.content.res.ResourcesCompat
import com.sameerasw.essentials.R
import com.sameerasw.essentials.domain.model.ActiveNotificationAlert
import com.sameerasw.essentials.domain.model.NotificationActionItem
import com.sameerasw.essentials.services.handlers.IslandTouchHandler
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class IslandOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density

    var touchHandler: IslandTouchHandler? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isNotificationAlertActive) return false
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

    var isIslandEnabled: Boolean = true
    var isShowGlow: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var activeNotificationAlert: ActiveNotificationAlert? = null
    var isNotificationAlertActive: Boolean = false
        private set
    var animatedNotificationFraction: Float = 0f
        private set
    private var notificationAnimator: ValueAnimator? = null
    private val notificationPillRect = RectF()
    private val notificationIconClipPath = Path()
    private val notificationContentClipPath = Path()

    private val queuedNotificationAlerts = mutableListOf<ActiveNotificationAlert>()
    private val bubbleFractions = floatArrayOf(0f, 0f)
    private val bubbleAnimators = arrayOfNulls<ValueAnimator>(2)
    private val bubbleRects = arrayOf(RectF(), RectF())

    private var isMerging: Boolean = false
    private var mergeFraction: Float = 1.0f
    private var mergeAnimator: ValueAnimator? = null
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

    fun updateDragCollapseFraction(fraction: Float) {
        dragCollapseFraction = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    fun resetDragOffset() {
        dragCollapseFraction = 0f
        dragTranslationX = 0f
        dragTranslationY = 0f
        dragScale = 1.0f
        invalidate()
    }

    fun animateDragDismissCollapse(onEnd: () -> Unit) {
        val startVal = dragCollapseFraction
        val anim = ValueAnimator.ofFloat(startVal, 1.0f).apply {
            duration = 200L
            interpolator = AppleDismissInterpolator(responseTimeSec = 0.20f)
            addUpdateListener {
                dragCollapseFraction = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    resetDragOffset()
                    onEnd()
                }
            })
            start()
        }
    }

    fun animateDragSnapBack() {
        val startVal = dragCollapseFraction
        val startX = dragTranslationX
        val startY = dragTranslationY
        val startScale = dragScale

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320L
            interpolator = AppleSpringInterpolator(dampingRatio = 0.72f, responseTimeSec = 0.35f)
            addUpdateListener {
                val f = it.animatedValue as Float
                dragCollapseFraction = startVal * (1f - f)
                dragTranslationX = startX * (1f - f)
                dragTranslationY = startY * (1f - f)
                dragScale = startScale + (1.0f - startScale) * f
                invalidate()
            }
            start()
        }
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
    private var actionExecutionAnimator: ValueAnimator? = null

    fun animateActionExecution(action: NotificationActionItem, onComplete: () -> Unit) {
        highlightedAction = action
        actionExecutionFraction = 0f
        actionExecutionAnimator?.cancel()
        actionExecutionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = AppleSpringInterpolator(dampingRatio = 0.85f, responseTimeSec = 0.28f)
            addUpdateListener {
                actionExecutionFraction = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    highlightedAction = null
                    actionExecutionFraction = 0f
                    invalidate()
                    onComplete()
                }
            })
            start()
        }
    }

    fun showNotificationAlert(alert: ActiveNotificationAlert) {
        if (!isIslandEnabled) return

        if (!isNotificationAlertActive || activeNotificationAlert == null) {
            activeNotificationAlert = alert
            isNotificationAlertActive = true

            notificationAnimator?.cancel()
            val startVal = animatedNotificationFraction
            notificationAnimator = ValueAnimator.ofFloat(startVal, 1.0f).apply {
                duration = 480L
                interpolator = AppleSpringInterpolator(dampingRatio = 0.70f, responseTimeSec = 0.50f)
                addUpdateListener { anim ->
                    animatedNotificationFraction = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
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

        if (queuedNotificationAlerts.size >= 2) {
            queuedNotificationAlerts.removeAt(0)
            bubbleFractions[0] = bubbleFractions[1]
            bubbleFractions[1] = 0f
        }

        queuedNotificationAlerts.add(alert)
        val bubbleIdx = queuedNotificationAlerts.size - 1
        animateBubbleIn(bubbleIdx)
        onAlertsChanged?.invoke()
    }

    private fun animateBubbleIn(index: Int) {
        if (index !in 0..1) return
        bubbleAnimators[index]?.cancel()
        val startVal = bubbleFractions[index]
        bubbleAnimators[index] = ValueAnimator.ofFloat(startVal, 1.0f).apply {
            duration = 420L
            interpolator = AppleSpringInterpolator(dampingRatio = 0.70f, responseTimeSec = 0.44f)
            addUpdateListener { anim ->
                bubbleFractions[index] = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun getQueuedAlertIndexAt(x: Float, y: Float): Int {
        if (expandedFraction > 0.1f) return -1
        for (i in queuedNotificationAlerts.indices) {
            if (i in 0..1 && bubbleFractions[i] > 0.3f) {
                val pad = 6f * density
                val touchRect = RectF(
                    bubbleRects[i].left - pad,
                    bubbleRects[i].top - pad,
                    bubbleRects[i].right + pad,
                    bubbleRects[i].bottom + pad,
                )
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
        mergeSourceBubbleLeft = if (bubbleRects[index].left > 0f) bubbleRects[index].left else (notificationPillRect.left - 40f * density)
        mergeSourceBubbleCenterX = if (bubbleRects[index].centerX() > 0f) bubbleRects[index].centerX() else (mergeSourceBubbleLeft + 20f * density)
        mergeSourceBubbleCenterY = if (bubbleRects[index].centerY() > 0f) bubbleRects[index].centerY() else notificationPillRect.centerY()

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

        mergeAnimator?.cancel()
        mergeAnimator = ValueAnimator.ofFloat(0f, 1.0f).apply {
            duration = 480L
            interpolator = AppleSpringInterpolator(dampingRatio = 0.72f, responseTimeSec = 0.48f)
            addUpdateListener { anim ->
                mergeFraction = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isMerging = false
                    previousAlert = null
                    mergeFraction = 1.0f
                    invalidate()
                }
            })
            start()
        }

        onAlertsChanged?.invoke()
        return true
    }

    var isExpanded: Boolean = false
        private set
    var expandedFraction: Float = 0f
        private set
    private var expansionAnimator: ValueAnimator? = null

    private var onExpandedStateChanged: ((Boolean) -> Unit)? = null

    fun setOnExpandedStateChangedListener(listener: ((Boolean) -> Unit)?) {
        onExpandedStateChanged = listener
    }

    fun toggleExpansion(): Boolean {
        if (!isNotificationAlertActive || activeNotificationAlert == null) return false
        setExpandedState(!isExpanded)
        return isExpanded
    }

    fun setExpandedState(expand: Boolean) {
        if (isExpanded == expand) return
        isExpanded = expand
        stopAllMarquees()

        expansionAnimator?.cancel()
        val startVal = expandedFraction
        val targetVal = if (expand) 1.0f else 0.0f
        expansionAnimator = ValueAnimator.ofFloat(startVal, targetVal).apply {
            duration = if (expand) 420L else 320L
            interpolator = if (expand) {
                AppleSpringInterpolator(dampingRatio = 0.72f, responseTimeSec = 0.46f)
            } else {
                AppleSpringInterpolator(dampingRatio = 0.78f, responseTimeSec = 0.36f)
            }
            addUpdateListener { anim ->
                expandedFraction = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onAlertsChanged?.invoke()
                }
            })
            start()
        }
        onExpandedStateChanged?.invoke(expand)
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
        val innerPadding = 16f * density + cornerExtraPadding
        val textWidth = (width - innerPadding * 2).toInt().coerceAtLeast(50)

        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(message, 0, message.length, bodyTextPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f * density, 1.0f)
                .setMaxLines(4)
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
        val headerSpacing = 2f * density
        val actionsHeight = if (alert.actions.isNotEmpty()) {
            48f * density
        } else {
            0f
        }
        val bottomPad = 14f * density + cornerExtraPadding * 0.3f
        val totalHeight = basePillHeight + headerSpacing + textHeight + actionsHeight + bottomPad
        return totalHeight.coerceAtLeast(basePillHeight)
    }

    private fun computeCollapsedTargetBounds(alert: ActiveNotificationAlert): RectF {
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val targetPillHeight = cameraRadiusPx * 2f + 14f * density
        val targetTop = cameraCenterY - targetPillHeight / 2f
        val targetBottom = cameraCenterY + targetPillHeight / 2f
        val iconSize = (targetPillHeight - 14f * density).coerceAtLeast(16f * density)
        val verticalPadding = (targetPillHeight - iconSize) / 2f

        val (sender, message) = computeSenderAndMessage(alert)
        val textSize = (targetPillHeight * 0.38f).coerceIn(13f * density, 20f * density)
        notificationSenderPaint.typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        notificationSenderPaint.textSize = textSize
        notificationBodyPaint.typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif", Typeface.NORMAL)
        notificationBodyPaint.textSize = textSize

        val isCenterCamera = abs(cameraCenterX - screenWidth / 2f) < 50f * density

        if (isCenterCamera) {
            val senderWidth = notificationSenderPaint.measureText(sender)
            val messageWidth = if (message.isNotBlank()) notificationBodyPaint.measureText(message) else 0f

            val distLeftNeeded = cameraRadiusPx + 10f * density + senderWidth + 8f * density + iconSize + verticalPadding
            val distRightNeeded = cameraRadiusPx + 10f * density + messageWidth + 16f * density
            val minHalfWidth = cameraRadiusPx + iconSize + 24f * density

            val maxScreenHalfWidth = minOf(
                cameraCenterX - 8f * density,
                screenWidth - cameraCenterX - 8f * density,
            ).coerceAtLeast(minHalfWidth)

            val maxAllowedHalfWidth = (maxWidthDp * density / 2f).coerceAtMost(maxScreenHalfWidth)
            val halfWidthNeeded = maxOf(distLeftNeeded, distRightNeeded)
            val halfWidth = halfWidthNeeded.coerceIn(minHalfWidth, maxAllowedHalfWidth)

            val targetLeft = cameraCenterX - halfWidth
            val targetRight = cameraCenterX + halfWidth

            return RectF(targetLeft, targetTop, targetRight, targetBottom)
        } else {
            val targetLeft = (cameraCenterX - cameraRadiusPx - verticalPadding).coerceAtLeast(8f * density)
            val iconLeft = cameraCenterX + cameraRadiusPx + 12f * density
            val senderLeft = iconLeft + iconSize + 8f * density
            val senderWidth = notificationSenderPaint.measureText(sender)
            val messageWidth = if (message.isNotBlank()) notificationBodyPaint.measureText(" • $message") else 0f

            val maxAllowedWidthPx = (maxWidthDp * density).coerceAtMost(screenWidth - 16f * density)
            val maxRight = (targetLeft + maxAllowedWidthPx).coerceAtMost(screenWidth - 8f * density)
            val distNeeded = senderLeft + senderWidth + messageWidth + 18f * density
            val targetRight = distNeeded.coerceIn(targetLeft + 80f * density, maxRight)

            return RectF(targetLeft, targetTop, targetRight, targetBottom)
        }
    }

    private fun computeNotificationTargetBounds(alert: ActiveNotificationAlert): RectF {
        val collapsedBounds = computeCollapsedTargetBounds(alert)
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
            dismissNotificationAlert()
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
        stopAllMarquees()
        isMerging = false
        previousAlert = null
        mergeFraction = 1.0f
        mergeAnimator?.cancel()
        queuedNotificationAlerts.clear()
        bubbleFractions[0] = 0f
        bubbleFractions[1] = 0f
        for (i in 0..1) {
            bubbleAnimators[i]?.cancel()
            bubbleAnimators[i] = null
        }

        if (!isNotificationAlertActive && animatedNotificationFraction <= 0f) return

        expansionAnimator?.cancel()
        notificationAnimator?.cancel()

        val startExpanded = expandedFraction
        val startNotif = animatedNotificationFraction

        notificationAnimator = ValueAnimator.ofFloat(1.0f, 0.0f).apply {
            duration = if (startExpanded > 0.05f) 320L else 280L
            interpolator = AppleDismissInterpolator(responseTimeSec = if (startExpanded > 0.05f) 0.32f else 0.28f)
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                animatedNotificationFraction = startNotif * f
                expandedFraction = startExpanded * f
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isExpanded = false
                    expandedFraction = 0f
                    isNotificationAlertActive = false
                    activeNotificationAlert = null
                    animatedNotificationFraction = 0f
                    invalidate()
                    onDismissAnimationEnd?.invoke()
                    onAlertsChanged?.invoke()
                }
            })
            start()
        }
    }

    private fun stopAllMarquees() {
        leftMarquee.stop()
        rightMarquee.stop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        notificationAnimator?.cancel()
        mergeAnimator?.cancel()
        expansionAnimator?.cancel()
        for (i in 0..1) {
            bubbleAnimators[i]?.cancel()
        }
        stopAllMarquees()
    }

    fun getActiveNotificationAlert(): ActiveNotificationAlert? = activeNotificationAlert

    fun getQueuedAlerts(): List<ActiveNotificationAlert> = queuedNotificationAlerts

    fun getNotificationPillBounds(): RectF = notificationPillRect

    fun getTotalAlertsBounds(): RectF {
        val alert = activeNotificationAlert ?: return notificationPillRect
        val mainBounds = computeNotificationTargetBounds(alert)
        val numBubbles = queuedNotificationAlerts.size
        if (numBubbles == 0 || expandedFraction >= 0.99f) return mainBounds

        val basePillHeight = cameraRadiusPx * 2f + 14f * density
        val bubbleSize = basePillHeight
        val bubbleGap = 8f * density
        val totalBubblesWidth = numBubbles * (bubbleSize + bubbleGap) * (1f - expandedFraction)

        return RectF(
            mainBounds.left - totalBubblesWidth,
            mainBounds.top,
            mainBounds.right,
            mainBounds.bottom,
        )
    }

    fun getAlertAt(x: Float, y: Float): ActiveNotificationAlert? {
        if (expandedFraction < 0.5f) {
            for (i in queuedNotificationAlerts.indices) {
                if (i in 0..1 && bubbleFractions[i] > 0.5f) {
                    if (bubbleRects[i].contains(x, y)) {
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val alert: ActiveNotificationAlert = activeNotificationAlert ?: return
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

            var totalBubblesWidth = 0f
            val queueVisibleFraction = (1f - expandedFraction).coerceIn(0f, 1f)
            for (i in queuedNotificationAlerts.indices) {
                if (i in 0..1) {
                    val bFrac = bubbleFractions[i] * queueVisibleFraction
                    totalBubblesWidth += (bubbleSize + bubbleGap) * bFrac
                }
            }

            val fullTargetLeft = targetBounds.left
            val adjustedTargetLeft = (fullTargetLeft + totalBubblesWidth).coerceAtMost(cameraCenterX - cameraRadiusPx - 20f * density)

            val initialLeft = cameraCenterX - cameraRadiusPx
            val initialRight = cameraCenterX + cameraRadiusPx

            val normalLeft = initialLeft + (adjustedTargetLeft - initialLeft) * fraction
            val baseLeft = if (isMerging && mergeSourcePillLeft > 0f) {
                mergeSourcePillLeft + (adjustedTargetLeft - mergeSourcePillLeft) * mergeFraction
            } else {
                normalLeft
            }
            val currentLeft = baseLeft + (initialLeft - baseLeft) * dragCollapseFraction

            val normalRight = initialRight + (targetRight - initialRight) * fraction
            val baseRight = if (isMerging && mergeSourcePillRight > 0f) {
                mergeSourcePillRight + (targetRight - mergeSourcePillRight) * mergeFraction
            } else {
                normalRight
            }
            val currentRight = baseRight - (baseRight - initialRight) * dragCollapseFraction

            val currentTop = targetTop
            val currentBottom = targetBottom
            notificationPillRect.set(currentLeft, currentTop, currentRight, currentBottom)
            val baseCornerRadius = basePillHeight / 2f
            val targetExpCornerRadius = expandedCornerRadiusDp * density
            val cornerRadius = baseCornerRadius + (targetExpCornerRadius - baseCornerRadius) * expandedFraction

            notificationPillPaint.color = Color.BLACK
            notificationPillPaint.alpha = 255

            canvas.drawRoundRect(notificationPillRect, cornerRadius, cornerRadius, notificationPillPaint)

            val inAlpha = ((fraction - 0.20f) / 0.80f).coerceIn(0f, 1f)
            val dragAlpha = (1f - dragCollapseFraction * 2.5f).coerceIn(0f, 1f)
            val baseGlowAlpha = (40f + 110f * expandedFraction) * inAlpha * dragAlpha
            val glowAlpha = if (isShowGlow) baseGlowAlpha.toInt().coerceIn(0, 255) else 0

            if (glowAlpha > 0) {
                val accentColor = alert.appColor ?: Color.WHITE
                val r = Color.red(accentColor)
                val g = Color.green(accentColor)
                val b = Color.blue(accentColor)

                val glowStartColor = Color.argb(glowAlpha, r, g, b)
                val glowMidColor = Color.argb((glowAlpha * 0.45f).toInt(), r, g, b)
                val glowEndColor = Color.argb(0, r, g, b)

                val glowOriginLeft = adjustedTargetLeft
                val glowWidth = (100f + 60f * expandedFraction) * density

                val glowSave = canvas.save()
                notificationContentClipPath.reset()
                notificationContentClipPath.addRoundRect(notificationPillRect, cornerRadius, cornerRadius, Path.Direction.CW)
                canvas.clipPath(notificationContentClipPath)

                glowPaint.shader = LinearGradient(
                    glowOriginLeft, currentTop,
                    glowOriginLeft + glowWidth, currentTop,
                    intArrayOf(glowStartColor, glowMidColor, glowEndColor),
                    floatArrayOf(0f, 0.4f, 1f),
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
                val cornerExtraPad = (expandedCornerRadiusDp * 0.35f * density) * expandedFraction

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
                            val prevIconTop = currentTop + verticalPadding
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
                            val prevSenderY = cameraCenterY + notificationSenderPaint.textSize * 0.35f
                            canvas.drawText(prevSender, prevSenderStart, prevSenderY, notificationSenderPaint)

                            val prevMsgStart = cameraCenterX + cameraRadiusPx + 10f * density + slideOutX
                            val prevMsgY = cameraCenterY + notificationBodyPaint.textSize * 0.35f
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

                val textAlpha = (inAlphaProgress * 255).toInt()
                notificationSenderPaint.alpha = textAlpha
                notificationBodyPaint.alpha = textAlpha

                if (isCenterCamera) {
                    val iconLeft = currentLeft + verticalPadding + cornerExtraPad + inSlideX
                    val iconTop = currentTop + verticalPadding
                    val iconRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

                    val displayIcon = alert.icon ?: alert.appIcon
                    if (displayIcon != null) {
                        iconPaint.alpha = textAlpha
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
                                currentTop = currentTop,
                                currentBottom = currentTop + basePillHeight,
                                isRightPillEdge = false,
                                cornerRadius = cornerRadius,
                            )
                        }

                        val collapsedRightAlpha = (textAlpha * (1f - expandedFraction * 2.5f).coerceIn(0f, 1f)).toInt()
                        if (collapsedRightAlpha > 0) {
                            notificationBodyPaint.alpha = collapsedRightAlpha
                            val msgStart = cameraCenterX + cameraRadiusPx + 10f * density + inSlideX
                            val msgEnd = currentRight - cornerExtraPad
                            if (msgEnd > msgStart + 10f * density && message.isNotBlank()) {
                                drawMarqueeText(
                                    canvas = canvas,
                                    text = message,
                                    paint = notificationBodyPaint,
                                    marqueeController = rightMarquee,
                                    clipLeft = msgStart,
                                    clipRight = msgEnd,
                                    currentTop = currentTop,
                                    currentBottom = currentTop + basePillHeight,
                                    isRightPillEdge = true,
                                    cornerRadius = cornerRadius,
                                )
                            }
                        }
                    }
                } else {
                    val spaceFromCutout = 14f * density
                    val iconLeft = cameraCenterX + cameraRadiusPx + spaceFromCutout + cornerExtraPad + inSlideX
                    val iconTop = currentTop + verticalPadding
                    val iconRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

                    val displayIcon = alert.icon ?: alert.appIcon
                    if (displayIcon != null) {
                        iconPaint.alpha = textAlpha
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

                    if (textAlpha > 0) {
                        val textStart = iconLeft + iconSize + 8f * density
                        val textEnd = currentRight - cornerExtraPad
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
                        val innerPadding = 16f * density + cornerExtraPad
                        val bodyLeft = currentLeft + innerPadding
                        val headerSpacing = 2f * density
                        val bodyTop = currentTop + basePillHeight + headerSpacing + (1f - expandedFraction) * -8f * density
                        val bodyWidth = (currentRight - currentLeft - innerPadding * 2).toInt().coerceAtLeast(50)

                        val bodyLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            StaticLayout.Builder.obtain(message, 0, message.length, bodyTextPaint, bodyWidth)
                                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                                .setLineSpacing(2f * density, 1.0f)
                                .setMaxLines(4)
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

                            val buttonRowTop = bodyTop + bodyLayout.height + 10f * density
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

            var runningLeft = currentLeft
            for (i in queuedNotificationAlerts.indices) {
                if (i in 0..1) {
                    val queuedAlert = queuedNotificationAlerts[i]
                    val bFrac = bubbleFractions[i] * queueVisibleFraction
                    if (bFrac > 0.01f) {
                        val slideOffset = if (isMerging) {
                            (bubbleSize + bubbleGap) * (1f - mergeFraction)
                        } else {
                            0f
                        }

                        val bRight = runningLeft - bubbleGap - slideOffset
                        val bLeft = bRight - bubbleSize
                        runningLeft = bLeft

                        bubbleRects[i].set(bLeft, currentTop, bRight, currentBottom)
                        val bRadius = bubbleSize / 2f

                        val bSaveCount = canvas.save()
                        canvas.scale(bFrac, bFrac, bubbleRects[i].centerX(), bubbleRects[i].centerY())

                        notificationPillPaint.color = Color.BLACK
                        notificationPillPaint.alpha = (bFrac * 255).toInt().coerceIn(0, 255)
                        canvas.drawRoundRect(bubbleRects[i], bRadius, bRadius, notificationPillPaint)

                        val qIcon = queuedAlert.icon ?: queuedAlert.appIcon
                        if (qIcon != null) {
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
                            iconPaint.alpha = (bFrac * 255).toInt().coerceIn(0, 255)
                            canvas.drawBitmap(qIcon, null, bIconRect, iconPaint)
                            canvas.restore()
                        }

                        canvas.restoreToCount(bSaveCount)
                    }
                }
            }

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
    ) {
        val availableWidth = (clipRight - clipLeft).coerceAtLeast(10f * density)
        if (animatedNotificationFraction >= 0.98f) {
            marqueeController.update(text, availableWidth, paint, density) {
                invalidate()
            }
        }

        val textY = cameraCenterY + paint.textSize * 0.35f

        if (marqueeController.isNeeded && animatedNotificationFraction >= 0.98f) {
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
            if (totalWidth > fadeWidth) {
                val fLeft = (fadeWidth / totalWidth).coerceIn(0f, 0.45f)
                marqueeFadePaint.shader = LinearGradient(
                    clipLeft, 0f, clipRight, 0f,
                    intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK),
                    floatArrayOf(0f, fLeft, 1f),
                    Shader.TileMode.CLAMP,
                )
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

private class MarqueeController {
    var offset: Float = 0f
    var isNeeded: Boolean = false
    var lastText: String = ""
    var lastWidth: Float = 0f
    private var animator: ValueAnimator? = null

    fun update(text: String, maxTextWidth: Float, textPaint: Paint, density: Float, onInvalidate: () -> Unit) {
        val textWidth = textPaint.measureText(text)
        val needed = (textWidth - maxTextWidth) > 1.5f * density && maxTextWidth > 0f

        if (needed) {
            if (!isNeeded || text != lastText || abs(maxTextWidth - lastWidth) > 1f) {
                isNeeded = true
                lastText = text
                lastWidth = maxTextWidth
                animator?.cancel()
                offset = 0f

                val marqueeGap = 28f * density
                val totalDistance = textWidth + marqueeGap
                val speedDpPerSec = 30f
                val durationMs = ((totalDistance / density) / speedDpPerSec * 1000L).toLong().coerceAtLeast(2000L)

                animator = ValueAnimator.ofFloat(0f, totalDistance).apply {
                    duration = durationMs
                    interpolator = LinearInterpolator()
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    startDelay = 1200L
                    addUpdateListener {
                        offset = it.animatedValue as Float
                        onInvalidate()
                    }
                    start()
                }
            }
        } else {
            if (isNeeded || text != lastText) {
                stop()
                lastText = text
                lastWidth = maxTextWidth
            }
        }
    }

    fun stop() {
        isNeeded = false
        lastText = ""
        lastWidth = 0f
        animator?.cancel()
        animator = null
        offset = 0f
    }
}

private class AppleSpringInterpolator(
    private val dampingRatio: Float = 0.70f,
    private val responseTimeSec: Float = 0.50f,
) : TimeInterpolator {
    private val omegaN = (2.0 * Math.PI / responseTimeSec).toFloat()
    private val omegaD = (omegaN * sqrt((1.0 - dampingRatio * dampingRatio))).toFloat()
    private val beta = (dampingRatio / sqrt((1.0 - dampingRatio * dampingRatio))).toFloat()

    override fun getInterpolation(input: Float): Float {
        if (input <= 0f) return 0f
        if (input >= 1f) return 1f
        val t = input * responseTimeSec
        val envelope = exp((-dampingRatio * omegaN * t).toDouble()).toFloat()
        val osc = cos((omegaD * t).toDouble()).toFloat() + beta * sin((omegaD * t).toDouble()).toFloat()
        return 1.0f - envelope * osc
    }
}

private class AppleDismissInterpolator(
    private val responseTimeSec: Float = 0.28f,
) : TimeInterpolator {
    private val omegaN = (2.0 * Math.PI / (responseTimeSec * 1.15f)).toFloat()

    override fun getInterpolation(input: Float): Float {
        if (input <= 0f) return 0f
        if (input >= 1f) return 1f
        val t = input * responseTimeSec
        val envelope = exp((-omegaN * t).toDouble()).toFloat()
        return 1.0f - (envelope * (1.0f + omegaN * t))
    }
}
