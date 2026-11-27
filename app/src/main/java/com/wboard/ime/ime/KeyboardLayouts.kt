package com.wboard.ime.ime

object KeyCode {
    const val SHIFT = -1
    const val BACKSPACE = -2
    const val SPACE = -3
    const val ENTER = -4
    const val MODE_SYMBOLS = -5
    const val MODE_ALPHA = -6
    const val MODE_MORE_SYMBOLS = -7
    const val VOICE = -8
}

enum class KeyboardMode {
    ALPHA,
    SYMBOLS,
    MORE_SYMBOLS,
    NUMERIC
}

enum class ShiftState {
    OFF,
    ONCE,
    CAPS_LOCK
}

data class KeySpec(
    val code: Int,
    val label: String,
    val weight: Float = 1.0f,
    val isSpecial: Boolean = false,
    val isAccent: Boolean = false,
    val hint: String? = null,
    val longPressOptions: List<String> = emptyList()
)

object KeyboardLayouts {

    private val NUMBER_ROW = listOf(
        key('1'), key('2'), key('3'), key('4'), key('5'),
        key('6'), key('7'), key('8'), key('9'), key('0')
    )

    private val ALPHA_ROWS = listOf(
        listOf(
            key('q', "1", listOf("1")),
            key('w', "2", listOf("2")),
            key('e', "3", listOf("3", "é", "è")),
            key('r', "4", listOf("4")),
            key('t', "5", listOf("5")),
            key('y', "6", listOf("6")),
            key('u', "7", listOf("7", "ü")),
            key('i', "8", listOf("8")),
            key('o', "9", listOf("9")),
            key('p', "0", listOf("0"))
        ),
        listOf(
            key('a', "@", listOf("@", "á")),
            key('s', "#", listOf("#")),
            key('d', "$", listOf("$")),
            key('f', "&", listOf("&")),
            key('g', "*", listOf("*")),
            key('h', "-", listOf("-", "_")),
            key('j', "+", listOf("+")),
            key('k', "(", listOf("(")),
            key('l', ")", listOf(")"))
        ),
        listOf(
            KeySpec(KeyCode.SHIFT, "⇧", weight = 1.45f, isSpecial = true),
            key('z', "!", listOf("!")),
            key('x', "\"", listOf("\"")),
            key('c', "'", listOf("'")),
            key('v', ":", listOf(":")),
            key('b', ";", listOf(";")),
            key('n', "/", listOf("/")),
            key('m', "?", listOf("?")),
            KeySpec(KeyCode.BACKSPACE, "⌫", weight = 1.45f, isSpecial = true)
        ),
        listOf(
            KeySpec(KeyCode.MODE_SYMBOLS, "?123", weight = 1.4f, isSpecial = true),
            key(',', longPress = listOf("!", "?")),
            KeySpec(KeyCode.SPACE, "Bahasa Indonesia", weight = 4.6f),
            key('.', hint = "…", longPress = listOf(",", "?", "!", ":", ";", "-")),
            KeySpec(KeyCode.ENTER, "↵", weight = 1.5f, isAccent = true)
        )
    )

    private val SYMBOL_ROWS = listOf(
        NUMBER_ROW,
        listOf(
            key('@'), key('#'), key('$'), key('_'), key('&'),
            key('-'), key('+'), key('('), key(')'), key('/')
        ),
        listOf(
            KeySpec(KeyCode.MODE_MORE_SYMBOLS, "=\\<", weight = 1.45f, isSpecial = true),
            key('*'), key('"'), key('\''), key(':'),
            key(';'), key('!'), key('?'),
            KeySpec(KeyCode.BACKSPACE, "⌫", weight = 1.45f, isSpecial = true)
        ),
        listOf(
            KeySpec(KeyCode.MODE_ALPHA, "ABC", weight = 1.4f, isSpecial = true),
            key(','),
            KeySpec(KeyCode.SPACE, "Spasi", weight = 4.6f),
            key('.'),
            KeySpec(KeyCode.ENTER, "↵", weight = 1.5f, isAccent = true)
        )
    )

