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
                val chunks = splitForTranslation(text, source)
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

    // Trechos menores preservam mais contexto útil por frase e evitam
    // cortar japonês/chinês em posições arbitrárias quando não há espaços.
    private fun splitForTranslation(input: String, language: String): List<String> {
        val usesSpaces = language == "en" || language == "ko"
        val limit = if (language == "ja" || language == "zh") 180 else 240
        val normalized = input
            .replace(Regex("[\\t\\r]+"), " ")
            .replace(Regex("\\n+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
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

        fun splitLongSentence(sentence: String) {
            var offset = 0
            while (offset < sentence.length) {
                var end = (offset + limit).coerceAtMost(sentence.length)
                if (end < sentence.length) {
                    val boundary = if (usesSpaces) {
                        sentence.lastIndexOf(' ', end - 1)
                    } else {
                        sentence.lastIndexOfAny(charArrayOf('、', '，', ',', '；', ':', '：', '。', '！', '？'), end - 1)
                    }
                    if (boundary > offset + limit / 2) end = boundary + 1
                    if (end < sentence.length && end > offset && sentence[end - 1].isHighSurrogate()) end--
                }
                if (end <= offset) end = (offset + limit).coerceAtMost(sentence.length)
                val part = sentence.substring(offset, end).trim()
                if (part.isNotEmpty()) chunks.add(part)
                offset = end
                while (offset < sentence.length && sentence[offset].isWhitespace()) offset++
            }
        }

        for (sentence in sentences) {
            if (sentence.length > limit) {
                flush()
                splitLongSentence(sentence)
            } else {
                val separator = if (current.isNotEmpty() && usesSpaces) 1 else 0
                if (current.isNotEmpty() && current.length + separator + sentence.length > limit) flush()
                if (current.isNotEmpty() && usesSpaces) current.append(' ')
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
