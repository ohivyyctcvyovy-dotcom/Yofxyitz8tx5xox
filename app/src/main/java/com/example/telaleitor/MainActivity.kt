package com.example.telaleitor

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val intent = Intent(this, ScreenReaderService::class.java).apply {
                    action = ScreenReaderService.ACTION_START
                    putExtra(ScreenReaderService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenReaderService.EXTRA_DATA, data)
                    putExtra(ScreenReaderService.EXTRA_LANG, selectedLanguage())
                }
                ContextCompat.startForegroundService(this, intent)
                moveTaskToBack(true) // volta para o app que estava aberto
            } else {
                Toast.makeText(this, "Permissão de captura negada", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener { startReading() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, ScreenReaderService::class.java))
            Toast.makeText(this, "Leitor encerrado. O botão flutuante foi removido.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectedLanguage(): String =
        when (findViewById<RadioGroup>(R.id.rgLang).checkedRadioButtonId) {
            R.id.rbAuto -> ScreenReaderService.LANG_AUTO
            R.id.rbKo -> ScreenReaderService.LANG_KOREAN
            R.id.rbZh -> ScreenReaderService.LANG_CHINESE
            R.id.rbEn -> ScreenReaderService.LANG_ENGLISH
            else -> ScreenReaderService.LANG_JAPANESE
        }

    private fun startReading() {
        // Permissão para desenhar a seleção por cima de outros apps
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Permita 'Exibir sobre outros apps' e toque em Iniciar de novo",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
