/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities - Pixel Search
 * File: MathEvaluator.kt
 * Description: Recursive descent mathematical expression evaluator supporting basic arithmetic, percentages, and exponentiation.
 */

package com.sameerasw.essentials.utils

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

object MathEvaluator {
    private val PERCENT_OF_REGEX =
        Regex("""^(\d+(?:\.\d+)?)\s*%\s*of\s*(\d+(?:\.\d+)?)$""", RegexOption.IGNORE_CASE)

    fun evaluate(expression: String): String? {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return null

        // Exclude inputs that are phone numbers or dates
        if (trimmed.startsWith('+') && !trimmed.drop(1).any { it in "+-*x×/÷^%" }) return null
        if (trimmed.matches(Regex("""^\d{2,4}-\d{1,4}-\d{1,4}$"""))) return null

        // Handle "X% of Y"
        val percentMatch = PERCENT_OF_REGEX.matchEntire(trimmed)
        if (percentMatch != null) {
            val (pStr, baseStr) = percentMatch.destructured
            val p = pStr.toDoubleOrNull() ?: return null
            val base = baseStr.toDoubleOrNull() ?: return null
            return formatResult((p / 100.0) * base)
        }

        // Must contain at least one math operator
        val hasOperator = trimmed.any { it in "+-*x×/÷^%" }
        if (!hasOperator) return null

        // Must contain at least one digit
        if (!trimmed.any { it.isDigit() }) return null

        val normalized =
            trimmed
                .replace('×', '*')
                .replace('x', '*')
                .replace('÷', '/')

        if (!normalized.all { it.isDigit() || it in ".+-*/%^() \t" }) return null

        return try {
            val result = Parser(normalized).parse()
            if (result.isNaN() || result.isInfinite()) null else formatResult(result)
        } catch (_: Exception) {
            null
        }
    }

    private fun formatResult(value: Double): String {
        return if (value == floor(value) && !value.isInfinite() && abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            val symbols = DecimalFormatSymbols(Locale.US)
            val df = DecimalFormat("0.######", symbols)
            df.format(value)
        }
    }

    private class Parser(private val input: String) {
        private var pos = -1
        private var ch = ' '

        private fun nextChar() {
            pos++
            ch = if (pos < input.length) input[pos] else '\u0000'
        }

        private fun eat(charToEat: Char): Boolean {
            while (ch == ' ' || ch == '\t') nextChar()
            if (ch == charToEat) {
                nextChar()
                return true
            }
            return false
        }

        fun parse(): Double {
            nextChar()
            val x = parseExpression()
            while (ch == ' ' || ch == '\t') nextChar()
            if (pos < input.length) throw IllegalArgumentException("Unexpected char: $ch")
            return x
        }

        private fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                when {
                    eat('+') -> x += parseTerm()
                    eat('-') -> x -= parseTerm()
                    else -> return x
                }
            }
        }

        private fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                when {
                    eat('*') -> x *= parseFactor()
                    eat('/') -> {
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw ArithmeticException("Division by zero")
                        x /= divisor
                    }
                    eat('%') -> {
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw ArithmeticException("Modulo by zero")
                        x %= divisor
                    }
                    else -> return x
                }
            }
        }

        private fun parseFactor(): Double {
            while (ch == ' ' || ch == '\t') nextChar()
            when {
                eat('+') -> return parseFactor()
                eat('-') -> return -parseFactor()
            }

            var x: Double
            val startPos = pos
            if (eat('(')) {
                x = parseExpression()
                if (!eat(')')) throw IllegalArgumentException("Missing ')'")
            } else if ((ch in '0'..'9') || ch == '.') {
                while ((ch in '0'..'9') || ch == '.') nextChar()
                val numStr = input.substring(startPos, pos)
                x = numStr.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number: $numStr")
            } else {
                throw IllegalArgumentException("Unexpected token: $ch")
            }

            if (eat('^')) x = x.pow(parseFactor())

            return x
        }
    }
}
