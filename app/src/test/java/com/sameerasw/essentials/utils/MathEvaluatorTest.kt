/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Unit Tests - Utilities
 * File: MathEvaluatorTest.kt
 * Description: Unit tests for MathEvaluator arithmetic expression parser.
 */

package com.sameerasw.essentials.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MathEvaluatorTest {
    @Test
    fun evaluate_basicArithmetic_returnsCorrectResult() {
        assertEquals("4", MathEvaluator.evaluate("2 + 2"))
        assertEquals("7", MathEvaluator.evaluate("10 - 3"))
        assertEquals("42", MathEvaluator.evaluate("6 * 7"))
        assertEquals("5", MathEvaluator.evaluate("15 / 3"))
        assertEquals("1", MathEvaluator.evaluate("10 % 3"))
    }

    @Test
    fun evaluate_operatorPrecedence_respectsMathRules() {
        assertEquals("14", MathEvaluator.evaluate("2 + 3 * 4"))
        assertEquals("20", MathEvaluator.evaluate("(2 + 3) * 4"))
        assertEquals("18", MathEvaluator.evaluate("20 - 4 / 2"))
        assertEquals("8", MathEvaluator.evaluate("(20 - 4) / 2"))
    }

    @Test
    fun evaluate_alternateMultiplicationAndDivisionSymbols_worksCorrectly() {
        assertEquals("30", MathEvaluator.evaluate("5 × 6"))
        assertEquals("30", MathEvaluator.evaluate("5 x 6"))
        assertEquals("3", MathEvaluator.evaluate("12 ÷ 4"))
    }

    @Test
    fun evaluate_exponentiation_returnsCorrectResult() {
        assertEquals("8", MathEvaluator.evaluate("2 ^ 3"))
        assertEquals("1024", MathEvaluator.evaluate("2 ^ 10"))
        assertEquals("10", MathEvaluator.evaluate("3 ^ 2 + 1"))
    }

    @Test
    fun evaluate_percentageOf_calculatesCorrectly() {
        assertEquals("30", MathEvaluator.evaluate("20% of 150"))
        assertEquals("30", MathEvaluator.evaluate("15% of 200"))
        assertEquals("40", MathEvaluator.evaluate("50 % of 80"))
        assertEquals("10", MathEvaluator.evaluate("12.5% of 80"))
    }

    @Test
    fun evaluate_decimalsAndNegativeNumbers_handledCorrectly() {
        assertEquals("6", MathEvaluator.evaluate("2.5 + 3.5"))
        assertEquals("2.5", MathEvaluator.evaluate("10 / 4"))
        assertEquals("3", MathEvaluator.evaluate("-5 + 8"))
        assertEquals("3", MathEvaluator.evaluate("8 + -5"))
    }

    @Test
    fun evaluate_divisionOrModuloByZero_returnsNull() {
        assertNull(MathEvaluator.evaluate("5 / 0"))
        assertNull(MathEvaluator.evaluate("5 % 0"))
    }

    @Test
    fun evaluate_nonMathInputs_returnsNull() {
        assertNull(MathEvaluator.evaluate(""))
        assertNull(MathEvaluator.evaluate("   "))
        assertNull(MathEvaluator.evaluate("hello world"))
        assertNull(MathEvaluator.evaluate("42")) // No operator
        assertNull(MathEvaluator.evaluate("+1234567890")) // Phone number
        assertNull(MathEvaluator.evaluate("2026-09-19")) // Date pattern
        assertNull(MathEvaluator.evaluate("2 +")) // Incomplete
        assertNull(MathEvaluator.evaluate("((2 + 3)")) // Mismatched parentheses
        assertNull(MathEvaluator.evaluate("2 + 3a")) // Invalid character
    }
}
