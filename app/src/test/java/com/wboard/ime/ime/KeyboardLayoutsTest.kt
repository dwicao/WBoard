package com.wboard.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardLayoutsTest {

    @Test
    fun topRowLongPressSwitchesBetweenNumbersAndSymbolsBasedOnNumberRowPref() {
        val withoutNumberRow = KeyboardLayouts.rowsFor(KeyboardMode.ALPHA, showNumberRow = false)
        assertEquals(4, withoutNumberRow.size)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), withoutNumberRow[0].map { it.hint })

        val withNumberRow = KeyboardLayouts.rowsFor(KeyboardMode.ALPHA, showNumberRow = true)
        assertEquals(5, withNumberRow.size)
        assertEquals(listOf("%", "^", "~", "=", "[", "]", "<", ">", "{", "}"), withNumberRow[1].map { it.hint })
    }

    @Test
    fun bottomRowKeysHaveExpectedHoldActions() {
        val rows = KeyboardLayouts.rowsFor(KeyboardMode.ALPHA, showNumberRow = false)
        val bottomRow = rows.last()
        val symKey = bottomRow.first { it.code == KeyCode.MODE_SYMBOLS }
        val commaKey = bottomRow.first { it.code == ','.code }
        val spaceKey = bottomRow.first { it.code == KeyCode.SPACE }

        assertEquals(HoldAction.CLIPBOARD, symKey.holdAction)
        assertEquals(HoldAction.SETTINGS, commaKey.holdAction)
        assertEquals(HoldAction.VOICE, spaceKey.holdAction)
    }

    @Test
    fun symbolAndAlphaLayoutsMaintainMatchingRowCounts() {
        assertEquals(
            KeyboardLayouts.rowsFor(KeyboardMode.ALPHA, showNumberRow = false).size,
            KeyboardLayouts.rowsFor(KeyboardMode.SYMBOLS, showNumberRow = false).size
        )
        assertEquals(
            KeyboardLayouts.rowsFor(KeyboardMode.ALPHA, showNumberRow = true).size,
            KeyboardLayouts.rowsFor(KeyboardMode.SYMBOLS, showNumberRow = true).size
        )
    }
}
