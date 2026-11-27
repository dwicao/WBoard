package com.wboard.ime.ime

import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wboard.ime.R
import com.wboard.ime.prediction.IndonesianPredictionEngine
import com.wboard.ime.prediction.IndonesianPredictionEngine.Suggestion
import com.wboard.ime.prediction.UserLexiconStore
import com.wboard.ime.prefs.WBoardPrefs
import com.wboard.ime.privacy.PrivacyGuard
import com.wboard.ime.ui.KeyboardTheme
import com.wboard.ime.ui.MainActivity
import com.wboard.ime.ui.SettingsActivity
import com.wboard.ime.voice.IndonesianVoiceController
import com.wboard.ime.voice.VoiceInputActivity
import com.wboard.ime.voice.VoiceResultBridge
import java.util.concurrent.Executors

class WBoardImeService : InputMethodService(),
    WBoardKeyboardView.Listener,
    SuggestionStripView.Listener,
    IndonesianVoiceController.Listener {

    private lateinit var prefs: WBoardPrefs
    private lateinit var userLexiconStore: UserLexiconStore
    private lateinit var predictionEngine: IndonesianPredictionEngine
    private lateinit var voiceController: IndonesianVoiceController

    private var rootContainer: LinearLayout? = null
    private var suggestionStrip: SuggestionStripView? = null
    private var keyboardView: WBoardKeyboardView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()

    private var currentTheme: KeyboardTheme = KeyboardTheme.LIGHT
    private var fieldPolicy = PrivacyGuard.evaluate(null)
    private var currentMode: KeyboardMode = KeyboardMode.ALPHA
    private var shiftState: ShiftState = ShiftState.OFF

    private val composingWord = StringBuilder()
    private var previousWord: String? = null
    private var lastAutoCorrected: Pair<String, String>? = null
    private var lastAutoCommitCandidate: String? = null
    private var lastShiftTapTime: Long = 0L
    private var lastSpaceTapTime: Long = 0L
    private var voiceIsOnDevice: Boolean = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mainHandler.post { applyPreferencesAndTheme() }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = WBoardPrefs(this)
        prefs.registerListener(prefListener)
        userLexiconStore = UserLexiconStore(this)
        predictionEngine = IndonesianPredictionEngine()
        voiceController = IndonesianVoiceController(this, this)

        bgExecutor.execute {
            runCatching {
                assets.open("dict/id_unigrams.tsv").use { unigrams ->
                    assets.open("dict/id_bigrams.tsv").use { bigrams ->
                        assets.open("dict/id_shorthands.tsv").use { shorthands ->
                            predictionEngine.loadFromStreams(unigrams, bigrams, shorthands)
                        }
                    }
                }
                if (prefs.learnWordsLocally) {
                    predictionEngine.setUserWords(userLexiconStore.load())
                }
            }
            mainHandler.post { refreshSuggestions() }
        }
    }

    override fun onCreateInputView(): View {
        val strip = SuggestionStripView(this).apply {
            listener = this@WBoardImeService
        }
        val kb = WBoardKeyboardView(this).apply {
            listener = this@WBoardImeService
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                strip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                kb,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, 0, 0, navBar.bottom)
            insets
        }

        suggestionStrip = strip
        keyboardView = kb
        rootContainer = container
        applyPreferencesAndTheme()
        return container
    }

    override fun onStartInputView(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(attribute, restarting)
        fieldPolicy = PrivacyGuard.evaluate(attribute)
        composingWord.setLength(0)
        lastAutoCorrected = null
        lastAutoCommitCandidate = null

        currentMode = if (fieldPolicy.preferNumericLayout) {
            KeyboardMode.NUMERIC
        } else {
            KeyboardMode.ALPHA
        }

        VoiceResultBridge.consumePending()?.let { voiceText ->
            if (!fieldPolicy.isPasswordOrSensitive) {
                val needsSpace = !voiceText.endsWith("\n") && !voiceText.endsWith(" ")
                currentInputConnection?.commitText(if (needsSpace) "$voiceText " else voiceText, 1)
            }
        }

        extractPreviousWordFromCursor()
        updateAutoCapitalization()
        applyPreferencesAndTheme()
        refreshSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (voiceController.isActive()) {
            voiceController.cancel()
        }
        commitComposingRaw()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        if (composingWord.isNotEmpty() && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            composingWord.setLength(0)
            lastAutoCommitCandidate = null
            currentInputConnection?.finishComposingText()
            extractPreviousWordFromCursor()
            refreshSuggestions()
        } else if (composingWord.isEmpty() && !voiceController.isActive()) {
            extractPreviousWordFromCursor()
            updateAutoCapitalization()
            refreshKeyboardView()
            refreshSuggestions()
        }
    }

    override fun onDestroy() {
        prefs.unregisterListener(prefListener)
        voiceController.destroy()
        bgExecutor.shutdownNow()
        super.onDestroy()
    }

    // --- WBoardKeyboardView.Listener ---

    override fun onKeyInput(code: Int, text: String?) {
        val ic = currentInputConnection ?: return
        if (voiceController.isActive()) {
            voiceController.cancel()
        }

        when (code) {
            KeyCode.SHIFT -> handleShiftTap()
            KeyCode.BACKSPACE -> handleBackspace(ic)
            KeyCode.SPACE -> handleSpace(ic)
            KeyCode.ENTER -> handleEnter(ic)
            KeyCode.MODE_SYMBOLS -> {
                commitComposingRaw()
                currentMode = KeyboardMode.SYMBOLS
                refreshKeyboardView()
            }
            KeyCode.MODE_MORE_SYMBOLS -> {
                commitComposingRaw()
                currentMode = KeyboardMode.MORE_SYMBOLS
                refreshKeyboardView()
            }
            KeyCode.MODE_ALPHA -> {
                currentMode = KeyboardMode.ALPHA
                updateAutoCapitalization()
                refreshKeyboardView()
            }
            else -> {
                val str = text ?: return
                handleTextInput(ic, str)
            }
        }
    }

    override fun onDeleteWord() {
        val ic = currentInputConnection ?: return
        lastAutoCorrected = null
        if (composingWord.isNotEmpty()) {
            composingWord.setLength(0)
            ic.setComposingText("", 1)
            refreshSuggestions()
            return
        }
        val before = ic.getTextBeforeCursor(40, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        var idx = before.length
        while (idx > 0 && before[idx - 1].isWhitespace()) idx--
        while (idx > 0 && !before[idx - 1].isWhitespace()) idx--
        val deleteCount = before.length - idx
        if (deleteCount > 0) {
            ic.deleteSurroundingText(deleteCount, 0)
        }
        extractPreviousWordFromCursor()
        refreshSuggestions()
    }

    override fun onMoveCursor(deltaChars: Int) {
        val ic = currentInputConnection ?: return
        commitComposingRaw()
        val keyCode = if (deltaChars < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val steps = kotlin.math.abs(deltaChars).coerceAtMost(8)
        for (i in 0 until steps) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    // --- SuggestionStripView.Listener ---

    override fun onSuggestionClicked(suggestion: Suggestion) {
        val ic = currentInputConnection ?: return
        if (composingWord.isNotEmpty()) {
            val original = composingWord.toString()
            ic.commitText("${suggestion.text} ", 1)
            if (suggestion.text != original) {
                lastAutoCorrected = original to suggestion.text
            } else {
                lastAutoCorrected = null
            }
            composingWord.setLength(0)
            lastAutoCommitCandidate = null
        } else {
            ic.commitText("${suggestion.text} ", 1)
            lastAutoCorrected = null
        }
        previousWord = suggestion.text.lowercase()
        maybeLearnWord(suggestion.text)
        updateAutoCapitalization()
        refreshKeyboardView()
        refreshSuggestions()
    }

    override fun onVoiceClicked() {
        if (!fieldPolicy.allowVoiceInput || !prefs.voiceInputEnabled) return
        if (voiceController.isActive()) {
            voiceController.stopListening()
            return
        }
        if (!IndonesianVoiceController.hasAudioPermission(this)) {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_REQUEST_MIC, true)
            }
            startActivity(intent)
            return
        }
        commitComposingRaw()
        val before = currentInputConnection?.getTextBeforeCursor(16, 0)?.toString().orEmpty()
        val capFirst = before.isBlank() || isSentenceBoundaryChar(before.trimEnd().lastOrNull())
        voiceController.startListening(
            strictOffline = prefs.voiceStrictOffline,
            applyPunctuation = prefs.voicePunctuationEnabled,
            capitalizeFirst = capFirst
        )
    }

    override fun onVoiceCancelClicked() {
        voiceController.cancel()
        currentInputConnection?.setComposingText("", 1)
        currentInputConnection?.finishComposingText()
    }

    override fun onSettingsClicked() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // --- IndonesianVoiceController.Listener ---

    override fun onListeningStarted(onDevice: Boolean) {
        voiceIsOnDevice = onDevice
        suggestionStrip?.setVoiceState(
            active = true,
            onDevice = onDevice,
            partialText = "",
            statusText = ""
        )
    }

    override fun onRmsChanged(level: Float) {
        suggestionStrip?.updateVoiceRms(level)
    }

    override fun onPartialResult(text: String) {
        currentInputConnection?.setComposingText(text, 1)
        suggestionStrip?.setVoiceState(
            active = true,
            onDevice = voiceIsOnDevice,
            partialText = text
        )
    }

    override fun onFinalResult(text: String) {
        val ic = currentInputConnection
        if (ic != null) {
            val needsSpace = !text.endsWith("\n") && !text.endsWith(" ")
            val toCommit = if (needsSpace) "$text " else text
            ic.commitText(toCommit, 1)
        }
        suggestionStrip?.setVoiceState(active = false)
        extractPreviousWordFromCursor()
        updateAutoCapitalization()
        refreshKeyboardView()
        refreshSuggestions()
    }

    override fun onVoiceError(reason: String) {
        currentInputConnection?.setComposingText("", 1)
        currentInputConnection?.finishComposingText()

        if (reason == "fallback_activity" && IndonesianVoiceController.isActivityAvailable(this)) {
            suggestionStrip?.setVoiceState(active = false)
            val before = currentInputConnection?.getTextBeforeCursor(16, 0)?.toString().orEmpty()
            val capFirst = before.isBlank() || isSentenceBoundaryChar(before.trimEnd().lastOrNull())
            val intent = Intent(this, VoiceInputActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(VoiceInputActivity.EXTRA_PUNCTUATION, prefs.voicePunctuationEnabled)
                putExtra(VoiceInputActivity.EXTRA_CAPITALIZE, capFirst)
                putExtra(VoiceInputActivity.EXTRA_PREFER_OFFLINE, prefs.voiceStrictOffline)
            }
            startActivity(intent)
            return
        }

        val message = when (reason) {
            "no_permission" -> getString(R.string.ime_voice_no_permission)
            "offline_unavailable" -> getString(R.string.ime_voice_unavailable)
            "no_match", "timeout" -> ""
            else -> getString(R.string.ime_voice_error, reason)
        }
        suggestionStrip?.setVoiceState(active = false, statusText = message)
        if (message.isNotEmpty()) {
            mainHandler.postDelayed({
                if (!voiceController.isActive()) {
                    suggestionStrip?.setVoiceState(active = false, statusText = "")
                    refreshSuggestions()
                }
            }, 2600L)
        } else {
            refreshSuggestions()
        }
    }

    override fun onVoiceStopped() {
        suggestionStrip?.setVoiceState(active = false)
        refreshSuggestions()
    }

    // --- Input Handling Helpers ---

    private fun handleTextInput(ic: InputConnection, str: String) {
        lastAutoCorrected = null
        val ch = str[0]
        val isWordChar = ch.isLetter() && fieldPolicy.allowPredictions && prefs.predictionsEnabled

        if (isWordChar) {
            composingWord.append(str)
            ic.setComposingText(composingWord.toString(), 1)
            if (shiftState == ShiftState.ONCE) {
                shiftState = ShiftState.OFF
                refreshKeyboardView()
            }
            refreshSuggestions()
        } else {
            commitComposingRaw()
            ic.commitText(str, 1)
            if (ch == '.' || ch == '?' || ch == '!') {
                previousWord = null
            }
            if (shiftState == ShiftState.ONCE && !ch.isWhitespace()) {
                shiftState = ShiftState.OFF
            } else {
                updateAutoCapitalization()
            }
            refreshKeyboardView()
            refreshSuggestions()
        }
    }

    private fun handleSpace(ic: InputConnection) {
        val now = System.currentTimeMillis()
        if (composingWord.isNotEmpty()) {
            val typed = composingWord.toString()
            val chosen = if (fieldPolicy.allowAutocorrect &&
                prefs.autocorrectEnabled &&
                lastAutoCommitCandidate != null
            ) {
                lastAutoCommitCandidate!!
            } else {
                typed
            }
            ic.commitText("$chosen ", 1)
            lastAutoCorrected = if (chosen != typed) typed to chosen else null
            composingWord.setLength(0)
            lastAutoCommitCandidate = null
            previousWord = chosen.lowercase()
            maybeLearnWord(chosen)
            lastSpaceTapTime = now
            updateAutoCapitalization()
            refreshKeyboardView()
            refreshSuggestions()
            return
        }

        // Double-space period shortcut
        if (prefs.doubleSpacePeriodEnabled &&
            !fieldPolicy.isPasswordOrSensitive &&
            now - lastSpaceTapTime <= DOUBLE_TAP_WINDOW_MS
        ) {
            val before = ic.getTextBeforeCursor(3, 0)?.toString().orEmpty()
            if (before.length >= 2 && before.endsWith(" ") && before[before.length - 2].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceTapTime = 0L
                previousWord = null
                updateAutoCapitalization()
                refreshKeyboardView()
                refreshSuggestions()
                return
            }
        }

        lastSpaceTapTime = now
        lastAutoCorrected = null
        ic.commitText(" ", 1)
        extractPreviousWordFromCursor()
        updateAutoCapitalization()
        refreshKeyboardView()
        refreshSuggestions()
    }

    private fun handleBackspace(ic: InputConnection) {
        // Immediate undo of auto-correction
        val undo = lastAutoCorrected
        if (undo != null && composingWord.isEmpty()) {
            val expectedSuffix = "${undo.second} "
            val before = ic.getTextBeforeCursor(expectedSuffix.length, 0)?.toString().orEmpty()
            if (before == expectedSuffix) {
                ic.deleteSurroundingText(expectedSuffix.length, 0)
                composingWord.append(undo.first)
                ic.setComposingText(undo.first, 1)
                lastAutoCorrected = null
                refreshSuggestions()
                return
            }
        }
        lastAutoCorrected = null

        if (composingWord.isNotEmpty()) {
            composingWord.deleteCharAt(composingWord.length - 1)
            ic.setComposingText(composingWord.toString(), 1)
            if (composingWord.isEmpty()) {
                ic.finishComposingText()
                extractPreviousWordFromCursor()
            }
            refreshSuggestions()
            return
        }

        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        extractPreviousWordFromCursor()
        updateAutoCapitalization()
        refreshKeyboardView()
        refreshSuggestions()
    }

    private fun handleEnter(ic: InputConnection) {
        commitComposingRaw()
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val isMultiLine = ((info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val noEnterAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

        if (!isMultiLine && !noEnterAction && action in HANDLED_IME_ACTIONS) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
            previousWord = null
            updateAutoCapitalization()
            refreshKeyboardView()
        }
        refreshSuggestions()
    }

    private fun handleShiftTap() {
        val now = System.currentTimeMillis()
        shiftState = when (shiftState) {
            ShiftState.OFF -> ShiftState.ONCE
            ShiftState.ONCE -> {
                if (now - lastShiftTapTime <= DOUBLE_TAP_WINDOW_MS) {
                    ShiftState.CAPS_LOCK
                } else {
                    ShiftState.OFF
                }
            }
            ShiftState.CAPS_LOCK -> ShiftState.OFF
        }
        lastShiftTapTime = now
        refreshKeyboardView()
    }

    private fun commitComposingRaw() {
        if (composingWord.isNotEmpty()) {
            val word = composingWord.toString()
            currentInputConnection?.commitText(word, 1)
            previousWord = word.lowercase()
            composingWord.setLength(0)
            lastAutoCommitCandidate = null
        }
    }

    private fun extractPreviousWordFromCursor() {
        val before = currentInputConnection?.getTextBeforeCursor(40, 0)?.toString().orEmpty()
        val trimmed = before.trimEnd()
        if (trimmed.isEmpty() || isSentenceBoundaryChar(before.lastOrNull(), includeNewline = true)) {
            previousWord = null
            return
        }
        val lastToken = trimmed.substringAfterLast(' ').substringAfterLast('\n')
        previousWord = lastToken.takeIf { token -> token.any { it.isLetter() } }?.lowercase()
    }

    private fun updateAutoCapitalization() {
        if (shiftState == ShiftState.CAPS_LOCK) return
        if (!prefs.autoCapitalizeEnabled || currentMode != KeyboardMode.ALPHA || fieldPolicy.isPasswordOrSensitive) {
            shiftState = ShiftState.OFF
            return
        }
        val ic = currentInputConnection
        val capsMode = ic?.getCursorCapsMode(
            currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT
        ) ?: 0
        val before = ic?.getTextBeforeCursor(24, 0)?.toString().orEmpty()
        val atSentenceBoundary = before.isEmpty() ||
            isSentenceBoundaryChar(before.trimEnd().lastOrNull(), includeNewline = true)
        shiftState = if (capsMode != 0 || atSentenceBoundary) ShiftState.ONCE else ShiftState.OFF
    }

    private fun isSentenceBoundaryChar(ch: Char?, includeNewline: Boolean = false): Boolean =
        when (ch) {
            '.', '?', '!' -> true
            '\n' -> includeNewline
            else -> false
        }

    private fun maybeLearnWord(word: String) {
        if (!prefs.learnWordsLocally || !fieldPolicy.allowLearning) return
        val clean = word.trim()
        if (clean.length < 2) return
        predictionEngine.recordUserWord(clean)
        bgExecutor.execute { userLexiconStore.recordWord(clean) }
    }

    private fun refreshSuggestions() {
        val strip = suggestionStrip ?: return
        if (voiceController.isActive()) return
        if (fieldPolicy.isPasswordOrSensitive || !fieldPolicy.allowPredictions || !prefs.predictionsEnabled) {
            strip.setSuggestions(emptyList())
            lastAutoCommitCandidate = null
            return
        }
        if (!predictionEngine.isLoaded) return

        val res = predictionEngine.suggest(
            composing = composingWord.toString(),
            previousWord = previousWord,
            maxResults = 3,
            enableShorthand = prefs.shorthandExpansionEnabled,
            enableAutocorrect = prefs.autocorrectEnabled && fieldPolicy.allowAutocorrect
        )
        lastAutoCommitCandidate = res.autoCommitCandidate
        strip.setSuggestions(res.suggestions)
    }

    private fun applyPreferencesAndTheme() {
        currentTheme = KeyboardTheme.resolve(this, prefs.themeMode)
        rootContainer?.setBackgroundColor(currentTheme.background)
        suggestionStrip?.apply {
            applyTheme(currentTheme)
            setPolicyState(
                passwordMode = fieldPolicy.isPasswordOrSensitive,
                allowMic = prefs.voiceInputEnabled && fieldPolicy.allowVoiceInput
            )
        }
        refreshKeyboardView()
    }

    private fun refreshKeyboardView() {
        keyboardView?.apply {
            applyTheme(currentTheme)
            configure(
                mode = currentMode,
                shiftState = shiftState,
                showNumberRow = prefs.showNumberRow,
                heightScale = prefs.heightPreset.scale,
                keyPopupEnabled = prefs.keyPopupEnabled,
                hapticsEnabled = prefs.hapticFeedbackEnabled,
                enterLabel = resolveEnterLabel(currentInputEditorInfo)
            )
        }
    }

    private fun resolveEnterLabel(info: EditorInfo?): String {
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        return when (action) {
            EditorInfo.IME_ACTION_SEARCH -> "Cari"
            EditorInfo.IME_ACTION_SEND -> "Kirim"
            EditorInfo.IME_ACTION_NEXT -> "Lanjut"
            EditorInfo.IME_ACTION_DONE -> "Selesai"
            EditorInfo.IME_ACTION_GO -> "Buka"
            else -> "↵"
        }
    }

    companion object {
        private const val DOUBLE_TAP_WINDOW_MS = 380L
        private val HANDLED_IME_ACTIONS = setOf(
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE
        )
    }
}
