package com.example.taobaotranslate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var languageSpinner: Spinner
    private lateinit var startButton: Button

    // All languages ML Kit can translate Chinese into, sorted by display name.
    private val languages: List<String> = TranslateLanguage.getAllLanguages()
        .filter { it != TranslateLanguage.CHINESE }
        .sortedBy { displayName(it) }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Granted or not, continue; the service still runs without a visible notification.
            downloadThenCapture()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, TranslateService::class.java)
                    .setAction(TranslateService.ACTION_START)
                    .putExtra(TranslateService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(TranslateService.EXTRA_DATA, data)
                    .putExtra(TranslateService.EXTRA_LANG, selectedLanguage())
                ContextCompat.startForegroundService(this, intent)
                openTaobao()
            } else {
                setStatus("Screen capture was not allowed, so nothing can be translated.")
                startButton.isEnabled = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        languageSpinner = findViewById(R.id.languageSpinner)
        startButton = findViewById(R.id.startButton)

        languageSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, languages.map { displayName(it) }
        )
        val saved = getPreferences(Context.MODE_PRIVATE)
            .getString(PREF_LANG, TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH
        languageSpinner.setSelection(languages.indexOf(saved).coerceAtLeast(0))

        startButton.setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(Intent(this, TranslateService::class.java).setAction(TranslateService.ACTION_STOP))
            setStatus("Stopped.")
        }
    }

    override fun onResume() {
        super.onResume()
        setStatus(
            if (TranslateService.running) "Translating. Use the notification to pause or stop."
            else "Ready."
        )
    }

    // ------------------------------------------------------------------ flow

    private fun onStartClicked() {
        if (TranslateService.running) {
            openTaobao()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            Toast.makeText(
                this, "Allow \"Display over other apps\", then come back and tap Start again.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        downloadThenCapture()
    }

    private fun downloadThenCapture() {
        val lang = selectedLanguage()
        getPreferences(Context.MODE_PRIVATE).edit().putString(PREF_LANG, lang).apply()

        startButton.isEnabled = false
        setStatus("Downloading language packs (first time only, needs internet)...")

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(lang)
                .build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.close()
                setStatus("Waiting for screen capture permission...")
                val mpm = getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
            .addOnFailureListener { e ->
                translator.close()
                setStatus("Language pack download failed: ${e.message}")
                startButton.isEnabled = true
            }
    }

    private fun openTaobao() {
        startButton.isEnabled = true
        val launch = packageManager.getLaunchIntentForPackage(TAOBAO_PACKAGE)
        if (launch != null) {
            startActivity(launch)
        } else {
            Toast.makeText(this, "Taobao isn't installed. Open it yourself.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }
    }

    // --------------------------------------------------------------- helpers

    private fun selectedLanguage(): String =
        languages.getOrElse(languageSpinner.selectedItemPosition) { TranslateLanguage.ENGLISH }

    private fun displayName(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH).ifBlank { tag }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    private companion object {
        const val PREF_LANG = "target_lang"
        const val TAOBAO_PACKAGE = "com.taobao.taobao"
    }
}
