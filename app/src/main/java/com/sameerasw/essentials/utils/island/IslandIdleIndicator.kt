/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities - Overlays - Island
 * File: IslandIdleIndicator.kt
 * Description: Time and battery indicator around the camera; standalone when idle, merged into compact pills.
 */

package com.sameerasw.essentials.utils.island

import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import com.sameerasw.essentials.utils.AppUtil
import com.sameerasw.essentials.utils.battery.BatteryInfoUtil

class IslandContentState(val visible: Float, val compact: Float)

interface IslandIdleHost {
    val density: Float
    val cameraCenterX: Float
    val cameraCenterY: Float
    val cameraRadiusPx: Float
    val cutoutGap: Float
    val screenWidth: Float
    val canMerge: Boolean
    val accentColor: Int

    fun requestRedraw()
    fun onInsetChanged()
    fun contentState(): IslandContentState?
    fun contentBounds(): RectF?
}

private class AnimatedColor(initial: Int) {
    var value: Int = initial
        private set
    private var from = initial
    private var to = initial
    private val animator = AnimatedFloatProperty()

    fun snapTo(color: Int) {
        animator.cancel()
        value = color
        from = color
        to = color
    }

    fun animateTo(color: Int, onUpdate: () -> Unit) {
        if (color == to) return
        from = value
        to = color
        animator.animateTo(
            from = 0f,
            to = 1f,
            spec = IslandTransitionSpec.ModeChange,
            onUpdate = {
                value = ArgbEvaluator().evaluate(it, from, to) as Int
                onUpdate()
            },
        )
    }

    fun cancel() = animator.cancel()
}

