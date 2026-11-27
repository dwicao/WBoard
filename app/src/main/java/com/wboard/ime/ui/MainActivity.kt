package com.wboard.ime.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wboard.ime.BuildConfig
import com.wboard.ime.R
import com.wboard.ime.prefs.WBoardPrefs
import com.wboard.ime.update.Updater
import com.wboard.ime.voice.DeviceSpecAdvisor
import com.wboard.ime.voice.IndonesianVoiceController
import com.wboard.ime.voice.VoiceResultBridge
import com.wboard.ime.voice.WhisperModelManager
import com.wboard.ime.voice.WhisperModelManager.ModelVariant
import com.wboard.ime.voice.WhisperVoiceController
import java.io.File

class MainActivity : Activity(), WhisperVoiceController.Listener {

    private lateinit var statusEnableIme: TextView
    private lateinit var statusSelectIme: TextView
    private lateinit var statusMicPerm: TextView
    private lateinit var txtDeviceSpec: TextView
    private lateinit var txtVoiceStatus: TextView
    private lateinit var modelsContainer: LinearLayout
    private lateinit var btnTestVoice: Button
    private lateinit var txtUpdatesCurrent: TextView
    private lateinit var txtUpdatesStatus: TextView
    private lateinit var btnCheckUpdates: Button
    private lateinit var btnInstallUpdate: Button
    private lateinit var editPlayground: EditText

    private lateinit var prefs: WBoardPrefs
    private lateinit var voiceController: WhisperVoiceController
    private lateinit var specReport: DeviceSpecAdvisor.SpecReport

    private var availableUpdate: Updater.Info? = null
    private var downloadedApk: File? = null
    private var downloadingVariant: ModelVariant? = null
    private var downloadCancelled: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = WBoardPrefs(this)
        voiceController = WhisperVoiceController(this, this)
        specReport = DeviceSpecAdvisor.inspect(this)

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
        txtDeviceSpec = findViewById(R.id.txt_device_spec)
        txtVoiceStatus = findViewById(R.id.txt_voice_status)
        modelsContainer = findViewById(R.id.whisper_models_container)
        btnTestVoice = findViewById(R.id.btn_test_voice)
        txtUpdatesCurrent = findViewById(R.id.txt_updates_current)
        txtUpdatesStatus = findViewById(R.id.txt_updates_status)
        btnCheckUpdates = findViewById(R.id.btn_check_updates)
        btnInstallUpdate = findViewById(R.id.btn_install_update)
        editPlayground = findViewById(R.id.edit_playground)

        txtUpdatesCurrent.text = getString(
            R.string.updates_current_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )

        txtDeviceSpec.text = getString(
            R.string.voice_device_spec,
            specReport.totalRamGb,
            specReport.cpuCores,
            if (specReport.is64Bit) "64-bit" else "32-bit",
            specReport.optimalModel.displayName,
            specReport.maxSupportedModel.displayName
        )

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

        btnTestVoice.setOnClickListener {
            if (!IndonesianVoiceController.hasAudioPermission(this)) {
                requestMicPermission()
                return@setOnClickListener
            }
            if (WhisperModelManager.activeModelFile(this, prefs.whisperModelId) == null) {
                txtVoiceStatus.text = getString(
                    R.string.voice_model_missing,
                    specReport.optimalModel.displayName
                )
                return@setOnClickListener
            }
            if (voiceController.isActive()) {
                voiceController.stopAndTranscribe()
            } else {
                voiceController.startListening(
                    preferredModelId = prefs.whisperModelId,
                    applyPunctuation = prefs.voicePunctuationEnabled,
                    capitalizeFirst = true
                )
            }
        }

        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnCheckUpdates.setOnClickListener { checkForUpdates() }
        btnInstallUpdate.setOnClickListener { downloadOrInstallUpdate() }

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
        downloadCancelled = true
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

