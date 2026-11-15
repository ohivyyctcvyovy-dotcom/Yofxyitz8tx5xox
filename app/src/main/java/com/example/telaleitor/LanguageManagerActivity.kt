package com.example.telaleitor

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class LanguageManagerActivity : Activity() {
    companion object {
        const val PREFS = "offline_language_packs"
        const val KEY_LANGUAGE = "selected_language"
        const val KEY_OCR = "selected_ocr"
        const val KEY_LABEL = "selected_label"
    }

    private data class LanguagePack(val label: String, val flag: String, val mlCode: String, val ocrCode: String)

    private val packs = listOf(
        LanguagePack("Japonês", "🇯🇵", TranslateLanguage.JAPANESE, "jpn"),
        LanguagePack("Coreano", "🇰🇷", TranslateLanguage.KOREAN, "kor"),
        LanguagePack("Chinês simplificado", "🇨🇳", TranslateLanguage.CHINESE, "chi_sim"),
        LanguagePack("Chinês tradicional", "🇹🇼", TranslateLanguage.CHINESE, "chi_tra"),
        LanguagePack("Tailandês", "🇹🇭", TranslateLanguage.THAI, "tha"),
        LanguagePack("Vietnamita", "🇻🇳", TranslateLanguage.VIETNAMESE, "vie"),
        LanguagePack("Indonésio", "🇮🇩", TranslateLanguage.INDONESIAN, "ind"),
        LanguagePack("Malaio", "🇲🇾", TranslateLanguage.MALAY, "msa"),
        LanguagePack("Hindi", "🇮🇳", TranslateLanguage.HINDI, "hin"),
        LanguagePack("Bengali", "🇧🇩", TranslateLanguage.BENGALI, "ben"),
        LanguagePack("Tâmil", "🇮🇳", TranslateLanguage.TAMIL, "tam"),
        LanguagePack("Télugo", "🇮🇳", TranslateLanguage.TELUGU, "tel"),
        LanguagePack("Gujarati", "🇮🇳", TranslateLanguage.GUJARATI, "guj"),
        LanguagePack("Canarim", "🇮🇳", TranslateLanguage.KANNADA, "kan"),
        LanguagePack("Marata", "🇮🇳", TranslateLanguage.MARATHI, "mar"),
        LanguagePack("Urdu", "🇵🇰", TranslateLanguage.URDU, "urd"),
        LanguagePack("Persa", "🇮🇷", TranslateLanguage.PERSIAN, "fas"),
        LanguagePack("Árabe", "🌙", TranslateLanguage.ARABIC, "ara"),
        LanguagePack("Georgiano", "🇬🇪", TranslateLanguage.GEORGIAN, "kat"),
        LanguagePack("Hebraico", "🇮🇱", TranslateLanguage.HEBREW, "heb"),
        LanguagePack("Turco", "🇹🇷", TranslateLanguage.TURKISH, "tur"),
        LanguagePack("Tagalog / Filipino", "🇵🇭", TranslateLanguage.TAGALOG, "fil")
    )

    private lateinit var container: LinearLayout
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF071321.toInt()) }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.addView(container)
        setContentView(scroll)
        addText("IDIOMAS OFFLINE", 23f, 0xFFFFFFFF.toInt(), true)
        addText("Escolha um idioma e instale os dois pacotes: reconhecimento de caracteres (OCR) e tradução para português. O download acontece somente quando você toca em Instalar.", 15f, 0xFFB8CBD9.toInt())
        addText("Depois de instalados, os pacotes funcionam no próprio celular, mesmo sem internet.", 14f, 0xFF48D7B0.toInt())
        packs.forEach { addPack(it) }
    }

    private fun addPack(pack: LanguagePack) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFF10263A.toInt())
        }
        card.addView(TextView(this).apply {
            text = "${pack.flag}  ${pack.label}"
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
        })
        card.addView(TextView(this).apply {
            text = "OCR: ${pack.ocrCode}  •  Tradução: ${pack.label} → Português"
            textSize = 12f
            setTextColor(0xFFB8CBD9.toInt())
            setPadding(0, dp(5), 0, dp(8))
        })
        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF48D7B0.toInt())
        }
        card.addView(status)
        val progress = ProgressBar(this).apply { visibility = View.GONE }
        card.addView(progress)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val install = Button(this).apply { text = "Instalar pacote" }
        val use = Button(this).apply { text = "Usar este idioma" }
        actions.addView(install, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(use, LinearLayout.LayoutParams(0, dp(48), 1f))
        card.addView(actions)
        container.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        fun refresh() {
            val installed = isInstalled(pack)
            status.text = if (installed) "Pacotes instalados neste aparelho" else "Ainda não instalado"
            install.text = if (installed) "Instalado" else "Instalar pacote"
            install.isEnabled = !installed
            use.isEnabled = installed
        }
        refresh()
        install.setOnClickListener {
            install.isEnabled = false
            use.isEnabled = false
            progress.visibility = View.VISIBLE
            status.text = "Baixando modelo de tradução..."
            installPack(pack) { success, message ->
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text = message
                    refresh()
                    if (!success) install.isEnabled = true
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }
        use.setOnClickListener {
            if (!isInstalled(pack)) return@setOnClickListener
            prefs.edit()
                .putString(KEY_LANGUAGE, pack.mlCode)
                .putString(KEY_OCR, pack.ocrCode)
                .putString(KEY_LABEL, pack.label)
                .apply()
            Toast.makeText(this, "${pack.label} selecionado para leitura e tradução.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun installPack(pack: LanguagePack, done: (Boolean, String) -> Unit) {
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(pack.mlCode)
                .setTargetLanguage(TranslateLanguage.PORTUGUESE)
                .build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.close()
                Thread {
                    try {
                        val folder = File(filesDir, "tesseract/tessdata")
                        if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("Não foi possível criar a pasta dos modelos.")
                        val target = File(folder, "${pack.ocrCode}.traineddata")
                        val temp = File(folder, "${pack.ocrCode}.traineddata.part")
                        val url = URL("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/${pack.ocrCode}.traineddata")
                        val connection = (url.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 20000
                            readTimeout = 90000
                            requestMethod = "GET"
                        }
                        try {
                            connection.connect()
                            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                                throw IllegalStateException("O servidor não encontrou o pacote OCR (${connection.responseCode}).")
                            }
                            connection.inputStream.use { input ->
                                temp.outputStream().use { output -> input.copyTo(output) }
                            }
                        } finally {
                            connection.disconnect()
                        }
                        if (temp.length() < 100_000L) {
                            temp.delete()
                            throw IllegalStateException("O arquivo OCR recebido parece incompleto.")
                        }
                        if (target.exists()) target.delete()
                        if (!temp.renameTo(target)) throw IllegalStateException("Não consegui salvar o pacote OCR.")
                        prefs.edit().putBoolean(installedKey(pack), true).apply()
                        done(true, "Pacotes de ${pack.label} instalados. Agora toque em Usar este idioma.")
                    } catch (e: Exception) {
                        done(false, "Falha no OCR de ${pack.label}: ${e.message ?: "erro de download"}.")
                    }
                }.start()
            }
            .addOnFailureListener { error ->
                translator.close()
                done(false, "Falha ao baixar a tradução de ${pack.label}: ${error.message ?: "verifique a internet"}.")
            }
    }

    private fun isInstalled(pack: LanguagePack): Boolean =
        prefs.getBoolean(installedKey(pack), false) &&
            File(filesDir, "tesseract/tessdata/${pack.ocrCode}.traineddata").exists()

    private fun installedKey(pack: LanguagePack) = "installed_${pack.mlCode}_${pack.ocrCode}"

    private fun addText(value: String, size: Float, color: Int, bold: Boolean = false) {
        container.addView(TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
