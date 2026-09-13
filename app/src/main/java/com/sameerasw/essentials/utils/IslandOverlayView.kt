/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities - Overlays
 * File: IslandOverlayView.kt
 * Description: Dynamic island notification pill overlay view expanding from camera cutout.
 */

package com.sameerasw.essentials.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import com.sameerasw.essentials.domain.model.ActiveNotificationAlert
import kotlin.math.abs

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

    private val notificationPillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.WHITE
    }

    private val notificationBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun showNotificationAlert(alert: ActiveNotificationAlert) {
        if (!isIslandEnabled) return
        activeNotificationAlert = alert
        isNotificationAlertActive = true

        notificationAnimator?.cancel()
        val startVal = animatedNotificationFraction
        notificationAnimator = ValueAnimator.ofFloat(startVal, 1.0f).apply {
            duration = 380L
            interpolator = OvershootInterpolator(1.05f)
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
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                animatedNotificationFraction = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
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
        val targetPillHeight = (cameraRadiusPx * 2f + 18f * density).coerceIn(38f * density, 44f * density)
        val targetTop = cameraCenterY - targetPillHeight / 2f
        val targetBottom = cameraCenterY + targetPillHeight / 2f
        val iconSize = (targetPillHeight - 16f * density).coerceIn(20f * density, 24f * density)

        val isCenterCamera = abs(cameraCenterX - screenWidth / 2f) < 50f * density

        if (isCenterCamera) {
            val spaceFromCutout = 20f * density
            val iconMarginLeft = 10f * density
            val minDistLeft = cameraRadiusPx + spaceFromCutout + iconSize + iconMarginLeft

            notificationBodyPaint.textSize = (16f * density).coerceIn(14f, 18f)
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
            val targetLeft = (cameraCenterX - cameraRadiusPx - 8f * density).coerceAtLeast(8f * density)
            val spaceFromCutout = 24f * density
            val iconLeft = cameraCenterX + cameraRadiusPx + spaceFromCutout
            val textLeft = iconLeft + iconSize + 14f * density

            val maxAllowedWidthPx = (maxWidthDp * density).coerceAtMost(screenWidth - 16f * density)
            val maxRight = (targetLeft + maxAllowedWidthPx).coerceAtMost(screenWidth - 8f * density)
            val maxAvailableTextWidth = (maxRight - textLeft - 18f * density).coerceAtLeast(50f * density)

            notificationBodyPaint.textSize = (16f * density).coerceIn(14f, 18f)
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

        if (animatedNotificationFraction > 0.005f && activeNotificationAlert != null) {
            val alert = activeNotificationAlert ?: return
            val fraction = animatedNotificationFraction.coerceIn(0f, 1f)
            val screenWidth = resources.displayMetrics.widthPixels.toFloat()

            val appColor = alert.appColor
            val accentColor = if (appColor != null && appColor != 0 && appColor != Color.TRANSPARENT) {
                appColor
            } else {
                Color.parseColor("#4285F4")
            }

            val targetBounds = computeNotificationTargetBounds(alert)
            val targetLeft = targetBounds.left
            val targetRight = targetBounds.right
            val targetTop = targetBounds.top
            val targetBottom = targetBounds.bottom

            val initialRadius = cameraRadiusPx + 4f * density
            val initialLeft = cameraCenterX - initialRadius
            val initialRight = cameraCenterX + initialRadius
            val initialTop = cameraCenterY - initialRadius
            val initialBottom = cameraCenterY + initialRadius

            val currentLeft = initialLeft + (targetLeft - initialLeft) * fraction
            val currentRight = initialRight + (targetRight - initialRight) * fraction
            val currentTop = initialTop + (targetTop - initialTop) * fraction
            val currentBottom = initialBottom + (targetBottom - initialBottom) * fraction
            notificationPillRect.set(currentLeft, currentTop, currentRight, currentBottom)
            val cornerRadius = (currentBottom - currentTop) / 2f

            notificationPillPaint.color = Color.BLACK
            notificationPillPaint.alpha = (255 * fraction).toInt()

            val finalStroke = 1.5f * density
            notificationPillBorderPaint.color = accentColor
            notificationPillBorderPaint.strokeWidth = finalStroke
            val borderAlpha = ((180 + 75 * (1f - fraction)) * fraction.coerceAtLeast(0.15f)).toInt().coerceIn(0, 255)
            notificationPillBorderPaint.alpha = borderAlpha

            canvas.drawRoundRect(notificationPillRect, cornerRadius, cornerRadius, notificationPillPaint)
            canvas.drawRoundRect(notificationPillRect, cornerRadius, cornerRadius, notificationPillBorderPaint)

            if (fraction > 0.35f) {
                val contentSaveCount = canvas.save()
                notificationContentClipPath.reset()
                notificationContentClipPath.addRoundRect(notificationPillRect, cornerRadius, cornerRadius, Path.Direction.CW)
                canvas.clipPath(notificationContentClipPath)

                val contentAlpha = ((fraction - 0.35f) / 0.65f).coerceIn(0f, 1f)
                val isCenterCamera = abs(cameraCenterX - screenWidth / 2f) < 50f * density

                if (isCenterCamera) {
                    val iconSize = (currentBottom - currentTop - 16f * density).coerceIn(20f * density, 24f * density)
                    val iconLeft = currentLeft + 10f * density
                    val iconTop = cameraCenterY - iconSize / 2f
                    val iconRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

                    if (alert.icon != null) {
                        iconPaint.alpha = (255 * contentAlpha).toInt()
                        notificationIconClipPath.reset()
                        notificationIconClipPath.addRoundRect(iconRect, iconSize * 0.28f, iconSize * 0.28f, Path.Direction.CW)
                        canvas.save()
                        canvas.clipPath(notificationIconClipPath)
                        canvas.drawBitmap(alert.icon, null, iconRect, iconPaint)
                        canvas.restore()
                    }

                    val spaceFromCutout = 20f * density
                    val textLeft = cameraCenterX + cameraRadiusPx + spaceFromCutout
                    val maxAvailableTextWidth = (currentRight - textLeft - 14f * density).coerceAtLeast(40f * density)

                    notificationBodyPaint.textSize = (16f * density).coerceIn(14f, 18f)
                    notificationBodyPaint.color = Color.WHITE
                    notificationBodyPaint.alpha = (250 * contentAlpha).toInt()

                    val displayText = computeNotificationDisplayText(alert)
                    val textY = cameraCenterY + notificationBodyPaint.textSize * 0.35f

                    drawMarqueeNotificationText(
                        canvas = canvas,
                        text = displayText,
                        textLeft = textLeft,
                        textY = textY,
                        currentTop = currentTop,
                        currentRight = currentRight,
                        currentBottom = currentBottom,
                        cornerRadius = cornerRadius,
                        maxAvailableTextWidth = maxAvailableTextWidth,
                    )
                } else {
                    val iconSize = (currentBottom - currentTop - 16f * density).coerceIn(22f * density, 26f * density)
                    val spaceFromCutout = 24f * density
                    val iconLeft = cameraCenterX + cameraRadiusPx + spaceFromCutout
                    val iconTop = cameraCenterY - iconSize / 2f
                    val iconRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

                    if (alert.icon != null) {
                        iconPaint.alpha = (255 * contentAlpha).toInt()
                        notificationIconClipPath.reset()
                        notificationIconClipPath.addRoundRect(iconRect, iconSize * 0.28f, iconSize * 0.28f, Path.Direction.CW)
                        canvas.save()
                        canvas.clipPath(notificationIconClipPath)
                        canvas.drawBitmap(alert.icon, null, iconRect, iconPaint)
                        canvas.restore()
                    }

                    val textLeft = iconLeft + iconSize + 14f * density
                    val maxAvailableTextWidth = (currentRight - textLeft - 18f * density).coerceAtLeast(50f * density)

                    notificationBodyPaint.textSize = (16f * density).coerceIn(14f, 18f)
                    notificationBodyPaint.color = Color.WHITE
                    notificationBodyPaint.alpha = (250 * contentAlpha).toInt()

                    val displayText = computeNotificationDisplayText(alert)
                    val textY = cameraCenterY + notificationBodyPaint.textSize * 0.35f

                    drawMarqueeNotificationText(
                        canvas = canvas,
                        text = displayText,
                        textLeft = textLeft,
                        textY = textY,
                        currentTop = currentTop,
                        currentRight = currentRight,
                        currentBottom = currentBottom,
                        cornerRadius = cornerRadius,
                        maxAvailableTextWidth = maxAvailableTextWidth,
                    )
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
        currentTop: Float,
        currentRight: Float,
        currentBottom: Float,
        cornerRadius: Float,
        maxAvailableTextWidth: Float,
    ) {
        if (animatedNotificationFraction >= 0.95f) {
            checkAndStartMarquee(text, maxAvailableTextWidth)
        }

        if (isMarqueeNeeded && animatedNotificationFraction >= 0.95f) {
            val fadeStart = (textLeft - 3f * density).coerceAtLeast(0f)
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
