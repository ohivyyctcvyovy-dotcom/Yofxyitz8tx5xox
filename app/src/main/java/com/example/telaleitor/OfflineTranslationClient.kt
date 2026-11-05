package com.example.telaleitor

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * SLIMT offline translation engine (translator-rs).
 * Translation models are installed into app-private storage on first use.
 */
class OfflineTranslationClient(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()

    fun translate(
        text: String,
        sourceLanguage: String?,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val source = normalizeLanguage(sourceLanguage)
        if (source == null) {
            onError("Selecione inglês, japonês, coreano ou chinês.")
            return
        }
        if (source == "pt") {
            onSuccess(text)
            return
        }
        executor.execute {
            try {
                val initialized = NativeOfflineTranslator.initialize(appContext)
                if (!initialized) error("Não foi possível iniciar o motor SLIMT.")
                // The catalog routes Japanese, Korean and Chinese through English.
                val sourceInstall = NativeOfflineTranslator.installLanguage(source)
                if (sourceInstall != "OK") error(sourceInstall.removePrefix("ERROR:"))
                if (source != "en") {
                    val englishInstall = NativeOfflineTranslator.installLanguage("en")
                    if (englishInstall != "OK") error(englishInstall.removePrefix("ERROR:"))
                }
                val translated = NativeOfflineTranslator.translate(source, text)
                if (translated.startsWith("ERROR:")) error(translated.removePrefix("ERROR:"))
                if (translated.isBlank()) error("O tradutor não retornou texto.")
                onSuccess(translated.trim())
            } catch (e: Exception) {
                Log.e("AuroraSLIMT", "Falha na tradução offline", e)
                onError("Falha no tradutor offline: ${e.localizedMessage ?: "tente novamente"}.")
            }
        }
    }

    private fun normalizeLanguage(code: String?): String? = when (code?.lowercase()) {
        "en", "english" -> "en"
        "ja", "ja-jp", "japanese" -> "ja"
        "ko", "ko-kr", "korean" -> "ko"
        "zh", "zh-cn", "zh-hans", "zh-tw", "zh-hant", "chinese" -> "zh"
        "pt", "pt-br", "portuguese" -> "pt"
        else -> null
    }
}
