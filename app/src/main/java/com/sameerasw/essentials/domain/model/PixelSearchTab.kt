/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Domain Layer Models & Registries
 * File: PixelSearchTab.kt
 * Description: Category filter tab enumeration for Pixel Search results.
 */

package com.sameerasw.essentials.domain.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.sameerasw.essentials.R

enum class PixelSearchTab(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int? = null,
) {
    ALL(R.string.pixel_search_tab_all),
    APPS(R.string.pixel_search_tab_apps, R.drawable.rounded_apps_24),
    CONTACTS(R.string.pixel_search_tab_contacts, R.drawable.rounded_call_24),
    SETTINGS(R.string.pixel_search_tab_settings, R.drawable.rounded_settings_24),
    WEB(R.string.pixel_search_tab_web, R.drawable.rounded_language_24),
}
