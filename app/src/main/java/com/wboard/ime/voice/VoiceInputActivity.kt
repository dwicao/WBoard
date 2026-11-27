package com.wboard.ime.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import com.wboard.ime.R

/**
 * Translucent fallback activity for Indonesian speech recognition when an OEM ROM
 * restricts SpeechRecognizer inside a background InputMethodService context.
 */
class VoiceInputActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val applyPunctuation = intent.getBooleanExtra(EXTRA_PUNCTUATION, true)
        val capitalizeFirst = intent.getBooleanExtra(EXTRA_CAPITALIZE, false)
        val preferOffline = intent.getBooleanExtra(EXTRA_PREFER_OFFLINE, false)

        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.ime_voice_listening))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (preferOffline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

        if (speechIntent.resolveActivity(packageManager) == null) {
            finish()
            return
        }

        @Suppress("DEPRECATION")
        startActivityForResult(speechIntent, REQ_SPEECH)
        intent.putExtra(EXTRA_PUNCTUATION, applyPunctuation)
        intent.putExtra(EXTRA_CAPITALIZE, capitalizeFirst)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK) {
            val raw = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            val applyPunctuation = intent.getBooleanExtra(EXTRA_PUNCTUATION, true)
            val capitalizeFirst = intent.getBooleanExtra(EXTRA_CAPITALIZE, false)
            val processed = IndonesianVoicePostProcessor.process(
                rawText = raw,
                applyPunctuationCommands = applyPunctuation,
                capitalizeFirst = capitalizeFirst
            )
            if (processed.isNotEmpty()) {
                VoiceResultBridge.postPending(processed)
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_PUNCTUATION = "extra_punctuation"
        const val EXTRA_CAPITALIZE = "extra_capitalize"
        const val EXTRA_PREFER_OFFLINE = "extra_prefer_offline"
        private const val REQ_SPEECH = 201
    }
}
