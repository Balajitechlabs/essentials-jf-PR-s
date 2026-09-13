/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Handlers
 * File: IslandTouchHandler.kt
 * Description: Touch handler managing gestures (tap to open, swipe up to dismiss) on Island overlay.
 */

package com.sameerasw.essentials.services.handlers

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.domain.HapticFeedbackType
import com.sameerasw.essentials.domain.model.ActiveNotificationAlert
import com.sameerasw.essentials.utils.HapticUtil
import com.sameerasw.essentials.utils.IslandOverlayView
import kotlin.math.hypot

class IslandTouchHandler(
    private val service: AccessibilityService,
) {
    private val settingsRepository by lazy { SettingsRepository(service) }
    var overlayView: IslandOverlayView? = null

    var onNotificationDismissRequested: (() -> Unit)? = null

    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downTime: Long = 0L
    private var isTouchActive: Boolean = false

    private val density: Float
        get() = service.resources.displayMetrics.density

    private val touchSlopPx: Float
        get() = 12f * density

    fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.rawX
        val y = event.rawY

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                downTime = SystemClock.uptimeMillis()
                isTouchActive = true
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isTouchActive) return false
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isTouchActive) return false
                val elapsed = SystemClock.uptimeMillis() - downTime
                val dx = x - downX
                val dy = y - downY
                val totalDist = hypot(dx, dy)

                val isNotifActive = overlayView?.isNotificationAlertActive == true
                if (isNotifActive) {
                    val isSwipeUp = dy < -touchSlopPx
                    val isSwipeDown = dy > touchSlopPx * 1.5f

                    if (isSwipeUp && settingsRepository.isIslandSwipeUpActionEnabled()) {
                        dismissNotification()
                        HapticUtil.performHapticForService(service, HapticFeedbackType.SUBTLE)
                    } else if (isSwipeDown) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                        dismissNotification()
                        HapticUtil.performHapticForService(service, HapticFeedbackType.DOUBLE)
                    } else if (totalDist < touchSlopPx * 2.5f && elapsed < 800L && settingsRepository.isIslandTapActionEnabled()) {
                        val alert = overlayView?.getAlertAt(x, y) ?: overlayView?.getActiveNotificationAlert()
                        if (alert != null) {
                            launchNotificationApp(alert)
                            if (alert.key == overlayView?.getActiveNotificationAlert()?.key) {
                                dismissNotification()
                            } else {
                                overlayView?.removeNotificationByKey(alert.key)
                            }
                        }
                        HapticUtil.performHapticForService(service, HapticFeedbackType.CLICK)
                    }
                }

                isTouchActive = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isTouchActive = false
                return false
            }
        }
        return false
    }

    private fun dismissNotification() {
        onNotificationDismissRequested?.invoke()
    }

    private fun launchNotificationApp(alert: ActiveNotificationAlert) {
        val pendingIntent = alert.contentIntent
        if (pendingIntent != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val options = ActivityOptions.makeBasic().apply {
                        pendingIntentBackgroundActivityStartMode =
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    }
                    pendingIntent.send(service, 0, null, null, null, null, options.toBundle())
                } else {
                    pendingIntent.send()
                }
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            val pm = service.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(alert.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(launchIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
