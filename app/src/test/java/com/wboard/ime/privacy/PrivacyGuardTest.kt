package com.wboard.ime.privacy

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyGuardTest {

    @Test
    fun passwordField_disablesPredictionsLearningAndVoice() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val policy = PrivacyGuard.evaluate(info)
        assertTrue(policy.isPasswordOrSensitive)
        assertFalse(policy.allowPredictions)
        assertFalse(policy.allowAutocorrect)
        assertFalse(policy.allowLearning)
        assertFalse(policy.allowVoiceInput)
    }

    @Test
    fun incognitoFlag_disablesLearningButKeepsStandardPredictions() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val policy = PrivacyGuard.evaluate(info)
        assertFalse(policy.isPasswordOrSensitive)
        assertTrue(policy.allowPredictions)
        assertFalse(policy.allowLearning)
    }

    @Test
    fun phoneField_prefersNumericLayout() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val policy = PrivacyGuard.evaluate(info)
        assertTrue(policy.preferNumericLayout)
        assertFalse(policy.allowPredictions)
    }
}
