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

    // Marquee state
    private var marqueeOffset: Float = 0f
    private var marqueeAnimator: ValueAnimator? = null
    private var isMarqueeNeeded: Boolean = false
    private var lastMarqueeText: String = ""
    private var lastMaxTextWidth: Float = 0f
    private val marqueeClipPath = Path()
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

    private val notificationBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
            interpolator = AppleSpringInterpolator(dampingRatio = 0.68f, responseTimeSec = 0.48f)
            addUpdateListener { anim ->
                animatedNotificationFraction = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun dismissNotificationAlert() {
        stopMarquee()
        if (!isNotificationAlertActive && animatedNotificationFraction <= 0f) return
        notificationAnimator?.cancel()
        val startVal = animatedNotificationFraction
        notificationAnimator = ValueAnimator.ofFloat(startVal, 0.0f).apply {
            duration = 300L
            interpolator = AppleDismissInterpolator(responseTimeSec = 0.30f)
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

    private fun checkAndStartMarquee(text: String, maxTextWidth: Float) {
        val textWidth = notificationBodyPaint.measureText(text)
        val needed = (textWidth - maxTextWidth) > 1.5f * density && maxTextWidth > 0f

        if (needed) {
            if (!isMarqueeNeeded || text != lastMarqueeText || abs(maxTextWidth - lastMaxTextWidth) > 1f) {
                isMarqueeNeeded = true
                lastMarqueeText = text
                lastMaxTextWidth = maxTextWidth
                marqueeAnimator?.cancel()
                marqueeOffset = 0f

                val marqueeGap = 28f * density
                val totalDistance = textWidth + marqueeGap
                val speedDpPerSec = 30f
                val durationMs = ((totalDistance / density) / speedDpPerSec * 1000L).toLong().coerceAtLeast(2000L)

                marqueeAnimator = ValueAnimator.ofFloat(0f, totalDistance).apply {
                    duration = durationMs
                    interpolator = LinearInterpolator()
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    startDelay = 1200L
                    addUpdateListener {
                        marqueeOffset = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        } else {
            if (isMarqueeNeeded || text != lastMarqueeText) {
                stopMarquee()
                lastMarqueeText = text
                lastMaxTextWidth = maxTextWidth
            }
        }
    }

    private fun stopMarquee() {
        isMarqueeNeeded = false
        lastMarqueeText = ""
        lastMaxTextWidth = 0f
        marqueeAnimator?.cancel()
        marqueeAnimator = null
        marqueeOffset = 0f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        notificationAnimator?.cancel()
        stopMarquee()
    }

    fun getActiveNotificationAlert(): ActiveNotificationAlert? = activeNotificationAlert

    fun getNotificationPillBounds(): RectF = notificationPillRect

    fun getNotificationTargetBounds(): RectF {
        val alert = activeNotificationAlert ?: return notificationPillRect
        return computeNotificationTargetBounds(alert)
    }

    private fun computeNotificationDisplayText(alert: ActiveNotificationAlert): String {
        val appName = try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(alert.packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            ""
        }

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

        return when {
            alert.text.isBlank() -> cleanTitle
            isTitleAppName -> alert.text.trim()
            alert.text.startsWith(cleanTitle, ignoreCase = true) -> alert.text.trim()
            else -> "$cleanTitle: ${alert.text.trim()}"
        }
    }

    private fun computeNotificationTargetBounds(alert: ActiveNotificationAlert): RectF {
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val targetPillHeight = cameraRadiusPx * 2f + 14f * density
        val targetTop = cameraCenterY - targetPillHeight / 2f
        val targetBottom = cameraCenterY + targetPillHeight / 2f
        val iconSize = (targetPillHeight - 14f * density).coerceAtLeast(16f * density)
        val verticalPadding = (targetPillHeight - iconSize) / 2f

        val isCenterCamera = abs(cameraCenterX - screenWidth / 2f) < 50f * density

        if (isCenterCamera) {
            val spaceFromCutout = 14f * density
            val minDistLeft = cameraRadiusPx + spaceFromCutout + iconSize + verticalPadding

            notificationBodyPaint.typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
            notificationBodyPaint.textSize = (targetPillHeight * 0.38f).coerceIn(13f * density, 20f * density)
            val displayText = computeNotificationDisplayText(alert)
            val textWidth = notificationBodyPaint.measureText(displayText)
            val distRightNeeded = cameraRadiusPx + spaceFromCutout + textWidth + 18f * density

            val maxScreenHalfWidth = minOf(
                cameraCenterX - 8f * density,
                screenWidth - cameraCenterX - 8f * density,
            ).coerceAtLeast(minDistLeft)

            val maxAllowedHalfWidth = (maxWidthDp * density / 2f).coerceAtMost(maxScreenHalfWidth)
            val halfWidth = if (distRightNeeded > maxAllowedHalfWidth) {
                maxAllowedHalfWidth
            } else {
                maxOf(minDistLeft, distRightNeeded).coerceIn(minDistLeft, maxAllowedHalfWidth)
            }

            val targetLeft = cameraCenterX - halfWidth
            val targetRight = cameraCenterX + halfWidth

            return RectF(targetLeft, targetTop, targetRight, targetBottom)
        } else {
            val targetLeft = (cameraCenterX - cameraRadiusPx - verticalPadding).coerceAtLeast(8f * density)
            val spaceFromCutout = 16f * density
            val iconLeft = cameraCenterX + cameraRadiusPx + spaceFromCutout
            val textLeft = iconLeft + iconSize + 12f * density

            val maxAllowedWidthPx = (maxWidthDp * density).coerceAtMost(screenWidth - 16f * density)
            val maxRight = (targetLeft + maxAllowedWidthPx).coerceAtMost(screenWidth - 8f * density)
            val maxAvailableTextWidth = (maxRight - textLeft - 18f * density).coerceAtLeast(50f * density)

            notificationBodyPaint.typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
            notificationBodyPaint.textSize = (targetPillHeight * 0.38f).coerceIn(13f * density, 20f * density)
            val displayText = computeNotificationDisplayText(alert)
            val textWidth = notificationBodyPaint.measureText(displayText)
            val distNeeded = textLeft + textWidth + 18f * density
            val targetRight = if (distNeeded > maxRight) {
                maxRight
            } else {
                distNeeded.coerceIn(targetLeft + 80f * density, maxRight)
            }

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

                val isCenterCamera = abs(cameraCenterX - screenWidth / 2f) < 50f * density

                if (isCenterCamera) {
                    val iconSize = (currentBottom - currentTop - 14f * density).coerceAtLeast(16f * density)
                    val verticalPadding = (currentBottom - currentTop - iconSize) / 2f
                    val iconLeft = currentLeft + verticalPadding
                    val iconTop = currentTop + verticalPadding
                    val iconRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

                    if (alert.icon != null) {
                        iconPaint.alpha = contentAlpha
                        val iconScale = 0.85f + 0.15f * contentAlphaProgress
                        val iconCenterX = iconRect.centerX()
                        val iconCenterY = iconRect.centerY()

                        canvas.save()
                        canvas.scale(iconScale, iconScale, iconCenterX, iconCenterY)
                        notificationIconClipPath.reset()
                        notificationIconClipPath.addRoundRect(iconRect, iconSize * 0.28f, iconSize * 0.28f, Path.Direction.CW)
                        canvas.clipPath(notificationIconClipPath)
                        canvas.drawBitmap(alert.icon, null, iconRect, iconPaint)
                        canvas.restore()
                    }

                    val spaceFromCutout = 14f * density
                    val textLeft = cameraCenterX + cameraRadiusPx + spaceFromCutout
                    val maxAvailableTextWidth = (currentRight - textLeft - 14f * density).coerceAtLeast(40f * density)

                    notificationBodyPaint.typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    notificationBodyPaint.textSize = ((targetBottom - targetTop) * 0.38f).coerceIn(13f * density, 20f * density)
                    notificationBodyPaint.color = Color.WHITE
                    val textAlphaProgress = ((fraction - 0.25f) / 0.60f).coerceIn(0f, 1f)
                    notificationBodyPaint.alpha = (textAlphaProgress * 255).toInt()

                    if (notificationBodyPaint.alpha > 0) {
                        val displayText = computeNotificationDisplayText(alert)
                        val textY = cameraCenterY + notificationBodyPaint.textSize * 0.35f

                        drawMarqueeNotificationText(
                            canvas = canvas,
                            text = displayText,
                            textLeft = textLeft,
                            textY = textY,
                            fadeStartPos = cameraCenterX + cameraRadiusPx + 4f * density,
                            currentTop = currentTop,
                            currentRight = currentRight,
                            currentBottom = currentBottom,
                            cornerRadius = cornerRadius,
                            maxAvailableTextWidth = maxAvailableTextWidth,
                        )
                    }
                } else {
                    val iconSize = (currentBottom - currentTop - 14f * density).coerceAtLeast(16f * density)
                    val verticalPadding = (currentBottom - currentTop - iconSize) / 2f
                    val spaceFromCutout = 16f * density
                    val iconLeft = cameraCenterX + cameraRadiusPx + spaceFromCutout
                    val iconTop = currentTop + verticalPadding
                    val iconRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

                    if (alert.icon != null) {
                        iconPaint.alpha = contentAlpha
                        val iconScale = 0.85f + 0.15f * contentAlphaProgress
                        val iconCenterX = iconRect.centerX()
                        val iconCenterY = iconRect.centerY()

                        canvas.save()
                        canvas.scale(iconScale, iconScale, iconCenterX, iconCenterY)
                        notificationIconClipPath.reset()
                        notificationIconClipPath.addRoundRect(iconRect, iconSize * 0.28f, iconSize * 0.28f, Path.Direction.CW)
                        canvas.clipPath(notificationIconClipPath)
                        canvas.drawBitmap(alert.icon, null, iconRect, iconPaint)
                        canvas.restore()
                    }

                    val textLeft = iconLeft + iconSize + 12f * density
                    val maxAvailableTextWidth = (currentRight - textLeft - 18f * density).coerceAtLeast(50f * density)

                    notificationBodyPaint.typeface = googleSansFlexTypeface ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    notificationBodyPaint.textSize = ((targetBottom - targetTop) * 0.38f).coerceIn(13f * density, 20f * density)
                    notificationBodyPaint.color = Color.WHITE
                    val textAlphaProgress = ((fraction - 0.25f) / 0.60f).coerceIn(0f, 1f)
                    notificationBodyPaint.alpha = (textAlphaProgress * 255).toInt()

                    if (notificationBodyPaint.alpha > 0) {
                        val displayText = computeNotificationDisplayText(alert)
                        val textY = cameraCenterY + notificationBodyPaint.textSize * 0.35f

                        drawMarqueeNotificationText(
                            canvas = canvas,
                            text = displayText,
                            textLeft = textLeft,
                            textY = textY,
                            fadeStartPos = iconLeft + iconSize + 2f * density,
                            currentTop = currentTop,
                            currentRight = currentRight,
                            currentBottom = currentBottom,
                            cornerRadius = cornerRadius,
                            maxAvailableTextWidth = maxAvailableTextWidth,
                        )
                    }
                }

                canvas.restoreToCount(contentSaveCount)
            }
        }
    }

    private fun drawMarqueeNotificationText(
        canvas: Canvas,
        text: String,
        textLeft: Float,
        textY: Float,
        fadeStartPos: Float,
        currentTop: Float,
        currentRight: Float,
        currentBottom: Float,
        cornerRadius: Float,
        maxAvailableTextWidth: Float,
    ) {
        if (animatedNotificationFraction >= 0.98f) {
            checkAndStartMarquee(text, maxAvailableTextWidth)
        }

        if (isMarqueeNeeded && animatedNotificationFraction >= 0.98f) {
            val fadeStart = fadeStartPos.coerceAtLeast(0f)
            val fadeWidth = 14f * density
            val marqueeBounds = RectF(fadeStart, currentTop, currentRight, currentBottom)
            val saveLayerCount = canvas.saveLayer(marqueeBounds, null)

            marqueeClipPath.reset()
            val radii = floatArrayOf(
                0f, 0f,
                cornerRadius, cornerRadius,
                cornerRadius, cornerRadius,
                0f, 0f,
            )
            marqueeClipPath.addRoundRect(
                marqueeBounds,
                radii,
                Path.Direction.CW,
            )
            canvas.clipPath(marqueeClipPath)

            val marqueeGap = 28f * density
            val textWidthMeasure = notificationBodyPaint.measureText(text)
            val x1 = textLeft - marqueeOffset
            val x2 = x1 + textWidthMeasure + marqueeGap
            canvas.drawText(text, x1, textY, notificationBodyPaint)
            canvas.drawText(text, x2, textY, notificationBodyPaint)

            marqueeFadePaint.shader = LinearGradient(
                fadeStart, 0f, fadeStart + fadeWidth, 0f,
                Color.TRANSPARENT, Color.BLACK,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(fadeStart, currentTop, fadeStart + fadeWidth, currentBottom, marqueeFadePaint)

            canvas.restoreToCount(saveLayerCount)
        } else {
            canvas.drawText(text, textLeft, textY, notificationBodyPaint)
        }
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