    private fun renderModelRows() {
        modelsContainer.removeAllViews()
        val d = resources.displayMetrics.density
        val active = WhisperModelManager.activeModelFile(this, prefs.whisperModelId)?.first

        for (variant in ModelVariant.entries) {
            val installed = WhisperModelManager.isModelInstalled(this, variant)
            val isActive = installed && active == variant
            val isDownloadingThis = downloadingVariant == variant
            val isOptimal = variant == specReport.optimalModel
            val isAboveMax = variant.ordinal > specReport.maxSupportedModel.ordinal

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_test_input)
                val pad = (10 * d).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * d).toInt()
                }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val titleCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = TextView(this).apply {
                text = "[${variant.tierLabel}] ${variant.displayName}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.wb_text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
            }
            titleCol.addView(titleTv)

            if (isOptimal || isAboveMax) {
                val badgeTv = TextView(this).apply {
                    text = if (isOptimal) {
                        getString(R.string.badge_recommended)
                    } else {
                        getString(R.string.badge_heavy)
                    }
                    setTextColor(
                        ContextCompat.getColor(
                            this@MainActivity,
                            if (isOptimal) R.color.wb_ok else R.color.wb_warn
                        )
                    )
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTypeface(null, Typeface.BOLD)
                }
                titleCol.addView(badgeTv)
            }

            val specTv = TextView(this).apply {
                text = variant.specSummary
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.wb_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            }
            titleCol.addView(specTv)

            topRow.addView(titleCol)

            val btnCol = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            if (isDownloadingThis) {
                val cancelBtn = makeSmallButton(getString(R.string.btn_model_cancel), primary = false) {
                    downloadCancelled = true
                }
                btnCol.addView(cancelBtn)
            } else if (installed) {
                if (isActive) {
                    val activeBtn = makeSmallButton(getString(R.string.btn_model_active), primary = true) {}
                    activeBtn.isEnabled = false
                    btnCol.addView(activeBtn)
                } else {
                    val useBtn = makeSmallButton(getString(R.string.btn_model_use), primary = true) {
                        prefs.whisperModelId = variant.id
                        refreshStatus()
                    }
                    btnCol.addView(useBtn)
                }
                val delBtn = makeSmallButton(getString(R.string.btn_model_delete), primary = false) {
                    WhisperModelManager.deleteModel(this, variant)
                    refreshStatus()
                }.apply {
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.wb_danger))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        (32 * d).toInt()
                    ).apply { marginStart = (6 * d).toInt() }
                }
                btnCol.addView(delBtn)
            } else {
                val dlBtn = makeSmallButton(getString(R.string.btn_model_download), primary = isOptimal) {
                    startModelDownload(variant)
                }
                dlBtn.isEnabled = downloadingVariant == null
                btnCol.addView(dlBtn)
            }

            topRow.addView(btnCol)
            card.addView(topRow)
            modelsContainer.addView(card)
        }
    }

    private fun makeSmallButton(label: String, primary: Boolean, onClick: () -> Unit): Button {
        val d = resources.displayMetrics.density
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setBackgroundResource(if (primary) R.drawable.bg_button_primary else R.drawable.bg_button_secondary)
            setTextColor(
                if (primary) 0xFFFFFFFF.toInt()
                else ContextCompat.getColor(this@MainActivity, R.color.wb_text_primary)
            )
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (32 * d).toInt()
            )
            setOnClickListener { onClick() }
        }
    }

    private fun startModelDownload(variant: ModelVariant) {
        if (downloadingVariant != null) return
        downloadingVariant = variant
        downloadCancelled = false
        txtVoiceStatus.text = getString(R.string.voice_model_downloading, variant.displayName, 0)
        renderModelRows()

        WhisperModelManager.downloadModelAsync(
            context = this,
            variant = variant,
            cancelled = { downloadCancelled || isDestroyed },
            onProgress = { done, total ->
                val pct = if (total > 0) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
                txtVoiceStatus.text = getString(R.string.voice_model_downloading, variant.displayName, pct)
            },
            onComplete = { result ->
                downloadingVariant = null
                result.onSuccess {
                    prefs.whisperModelId = variant.id
                    refreshStatus()
                }.onFailure { err ->
                    txtVoiceStatus.text = getString(R.string.voice_model_error, err.message ?: "error")
                    renderModelRows()
                }
            }
        )
    }

    private fun checkForUpdates() {
        btnCheckUpdates.isEnabled = false
        btnInstallUpdate.visibility = View.GONE
        downloadedApk = null
        txtUpdatesStatus.text = getString(R.string.updates_checking)

        Updater.checkAsync { result ->
            btnCheckUpdates.isEnabled = true
            result.onSuccess { info ->
                if (info.isNewerThan(BuildConfig.VERSION_CODE.toLong())) {
                    availableUpdate = info
                    val notesText = if (info.notes.isNotEmpty()) {
                        "\n" + info.notes.joinToString("\n") { "• $it" }
                    } else {
                        ""
                    }
                    txtUpdatesStatus.text = getString(
                        R.string.updates_available,
                        info.version,
                        info.releasedAt.ifBlank { "release" }
                    ) + notesText
                    btnInstallUpdate.visibility = View.VISIBLE
                } else {
                    availableUpdate = null
                    txtUpdatesStatus.text = getString(R.string.updates_up_to_date, BuildConfig.VERSION_NAME)
                }
            }.onFailure { err ->
                txtUpdatesStatus.text = getString(R.string.updates_error, err.message ?: "error")
            }
        }
    }

    private fun downloadOrInstallUpdate() {
        val info = availableUpdate ?: return
        val ready = downloadedApk
        if (ready != null && ready.exists()) {
            triggerInstall(ready)
            return
        }

        btnCheckUpdates.isEnabled = false
        btnInstallUpdate.isEnabled = false
        txtUpdatesStatus.text = getString(R.string.updates_downloading, 0)

        Updater.downloadAsync(
            context = this,
            info = info,
            onProgress = { done, total ->
                val pct = if (total > 0) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
                txtUpdatesStatus.text = getString(R.string.updates_downloading, pct)
            },
            onComplete = { result ->
                btnCheckUpdates.isEnabled = true
                btnInstallUpdate.isEnabled = true
                result.onSuccess { file ->
                    downloadedApk = file
                    txtUpdatesStatus.text = getString(R.string.updates_ready)
                    triggerInstall(file)
                }.onFailure { err ->
                    txtUpdatesStatus.text = getString(R.string.updates_error, err.message ?: "error")
                }
            }
        )
    }

    private fun triggerInstall(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !Updater.canRequestPackageInstalls(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        Updater.installApk(this, file)
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

    // --- WhisperVoiceController.Listener ---

    override fun onVoiceListeningStarted() {
        txtVoiceStatus.text = getString(R.string.voice_test_listening)
    }

    override fun onVoiceRmsChanged(level: Float) = Unit

    override fun onVoiceTranscribing() {
        txtVoiceStatus.text = getString(R.string.voice_test_processing)
    }

    override fun onVoiceFinalResult(text: String) {
        txtVoiceStatus.text = getString(R.string.voice_test_result, text)
        editPlayground.append(if (editPlayground.text.isEmpty()) text else " $text")
    }

    override fun onVoiceError(reason: String) {
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

        val installed = WhisperModelManager.activeModelFile(this, prefs.whisperModelId)
        btnTestVoice.isEnabled = installed != null
        renderModelRows()

        if (downloadingVariant == null && !voiceController.isActive()) {
            txtVoiceStatus.text = if (installed != null) {
                getString(R.string.voice_model_ready, installed.first.displayName)
            } else {
                getString(R.string.voice_model_missing, specReport.optimalModel.displayName)
            }
        }
    }

    companion object {
        const val EXTRA_REQUEST_MIC = "extra_request_mic"
        const val EXTRA_SETUP_VOICE_MODEL = "extra_setup_voice_model"
        private const val REQ_MIC = 101
    }
}
