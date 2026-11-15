package com.example.telaleitor

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val prefs = getSharedPreferences(LanguageManagerActivity.PREFS, MODE_PRIVATE)
            val intent = Intent(this, ScreenReaderService::class.java).apply {
                action = ScreenReaderService.ACTION_START
                putExtra(ScreenReaderService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenReaderService.EXTRA_DATA, data)
                putExtra(ScreenReaderService.EXTRA_LANG, prefs.getString(LanguageManagerActivity.KEY_LANGUAGE, "ja"))
                putExtra(ScreenReaderService.EXTRA_OCR_LANG, prefs.getString(LanguageManagerActivity.KEY_OCR, "jpn"))
            }
            ContextCompat.startForegroundService(this, intent)
            moveTaskToBack(true)
        } else Toast.makeText(this, "Permissão de captura negada", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        findViewById<Button>(R.id.btnLanguages).setOnClickListener { startActivity(Intent(this, LanguageManagerActivity::class.java)) }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startReading() }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(LanguageManagerActivity.PREFS, MODE_PRIVATE)
        val label = prefs.getString(LanguageManagerActivity.KEY_LABEL, "Japonês") ?: "Japonês"
        findViewById<TextView>(R.id.tvSelectedLanguage)?.text = "Idioma selecionado: $label"
    }

    private fun startReading() {
        val prefs = getSharedPreferences(LanguageManagerActivity.PREFS, MODE_PRIVATE)
        val ocr = prefs.getString(LanguageManagerActivity.KEY_OCR, "jpn") ?: "jpn"
        if (!java.io.File(filesDir, "tesseract/tessdata/$ocr.traineddata").exists()) {
            Toast.makeText(this, "Instale primeiro o pacote desse idioma em Gerenciar idiomas.", Toast.LENGTH_LONG).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Permita 'Exibir sobre outros apps' e toque em Iniciar de novo", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}