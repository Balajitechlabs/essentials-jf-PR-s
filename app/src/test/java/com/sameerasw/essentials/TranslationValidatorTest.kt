/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 */

package com.sameerasw.essentials

import com.sameerasw.essentials.translation.TranslationValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationValidatorTest {

    @Test
    fun validTranslation_passesValidation() {
        val source = "Travelling to %1$s"
        val translation = "Viajando a %1$s"
        val result = TranslationValidator.validate(source, translation)
        assertTrue(result.isValid)
        assertTrue(result.warnings.isEmpty())
        assertFalse(result.hasCrashRisk)
    }

    @Test
    fun missingPlaceholder_failsValidation() {
        val source = "Travelling to %1$s"
        val translation = "Viajando sin destino"
        val result = TranslationValidator.validate(source, translation)
        assertFalse(result.isValid)
        assertEquals(listOf("%1$s"), result.missingPlaceholders)
        assertFalse(result.hasCrashRisk)
    }

    @Test
    fun cyrillicFormatSpecifier_flagsCrashRisk() {
        val source = "Remaining distance: %1$.1f km"
        // Cyrillic 'ф' (U+0444)
        val translation = "Преостала удаљеност: %1$.1ф км"
        val result = TranslationValidator.validate(source, translation)
        assertFalse(result.isValid)
        assertTrue(result.hasCrashRisk)
        assertTrue(result.warnings.any { it.contains("non-ASCII character") })
    }

    @Test
    fun commaFormatSpecifier_flagsWarning() {
        val source = "Remaining: %1$.1f km"
        val translation = "Restant: %1,1f km"
        val result = TranslationValidator.validate(source, translation)
        assertFalse(result.isValid)
        assertTrue(result.warnings.any { it.contains("comma instead of a decimal point") })
    }

    @Test
    fun unescapedSingleQuote_flagsWarning() {
        val source = "It is working"
        val translation = "It's working"
        val result = TranslationValidator.validate(source, translation)
        assertFalse(result.isValid)
        assertTrue(result.warnings.any { it.contains("Unescaped single quote") })
    }

    @Test
    fun escapedSingleQuote_passes() {
        val source = "It is working"
        val translation = "It\\'s working"
        val result = TranslationValidator.validate(source, translation)
        assertTrue(result.isValid)
    }

    @Test
    fun doubleQuotedSingleQuote_passes() {
        val source = "It is working"
        val translation = "\"It's working\""
        val result = TranslationValidator.validate(source, translation)
        assertTrue(result.isValid)
    }

    @Test
    fun unescapedPercentInFormattedString_flagsWarning() {
        val source = "Battery %1$s (%2$d%%)"
        val translation = "Batería %1$s (%2$d%)"
        val result = TranslationValidator.validate(source, translation)
        assertFalse(result.isValid)
        assertTrue(result.warnings.any { it.contains("must be escaped as '%%'") })
    }

    @Test
    fun extractPlaceholders_extractsDistinctTokens() {
        val source = "Battery at %1$d%% with %2$s remaining (%1$d)"
        val placeholders = TranslationValidator.extractPlaceholders(source)
        assertEquals(listOf("%1$d", "%2$s"), placeholders)
    }
}
