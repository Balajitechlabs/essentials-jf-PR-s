/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Translation
 * File: TranslationSessionSheet.kt
 * Description: Component file for TranslationSessionSheet.kt.
 */

package com.sameerasw.essentials.translation.ui

import android.util.Log
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sameerasw.essentials.R
import com.sameerasw.essentials.data.repository.GitHubRepository
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.translation.StringLoader
import com.sameerasw.essentials.translation.TranslationManager
import com.sameerasw.essentials.translation.TranslationValidator
import com.sameerasw.essentials.translation.model.TranslationEdit
import com.sameerasw.essentials.ui.core.containers.RoundedCardContainer
import com.sameerasw.essentials.ui.core.sheets.EssentialsBottomSheet
import com.sameerasw.essentials.utils.HapticUtil
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationSessionSheet(
    onDismissRequest: () -> Unit,
    onNeedLogin: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val settingsRepository = remember { SettingsRepository(context) }
    val gitHubRepository = remember { GitHubRepository() }
    val currentUser = remember { settingsRepository.getGitHubUser() }

    val edits =
        remember { mutableStateListOf<TranslationEdit>().apply { addAll(TranslationManager.session.edits) } }

    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successSubmitted by remember { mutableStateOf(false) }
    var showWarningDialog by remember { mutableStateOf(false) }

    val performSubmit = {
        val token = settingsRepository.getGitHubToken()
        if (token == null || currentUser == null) {
            onNeedLogin()
        } else {
            isSubmitting = true
            errorMessage = null
            scope.launch {
                val jsonPayload = TranslationManager.session.toJsonPayload()
                val commentBody =
                    "Automated translation submission from Essentials app.\n\n```json\n$jsonPayload\n```"
                Log.d(
                    "TranslationSessionSheet",
                    "Posting submission comment for user: ${currentUser.login}",
                )
                val success =
                    gitHubRepository.addDiscussionComment(
                        token = token,
                        owner = "sameerasw",
                        repo = "essentials",
                        discussionNumber = 601,
                        body = commentBody,
                    )
                isSubmitting = false
                if (success) {
                    Log.d(
                        "TranslationSessionSheet",
                        "Discussion comment posted successfully",
                    )
                    successSubmitted = true
                    TranslationManager.discardSession()
                } else {
                    Log.e(
                        "TranslationSessionSheet",
                        "Posting discussion comment failed",
                    )
                    errorMessage =
                        context.getString(R.string.translation_submit_error)
                }
            }
        }
    }

    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.translation_format_warning_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(text = stringResource(R.string.translation_submit_warnings_prompt))
            },
            confirmButton = {
                Button(
                    onClick = {
                        HapticUtil.performUIHaptic(view)
                        showWarningDialog = false
                        performSubmit()
                    },
                ) {
                    Text("Submit Anyway")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        HapticUtil.performUIHaptic(view)
                        showWarningDialog = false
                    },
                ) {
                    Text("Review Edits")
                }
            },
        )
    }

    EssentialsBottomSheet(
        onDismissRequest = {
            if (successSubmitted) {
                TranslationManager.discardSession()
            }
            onDismissRequest()
        },
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.settings_translated_texts),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${edits.size} edit(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Row {
                    OutlinedButton(
                        onClick = {
                            HapticUtil.performUIHaptic(view)
                            TranslationManager.discardSession()
                            edits.clear()
                            onDismissRequest()
                        },
                    ) {
                        Text(stringResource(R.string.translation_discard))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            HapticUtil.performUIHaptic(view)
                            val hasAnyWarning =
                                edits.any { edit ->
                                    val src =
                                        StringLoader.getTranslationsForKey(
                                            context,
                                            edit.key,
                                        )["en"] ?: ""
                                    !TranslationValidator.validate(src, edit.newValue).isValid
                                }
                            if (hasAnyWarning) {
                                showWarningDialog = true
                            } else {
                                performSubmit()
                            }
                        },
                        enabled = !isSubmitting && edits.isNotEmpty() && !successSubmitted,
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                modifier =
                                    Modifier
                                        .height(16.dp)
                                        .width(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(stringResource(R.string.translation_submit))
                        }
                    }
                }
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (successSubmitted) {
                Text(
                    text = stringResource(R.string.translation_submitted),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Edits List wrapped in RoundedCardContainer
            if (edits.isNotEmpty()) {
                RoundedCardContainer {
                    edits.forEach { edit ->
                        val sourceEnglish =
                            remember(edit.key) {
                                StringLoader.getTranslationsForKey(context, edit.key)["en"] ?: ""
                            }
                        val validation =
                            remember(sourceEnglish, edit.newValue) {
                                TranslationValidator.validate(sourceEnglish, edit.newValue)
                            }

                        ListItem(
                            modifier =
                                Modifier
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(color = MaterialTheme.colorScheme.surfaceBright),
                            supportingContent = {
                                Column {
                                    Text(
                                        text = "Original: ${edit.originalValue}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "New: ${edit.newValue}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (!validation.isValid) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.padding(top = 4.dp),
                                        ) {
                                            Icon(
                                                painter =
                                                    painterResource(
                                                        if (validation.hasCrashRisk) {
                                                            R.drawable.rounded_release_alert_24
                                                        } else {
                                                            R.drawable.rounded_info_24
                                                        },
                                                    ),
                                                contentDescription = null,
                                                tint =
                                                    if (validation.hasCrashRisk) {
                                                        MaterialTheme.colorScheme.error
                                                    } else {
                                                        MaterialTheme.colorScheme.tertiary
                                                    },
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Text(
                                                text = validation.warnings.first(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color =
                                                    if (validation.hasCrashRisk) {
                                                        MaterialTheme.colorScheme.error
                                                    } else {
                                                        MaterialTheme.colorScheme.tertiary
                                                    },
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            },
                            trailingContent =
                                if (!successSubmitted) {
                                    {
                                        IconButton(
                                            onClick = {
                                                HapticUtil.performUIHaptic(view)
                                                TranslationManager.removeEdit(edit.key, edit.locale)
                                                edits.remove(edit)
                                            },
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.rounded_delete_24),
                                                contentDescription = "Remove edit",
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                        ) {
                            Text(
                                text = "Key: ${edit.key} (${edit.locale.uppercase()})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "No pending edits",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}
