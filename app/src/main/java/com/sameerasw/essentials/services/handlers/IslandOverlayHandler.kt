/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Handlers
 * File: IslandOverlayHandler.kt
 * Description: Background handler managing Island dynamic notification overlay window and life-cycle.
 */

package com.sameerasw.essentials.services.handlers

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.DisplayCutout
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowMetrics
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.domain.model.ActiveNotificationAlert
import com.sameerasw.essentials.services.NotificationListener
import com.sameerasw.essentials.utils.IslandOverlayView
import com.sameerasw.essentials.utils.OverlayHelper

class IslandOverlayHandler(
    private val service: AccessibilityService,
) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val settingsRepository by lazy { SettingsRepository(service) }
    private var windowManager: WindowManager? = null
    private var overlayView: IslandOverlayView? = null
    private var touchAnchorView: View? = null
    private val touchHandler by lazy { IslandTouchHandler(service) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isOverlayAdded = false
    private var isTouchAnchorAdded = false
    private var isNotificationsListenerRegistered = false

    private var cameraCenterX = 0f
    private var cameraCenterY = 0f
    private var cameraRadiusPx = 36f

    private var isScreenOff = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_SCREEN_OFF) {
                isScreenOff = true
                if (settingsRepository.isIslandHideWhenScreenOffEnabled()) {
                    mainHandler.removeCallbacks(dismissNotificationRunnable)
                    overlayView?.dismissNotificationAlert()
                    restoreTouchAnchor()
                }
            } else if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_USER_PRESENT) {
                isScreenOff = false
            }
        }
    }

    private val dismissNotificationRunnable = Runnable {
        overlayView?.dismissNotificationAlert()
        restoreTouchAnchor()
    }

    private val notificationAlertListener = object : NotificationListener.NotificationAlertListener {
        override fun onNotificationAlertPosted(alert: ActiveNotificationAlert) {
            if (!settingsRepository.isIslandEnabled()) return
            if (isScreenOff && settingsRepository.isIslandHideWhenScreenOffEnabled()) return

            mainHandler.post {
                ensureOverlayAttached()
                overlayView?.showNotificationAlert(alert)
                expandTouchAnchorForNotification()
                val timeout = settingsRepository.getIslandTimeoutMs()
                mainHandler.removeCallbacks(dismissNotificationRunnable)
                mainHandler.postDelayed(dismissNotificationRunnable, timeout)
            }
        }

        override fun onNotificationAlertRemoved(key: String) {
            mainHandler.post {
                if (overlayView?.getActiveNotificationAlert()?.key == key) {
                    mainHandler.removeCallbacks(dismissNotificationRunnable)
                    overlayView?.dismissNotificationAlert()
                    restoreTouchAnchor()
                }
            }
        }
    }

    init {
        settingsRepository.registerOnSharedPreferenceChangeListener(this)
        windowManager = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        service.registerReceiver(screenReceiver, filter)

        touchHandler.onNotificationDismissRequested = {
            mainHandler.removeCallbacks(dismissNotificationRunnable)
            overlayView?.dismissNotificationAlert()
            restoreTouchAnchor()
        }

        updateOverlay()
    }

    fun onConfigurationChanged() {
        if (settingsRepository.isIslandEnabled()) {
            updateOverlayPosition()
        }
    }

    private fun registerNotificationsListener() {
        if (!isNotificationsListenerRegistered) {
            NotificationListener.addNotificationAlertListener(notificationAlertListener)
            isNotificationsListenerRegistered = true
        }
    }

    private fun unregisterNotificationsListener() {
        if (isNotificationsListenerRegistered) {
            NotificationListener.removeNotificationAlertListener(notificationAlertListener)
            isNotificationsListenerRegistered = false
            mainHandler.removeCallbacks(dismissNotificationRunnable)
            overlayView?.dismissNotificationAlert()
            restoreTouchAnchor()
        }
    }

    private fun ensureOverlayAttached() {
        if (isOverlayAdded && overlayView != null) return
        updateOverlay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun updateOverlay() {
        val wm = windowManager ?: return

        if (!settingsRepository.isIslandEnabled()) {
            unregisterNotificationsListener()
            removeOverlay()
            return
        }

        registerNotificationsListener()

        if (overlayView == null) {
            overlayView = IslandOverlayView(service).apply {
                this.isIslandEnabled = settingsRepository.isIslandEnabled()
                this.onDismissAnimationEnd = {
                    restoreTouchAnchor()
                }
            }

            val params = OverlayHelper.createOverlayLayoutParams(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                isTouchable = false
            )

            try {
                wm.addView(overlayView, params)
                isOverlayAdded = true
            } catch (e: Exception) {
                Log.e("IslandOverlayHandler", "Failed to add Island overlay", e)
            }
        }

        if (touchAnchorView == null) {
            touchAnchorView = View(service).apply {
                touchHandler.overlayView = this@IslandOverlayHandler.overlayView
                setOnTouchListener { _, event ->
                    touchHandler.onTouchEvent(event)
                }
            }
        }

        updateOverlayPosition()
    }

    private fun updateOverlayPosition() {
        val wm = windowManager ?: return

        val screenWidth: Int
        val screenHeight: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics: WindowMetrics = wm.maximumWindowMetrics
            val bounds: Rect = metrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(size)
            screenWidth = size.x
            screenHeight = size.y
        }

        val useAutoDetect = settingsRepository.isIslandAutoDetectEnabled()
        var detectedCutout = false

        if (useAutoDetect && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val cutout: DisplayCutout? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    wm.maximumWindowMetrics.windowInsets.displayCutout
                } else {
                    null
                }

                if (cutout != null) {
                    val rects = cutout.boundingRects
                    if (rects.isNotEmpty()) {
                        val topCutout = rects.find { it.top == 0 } ?: rects.first()
                        cameraCenterX = topCutout.centerX().toFloat()
                        cameraCenterY = topCutout.centerY().toFloat()
                        cameraRadiusPx = (topCutout.width().coerceAtMost(topCutout.height()) / 2f).coerceAtLeast(24f)
                        detectedCutout = true
                    }
                }
            } catch (_: Exception) {}
        }

        if (!detectedCutout) {
            val offsetXPercent = settingsRepository.getIslandCameraOffsetX()
            val offsetYPercent = settingsRepository.getIslandCameraOffsetY()
            val sizeScale = settingsRepository.getIslandCameraSize()

            cameraCenterX = (offsetXPercent / 100f) * screenWidth
            cameraCenterY = (offsetYPercent / 100f) * screenHeight
            cameraRadiusPx = (36f * service.resources.displayMetrics.density) * sizeScale
        }

        overlayView?.cameraCenterX = cameraCenterX
        overlayView?.cameraCenterY = cameraCenterY
        overlayView?.cameraRadiusPx = cameraRadiusPx
        overlayView?.maxWidthDp = settingsRepository.getIslandMaxWidth()
        overlayView?.invalidate()
    }

    private fun expandTouchAnchorForNotification() {
        val wm = windowManager ?: return
        val anchor = touchAnchorView ?: return
        val ov = overlayView ?: return

        val targetBounds = ov.getNotificationTargetBounds()
        val density = service.resources.displayMetrics.density
        val padH = 16f * density
        val padV = 10f * density

        val touchWidth = ((targetBounds.right - targetBounds.left) + padH * 2).toInt()
        val touchHeight = ((targetBounds.bottom - targetBounds.top) + padV * 2).toInt()

        val touchParams = WindowManager.LayoutParams(
            touchWidth,
            touchHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (targetBounds.left - padH).toInt()
            y = (targetBounds.top - padV).toInt()
        }

        if (!isTouchAnchorAdded) {
            try {
                wm.addView(anchor, touchParams)
                isTouchAnchorAdded = true
            } catch (e: Exception) {
                Log.e("IslandOverlayHandler", "Failed to add Island touch anchor", e)
            }
        } else {
            try {
                wm.updateViewLayout(anchor, touchParams)
            } catch (e: Exception) {
                Log.e("IslandOverlayHandler", "Failed to update Island touch anchor", e)
            }
        }
    }

    private fun restoreTouchAnchor() {
        val wm = windowManager ?: return
        val anchor = touchAnchorView ?: return
        if (isTouchAnchorAdded) {
            try {
                wm.removeViewImmediate(anchor)
                isTouchAnchorAdded = false
            } catch (_: Exception) {}
        }
    }

    private fun removeOverlay() {
        val wm = windowManager ?: return
        restoreTouchAnchor()
        overlayView?.let {
            if (isOverlayAdded) {
                try {
                    wm.removeViewImmediate(it)
                } catch (_: Exception) {}
                isOverlayAdded = false
            }
        }
        overlayView = null
    }

    fun onDestroy() {
        settingsRepository.unregisterOnSharedPreferenceChangeListener(this)
        try {
            service.unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        unregisterNotificationsListener()
        removeOverlay()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            SettingsRepository.KEY_ISLAND_ENABLED -> updateOverlay()
            SettingsRepository.KEY_ISLAND_USE_AUTO_DETECT,
            SettingsRepository.KEY_ISLAND_CAMERA_OFFSET_X,
            SettingsRepository.KEY_ISLAND_CAMERA_OFFSET_Y,
            SettingsRepository.KEY_ISLAND_CAMERA_SIZE,
            SettingsRepository.KEY_ISLAND_MAX_WIDTH -> updateOverlayPosition()
        }
    }
}
