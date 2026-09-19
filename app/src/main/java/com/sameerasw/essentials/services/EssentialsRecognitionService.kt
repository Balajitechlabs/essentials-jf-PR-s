/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Receivers
 * File: EssentialsRecognitionService.kt
 * Description: Speech recognition service stub required for system assistant integration.
 */

package com.sameerasw.essentials.services

import android.content.Intent
import android.speech.RecognitionService

class EssentialsRecognitionService : RecognitionService() {
    override fun onStartListening(
        recognizerIntent: Intent?,
        listener: Callback?,
    ) {
    }

    override fun onStopListening(listener: Callback?) {
    }

    override fun onCancel(listener: Callback?) {
    }
}
