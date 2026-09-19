/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Translation
 * File: TranslationValidator.kt
 * Description: Real-time validator for string placeholders, Android XML escaping, and specifier safety.
 */

package com.sameerasw.essentials.translation

import java.util.regex.Pattern

data class ValidationResult(
    val isValid: Boolean,
    val warnings: List<String>,
    val missingPlaceholders: List<String>,
    val availablePlaceholders: List<String>,
    val hasCrashRisk: Boolean = false,
)

object TranslationValidator {
    // Positional or standard format specifier tokens e.g. %1$s, %2$d, %s, %d, %1$.1f (excluding %%)
    private val FORMAT_TOKEN_REGEX = Pattern.compile("%(?:(\\d+)\\$)?[-+ #0(]*\\d*(?:\\.\\d+)?[a-zA-Z]")

    // Malformed positional or standard format tokens with non-ASCII characters (e.g. Cyrillic ф)
    private val NON_ASCII_SPEC_REGEX = Pattern.compile("%(?:(\\d+)\\$)?[-+ #0(]*\\d*(?:\\.\\d+)?([^\\u0000-\\u007F])")

    // Comma instead of dot in format specifier (e.g. %1,1f or %1$,1f)
    private val COMMA_SPEC_REGEX = Pattern.compile("%(\\d+)(?:\\$,|,)\\d*[a-zA-Z]")

    /**
     * Extracts distinct placeholder tokens from a source string (e.g., ["%1$s", "%2$d", "%s"]).
     */
    fun extractPlaceholders(sourceText: String): List<String> {
        val sanitized = sourceText.replace("%%", "")
        val matcher = FORMAT_TOKEN_REGEX.matcher(sanitized)
        val placeholders = mutableListOf<String>()
        while (matcher.find()) {
            val token = matcher.group(0)
            if (token != null && !placeholders.contains(token)) {
                placeholders.add(token)
            }
        }
        return placeholders
    }

    /**
     * Extracts positional indices (e.g. ["1", "2"]) present in a string.
     */
    fun extractPositionalIndices(text: String): Set<String> {
        val matcher = Pattern.compile("%(\\d+)\\$").matcher(text)
        val indices = mutableSetOf<String>()
        while (matcher.find()) {
            matcher.group(1)?.let { indices.add(it) }
        }
        return indices
    }

    /**
     * Validates translated text against the source text.
     */
    fun validate(
        sourceText: String,
        translatedText: String,
    ): ValidationResult {
        val warnings = mutableListOf<String>()
        var hasCrashRisk = false

        val availablePlaceholders = extractPlaceholders(sourceText)
        val transIndices = extractPositionalIndices(translatedText)
        val sourceIndices = extractPositionalIndices(sourceText)

        // 1. Missing placeholders (positional e.g. %1$s or standard e.g. %s)
        val missing = mutableListOf<String>()
        for (placeholder in availablePlaceholders) {
            val matcher = Pattern.compile("%(\\d+)\\$").matcher(placeholder)
            if (matcher.find()) {
                val index = matcher.group(1)
                if (index != null && !transIndices.contains(index)) {
                    missing.add(placeholder)
                }
            } else {
                val type = placeholder.last()
                val hasMatchingSpecifier = Pattern.compile("%(?:\\d+\\$)?[^%]*$type").matcher(translatedText).find()
                if (!hasMatchingSpecifier) {
                    missing.add(placeholder)
                }
            }
        }

        if (missing.isNotEmpty()) {
            warnings.add("Missing required placeholder(s): ${missing.joinToString(", ")}. Dynamic values will not display.")
        }

        // 2. Extra positional placeholders (CRASH RISK: MissingFormatArgumentException)
        val extraIndices = transIndices - sourceIndices
        if (extraIndices.isNotEmpty()) {
            hasCrashRisk = true
            warnings.add("Unexpected placeholder index ${extraIndices.map { "%$it$" }.joinToString(", ")} not in original text. This will crash the app with MissingFormatArgumentException!")
        }

        // 3. Malformed non-ASCII format specifiers (CRASH RISK)
        val nonAsciiMatcher = NON_ASCII_SPEC_REGEX.matcher(translatedText)
        while (nonAsciiMatcher.find()) {
            val matchStr = nonAsciiMatcher.group(0) ?: ""
            val badChar = nonAsciiMatcher.group(2) ?: ""
            hasCrashRisk = true
            warnings.add("Malformed token '$matchStr' contains invalid non-ASCII character '$badChar'. This will crash the app at runtime!")
        }

        // 3. Comma delimiter in format specifier (e.g. %1,1f or %1$,1f)
        val commaMatcher = COMMA_SPEC_REGEX.matcher(translatedText)
        while (commaMatcher.find()) {
            val matchStr = commaMatcher.group(0) ?: ""
            val suggested =
                if (matchStr.contains("$,")) {
                    matchStr.replace("$,", "$.")
                } else {
                    matchStr.replace(",", "$.")
                }
            warnings.add("Format token '$matchStr' uses a comma instead of a decimal point. Use '$suggested'.")
        }

        // 4. Unescaped single quotes
        val stripped = translatedText.trim()
        val isDoubleQuoted = stripped.length >= 2 && stripped.startsWith('"') && stripped.endsWith('"')
        if (!isDoubleQuoted) {
            val unescapedQuoteMatcher = Pattern.compile("(?<!\\\\)'").matcher(translatedText)
            if (unescapedQuoteMatcher.find()) {
                warnings.add("Unescaped single quote (') found. Use \\' to prevent XML compilation errors.")
            }
        }

        // 5. Unescaped percent sign in formatted string
        if (availablePlaceholders.isNotEmpty()) {
            var temp = translatedText.replace("%%", "")
            temp = FORMAT_TOKEN_REGEX.matcher(temp).replaceAll("")
            if (temp.contains("%")) {
                warnings.add("Unescaped '%' found. In formatted strings, literal percent signs must be escaped as '%%'.")
            }
        }

        return ValidationResult(
            isValid = warnings.isEmpty(),
            warnings = warnings,
            missingPlaceholders = missing,
            availablePlaceholders = availablePlaceholders,
            hasCrashRisk = hasCrashRisk,
        )
    }
}
