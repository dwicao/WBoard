package com.wboard.ime.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.wboard.ime.prefs.WBoardPrefs

data class KeyboardTheme(
    val isDark: Boolean,
    val background: Int,
    val stripBackground: Int,
    val keyBg: Int,
    val keyPressedBg: Int,
    val specialKeyBg: Int,
    val accentBg: Int,
    val accentText: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val chipHighlightBg: Int,
    val divider: Int,
    val popupBg: Int,
    val voiceActiveBg: Int
) {
    companion object {
        val LIGHT = KeyboardTheme(
            isDark = false,
            background = Color.parseColor("#ECEFF4"),
            stripBackground = Color.parseColor("#E3E8F0"),
            keyBg = Color.parseColor("#FFFFFF"),
            keyPressedBg = Color.parseColor("#D8E0EC"),
            specialKeyBg = Color.parseColor("#D8DEE9"),
            accentBg = Color.parseColor("#0F766E"),
            accentText = Color.parseColor("#FFFFFF"),
            textPrimary = Color.parseColor("#111827"),
            textSecondary = Color.parseColor("#5B6472"),
            chipHighlightBg = Color.parseColor("#CCFBF1"),
            divider = Color.parseColor("#CBD5E1"),
            popupBg = Color.parseColor("#FFFFFF"),
            voiceActiveBg = Color.parseColor("#0F766E")
        )

        val DARK = KeyboardTheme(
            isDark = true,
            background = Color.parseColor("#14171D"),
            stripBackground = Color.parseColor("#1B1F27"),
            keyBg = Color.parseColor("#262B36"),
            keyPressedBg = Color.parseColor("#373E4D"),
            specialKeyBg = Color.parseColor("#1E232C"),
            accentBg = Color.parseColor("#14B8A6"),
            accentText = Color.parseColor("#042F2E"),
            textPrimary = Color.parseColor("#F3F4F6"),
            textSecondary = Color.parseColor("#9CA3AF"),
            chipHighlightBg = Color.parseColor("#134E4A"),
            divider = Color.parseColor("#2C3340"),
            popupBg = Color.parseColor("#323846"),
            voiceActiveBg = Color.parseColor("#14B8A6")
        )

        val AMOLED = KeyboardTheme(
            isDark = true,
            background = Color.parseColor("#000000"),
            stripBackground = Color.parseColor("#0A0A0A"),
            keyBg = Color.parseColor("#16181D"),
            keyPressedBg = Color.parseColor("#2A2E38"),
            specialKeyBg = Color.parseColor("#0F1115"),
            accentBg = Color.parseColor("#2DD4BF"),
            accentText = Color.parseColor("#042F2E"),
            textPrimary = Color.parseColor("#F9FAFB"),
            textSecondary = Color.parseColor("#9CA3AF"),
            chipHighlightBg = Color.parseColor("#0F3D39"),
            divider = Color.parseColor("#1F242D"),
            popupBg = Color.parseColor("#222630"),
            voiceActiveBg = Color.parseColor("#2DD4BF")
        )

        fun resolve(context: Context, mode: WBoardPrefs.ThemeMode): KeyboardTheme {
            return when (mode) {
                WBoardPrefs.ThemeMode.LIGHT -> LIGHT
                WBoardPrefs.ThemeMode.DARK -> DARK
                WBoardPrefs.ThemeMode.AMOLED -> AMOLED
                WBoardPrefs.ThemeMode.AUTO -> {
                    val nightMask = context.resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK
                    if (nightMask == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
                }
            }
        }
    }
}
