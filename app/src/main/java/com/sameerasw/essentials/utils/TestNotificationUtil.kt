/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities
 * File: TestNotificationUtil.kt
 * Description: Realistic test notification generator for Dynamic Island preview with rich apps and actions.
 */

package com.sameerasw.essentials.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.sameerasw.essentials.domain.model.ActiveNotificationAlert
import com.sameerasw.essentials.domain.model.NotificationActionItem
import kotlin.random.Random

object TestNotificationUtil {

    private data class AppPreset(
        val appName: String,
        val packageName: String,
        val color: Int,
        val iconLetter: String,
        val sampleSenders: List<String>,
        val sampleTitles: List<String>,
        val sampleBodies: List<String>,
        val sampleActions: List<List<NotificationActionItem>>
    )

    private val apps = listOf(
        AppPreset(
            appName = "WhatsApp",
            packageName = "com.whatsapp",
            color = 0xFF25D366.toInt(),
            iconLetter = "W",
            sampleSenders = listOf("Alice Johnson", "Michael Chen", "Sarah Williams", "Family Group", "Team Alpha"),
            sampleTitles = listOf("Alice Johnson", "Michael Chen", "Sarah Williams", "Family Group", "Team Alpha"),
            sampleBodies = listOf(
                "Hey! Are you free for lunch today?",
                "Can you review the latest design updates?",
                "Running 10 minutes late to the meeting!",
                "Great job on the release today! 🎉",
                "Sent an attachment: project_specs.pdf"
            ),
            sampleActions = listOf(
                listOf(
                    NotificationActionItem(title = "Reply", isQuickReply = true, actionKey = "wa_reply"),
                    NotificationActionItem(title = "Mark as read", isQuickReply = false, actionKey = "wa_read")
                ),
                listOf(
                    NotificationActionItem(title = "Reply", isQuickReply = true, actionKey = "wa_reply"),
                    NotificationActionItem(title = "Mute", isQuickReply = false, actionKey = "wa_mute")
                )
            )
        ),
        AppPreset(
            appName = "Telegram",
            packageName = "org.telegram.messenger",
            color = 0xFF24A1DE.toInt(),
            iconLetter = "T",
            sampleSenders = listOf("Elena Rostova", "Alexandre Dumas", "Dev Community", "Product Updates"),
            sampleTitles = listOf("Elena Rostova", "Alexandre Dumas", "Dev Community", "Product Updates"),
            sampleBodies = listOf(
                "Check out this new Kotlin Multiplatform library!",
                "Did you test the new animation curves?",
                "Server deployment finished with 0 errors.",
                "Let's sync up after the standup call."
            ),
            sampleActions = listOf(
                listOf(
                    NotificationActionItem(title = "Reply", isQuickReply = true, actionKey = "tg_reply"),
                    NotificationActionItem(title = "Mark as read", isQuickReply = false, actionKey = "tg_read")
                )
            )
        ),
        AppPreset(
            appName = "Gmail",
            packageName = "com.google.android.gm",
            color = 0xFFEA4335.toInt(),
            iconLetter = "M",
            sampleSenders = listOf("GitHub", "Google Cloud", "Stripe", "Figma", "Spotify"),
            sampleTitles = listOf(
                "Security alert: New sign-in detected",
                "Your monthly invoice is ready",
                "New comment on 'Island Design Spec'",
                "Your weekly developer digest",
                "Build #482 succeeded on main branch"
            ),
            sampleBodies = listOf(
                "We detected a sign-in from a new macOS device in Colombo, LK.",
                "Your receipt for Google Cloud Platform Services is now available.",
                "Sameera mentioned you in a comment on Frame 4.",
                "Explore the top open-source projects trending this week.",
                "All 42 test suites passed in 1m 24s."
            ),
            sampleActions = listOf(
                listOf(
                    NotificationActionItem(title = "Archive", isQuickReply = false, actionKey = "gm_archive"),
                    NotificationActionItem(title = "Reply", isQuickReply = true, actionKey = "gm_reply")
                ),
                listOf(
                    NotificationActionItem(title = "Delete", isQuickReply = false, actionKey = "gm_delete"),
                    NotificationActionItem(title = "Mark as read", isQuickReply = false, actionKey = "gm_read")
                )
            )
        ),
        AppPreset(
            appName = "GitHub",
            packageName = "com.github.android",
            color = 0xFF24292E.toInt(),
            iconLetter = "G",
            sampleSenders = listOf("GitHub Actions", "Dependabot", "Octocat", "Code Review"),
            sampleTitles = listOf(
                "airsync-android: PR #42 merged",
                "Security vulnerability in gradle wrapper",
                "Review requested on 'Dynamic Island actions'",
                "Workflow run completed: CI / Build"
            ),
            sampleBodies = listOf(
                "sameerasw merged commit 8a1f20 into main.",
                "Dependabot created a PR to bump compose-bom to 2026.03.00.",
                "Please review the changes requested by the core team.",
                "All checks passed successfully."
            ),
            sampleActions = listOf(
                listOf(
                    NotificationActionItem(title = "View PR", isQuickReply = false, actionKey = "gh_view"),
                    NotificationActionItem(title = "Approve", isQuickReply = false, actionKey = "gh_approve")
                ),
                listOf(
                    NotificationActionItem(title = "Reply", isQuickReply = true, actionKey = "gh_reply")
                )
            )
        ),
        AppPreset(
            appName = "Messages",
            packageName = "com.google.android.apps.messaging",
            color = 0xFF1A73E8.toInt(),
            iconLetter = "SMS",
            sampleSenders = listOf("David Kim", "Bank of Ceylon", "Uber", "Verification"),
            sampleTitles = listOf("David Kim", "Bank OTP", "Uber", "Auth Code"),
            sampleBodies = listOf(
                "Can you pick up the package on your way home?",
                "Your one-time verification code is 849201. Valid for 5 mins.",
                "Your driver is arriving in a White Prius (CAB-1234).",
                "Hey, where are we meeting tonight?"
            ),
            sampleActions = listOf(
                listOf(
                    NotificationActionItem(title = "Reply", isQuickReply = true, actionKey = "msg_reply"),
                    NotificationActionItem(title = "Copy Code", isQuickReply = false, actionKey = "msg_copy")
                ),
                listOf(
                    NotificationActionItem(title = "Mark as read", isQuickReply = false, actionKey = "msg_read")
                )
            )
        ),
        AppPreset(
            appName = "Slack",
            packageName = "com.Slack",
            color = 0xFF4A154B.toInt(),
            iconLetter = "S",
            sampleSenders = listOf("#general", "#dev-android", "Jordan Smith", "#design-critique"),
            sampleTitles = listOf("#general", "#dev-android", "Jordan Smith", "#design-critique"),
            sampleBodies = listOf(
                "Can anyone look at the crash log on Android 15?",
                "Pushed the fix for segmented button corner radii!",
                "Are you attending the sprint retrospective at 2 PM?",
                "The new pitch black tokens look super clean."
            ),
            sampleActions = listOf(
                listOf(
                    NotificationActionItem(title = "Reply", isQuickReply = true, actionKey = "slack_reply"),
                    NotificationActionItem(title = "Open Channel", isQuickReply = false, actionKey = "slack_open")
                )
            )
        )
    )

