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
        private const val CHUNK_SECONDS = 6
        private const val MODEL_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/"
    }

    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
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
                error("Download do modelo de voz falhou: HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (running.get()) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
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
                if (count <= 0) continue
                var offset = 0
                while (offset < count && filled < chunk.size) {
                    val copy = minOf(count - offset, chunk.size - filled)
                    System.arraycopy(shortBuffer, offset, chunk, filled, copy)
                    filled += copy
                    offset += copy
                }
                if (filled == chunk.size) {
                    if (running.get()) recognizeChunk(chunk)
                    filled = 0
                }
            }
        } finally {
            try { audioRecord.stop() } catch (_: Exception) {}
            audioRecord.release()
            recorder = null
        }
    }

    private fun recognizeChunk(samples: ShortArray) {
        var energy = 0.0
        for (sample in samples) {
            val value = sample.toDouble() / 32768.0
            energy += value * value
        }
        val rms = sqrt(energy / samples.size)
        if (rms < 0.006) return

        val localRecognizer = recognizer ?: return
        val floats = FloatArray(samples.size) { samples[it].toFloat() / 32768.0f }
        val stream = localRecognizer.createStream()
        try {
            stream.acceptWaveform(floats, SAMPLE_RATE)
            localRecognizer.decode(stream)
            val result = localRecognizer.getResult(stream)
            val text = result.text.trim()
            val rawLanguage = result.lang.trim().lowercase()
            val language = when (rawLanguage) {
                "english" -> "en"
                "japanese" -> "ja"
                "korean" -> "ko"
                "chinese", "cantonese", "yue" -> "zh"
                else -> rawLanguage.substringBefore('-')
            }
            if (text.length >= 2 && language in setOf("en", "ja", "ko", "zh")) {
                Log.d(TAG, "Trecho reconhecido ($language): $text")
                onTranscript(text, language)
            } else if (text.length >= 2) {
                Log.d(TAG, "Trecho reconhecido sem idioma compatível: $rawLanguage")
            }
        } finally {
            stream.release()
        }
    }

    fun stop() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        recorder = null
    }
}