package com.example.taobaotranslate

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

    private val prefs by lazy {
        getSharedPreferences(TranslateAccessibilityService.PREFS, MODE_PRIVATE)
    }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Granted or not, continue; translation works without the notification.
            downloadThenContinue()
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
        val saved = prefs.getString(TranslateAccessibilityService.PREF_LANG, TranslateLanguage.ENGLISH)
            ?: TranslateLanguage.ENGLISH
        languageSpinner.setSelection(languages.indexOf(saved).coerceAtLeast(0))

        startButton.setOnClickListener { onStartClicked() }

        // Android 13+ hides accessibility switches of apps installed from an APK
        // file until "Allow restricted settings" is chosen on the app info page.
        val restrictedButton = findViewById<Button>(R.id.restrictedButton)
        restrictedButton.visibility = if (Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE
        restrictedButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            Toast.makeText(
                this, "Tap ⋮ (top right), then \"Allow restricted settings\".", Toast.LENGTH_LONG
            ).show()
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            sendBroadcast(Intent(TranslateAccessibilityService.ACTION_TURN_OFF).setPackage(packageName))
            setStatus("Turned off. Tap Start to turn it back on.")
        }
    }

    override fun onResume() {
        super.onResume()
        setStatus(
            if (serviceEnabled()) "On. Translations appear whenever Taobao is open. Use the notification to pause."
            else "Off. Tap Start to set it up."
        )
    }

    // ------------------------------------------------------------------ flow

    private fun onStartClicked() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        downloadThenContinue()
    }

    private fun downloadThenContinue() {
        val lang = selectedLanguage()
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
                startButton.isEnabled = true
                // A running service picks the new language up straight away.
                prefs.edit().putString(TranslateAccessibilityService.PREF_LANG, lang).apply()
                if (serviceEnabled()) openTaobao() else openAccessibilitySettings()
            }
            .addOnFailureListener { e ->
                translator.close()
                startButton.isEnabled = true
                setStatus("Language pack download failed: ${e.message}")
            }
    }

    private fun openAccessibilitySettings() {
        setStatus("Turn on \"Taobao Live Translate\" in Accessibility, then come back and tap Start.")
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(
            this,
            "Find \"Taobao Live Translate\" (often under Installed or Downloaded apps) and turn it on. " +
                "If it's greyed out, use \"Allow restricted settings\" first.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openTaobao() {
        val launch = packageManager.getLaunchIntentForPackage(TranslateAccessibilityService.TAOBAO_PACKAGE)
        if (launch != null) {
            startActivity(launch)
        } else {
            Toast.makeText(this, "Taobao isn't installed. Open it yourself.", Toast.LENGTH_LONG).show()
        }
    }

    // --------------------------------------------------------------- helpers

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, TranslateAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun selectedLanguage(): String =
        languages.getOrElse(languageSpinner.selectedItemPosition) { TranslateLanguage.ENGLISH }

    private fun displayName(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH).ifBlank { tag }

    private fun setStatus(text: String) {
        statusText.text = text
    }
}
