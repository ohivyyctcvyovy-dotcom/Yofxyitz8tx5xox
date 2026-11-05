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

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }

    fun translate(
        text: String,
        sourceLanguage: String?,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val source = normalizeLanguage(sourceLanguage)
        if (source == null) {
            onError("Idioma não compatível com os modelos offline atuais. São aceitos inglês, japonês, coreano e chinês; português é mantido sem tradução.")
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
                // O idioma intermediário é inglês, mas o pacote obrigatório de destino
                // é inglês -> português. O catálogo associa esse pacote ao idioma "pt".
                if (source != "en") {
                    val sourceInstall = NativeOfflineTranslator.installLanguage(source)
                    if (sourceInstall != "OK") error(sourceInstall.removePrefix("ERROR:"))
                }
                val targetInstall = NativeOfflineTranslator.installLanguage("pt")
                if (targetInstall != "OK") error(targetInstall.removePrefix("ERROR:"))
                // O modelo SLIMT pode falhar com blocos muito longos. Traduzimos
                // em partes menores, preservando a ordem e os limites das frases.
                val chunks = splitForTranslation(text)
                val translated = chunks.map { chunk ->
                    val part = NativeOfflineTranslator.translate(source, chunk)
                    if (part.startsWith("ERROR:")) error(part.removePrefix("ERROR:"))
                    if (part.isBlank()) error("O tradutor não retornou um trecho.")
                    part.trim()
                }.joinToString(" ").trim()
                if (translated.isBlank()) error("O tradutor não retornou texto.")
                onSuccess(translated)
            } catch (e: LinkageError) {
                Log.e("AuroraSLIMT", "Biblioteca nativa SLIMT indisponível", e)
                onError("O motor nativo de tradução não conseguiu iniciar neste aparelho. Reinstale a versão mais recente do Aurora 455.")
            } catch (e: Exception) {
                Log.e("AuroraSLIMT", "Falha na tradução offline", e)
                onError("Falha no tradutor offline: ${e.localizedMessage ?: "tente novamente"}.")
            }
        }
    }

    private fun splitForTranslation(input: String, limit: Int = 450): List<String> {
        val normalized = input.replace(Regex("[\\t\\r]+"), " ").trim()
        if (normalized.length <= limit) return listOf(normalized)

        val sentences = normalized.split(Regex("(?<=[.!?。！？；;])"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                current.setLength(0)
            }
        }

        for (sentence in sentences) {
            if (sentence.length > limit) {
                flush()
                var offset = 0
                while (offset < sentence.length) {
                    var end = (offset + limit).coerceAtMost(sentence.length)
                    if (end < sentence.length) {
                        val boundary = sentence.lastIndexOf(' ', end - 1)
                        if (boundary > offset + limit / 2) end = boundary
                    }
                    if (end < sentence.length && end > offset && sentence[end - 1].isHighSurrogate()) end--
                    if (end <= offset) end = (offset + limit).coerceAtMost(sentence.length)
                    chunks.add(sentence.substring(offset, end).trim())
                    offset = end
                    while (offset < sentence.length && sentence[offset].isWhitespace()) offset++
                }
            } else {
                val separator = if (current.isNotEmpty()) 1 else 0
                if (current.length + separator + sentence.length > limit) flush()
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
        }
        flush()
        return chunks.filter { it.isNotBlank() }
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