    private val SYMBOL_ROWS_5 = listOf(
        NUMBER_ROW,
        listOf(
            key('@'), key('#'), key('$'), key('_'), key('&'),
            key('-'), key('+'), key('('), key(')'), key('/')
        ),
        listOf(
            key('~'), key('`'), key('|'), key('•'), key('√'),
            key('÷'), key('×'), key('{'), key('}'), key('\\')
        ),
        listOf(
            KeySpec(KeyCode.MODE_MORE_SYMBOLS, "=\\<", weight = 1.45f, isSpecial = true),
            key('*'), key('"'), key('\''), key(':'),
            key(';'), key('!'), key('?'),
            KeySpec(KeyCode.BACKSPACE, "⌫", weight = 1.45f, isSpecial = true)
        ),
        listOf(
            KeySpec(KeyCode.MODE_ALPHA, "ABC", weight = 1.4f, isSpecial = true),
            key(','),
            KeySpec(KeyCode.SPACE, "Spasi", weight = 4.6f),
            key('.'),
            KeySpec(KeyCode.ENTER, "↵", weight = 1.5f, isAccent = true)
        )
    )

    private val MORE_SYMBOL_ROWS = listOf(
        listOf(
            key('~'), key('`'), key('|'), key('•'), key('√'),
            key('π'), key('÷'), key('×'), key('§'), key('∆')
        ),
        listOf(
            key('£'), key('€'), key('¥'), key('^'), key('°'),
            key('='), key('{'), key('}'), key('\\'), key('%')
        ),
        listOf(
            KeySpec(KeyCode.MODE_SYMBOLS, "?123", weight = 1.45f, isSpecial = true),
            key('['), key(']'), key('<'), key('>'),
            key('©'), key('®'), key('™'),
            KeySpec(KeyCode.BACKSPACE, "⌫", weight = 1.45f, isSpecial = true)
        ),
        listOf(
            KeySpec(KeyCode.MODE_ALPHA, "ABC", weight = 1.4f, isSpecial = true),
            key('<'),
            KeySpec(KeyCode.SPACE, "Spasi", weight = 4.6f),
            key('>'),
            KeySpec(KeyCode.ENTER, "↵", weight = 1.5f, isAccent = true)
        )
    )

    private val MORE_SYMBOL_ROWS_5 = listOf(NUMBER_ROW) + MORE_SYMBOL_ROWS

    private val NUMERIC_ROWS = listOf(
        listOf(key('1'), key('2'), key('3'), key('-', isSpecial = true)),
        listOf(key('4'), key('5'), key('6'), key('+', isSpecial = true)),
        listOf(key('7'), key('8'), key('9'), KeySpec(KeyCode.BACKSPACE, "⌫", isSpecial = true)),
        listOf(key('*'), key('0'), key('.'), KeySpec(KeyCode.ENTER, "↵", isAccent = true))
    )

    fun rowsFor(mode: KeyboardMode, showNumberRow: Boolean): List<List<KeySpec>> {
        return when (mode) {
            KeyboardMode.ALPHA -> if (showNumberRow) listOf(NUMBER_ROW) + ALPHA_ROWS else ALPHA_ROWS
            KeyboardMode.SYMBOLS -> if (showNumberRow) SYMBOL_ROWS_5 else SYMBOL_ROWS
            KeyboardMode.MORE_SYMBOLS -> if (showNumberRow) MORE_SYMBOL_ROWS_5 else MORE_SYMBOL_ROWS
            KeyboardMode.NUMERIC -> NUMERIC_ROWS
        }
    }

    private fun key(
        char: Char,
        hint: String? = null,
        longPress: List<String> = emptyList(),
        isSpecial: Boolean = false
    ): KeySpec = KeySpec(
        code = char.code,
        label = char.toString(),
        isSpecial = isSpecial,
        hint = hint,
        longPressOptions = longPress
    )
}
