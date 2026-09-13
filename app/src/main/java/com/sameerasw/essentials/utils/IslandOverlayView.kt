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
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.res.ResourcesCompat
import com.sameerasw.essentials.R
import com.sameerasw.essentials.domain.model.ActiveNotificationAlert
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class IslandOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density

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

    var isIslandEnabled: Boolean = true

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
        notificationAnimator?.cancel()
        val startVal = animatedNotificationFraction
        notificationAnimator = ValueAnimator.ofFloat(startVal, 0.0f).apply {
            duration = 280L
            interpolator = AppleDismissInterpolator(responseTimeSec = 0.28f)
            addUpdateListener { anim ->
                animatedNotificationFraction = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
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
        if (numBubbles == 0) return mainBounds

        val targetPillHeight = cameraRadiusPx * 2f + 14f * density
        val bubbleSize = targetPillHeight
        val bubbleGap = 8f * density
        val totalBubblesWidth = numBubbles * (bubbleSize + bubbleGap)

        return RectF(
            mainBounds.left - totalBubblesWidth,
            mainBounds.top,
            mainBounds.right,
            mainBounds.bottom,
        )
    }

    fun getAlertAt(x: Float, y: Float): ActiveNotificationAlert? {
        for (i in queuedNotificationAlerts.indices) {
            if (i in 0..1 && bubbleFractions[i] > 0.5f) {
                if (bubbleRects[i].contains(x, y)) {
                    return queuedNotificationAlerts[i]
                }
            }
        }
        if (notificationPillRect.contains(x, y)) {
            return activeNotificationAlert
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

    private fun computeNotificationTargetBounds(alert: ActiveNotificationAlert): RectF {
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (animatedNotificationFraction > 0.001f && activeNotificationAlert != null) {
            val alert = activeNotificationAlert ?: return
            val fraction = animatedNotificationFraction
            val screenWidth = resources.displayMetrics.widthPixels.toFloat()

            val targetBounds = computeNotificationTargetBounds(alert)
            val targetTop = targetBounds.top
            val targetBottom = targetBounds.bottom
            val targetRight = targetBounds.right

            val targetPillHeight = targetBottom - targetTop
            val bubbleSize = targetPillHeight
            val bubbleGap = 8f * density

            var totalBubblesWidth = 0f
            for (i in queuedNotificationAlerts.indices) {
                if (i in 0..1) {
                    val bFrac = bubbleFractions[i]
                    totalBubblesWidth += (bubbleSize + bubbleGap) * bFrac
                }
            }

            val fullTargetLeft = targetBounds.left
            val adjustedTargetLeft = (fullTargetLeft + totalBubblesWidth).coerceAtMost(cameraCenterX - cameraRadiusPx - 20f * density)

            val initialLeft = cameraCenterX - cameraRadiusPx
            val initialRight = cameraCenterX + cameraRadiusPx

            val normalLeft = initialLeft + (adjustedTargetLeft - initialLeft) * fraction
            val currentLeft = if (isMerging && mergeSourcePillLeft > 0f) {
                mergeSourcePillLeft + (adjustedTargetLeft - mergeSourcePillLeft) * mergeFraction
            } else {
                normalLeft
            }

            val normalRight = initialRight + (targetRight - initialRight) * fraction
            val currentRight = if (isMerging && mergeSourcePillRight > 0f) {
                mergeSourcePillRight + (targetRight - mergeSourcePillRight) * mergeFraction
            } else {
                normalRight
            }

            val currentTop = targetTop
            val currentBottom = targetBottom
            notificationPillRect.set(currentLeft, currentTop, currentRight, currentBottom)
            val cornerRadius = (currentBottom - currentTop) / 2f

            notificationPillPaint.color = Color.BLACK
            notificationPillPaint.alpha = 255

            // Draw Liquid Main Pill
            canvas.drawRoundRect(notificationPillRect, cornerRadius, cornerRadius, notificationPillPaint)

            // Content alpha & scale transitions matching iOS Dynamic Island
            val contentAlphaProgress = ((fraction - 0.15f) / 0.65f).coerceIn(0f, 1f)
            val baseContentAlpha = (contentAlphaProgress * 255).toInt()

            if (baseContentAlpha > 0) {
                val contentSaveCount = canvas.save()
                notificationContentClipPath.reset()
                notificationContentClipPath.addRoundRect(notificationPillRect, cornerRadius, cornerRadius, Path.Direction.CW)
                canvas.clipPath(notificationContentClipPath)

                val textSize = ((targetBottom - targetTop) * 0.38f).coerceIn(13f * density, 20f * density)
                notificationSenderPaint.typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
                notificationSenderPaint.textSize = textSize
                notificationBodyPaint.typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif", Typeface.NORMAL)
                notificationBodyPaint.textSize = textSize

                val isCenterCamera = abs(cameraCenterX - screenWidth / 2f) < 50f * density
                val iconSize = (currentBottom - currentTop - 14f * density).coerceAtLeast(16f * density)
                val verticalPadding = (currentBottom - currentTop - iconSize) / 2f

                if (isMerging && previousAlert != null) {
                    val outAlphaProgress = (1f - mergeFraction * 2.2f).coerceIn(0f, 1f)
                    val outAlpha = (outAlphaProgress * 255).toInt()
                    val slideOutX = mergeFraction * 32f * density

                    if (outAlpha > 0) {
                        val (prevSender, prevMessage) = computeSenderAndMessage(previousAlert!!)
                        notificationSenderPaint.alpha = outAlpha
                        notificationBodyPaint.alpha = outAlpha

                        if (isCenterCamera) {
                            val prevIconLeft = currentLeft + verticalPadding + slideOutX
                            val prevIconTop = currentTop + verticalPadding
                            val prevIconRect = RectF(prevIconLeft, prevIconTop, prevIconLeft + iconSize, prevIconTop + iconSize)
                            val prevDisplayIcon = previousAlert!!.icon ?: previousAlert!!.appIcon

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
                    val iconLeft = currentLeft + verticalPadding + inSlideX
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
                        // Left wing: Sender name
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
                                currentBottom = currentBottom,
                                isRightPillEdge = false,
                                cornerRadius = cornerRadius,
                            )
                        }

                        // Right wing: Message text
                        val msgStart = cameraCenterX + cameraRadiusPx + 10f * density + inSlideX
                        val msgEnd = currentRight - 14f * density
                        if (msgEnd > msgStart + 10f * density && message.isNotBlank()) {
                            drawMarqueeText(
                                canvas = canvas,
                                text = message,
                                paint = notificationBodyPaint,
                                marqueeController = rightMarquee,
                                clipLeft = msgStart,
                                clipRight = msgEnd,
                                currentTop = currentTop,
                                currentBottom = currentBottom,
                                isRightPillEdge = true,
                                cornerRadius = cornerRadius,
                            )
                        }
                    }
                } else {
                    val spaceFromCutout = 14f * density
                    val iconLeft = cameraCenterX + cameraRadiusPx + spaceFromCutout + inSlideX
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
                        val textEnd = currentRight - 14f * density
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
                                currentBottom = currentBottom,
                                isRightPillEdge = true,
                                cornerRadius = cornerRadius,
                            )
                        }
                    }
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
                    val bFrac = bubbleFractions[i]
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
            val fadeWidth = 12f * density
            if (totalWidth > fadeWidth * 2) {
                val fLeft = (fadeWidth / totalWidth).coerceIn(0f, 0.45f)
                val fRight = 1f - fLeft
                marqueeFadePaint.shader = LinearGradient(
                    clipLeft, 0f, clipRight, 0f,
                    intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                    floatArrayOf(0f, fLeft, fRight, 1f),
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
