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

    private val leftMarquee = MarqueeController()
    private val rightMarquee = MarqueeController()
    private val marqueeFadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    var onDismissAnimationEnd: (() -> Unit)? = null

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
    }

    fun dismissNotificationAlert() {
        stopAllMarquees()
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
        stopAllMarquees()
    }

    fun getActiveNotificationAlert(): ActiveNotificationAlert? = activeNotificationAlert

    fun getNotificationPillBounds(): RectF = notificationPillRect

    fun getNotificationTargetBounds(): RectF {
        val alert = activeNotificationAlert ?: return notificationPillRect
        return computeNotificationTargetBounds(alert)
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
            val targetLeft = targetBounds.left
            val targetRight = targetBounds.right
            val targetTop = targetBounds.top
            val targetBottom = targetBounds.bottom

            val initialLeft = cameraCenterX - cameraRadiusPx
            val initialRight = cameraCenterX + cameraRadiusPx

            val currentLeft = initialLeft + (targetLeft - initialLeft) * fraction
            val currentRight = initialRight + (targetRight - initialRight) * fraction
            val currentTop = targetTop
            val currentBottom = targetBottom
            notificationPillRect.set(currentLeft, currentTop, currentRight, currentBottom)
            val cornerRadius = (currentBottom - currentTop) / 2f

            notificationPillPaint.color = Color.BLACK
            notificationPillPaint.alpha = 255

            canvas.drawRoundRect(notificationPillRect, cornerRadius, cornerRadius, notificationPillPaint)

            // Content alpha & scale transitions matching iOS Dynamic Island
            val contentAlphaProgress = ((fraction - 0.15f) / 0.65f).coerceIn(0f, 1f)
            val contentAlpha = (contentAlphaProgress * 255).toInt()

            if (contentAlpha > 0) {
                val contentSaveCount = canvas.save()
                notificationContentClipPath.reset()
                notificationContentClipPath.addRoundRect(notificationPillRect, cornerRadius, cornerRadius, Path.Direction.CW)
                canvas.clipPath(notificationContentClipPath)

                val (sender, message) = computeSenderAndMessage(alert)
                val textSize = ((targetBottom - targetTop) * 0.38f).coerceIn(13f * density, 20f * density)

                notificationSenderPaint.typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
                notificationSenderPaint.textSize = textSize
                val textAlphaProgress = ((fraction - 0.25f) / 0.60f).coerceIn(0f, 1f)
                val textAlpha = (textAlphaProgress * 255).toInt()
                notificationSenderPaint.alpha = textAlpha

                notificationBodyPaint.typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif", Typeface.NORMAL)
                notificationBodyPaint.textSize = textSize
                notificationBodyPaint.alpha = textAlpha

                val isCenterCamera = abs(cameraCenterX - screenWidth / 2f) < 50f * density

                if (isCenterCamera) {
                    val iconSize = (currentBottom - currentTop - 14f * density).coerceAtLeast(16f * density)
                    val verticalPadding = (currentBottom - currentTop - iconSize) / 2f
                    val iconLeft = currentLeft + verticalPadding
                    val iconTop = currentTop + verticalPadding
                    val iconRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

                    val displayIcon = alert.icon ?: alert.appIcon
                    if (displayIcon != null) {
                        iconPaint.alpha = contentAlpha
                        val iconScale = 0.85f + 0.15f * contentAlphaProgress
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
                        val msgStart = cameraCenterX + cameraRadiusPx + 10f * density
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
                    val iconSize = (currentBottom - currentTop - 14f * density).coerceAtLeast(16f * density)
                    val verticalPadding = (currentBottom - currentTop - iconSize) / 2f
                    val spaceFromCutout = 14f * density
                    val iconLeft = cameraCenterX + cameraRadiusPx + spaceFromCutout
                    val iconTop = currentTop + verticalPadding
                    val iconRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

                    val displayIcon = alert.icon ?: alert.appIcon
                    if (displayIcon != null) {
                        iconPaint.alpha = contentAlpha
                        val iconScale = 0.85f + 0.15f * contentAlphaProgress
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
