/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Translation
 * File: TranslationBottomSheet.kt
 * Description: Component file for TranslationBottomSheet.kt.
 */

package com.sameerasw.essentials.translation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.sameerasw.essentials.R
import com.sameerasw.essentials.translation.StringLoader
import com.sameerasw.essentials.translation.TranslationManager
import com.sameerasw.essentials.translation.TranslationValidator
import com.sameerasw.essentials.ui.core.containers.RoundedCardContainer
import com.sameerasw.essentials.ui.core.sheets.EssentialsBottomSheet
import com.sameerasw.essentials.utils.HapticUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationBottomSheet(
    stringKey: String,
    initialTargetLocale: String? = null,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current

    val currentLocale =
        remember {
            val appLocale =
                context.resources.configuration.locales[0]
                    .language
            if (appLocale != "en" && appLocale.isNotBlank()) appLocale else initialTargetLocale ?: "si"
        }

    val translations =
        remember(stringKey) {
            StringLoader.getTranslationsForKey(context, stringKey)
        }

    val sourceEnglish = translations["en"] ?: ""
    val originalTargetVal = translations[currentLocale] ?: ""
    val currentDisplayVal =
        TranslationManager.getOverriddenText(stringKey, currentLocale, originalTargetVal)

    var inputTextFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = currentDisplayVal,
                selection = TextRange(currentDisplayVal.length),
            ),
        )
    }

    val inputText = inputTextFieldValue.text

    val validationResult =
        remember(sourceEnglish, inputText) {
            TranslationValidator.validate(sourceEnglish, inputText)
        }

    EssentialsBottomSheet(
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Column {
                Text(
                    text = "Translate String",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Key: $stringKey",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Source & Target Cards wrapped in RoundedCardContainer
            RoundedCardContainer {
                ListItem(
                    modifier =
                        Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(MaterialTheme.colorScheme.surfaceBright),
                    supportingContent = {
                        Text(
                            text = sourceEnglish.ifBlank { stringKey },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                ) {
                    Text(
                        text = stringResource(R.string.translation_source_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ListItem(
                    modifier =
                        Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(MaterialTheme.colorScheme.surfaceBright),
                    supportingContent = {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            OutlinedTextField(
                                value = inputTextFieldValue,
                                onValueChange = { inputTextFieldValue = it },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(end = 8.dp),
                                placeholder = { Text("Enter translation in ${currentLocale.uppercase()}…") },
                                singleLine = false,
                                maxLines = 4,
                                shape = MaterialTheme.shapes.large,
                            )

                            // Helper Chips for Placeholders
                            if (validationResult.availablePlaceholders.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.translation_insert_placeholder),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                ) {
                                    validationResult.availablePlaceholders.forEach { placeholder ->
                                        val isPresent = !validationResult.missingPlaceholders.contains(placeholder)
                                        SuggestionChip(
                                            onClick = {
                                                HapticUtil.performUIHaptic(view)
                                                val currentText = inputTextFieldValue.text
                                                val selection = inputTextFieldValue.selection
                                                val start = selection.min.coerceIn(0, currentText.length)
                                                val end = selection.max.coerceIn(0, currentText.length)
                                                val newText =
                                                    buildString {
                                                        append(currentText.substring(0, start))
                                                        append(placeholder)
                                                        append(currentText.substring(end))
                                                    }
                                                val newCursor = start + placeholder.length
                                                inputTextFieldValue =
                                                    TextFieldValue(
                                                        text = newText,
                                                        selection = TextRange(newCursor),
                                                    )
                                            },
                                            label = {
                                                Text(
                                                    text = if (isPresent) "✓ $placeholder" else "+ $placeholder",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            },
                                            colors =
                                                SuggestionChipDefaults.suggestionChipColors(
                                                    containerColor =
                                                        if (isPresent) {
                                                            MaterialTheme.colorScheme.surfaceVariant
                                                        } else {
                                                            MaterialTheme.colorScheme.primaryContainer
                                                        },
                                                    labelColor =
                                                        if (isPresent) {
                                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                        } else {
                                                            MaterialTheme.colorScheme.onPrimaryContainer
                                                        },
                                                ),
                                            border = null,
                                            modifier = Modifier.height(28.dp),
                                        )
                                    }
                                }
                            }
                        }
                    },
                ) {
                    Text(
                        text = "Target Language (${currentLocale.uppercase()})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Live Warning / Error Banner
            if (!validationResult.isValid) {
                RoundedCardContainer {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(
                                    if (validationResult.hasCrashRisk) {
                                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                    } else {
                                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                                    },
                                ).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter =
                                    painterResource(
                                        if (validationResult.hasCrashRisk) {
                                            R.drawable.rounded_release_alert_24
                                        } else {
                                            R.drawable.rounded_info_24
                                        },
                                    ),
                                contentDescription = null,
                                tint =
                                    if (validationResult.hasCrashRisk) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.tertiary
                                    },
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text =
                                    if (validationResult.hasCrashRisk) {
                                        stringResource(R.string.translation_format_error_title)
                                    } else {
                                        stringResource(R.string.translation_format_warning_title)
                                    },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color =
                                    if (validationResult.hasCrashRisk) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    },
                            )
                        }
                        validationResult.warnings.forEach { warning ->
                            Text(
                                text = "• $warning",
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    if (validationResult.hasCrashRisk) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    },
                            )
                        }
                    }
                }
            }

            // Action Buttons
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = {
                        HapticUtil.performUIHaptic(view)
                        onDismissRequest()
                    },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = {
                        HapticUtil.performUIHaptic(view)
                        if (validationResult.hasCrashRisk) {
                            android.widget.Toast
                                .makeText(
                                    context,
                                    "Warning: String contains invalid characters that may crash the app.",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                        }
                        if (inputText.trim() != originalTargetVal.trim() && inputText.isNotBlank()) {
                            TranslationManager.addEdit(
                                key = stringKey,
                                locale = currentLocale,
                                originalValue = originalTargetVal,
                                newValue = inputText,
                            )
                            android.widget.Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.translation_saved_toast),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                        } else {
                            TranslationManager.removeEdit(stringKey, currentLocale)
                        }
                        onDismissRequest()
                    },
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.rounded_check_24),
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save Edit")
                }
            }
        }
    }
}
