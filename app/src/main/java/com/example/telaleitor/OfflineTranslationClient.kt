package com.example.telaleitor

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Tradução no próprio aparelho com ML Kit.
 * Os modelos são baixados pelo Aurora no primeiro uso; depois ficam disponíveis offline.
 * O texto reconhecido nunca é enviado para um serviço de tradução na nuvem.
 */
class OfflineTranslationClient(context: Context) {
    private val appContext = context.applicationContext

    fun translate(
        text: String,
        sourceLanguage: String?,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val source = toMlKitLanguage(sourceLanguage)
        if (source == null) {
            onError("Não consegui identificar o idioma. Selecione inglês, japonês, coreano ou chinês e tente novamente.")
            return
        }
        if (source == TranslateLanguage.PORTUGUESE) {
            onSuccess(text)
            return
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(TranslateLanguage.PORTUGUESE)
            .build()
        val translator: Translator = Translation.getClient(options)
        val conditions = DownloadConditions.Builder().build()

        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translated ->
                        translator.close()
                        val result = translated.trim()
                        if (result.isBlank()) onError("O tradutor não retornou texto.")
                        else onSuccess(result)
                    }
                    .addOnFailureListener { error ->
                        translator.close()
                        onError("Falha ao traduzir: ${error.localizedMessage ?: "tente novamente"}.")
                    }
            }
            .addOnFailureListener { error ->
                translator.close()
                onError(
                    "Não foi possível baixar o modelo de idioma. Conecte à internet uma vez, " +
                    "tente novamente e depois a tradução funcionará offline. " +
                    "Detalhe: ${error.localizedMessage ?: "download não concluído"}"
                )
            }
    }

    private fun toMlKitLanguage(code: String?): String? = when (code?.lowercase()) {
        "en" -> TranslateLanguage.ENGLISH
        "ja" -> TranslateLanguage.JAPANESE
        "ko" -> TranslateLanguage.KOREAN
        "zh", "zh-cn", "zh-tw" -> TranslateLanguage.CHINESE
        "pt" -> TranslateLanguage.PORTUGUESE
        else -> null
    }
}
