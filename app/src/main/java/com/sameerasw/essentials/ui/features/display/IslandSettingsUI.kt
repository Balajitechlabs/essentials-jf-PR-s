/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: UI Feature - Display
 * File: IslandSettingsUI.kt
 * Description: UI settings composable for Island dynamic notifications feature.
 */

package com.sameerasw.essentials.ui.features.display

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.essentials.R
import com.sameerasw.essentials.ui.components.sliders.ConfigSliderItem
import com.sameerasw.essentials.ui.core.cards.IconToggleItem
import com.sameerasw.essentials.ui.core.containers.RoundedCardContainer
import com.sameerasw.essentials.ui.core.sheets.PermissionsBottomSheet
import com.sameerasw.essentials.ui.modifiers.highlight
import com.sameerasw.essentials.utils.HapticUtil
import com.sameerasw.essentials.utils.PermissionUIHelper
import com.sameerasw.essentials.utils.PermissionUtils
import com.sameerasw.essentials.utils.ShellUtils
import com.sameerasw.essentials.viewmodels.MainViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun IslandSettingsUI(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    highlightSetting: String? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var requestingPermissionsFor by remember { mutableStateOf<Pair<Int, List<String>>?>(null) }

    if (requestingPermissionsFor != null) {
        val (titleRes, permKeys) = requestingPermissionsFor!!
        val permissionItems = PermissionUIHelper.getPermissionItems(permKeys, context, viewModel)
        PermissionsBottomSheet(
            onDismissRequest = {
                requestingPermissionsFor = null
                viewModel.check(context)
            },
            featureTitle = stringResource(titleRes),
            permissions = permissionItems,
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RoundedCardContainer(
            spacing = 2.dp,
            cornerRadius = 24.dp,
        ) {
            IconToggleItem(
                iconRes = R.drawable.rounded_notifications_unread_24,
                title = stringResource(R.string.island_enable_title),
                description = stringResource(R.string.island_enable_desc),
                isChecked = viewModel.isIslandEnabled.value,
                onCheckedChange = { checked ->
                    HapticUtil.performVirtualKeyHaptic(view)
                    if (checked) {
                        val missingPermissions = mutableListOf<String>()
                        if (!viewModel.isAccessibilityEnabled.value) {
                            missingPermissions.add("ACCESSIBILITY")
                        }
                        if (!viewModel.isNotificationListenerEnabled.value) {
                            missingPermissions.add("NOTIFICATION_LISTENER")
                        }

                        if (missingPermissions.isNotEmpty()) {
                            requestingPermissionsFor = Pair(R.string.island_title, missingPermissions)
                        } else {
                            viewModel.setIslandEnabled(true)
                        }
                    } else {
                        viewModel.setIslandEnabled(false)
                    }
                },
                modifier = Modifier.highlight(highlightSetting == "island_enabled"),
            )
        }

        Text(
            text = stringResource(R.string.island_section_position),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )

        RoundedCardContainer(
            spacing = 2.dp,
            cornerRadius = 24.dp,
        ) {
            IconToggleItem(
                iconRes = R.drawable.rounded_center_focus_strong_24,
                title = stringResource(R.string.island_auto_detect_title),
                description = stringResource(R.string.island_auto_detect_desc),
                isChecked = viewModel.isIslandAutoDetect.value,
                onCheckedChange = { checked ->
                    HapticUtil.performVirtualKeyHaptic(view)
                    viewModel.setIslandAutoDetect(checked)
                },
                modifier = Modifier.highlight(highlightSetting == "island_use_auto_detect"),
            )

            AnimatedVisibility(
                visible = !viewModel.isIslandAutoDetect.value,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ConfigSliderItem(
                        title = stringResource(R.string.island_camera_offset_x_title),
                        value = viewModel.islandCameraOffsetX.floatValue,
                        onValueChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setIslandCameraOffsetX(it)
                        },
                        valueRange = 0f..100f,
                        increment = 1f,
                        iconRes = R.drawable.rounded_border_left_24,
                        valueFormatter = { "${it.toInt()}%" },
                    )
                    ConfigSliderItem(
                        title = stringResource(R.string.island_camera_offset_y_title),
                        value = viewModel.islandCameraOffsetY.floatValue,
                        onValueChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setIslandCameraOffsetY(it)
                        },
                        valueRange = 0f..20f,
                        increment = 0.5f,
                        iconRes = R.drawable.rounded_border_top_24,
                        valueFormatter = { "%.1f%%".format(it) },
                    )
                }
            }

            ConfigSliderItem(
                title = stringResource(R.string.island_camera_size_title),
                value = viewModel.islandCameraSize.floatValue,
                onValueChange = {
                    HapticUtil.performUIHaptic(view)
                    viewModel.setIslandCameraSize(it)
                },
                valueRange = 0.05f..2.0f,
                increment = 0.05f,
                iconRes = R.drawable.rounded_arrows_outward_24,
                valueFormatter = { "%.2fx".format(it) },
            )
        }

        Text(
            text = stringResource(R.string.island_section_behavior),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )

        RoundedCardContainer(
            spacing = 2.dp,
            cornerRadius = 24.dp,
        ) {
            IconToggleItem(
                iconRes = R.drawable.rounded_notifications_off_24,
                title = stringResource(R.string.island_suppress_system_heads_up_title),
                description = stringResource(R.string.island_suppress_system_heads_up_desc),
                isChecked = viewModel.isIslandSuppressSystemHeadsUp.value,
                onCheckedChange = { checked ->
                    HapticUtil.performVirtualKeyHaptic(view)
                    if (checked && !PermissionUtils.canWriteSecureSettings(context) && !ShellUtils.isAvailable(context)) {
                        requestingPermissionsFor = Pair(R.string.island_suppress_system_heads_up_title, listOf("WRITE_SECURE_SETTINGS"))
                    } else {
                        viewModel.setIslandSuppressSystemHeadsUp(checked)
                    }
                },
                modifier = Modifier.highlight(highlightSetting == "island_suppress_system_heads_up"),
            )

            IconToggleItem(
                iconRes = R.drawable.rounded_mobile_lock_portrait_24,
                title = stringResource(R.string.island_hide_when_screen_off_title),
                description = stringResource(R.string.island_hide_when_screen_off_desc),
                isChecked = viewModel.isIslandHideWhenScreenOff.value,
                onCheckedChange = { checked ->
                    HapticUtil.performVirtualKeyHaptic(view)
                    viewModel.setIslandHideWhenScreenOff(checked)
                },
                modifier = Modifier.highlight(highlightSetting == "island_hide_when_screen_off"),
            )

            ConfigSliderItem(
                title = stringResource(R.string.island_timeout_title),
                value = (viewModel.islandTimeoutMs.longValue / 1000f),
                onValueChange = {
                    HapticUtil.performUIHaptic(view)
                    viewModel.setIslandTimeoutMs((it * 1000).toLong())
                },
                valueRange = 2f..10f,
                increment = 0.5f,
                iconRes = R.drawable.rounded_timer_24,
                valueFormatter = { "%.1fs".format(it) },
            )
        }

        Text(
            text = stringResource(R.string.island_section_actions),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )

        RoundedCardContainer(
            spacing = 2.dp,
            cornerRadius = 24.dp,
        ) {
            IconToggleItem(
                iconRes = R.drawable.rounded_touch_app_24,
                title = stringResource(R.string.island_action_tap_title),
                description = stringResource(R.string.island_action_tap_desc),
                isChecked = viewModel.isIslandTapActionEnabled.value,
                onCheckedChange = { checked ->
                    HapticUtil.performVirtualKeyHaptic(view)
                    viewModel.setIslandTapActionEnabled(checked)
                },
                modifier = Modifier.highlight(highlightSetting == "island_tap_action_enabled"),
            )
            IconToggleItem(
                iconRes = R.drawable.rounded_pan_tool_alt_24,
                title = stringResource(R.string.island_action_swipe_up_title),
                description = stringResource(R.string.island_action_swipe_up_desc),
                isChecked = viewModel.isIslandSwipeUpActionEnabled.value,
                onCheckedChange = { checked ->
                    HapticUtil.performVirtualKeyHaptic(view)
                    viewModel.setIslandSwipeUpActionEnabled(checked)
                },
                modifier = Modifier.highlight(highlightSetting == "island_swipe_up_action_enabled"),
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