    private fun generateAppBitmap(letter: String, bgColor: Int): Bitmap {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawRoundRect(rect, 24f, 24f, paint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = if (letter.length > 1) 32f else 46f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        val fontMetrics = textPaint.fontMetrics
        val yPos = (size / 2f) - ((fontMetrics.descent + fontMetrics.ascent) / 2f)
        canvas.drawText(letter, size / 2f, yPos, textPaint)

        return bitmap
    }

    /**
     * Generates a realistic test notification alert for Island preview
     */
    fun generateRandomNotification(context: Context? = null): ActiveNotificationAlert {
        val preset = apps.random()
        val randomSender = preset.sampleSenders.random()
        val randomTitle = preset.sampleTitles.random()
        val randomBody = preset.sampleBodies.random()
        val actions = preset.sampleActions.randomOrNull() ?: emptyList()
        val mockIcon = generateAppBitmap(preset.iconLetter, preset.color)

        return ActiveNotificationAlert(
            key = "simulated_${preset.packageName}_${System.currentTimeMillis()}",
            packageName = preset.packageName,
            title = randomTitle,
            text = randomBody,
            icon = mockIcon,
            contentIntent = null,
            timestamp = System.currentTimeMillis(),
            appColor = preset.color,
            senderName = randomSender,
            appName = preset.appName,
            appIcon = mockIcon,
            actions = actions
        )
    }
}
