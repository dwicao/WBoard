package com.wboard.ime.privacy

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Evaluates Android EditorInfo flags to enforce strict privacy boundaries
 * (password shielding, incognito mode, and sensitive field protection).
 */
object PrivacyGuard {

    data class FieldPolicy(
        val isPasswordOrSensitive: Boolean,
        val allowPredictions: Boolean,
        val allowAutocorrect: Boolean,
        val allowLearning: Boolean,
        val allowVoiceInput: Boolean,
        val preferNumericLayout: Boolean
    )

    fun evaluate(editorInfo: EditorInfo?): FieldPolicy {
        if (editorInfo == null) {
            return FieldPolicy(
                isPasswordOrSensitive = false,
                allowPredictions = true,
                allowAutocorrect = true,
                allowLearning = false,
                allowVoiceInput = true,
                preferNumericLayout = false
            )
        }

        val inputType = editorInfo.inputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val flags = inputType and InputType.TYPE_MASK_FLAGS

        val isPassword = isPasswordVariation(inputClass, variation)
        val noPersonalizedLearning =
            (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        val privateOptions = editorInfo.privateImeOptions.orEmpty().lowercase()
        val hasIncognitoPrivateOption =
            privateOptions.contains("incognito") || privateOptions.contains("nopersonalizedlearning")

        val isNumeric = inputClass == InputType.TYPE_CLASS_NUMBER ||
            inputClass == InputType.TYPE_CLASS_PHONE ||
            inputClass == InputType.TYPE_CLASS_DATETIME

        val noSuggestionsFlag = (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val isStructuredField = inputClass == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_URI ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_FILTER
            )

        val allowPredictions = !isPassword && !isNumeric && !noSuggestionsFlag
        val allowAutocorrect = allowPredictions && !isStructuredField
        val allowLearning = !isPassword && !isNumeric && !noPersonalizedLearning && !hasIncognitoPrivateOption
        val allowVoice = !isPassword && !isNumeric

        return FieldPolicy(
            isPasswordOrSensitive = isPassword,
            allowPredictions = allowPredictions,
            allowAutocorrect = allowAutocorrect,
            allowLearning = allowLearning,
            allowVoiceInput = allowVoice,
            preferNumericLayout = isNumeric
        )
    }

    internal fun isPasswordVariation(inputClass: Int, variation: Int): Boolean {
        if (inputClass == InputType.TYPE_CLASS_NUMBER) {
            return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        if (inputClass == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }
        return false
    }
}
