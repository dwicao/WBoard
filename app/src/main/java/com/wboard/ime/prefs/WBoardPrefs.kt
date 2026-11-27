package com.wboard.ime.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Privacy-first local preferences stored strictly in private app SharedPreferences.
 * Cloud backup and device transfer are disabled in AndroidManifest / data_extraction_rules.
 */
class WBoardPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class ThemeMode(val key: String, val label: String) {
        AUTO("auto", "System Default"),
        LIGHT("light", "Light Minimal"),
        DARK("dark", "Dark Slate"),
        AMOLED("amoled", "AMOLED Black");

        companion object {
            fun fromKey(key: String?): ThemeMode =
                entries.firstOrNull { it.key == key } ?: AUTO
        }
    }

    enum class HeightPreset(val key: String, val label: String, val scale: Float) {
        COMPACT("compact", "Compact", 0.88f),
        NORMAL("normal", "Normal", 1.0f),
        TALL("tall", "Tall", 1.14f);

        companion object {
            fun fromKey(key: String?): HeightPreset =
                entries.firstOrNull { it.key == key } ?: NORMAL
        }
    }

    var themeMode: ThemeMode
        get() = ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, ThemeMode.AUTO.key))
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.key).apply()

    var heightPreset: HeightPreset
        get() = HeightPreset.fromKey(prefs.getString(KEY_HEIGHT_PRESET, HeightPreset.NORMAL.key))
        set(value) = prefs.edit().putString(KEY_HEIGHT_PRESET, value.key).apply()

    var showNumberRow: Boolean
        get() = prefs.getBoolean(KEY_NUMBER_ROW, false)
        set(value) = prefs.edit().putBoolean(KEY_NUMBER_ROW, value).apply()

    var keyPopupEnabled: Boolean
        get() = prefs.getBoolean(KEY_POPUP, true)
        set(value) = prefs.edit().putBoolean(KEY_POPUP, value).apply()

    var hapticFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    var predictionsEnabled: Boolean
        get() = prefs.getBoolean(KEY_PREDICTIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_PREDICTIONS, value).apply()

    var autocorrectEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOCORRECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOCORRECT, value).apply()

    var shorthandExpansionEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHORTHAND, true)
        set(value) = prefs.edit().putBoolean(KEY_SHORTHAND, value).apply()

    var autoCapitalizeEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAPS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CAPS, value).apply()

    var doubleSpacePeriodEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_SPACE_PERIOD, true)
        set(value) = prefs.edit().putBoolean(KEY_DOUBLE_SPACE_PERIOD, value).apply()

    /**
     * Disabled by default for maximum privacy. When enabled, words are still only
     * learned outside sensitive/incognito/password fields and kept in app-private storage.
     */
    var learnWordsLocally: Boolean
        get() = prefs.getBoolean(KEY_LEARN_WORDS, false)
        set(value) = prefs.edit().putBoolean(KEY_LEARN_WORDS, value).apply()

    var voiceInputEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_ENABLED, value).apply()

    /**
     * Enabled by default for privacy: enforces on-device recognition on Android 12+
     * and requests offline-only recognition on older Android versions.
     */
    var voiceStrictOffline: Boolean
        get() = prefs.getBoolean(KEY_VOICE_STRICT_OFFLINE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_STRICT_OFFLINE, value).apply()

    var voicePunctuationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_PUNCTUATION, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_PUNCTUATION, value).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val PREFS_NAME = "wboard_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_HEIGHT_PRESET = "height_preset"
        private const val KEY_NUMBER_ROW = "show_number_row"
        private const val KEY_POPUP = "key_popup"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_PREDICTIONS = "predictions"
        private const val KEY_AUTOCORRECT = "autocorrect"
        private const val KEY_SHORTHAND = "shorthand"
        private const val KEY_AUTO_CAPS = "auto_caps"
        private const val KEY_DOUBLE_SPACE_PERIOD = "double_space_period"
        private const val KEY_LEARN_WORDS = "learn_words"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_VOICE_STRICT_OFFLINE = "voice_strict_offline"
        private const val KEY_VOICE_PUNCTUATION = "voice_punctuation"
    }
}
