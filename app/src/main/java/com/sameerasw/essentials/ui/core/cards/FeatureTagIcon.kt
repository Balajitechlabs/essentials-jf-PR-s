/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Core UI Components
 * File: FeatureTagIcon.kt
 * Description: Small icon-only status chip used to mark beta, unsupported, and legacy features.
 */

package com.sameerasw.essentials.ui.core.cards

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
fun FeatureTagIcon(
    @DrawableRes iconRes: Int,
    containerColor: Color,
    contentColor: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(containerColor, MaterialTheme.shapes.extraSmall)
                .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
    }
}
