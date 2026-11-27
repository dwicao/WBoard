package com.wboard.ime.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wboard.ime.R
import com.wboard.ime.prediction.UserLexiconStore
import com.wboard.ime.prefs.WBoardPrefs

class SettingsActivity : Activity() {

    private lateinit var prefs: WBoardPrefs
    private lateinit var userLexiconStore: UserLexiconStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = WBoardPrefs(this)
        userLexiconStore = UserLexiconStore(this)

        val root = findViewById<View>(R.id.settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (18 * resources.displayMetrics.density).toInt()
            v.setPadding(pad, pad + bars.top, pad, pad + bars.bottom)
            insets
        }

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        setupSpinners()
        setupSwitches()

        findViewById<Button>(R.id.btn_clear_learned).setOnClickListener {
            val cleared = userLexiconStore.clear()
            Toast.makeText(
                this,
                getString(R.string.toast_learned_cleared, cleared),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupSpinners() {
        val themeSpinner = findViewById<Spinner>(R.id.spinner_theme)
        val themes = WBoardPrefs.ThemeMode.entries
        themeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            themes.map { it.label }
        )
        themeSpinner.setSelection(themes.indexOf(prefs.themeMode).coerceAtLeast(0))
        themeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                prefs.themeMode = themes[pos]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val heightSpinner = findViewById<Spinner>(R.id.spinner_height)
        val heights = WBoardPrefs.HeightPreset.entries
        heightSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            heights.map { it.label }
        )
        heightSpinner.setSelection(heights.indexOf(prefs.heightPreset).coerceAtLeast(0))
        heightSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                prefs.heightPreset = heights[pos]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupSwitches() {
        bindSwitch(R.id.switch_number_row, prefs.showNumberRow) { prefs.showNumberRow = it }
        bindSwitch(R.id.switch_key_popup, prefs.keyPopupEnabled) { prefs.keyPopupEnabled = it }
        bindSwitch(R.id.switch_haptics, prefs.hapticFeedbackEnabled) { prefs.hapticFeedbackEnabled = it }

        bindSwitch(R.id.switch_predictions, prefs.predictionsEnabled) { prefs.predictionsEnabled = it }
        bindSwitch(R.id.switch_autocorrect, prefs.autocorrectEnabled) { prefs.autocorrectEnabled = it }
        bindSwitch(R.id.switch_shorthand, prefs.shorthandExpansionEnabled) { prefs.shorthandExpansionEnabled = it }
        bindSwitch(R.id.switch_auto_caps, prefs.autoCapitalizeEnabled) { prefs.autoCapitalizeEnabled = it }
        bindSwitch(R.id.switch_double_space, prefs.doubleSpacePeriodEnabled) { prefs.doubleSpacePeriodEnabled = it }
        bindSwitch(R.id.switch_learn_words, prefs.learnWordsLocally) { prefs.learnWordsLocally = it }

        bindSwitch(R.id.switch_voice_enabled, prefs.voiceInputEnabled) { prefs.voiceInputEnabled = it }
        bindSwitch(R.id.switch_voice_offline, prefs.voiceStrictOffline) { prefs.voiceStrictOffline = it }
        bindSwitch(R.id.switch_voice_punct, prefs.voicePunctuationEnabled) { prefs.voicePunctuationEnabled = it }

        bindSwitch(R.id.switch_clipboard_enabled, prefs.clipboardEnabled) { prefs.clipboardEnabled = it }
        bindSwitch(R.id.switch_clipboard_history, prefs.clipboardHistoryEnabled) { prefs.clipboardHistoryEnabled = it }
    }

    private fun bindSwitch(id: Int, initial: Boolean, onChanged: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        }
    }
}
