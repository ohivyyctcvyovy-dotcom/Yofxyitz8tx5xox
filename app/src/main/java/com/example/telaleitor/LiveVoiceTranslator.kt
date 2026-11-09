package com.example.telaleitor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Captura somente áudio de reprodução permitido pelo Android e reconhece fala
 * localmente com SenseVoice. O modelo é baixado uma vez e fica no armazenamento
 * privado do aplicativo; a inferência não envia áudio para servidores.
 */
class LiveVoiceTranslator(
    private val filesDir: File,
    private val projection: MediaProjection,
    private val onTranscript: (text: String, language: String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "AuroraLiveVoice"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SECONDS = 3
        // Revisão fixa para não depender de alterações futuras no branch "main".
        private const val MODEL_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07"
    }

    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    // Reconhecimento separado da captura para evitar bloquear a leitura do áudio.
    // A fila limitada descarta trechos antigos se o aparelho não acompanhar o vídeo.
    private val recognitionWorker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(2),
        ThreadPoolExecutor.DiscardOldestPolicy()
    )
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var recognizer: OfflineRecognizer? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker.execute {
            try {
                val modelDir = File(filesDir, "speech-models/sensevoice")
                if (!modelDir.exists() && !modelDir.mkdirs()) {
                    error("Não foi possível criar a pasta do modelo de voz.")
                }
                val model = File(modelDir, "model.int8.onnx")
                val tokens = File(modelDir, "tokens.txt")
                if (!model.exists() || model.length() < 100_000_000L) {
                    download("$MODEL_BASE/model.int8.onnx", model)
                }
                if (!tokens.exists() || tokens.length() < 100_000L) {
                    download("$MODEL_BASE/tokens.txt", tokens)
                }
                if (!running.get()) return@execute

                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = model.absolutePath,
                            language = "auto",
                            useInverseTextNormalization = true
                        ),
                        tokens = tokens.absolutePath,
                        numThreads = 2,
                        debug = false
                    ),
                    decodingMethod = "greedy_search"
                )
                recognizer = OfflineRecognizer(config = config)
                try {
                    captureLoop()
                } finally {
                    try { recognizer?.release() } catch (_: Exception) {}
                    recognizer = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Falha na tradução de voz ao vivo", e)
                onError(e.message ?: "Não foi possível iniciar o reconhecimento de voz offline.")
                stop()
            }
        }
    }

    private fun download(address: String, destination: File) {
        onStatus("Baixando o modelo de voz offline (aprox. 228 MB) pela primeira vez. Mantenha a internet ativa.")
        val temp = File(destination.parentFile, destination.name + ".part")
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Aurora455/1.0")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("Download do modelo de voz falhou: HTTP ${connection.responseCode}. Endereço: $address")
            }
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var downloaded = 0L
                    while (running.get()) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                    }
                    output.fd.sync()
                    val expected = connection.contentLengthLong
                    if (running.get() && expected > 0L && downloaded != expected) {
                        error("Download incompleto do modelo de voz: $downloaded de $expected bytes.")
                    }
                }
            }
            if (!running.get()) return
            if (!temp.exists() || temp.length() < 100_000L) error("O arquivo do modelo de voz ficou incompleto.")
            if (destination.exists()) destination.delete()
            if (!temp.renameTo(destination)) error("Não foi possível salvar o modelo de voz.")
        } finally {
            connection.disconnect()
            if (temp.exists() && !destination.exists()) temp.delete()
        }
    }

    private fun captureLoop() {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) error("O Android não disponibilizou um buffer de áudio válido.")
        // Reserva espaço para o reconhecedor processar um trecho sem descartar
        // imediatamente o áudio que continua chegando do vídeo.
        val bufferBytes = maxOf(minBuffer * 2, SAMPLE_RATE * 2 * CHUNK_SECONDS * 2)
        val audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        recorder = audioRecord
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            recorder = null
            error("Não foi possível capturar o áudio interno. O aplicativo de vídeo pode bloquear a captura.")
        }

        val chunkSamples = SAMPLE_RATE * CHUNK_SECONDS
        val shortBuffer = ShortArray(4096)
        val chunk = ShortArray(chunkSamples)
        var filled = 0
        audioRecord.startRecording()
        onStatus("Áudio interno conectado. Aguardando fala do vídeo…")
        try {
            while (running.get()) {
                val count = audioRecord.read(shortBuffer, 0, shortBuffer.size, AudioRecord.READ_BLOCKING)
                if (count == AudioRecord.ERROR_DEAD_OBJECT) {
                    error("O Android encerrou a captura de áudio. Reinicie a leitura e tente novamente.")
                }
                if (count < 0) {
                    error("Falha ao ler o áudio interno (código $count).")
                }
                if (count == 0) continue
                var offset = 0
                while (offset < count && filled < chunk.size) {
                    val copy = minOf(count - offset, chunk.size - filled)
                    System.arraycopy(shortBuffer, offset, chunk, filled, copy)
                    filled += copy
                    offset += copy
                }
                if (filled == chunk.size) {
                    if (running.get()) {
                        val completedChunk = chunk.copyOf()
                        try {
                            recognitionWorker.execute {
                                if (running.get()) {
                                    try {
                                        recognizeChunk(completedChunk)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Falha ao reconhecer trecho de áudio", e)
                                        onError("O reconhecimento de voz falhou: ${e.localizedMessage ?: "erro interno do SenseVoice"}.")
                                    }
                                }
                            }
                        } catch (_: java.util.concurrent.RejectedExecutionException) {
                            // O usuário pode ter parado a leitura exatamente neste trecho.
                        }
                    }
                    filled = 0
                }
            }
        } finally {
            try { audioRecord.stop() } catch (_: Exception) {}
            audioRecord.release()
            recorder = null
        }
    }

    private var silentChunks = 0
    private var silenceWarningShown = false

    private fun recognizeChunk(samples: ShortArray) {
        var energy = 0.0
        for (sample in samples) {
            val value = sample.toDouble() / 32768.0
            energy += value * value
        }
        val rms = sqrt(energy / samples.size)
        if (rms < 0.006) {
            silentChunks++
            Log.d(TAG, "Trecho sem áudio útil; RMS=$rms")
            if (silentChunks >= 5 && !silenceWarningShown) {
                silenceWarningShown = true
                onStatus("Ainda não recebi som do vídeo. Confira se ele está tocando e se o aplicativo permite captura de áudio interno.")
            }
            return
        }
        silentChunks = 0
        silenceWarningShown = false

        val localRecognizer = recognizer ?: return
        val floats = FloatArray(samples.size) { samples[it].toFloat() / 32768.0f }
        val stream = localRecognizer.createStream()
        try {
            stream.acceptWaveform(floats, SAMPLE_RATE)
            localRecognizer.decode(stream)
            val result = localRecognizer.getResult(stream)
            val text = result.text.trim()
            // SenseVoice retorna tags especiais como "<|ja|>" e "<|zh|>",
            // não os nomes "Japanese"/"Chinese". Remova os delimitadores
            // antes de encaminhar o idioma ao tradutor SLIMT.
            val rawLanguage = result.lang.trim().lowercase()
            val languageTag = rawLanguage
                .removePrefix("<|")
                .removeSuffix("|>")
                .substringBefore('-')
                .trim()
            val language = when (languageTag) {
                "en", "english" -> "en"
                "ja", "japanese" -> "ja"
                "ko", "korean" -> "ko"
                "zh", "chinese", "yue", "cantonese" -> "zh"
                else -> ""
            }
            Log.d(TAG, "SenseVoice retornou idioma='$rawLanguage', texto=" + text.length + " caracteres")
            if (text.length >= 2 && language in setOf("en", "ja", "ko", "zh")) {
                Log.d(TAG, "Trecho reconhecido ($language): $text")
                onTranscript(text, language)
            } else if (text.length >= 2) {
                Log.w(TAG, "Trecho descartado: idioma SenseVoice não compatível: $rawLanguage")
                onStatus("A fala foi reconhecida, mas o idioma retornado ($rawLanguage) não está mapeado para tradução.")
            } else {
                Log.d(TAG, "Nenhuma fala reconhecida neste trecho.")
            }
        } finally {
            stream.release()
        }
    }

    fun stop() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        recorder = null
        recognitionWorker.shutdownNow()
        worker.shutdownNow()
    }
}