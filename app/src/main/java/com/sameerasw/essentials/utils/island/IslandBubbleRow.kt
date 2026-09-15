/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities - Overlays - Island
 * File: IslandBubbleRow.kt
 * Description: Reusable renderer for bubbles docked to an edge of the island pill.
 */

package com.sameerasw.essentials.utils.island

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF

enum class IslandBubbleSide { LEADING, TRAILING }

enum class IslandBubbleIconShape { ROUNDED_SQUARE, CIRCLE }

class IslandBubbleSpec(
    val key: Any,
    val visibleFraction: Float,
    val icon: Bitmap?,
    val iconShape: IslandBubbleIconShape = IslandBubbleIconShape.ROUNDED_SQUARE,
    val iconTint: Int? = null,
)

object IslandBubbleRow {
    fun reservedWidth(bubbles: List<IslandBubbleSpec>, bubbleSize: Float, bubbleGap: Float): Float {
        var width = 0f
        for (bubble in bubbles) width += (bubbleSize + bubbleGap) * bubble.visibleFraction
        return width
    }

    fun draw(
        canvas: Canvas,
        side: IslandBubbleSide,
        bubbles: List<IslandBubbleSpec>,
        anchorEdge: Float,
        top: Float,
        bottom: Float,
        bubbleSize: Float,
        bubbleGap: Float,
        density: Float,
        pillPaint: Paint,
        iconPaint: Paint,
        tintPaint: Paint,
        outRects: MutableMap<Any, RectF>,
        extraGap: Float = 0f,
    ) {
        outRects.clear()
        var running = anchorEdge
        val radius = bubbleSize / 2f
        val iconSize = (bubbleSize - 12f * density).coerceAtLeast(14f * density)
        val iconPad = (bubbleSize - iconSize) / 2f
        val clipPath = Path()
        val gap = bubbleGap + extraGap

        for (bubble in bubbles) {
            val frac = bubble.visibleFraction
            if (frac <= 0.01f) continue

            val left: Float
            val right: Float
            if (side == IslandBubbleSide.LEADING) {
                right = running - gap
                left = right - bubbleSize
                running = left
            } else {
                left = running + gap
                right = left + bubbleSize
                running = right
            }

            val rect = RectF(left, top, right, bottom)
            outRects[bubble.key] = rect

            val saveCount = canvas.save()
            canvas.scale(frac, frac, rect.centerX(), rect.centerY())

            pillPaint.alpha = (frac * 255).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, radius, radius, pillPaint)

            val icon = bubble.icon
            if (icon != null) {
                val iconRect = RectF(left + iconPad, top + iconPad, left + iconPad + iconSize, top + iconPad + iconSize)
                clipPath.reset()
                if (bubble.iconShape == IslandBubbleIconShape.CIRCLE) {
                    clipPath.addCircle(iconRect.centerX(), iconRect.centerY(), iconSize / 2f, Path.Direction.CW)
                } else {
                    clipPath.addRoundRect(iconRect, iconSize * 0.28f, iconSize * 0.28f, Path.Direction.CW)
                }
                canvas.save()
                canvas.clipPath(clipPath)
                val paint = if (bubble.iconTint != null) tintPaint else iconPaint
                if (bubble.iconTint != null) {
                    paint.colorFilter = PorterDuffColorFilter(bubble.iconTint, PorterDuff.Mode.SRC_IN)
                }
                paint.alpha = (frac * 255).toInt().coerceIn(0, 255)
                canvas.drawBitmap(icon, null, iconRect, paint)
                canvas.restore()
            }

            canvas.restoreToCount(saveCount)
        }
    }
}
