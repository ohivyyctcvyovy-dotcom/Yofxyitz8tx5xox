package com.example.telaleitor

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
        LanguagePack("Português (Brasil)", "🇧🇷", TranslateLanguage.PORTUGUESE, "por"),
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
        LanguagePack("Árabe", "🇸🇦", TranslateLanguage.ARABIC, "ara"),
        LanguagePack("Georgiano", "🇬🇪", TranslateLanguage.GEORGIAN, "kat"),
        LanguagePack("Hebraico", "🇮🇱", TranslateLanguage.HEBREW, "heb"),
        LanguagePack("Turco", "🇹🇷", TranslateLanguage.TURKISH, "tur"),
        LanguagePack("Tagalog / Filipino", "🇵🇭", TranslateLanguage.TAGALOG, "fil"),
        LanguagePack("Inglês (Estados Unidos)", "🇺🇸", TranslateLanguage.ENGLISH, "eng"),
        LanguagePack("Inglês (Reino Unido)", "🇬🇧", TranslateLanguage.ENGLISH, "eng")
    )

    private lateinit var container: LinearLayout
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 17, 31)
        window.navigationBarColor = Color.rgb(7, 17, 31)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 17, 31))
            clipToPadding = false
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(30))
        }
        scroll.addView(container)
        setContentView(scroll)

        val header = panel(0xFF10243A.toInt(), 22)
        header.addView(text("IDIOMAS", 12f, 0xFF55D6C2.toInt(), true))
        header.addView(text("Sua leitura.\nEm português.", 27f, Color.WHITE, true).apply {
            setPadding(0, dp(8), 0, dp(8))
        })
        header.addView(text("Escolha o idioma do conteúdo que aparece na tela. O aplicativo reconhece e traduz sempre para português.", 14f, 0xFFC0CEDC.toInt()))
        header.addView(text("🇧🇷  Destino fixo: Português", 14f, 0xFF70E0C6.toInt(), true).apply {
            setPadding(0, dp(14), 0, 0)
        })
        container.addView(header, matchWrap().apply { bottomMargin = dp(18) })

        container.addView(text("Biblioteca de idiomas", 21f, Color.WHITE, true))
        container.addView(text("Instale somente os idiomas que você usa. Cada opção tem reconhecimento de texto (OCR) e tradução offline.", 14f, 0xFF9FB2C5.toInt()).apply {
            setPadding(0, dp(5), 0, dp(16))
        })
        packs.forEach { addPack(it) }
        container.addView(text("Os pacotes são baixados uma vez pela internet. Depois, a leitura e a tradução acontecem no aparelho. Inglês dos EUA e do Reino Unido compartilham os mesmos modelos de idioma.", 12f, 0xFF8297AC.toInt()).apply {
            setPadding(dp(4), dp(8), dp(4), dp(18))
        })
    }

    private fun addPack(pack: LanguagePack) {
        val card = panel(0xFF11283E.toInt(), 18)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(pack.flag, 29f, Color.WHITE).apply { setPadding(0, 0, dp(12), 0) })
        val nameColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        nameColumn.addView(text(pack.label, 16f, Color.WHITE, true))
        nameColumn.addView(text(if (pack.mlCode == TranslateLanguage.ENGLISH) "Inglês → Português" else "${pack.label} → Português", 12f, 0xFF91A9BD.toInt()).apply {
            setPadding(0, dp(3), 0, 0)
        })
        top.addView(nameColumn)
        card.addView(top)

        val status = text("Verificando pacotes…", 12f, 0xFF9FB2C5.toInt()).apply { setPadding(0, dp(12), 0, dp(8)) }
        card.addView(status)
        val progress = ProgressBar(this).apply { visibility = View.GONE }
        card.addView(progress)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val install = Button(this).apply {
            text = "Instalar"
            isAllCaps = false
            setTextColor(Color.rgb(5, 25, 37))
            background = rounded(0xFF55D6C2.toInt(), 12)
        }
        val use = Button(this).apply {
            text = "Selecionar"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(0xFF29445D.toInt(), 12)
        }
        actions.addView(install, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(7) })
        actions.addView(use, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(7) })
        card.addView(actions)
        container.addView(card, matchWrap().apply { bottomMargin = dp(11) })

        fun refresh() {
            val installed = isInstalled(pack)
            status.text = if (installed) "✓ Pacotes instalados neste aparelho" else "Pacote ainda não instalado"
            status.setTextColor(if (installed) 0xFF70E0C6.toInt() else 0xFF9FB2C5.toInt())
            install.text = if (installed) "Instalado" else "Instalar pacote"
            install.isEnabled = !installed
            install.alpha = if (installed) 0.6f else 1f
            use.isEnabled = installed
            use.alpha = if (installed) 1f else 0.48f
        }
        refresh()
        install.setOnClickListener {
            install.isEnabled = false
            use.isEnabled = false
            progress.visibility = View.VISIBLE
            status.text = "Baixando modelos… mantenha a internet ligada."
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
                .putString(KEY_LABEL, "${pack.flag}  ${pack.label}")
                .apply()
            Toast.makeText(this, "${pack.flag} ${pack.label} selecionado. A tradução será sempre para português.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun installPack(pack: LanguagePack, done: (Boolean, String) -> Unit) {
        fun downloadOcrModel() {
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
                        connection.inputStream.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                    } finally { connection.disconnect() }
                    if (temp.length() < 100_000L) {
                        temp.delete()
                        throw IllegalStateException("O arquivo OCR recebido parece incompleto.")
                    }
                    if (target.exists()) target.delete()
                    if (!temp.renameTo(target)) throw IllegalStateException("Não consegui salvar o pacote OCR.")
                    prefs.edit().putBoolean(installedKey(pack), true).apply()
                    done(true, "Pacote de reconhecimento de ${pack.label} instalado.")
                } catch (e: Exception) {
                    done(false, "Falha no OCR de ${pack.label}: ${e.message ?: "erro de download"}.")
                }
            }.start()
        }

        // Português é o idioma final: precisa de OCR e voz, mas não de tradução para si mesmo.
        if (pack.mlCode == TranslateLanguage.PORTUGUESE) {
            downloadOcrModel()
            return
        }

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(pack.mlCode)
                .setTargetLanguage(TranslateLanguage.PORTUGUESE)
                .build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.close()
                downloadOcrModel()
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

    private fun panel(color: Int, radius: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(color, radius)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), 0xFF203B52.toInt())
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}