class IslandIdleIndicator(
    private val context: Context,
    private val host: IslandIdleHost,
    typeface: Typeface?,
) {
    var isEnabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                refreshWanted()
            }
        }

    var useBatteryIcon: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                styleAnimator.animateTo(
                    from = styleFraction,
                    to = if (value) 1f else 0f,
                    spec = IslandTransitionSpec.ModeChange,
                    onUpdate = {
                        styleFraction = it
                        host.requestRedraw()
                    },
                )
            }
        }

    private var wantedFraction = 0f
    private var wantedTarget = false
    private val wantedAnimator = AnimatedFloatProperty()
    private var standaloneFraction = 0f
    private var styleFraction = 0f
    private val styleAnimator = AnimatedFloatProperty()

    private var timeText = ""
    private var timePrev = ""
    private var timeRollFraction = 1f
    private val timeRollAnimator = AnimatedFloatProperty()

    private var batteryTarget = 100
    private var batteryDisplay = 100f
    private var batteryCharging = false
    private var batteryInitialized = false
    private val batteryAnimator = AnimatedFloatProperty()
    private val ringColor = AnimatedColor(Color.WHITE)
    private val iconTint = AnimatedColor(Color.WHITE)
    private var iconRes = 0
    private var icon: Bitmap? = null
    private var iconPrev: Bitmap? = null
    private var iconFade = 1f
    private val iconAnimator = AnimatedFloatProperty()

    private val pillRect = RectF()
    private val ringRect = RectF()
    private val clipRect = RectF()
    private val batteryRect = RectF()
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        this.typeface = typeface ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val rowHeight: Float get() = host.cameraRadiusPx * 2f + 14f * host.density
    private val batterySize: Float get() = (host.cameraRadiusPx * 2f).coerceAtLeast(16f * host.density)

    private fun timeSlotWidth(): Float {
        timePaint.textSize = (rowHeight * 0.34f).coerceIn(12f * host.density, 18f * host.density)
        return timePaint.measureText("0") * 4f + timePaint.measureText(":")
    }

    private fun sideWidth(): Float = maxOf(timeSlotWidth(), batterySize)

    fun leftInset(): Float =
        if (wantedFraction > 0f && host.canMerge) (sideWidth() + host.cutoutGap) * wantedFraction else 0f

    fun rightInset(): Float = leftInset()

    private fun refreshWanted() {
        val wanted = isEnabled && timeText.isNotEmpty()
        if (wanted == wantedTarget) return
        wantedTarget = wanted
        wantedAnimator.animateTo(
            from = wantedFraction,
            to = if (wanted) 1f else 0f,
            spec = IslandTransitionSpec.ModeChange,
            onUpdate = {
                wantedFraction = it
                host.requestRedraw()
                host.onInsetChanged()
            },
        )
    }

    // Standalone visibility tracks the live content fractions instead of running its own animator,
    // so the idle pill and content pills always cross over in lockstep.
    private fun computeStandalone(): Float {
        val state = host.contentState()
        val block = if (state == null) 0f else state.visible * (1f - if (host.canMerge) state.compact else 0f)
        return (wantedFraction * (1f - block)).coerceIn(0f, 1f)
    }

    private fun mergedFraction(): Float {
        val state = host.contentState() ?: return 0f
        return if (host.canMerge) state.visible * state.compact * wantedFraction else 0f
    }

    private fun isShowing(): Boolean = maxOf(computeStandalone(), mergedFraction()) > 0.05f

    fun setTime(text: String) {
        if (text == timeText) return
        if (timeText.isNotEmpty() && isShowing()) {
            timePrev = timeText
            timeRollFraction = 0f
            timeRollAnimator.animateTo(
                from = 0f,
                to = 1f,
                spec = IslandTransitionSpec.ModeChange,
                onUpdate = {
                    timeRollFraction = it
                    host.requestRedraw()
                },
            )
        }
        timeText = text
        refreshWanted()
        host.requestRedraw()
    }

    fun setBattery(level: Int, charging: Boolean) {
        val clamped = level.coerceIn(0, 100)
        val chargingChanged = charging != batteryCharging
        batteryCharging = charging
        val levelChanged = clamped != batteryTarget
        batteryTarget = clamped
        updateIcon(clamped, charging)

        val ring = targetRingColor()
        val tint = if (charging || clamped <= LOW_LEVEL) ring else Color.WHITE
        if (!batteryInitialized) {
            batteryInitialized = true
            batteryDisplay = clamped.toFloat()
            ringColor.snapTo(ring)
            iconTint.snapTo(tint)
            host.requestRedraw()
            return
        }
        ringColor.animateTo(ring) { host.requestRedraw() }
        iconTint.animateTo(tint) { host.requestRedraw() }
        if (levelChanged) {
            batteryAnimator.animateTo(
                from = batteryDisplay,
                to = clamped.toFloat(),
                spec = IslandTransitionSpec.ModeChange,
                onUpdate = {
                    batteryDisplay = it
                    host.requestRedraw()
                },
            )
        } else if (chargingChanged) {
            host.requestRedraw()
        }
    }

    private fun targetRingColor(): Int = when {
        batteryCharging -> CHARGING_COLOR
        batteryTarget <= LOW_LEVEL -> LOW_COLOR
        else -> host.accentColor
    }

    private fun updateIcon(level: Int, charging: Boolean) {
        val res = BatteryInfoUtil.getBatteryIconRes(context, level, charging)
        if (res == iconRes && icon != null) return
        val drawable = ContextCompat.getDrawable(context, res) ?: return
        val bitmap = try {
            AppUtil.drawableToBitmap(drawable, (24f * host.density).toInt())
        } catch (_: Exception) {
            return
        }
        if (icon != null && isShowing()) {
            iconPrev = icon
            iconFade = 0f
            iconAnimator.animateTo(
                from = 0f,
                to = 1f,
                spec = IslandTransitionSpec.ModeChange,
                onUpdate = {
                    iconFade = it
                    if (it >= 1f) iconPrev = null
                    host.requestRedraw()
                },
            )
        } else {
            iconPrev = null
            iconFade = 1f
        }
        iconRes = res
        icon = bitmap
    }

    fun drawBackground(canvas: Canvas) {
        standaloneFraction = computeStandalone()
        val f = standaloneFraction
        if (f <= 0.001f) return

        val pad = (rowHeight - batterySize) / 2f
        val side = sideWidth()
        val targetLeft = (host.cameraCenterX - host.cameraRadiusPx - host.cutoutGap - side - pad)
            .coerceAtLeast(8f * host.density)
        val targetRight = (host.cameraCenterX + host.cameraRadiusPx + host.cutoutGap + side + pad)
            .coerceAtMost(host.screenWidth - 8f * host.density)
        val initialLeft = host.cameraCenterX - host.cameraRadiusPx
        val initialRight = host.cameraCenterX + host.cameraRadiusPx
        pillRect.set(
            initialLeft + (targetLeft - initialLeft) * f,
            host.cameraCenterY - rowHeight / 2f,
            initialRight + (targetRight - initialRight) * f,
            host.cameraCenterY + rowHeight / 2f,
        )
        canvas.drawRoundRect(pillRect, rowHeight / 2f, rowHeight / 2f, pillPaint)
    }

    fun drawForeground(canvas: Canvas) {
        val standaloneAlpha = ((standaloneFraction - 0.3f) / 0.7f).coerceIn(0f, 1f)
        val merged = mergedFraction()
        val alpha = (maxOf(standaloneAlpha, merged) * 255f).toInt().coerceIn(0, 255)
        if (alpha <= 0) return

        // Content is revealed with whichever pill currently holds it, never floating on empty screen.
        var hasClip = false
        if (standaloneFraction > 0.001f) {
            clipRect.set(pillRect)
            hasClip = true
        }
        if (merged > 0.001f) {
            host.contentBounds()?.let {
                if (hasClip) clipRect.union(it) else clipRect.set(it)
                hasClip = true
            }
        }
        val save = canvas.save()
        if (hasClip) canvas.clipRect(clipRect)

        val timeWidth = timeSlotWidth()
        val top = host.cameraCenterY - rowHeight / 2f
        val bottom = host.cameraCenterY + rowHeight / 2f
        val textRight = host.cameraCenterX - host.cameraRadiusPx - host.cutoutGap
        drawRollingDigits(
            canvas = canvas,
            paint = timePaint,
            oldText = timePrev,
            newText = timeText,
            fraction = timeRollFraction,
            edgeX = textRight,
            baseY = (top + bottom) / 2f + timePaint.textSize * 0.35f,
            clipLeft = textRight - timeWidth,
            clipTop = top,
            clipRight = textRight,
            clipBottom = bottom,
            baseAlpha = alpha,
        )

        val batteryRight = host.cameraCenterX + host.cameraRadiusPx + host.cutoutGap + sideWidth()
        val batteryLeft = batteryRight - batterySize
        batteryRect.set(
            batteryLeft,
            host.cameraCenterY - batterySize / 2f,
            batteryRight,
            host.cameraCenterY + batterySize / 2f,
        )
        val ringAlpha = (alpha * (1f - styleFraction)).toInt()
        val iconAlpha = (alpha * styleFraction).toInt()
        if (ringAlpha > 0) drawRing(canvas, ringAlpha)
        if (iconAlpha > 0) drawIcon(canvas, iconAlpha)
        canvas.restoreToCount(save)
    }

    private fun drawRing(canvas: Canvas, alpha: Int) {
        val stroke = batteryRect.width() * 0.16f
        ringRect.set(
            batteryRect.left + stroke / 2f,
            batteryRect.top + stroke / 2f,
            batteryRect.right - stroke / 2f,
            batteryRect.bottom - stroke / 2f,
        )
        ringPaint.strokeWidth = stroke * 0.5f
        ringPaint.color = Color.WHITE
        ringPaint.alpha = (alpha * 0.25f).toInt()
        canvas.drawArc(ringRect, 0f, 360f, false, ringPaint)

        val sweep = 360f * (batteryDisplay / 100f)
        if (sweep > 0.5f) {
            ringPaint.strokeWidth = stroke
            ringPaint.color = ringColor.value
            ringPaint.alpha = alpha
            canvas.drawArc(ringRect, -90f, sweep, false, ringPaint)
        }
    }

    private fun drawIcon(canvas: Canvas, alpha: Int) {
        iconPaint.colorFilter = PorterDuffColorFilter(iconTint.value, PorterDuff.Mode.SRC_IN)
        iconPrev?.let {
            iconPaint.alpha = (alpha * (1f - iconFade)).toInt().coerceIn(0, 255)
            canvas.drawBitmap(it, null, batteryRect, iconPaint)
        }
        icon?.let {
            iconPaint.alpha = (alpha * iconFade).toInt().coerceIn(0, 255)
            canvas.drawBitmap(it, null, batteryRect, iconPaint)
        }
        iconPaint.colorFilter = null
    }

    fun cancelAnimations() {
        wantedAnimator.cancel()
        styleAnimator.cancel()
        timeRollAnimator.cancel()
        batteryAnimator.cancel()
        iconAnimator.cancel()
        ringColor.cancel()
        iconTint.cancel()
    }

    private companion object {
        const val LOW_LEVEL = 15
        val CHARGING_COLOR = 0xFF6DD58C.toInt()
        val LOW_COLOR = 0xFFFF6B6B.toInt()
    }
}
