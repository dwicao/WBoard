package com.wboard.ime.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wboard.ime.R
import com.wboard.ime.prefs.WBoardPrefs
import com.wboard.ime.voice.IndonesianVoiceController
import com.wboard.ime.voice.VoiceInputActivity
import com.wboard.ime.voice.VoiceResultBridge

class MainActivity : Activity(), IndonesianVoiceController.Listener {

    private lateinit var statusEnableIme: TextView
    private lateinit var statusSelectIme: TextView
    private lateinit var statusMicPerm: TextView
    private lateinit var txtVoiceStatus: TextView
    private lateinit var txtPrivacyInternet: TextView
    private lateinit var editPlayground: EditText

    private lateinit var prefs: WBoardPrefs
    private lateinit var voiceController: IndonesianVoiceController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = WBoardPrefs(this)
        voiceController = IndonesianVoiceController(this, this)

        val root = findViewById<View>(R.id.main_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (18 * resources.displayMetrics.density).toInt()
            v.setPadding(pad, pad + bars.top, pad, pad + bars.bottom)
            insets
        }

        statusEnableIme = findViewById(R.id.status_enable_ime)
        statusSelectIme = findViewById(R.id.status_select_ime)
        statusMicPerm = findViewById(R.id.status_mic_perm)
        txtVoiceStatus = findViewById(R.id.txt_voice_status)
        txtPrivacyInternet = findViewById(R.id.txt_privacy_internet)
        editPlayground = findViewById(R.id.edit_playground)

        findViewById<Button>(R.id.btn_enable_ime).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.btn_select_ime).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        findViewById<Button>(R.id.btn_grant_mic).setOnClickListener {
            requestMicPermission()
        }

        findViewById<Button>(R.id.btn_test_voice).setOnClickListener {
            if (!IndonesianVoiceController.hasAudioPermission(this)) {
                requestMicPermission()
                return@setOnClickListener
            }
            if (voiceController.isActive()) {
                voiceController.stopListening()
            } else {
                voiceController.startListening(
                    strictOffline = prefs.voiceStrictOffline,
                    applyPunctuation = prefs.voicePunctuationEnabled,
                    capitalizeFirst = true
                )
            }
        }

        findViewById<Button>(R.id.btn_voice_settings).setOnClickListener {
            val intents = listOf(
                Intent("com.android.settings.VOICE_INPUT_SETTINGS"),
                Intent(Settings.ACTION_LOCALE_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            for (intent in intents) {
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    break
                }
            }
        }

        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        handleMicIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleMicIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        VoiceResultBridge.consumePending()?.let { voiceText ->
            editPlayground.append(if (editPlayground.text.isEmpty()) voiceText else " $voiceText")
            txtVoiceStatus.text = getString(R.string.voice_test_result, voiceText)
        }
        refreshStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshStatus()
    }

    override fun onDestroy() {
        voiceController.destroy()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) refreshStatus()
    }

    private fun handleMicIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true &&
            !IndonesianVoiceController.hasAudioPermission(this)
        ) {
            requestMicPermission()
        }
    }

    private fun requestMicPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQ_MIC
        )
    }

    // --- IndonesianVoiceController.Listener ---

    override fun onListeningStarted(onDevice: Boolean) {
        txtVoiceStatus.text = getString(R.string.voice_test_listening)
    }

    override fun onRmsChanged(level: Float) = Unit

    override fun onPartialResult(text: String) {
        txtVoiceStatus.text = getString(R.string.voice_test_result, text)
    }

    override fun onFinalResult(text: String) {
        txtVoiceStatus.text = getString(R.string.voice_test_result, text)
        editPlayground.append(if (editPlayground.text.isEmpty()) text else " $text")
    }

    override fun onVoiceError(reason: String) {
        if (reason == "fallback_activity" && IndonesianVoiceController.isActivityAvailable(this)) {
            startActivity(Intent(this, VoiceInputActivity::class.java).apply {
                putExtra(VoiceInputActivity.EXTRA_PUNCTUATION, prefs.voicePunctuationEnabled)
                putExtra(VoiceInputActivity.EXTRA_CAPITALIZE, true)
                putExtra(VoiceInputActivity.EXTRA_PREFER_OFFLINE, prefs.voiceStrictOffline)
            })
            return
        }
        txtVoiceStatus.text = getString(R.string.voice_test_error, reason)
    }

    override fun onVoiceStopped() {
        refreshStatus()
    }

    private fun refreshStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val active = defaultIme?.startsWith("$packageName/") == true
        val micGranted = IndonesianVoiceController.hasAudioPermission(this)

        statusEnableIme.text = if (enabled) {
            getString(R.string.status_enabled)
        } else {
            getString(R.string.step_enable_ime_desc)
        }
        statusEnableIme.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.wb_ok else R.color.wb_text_secondary)
        )

        statusSelectIme.text = if (active) {
            getString(R.string.status_active)
        } else {
            getString(R.string.step_select_ime_desc)
        }
        statusSelectIme.setTextColor(
            ContextCompat.getColor(this, if (active) R.color.wb_ok else R.color.wb_text_secondary)
        )

        statusMicPerm.text = if (micGranted) {
            getString(R.string.status_granted)
        } else {
            getString(R.string.step_mic_perm_desc)
        }
        statusMicPerm.setTextColor(
            ContextCompat.getColor(this, if (micGranted) R.color.wb_ok else R.color.wb_text_secondary)
        )

        if (!voiceController.isActive()) {
            val voiceAvailability = IndonesianVoiceController.checkAvailability(this)
            txtVoiceStatus.text = when (voiceAvailability) {
                IndonesianVoiceController.Availability.ON_DEVICE_READY ->
                    getString(R.string.voice_on_device_ready)
                IndonesianVoiceController.Availability.SYSTEM_ONLY ->
                    getString(R.string.voice_system_only)
                IndonesianVoiceController.Availability.ACTIVITY_ONLY ->
                    getString(R.string.voice_activity_only)
                IndonesianVoiceController.Availability.UNAVAILABLE ->
                    getString(R.string.voice_unavailable)
            }
        }

        val hasInternet = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.INTERNET
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasInternet) {
            txtPrivacyInternet.text = getString(R.string.privacy_no_internet)
        }
    }

    companion object {
        const val EXTRA_REQUEST_MIC = "extra_request_mic"
        private const val REQ_MIC = 101
    }
}
