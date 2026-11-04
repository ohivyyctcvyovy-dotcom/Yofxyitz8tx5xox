package com.example.telaleitor

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.WindowManager
import android.view.Gravity
import android.view.MotionEvent
import android.graphics.Typeface
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

class ScreenReaderService : Service() {

    companion object {
        const val ACTION_START = "com.example.telaleitor.START"
        const val ACTION_SELECT = "com.example.telaleitor.SELECT"
        const val ACTION_STOP = "com.example.telaleitor.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_LANG = "lang"
        private const val CHANNEL_ID = "leitor_tela"
        private const val NOTIF_ID = 1
        private const val TAG = "ScreenReader"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var overlay: SelectionView? = null
    private var floatingButton: TextView? = null
    private var sourceLang: String = TranslateLanguage.JAPANESE
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var screenW = 0
    private var screenH = 0
    private var selectionModeVision = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "Captura encerrada pelo sistema")
            handler.post { stopSelf() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) configurePortugueseVoice()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground()
                sourceLang = intent.getStringExtra(EXTRA_LANG) ?: TranslateLanguage.JAPANESE
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = readDataExtra(intent)
                if (data == null) {
                    toast("Captura de tela não autorizada")
                    stopSelf()
                    return START_NOT_STICKY
                }
                releaseCapture()
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val p = mpm.getMediaProjection(resultCode, data)
                projection = p
                if (Build.VERSION.SDK_INT >= 34) p.registerCallback(projectionCallback, handler)
                setupCapture()
                showFloatingButton()
            }
            ACTION_SELECT -> {
                if (projection == null) toast("Inicie a leitura pelo app primeiro")
                else { selectionModeVision = false; showSelection() }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        removeFloatingButton()
        releaseCapture()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun readDataExtra(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_DATA)

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIF_ID, notification)
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val selectPi = PendingIntent.getService(this, 1, Intent(this, ScreenReaderService::class.java).setAction(ACTION_SELECT), flags)
        val stopPi = PendingIntent.getService(this, 2, Intent(this, ScreenReaderService::class.java).setAction(ACTION_STOP), flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Leitor 455 ativo")
            .setContentText("① Tela: ler texto • ② Texto: selecionar • ③ Visão: descrever elementos")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_crop, "Selecionar texto", selectPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Encerrar", stopPi)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Leitor de tela", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun setupCapture() {
        val p = projection ?: return
        val metrics = resources.displayMetrics
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        reader = r
        virtualDisplay = p.createVirtualDisplay("ScreenReader", screenW, screenH, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)
    }

    private fun releaseCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        val p = projection
        projection = null
        if (p != null) {
            if (Build.VERSION.SDK_INT >= 34) try { p.unregisterCallback(projectionCallback) } catch (_: Exception) {}
            p.stop()
        }
    }

    // Botão segmentado: toque em cada faixa; arraste para reposicionar.
    private fun showFloatingButton() {
        if (floatingButton != null || !Settings.canDrawOverlays(this)) return
        val button = TextView(this).apply {
            text = "♛ 455  |  TELA  |  TEXTO  |  VISÃO  |  ×"
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(12, 14, 12, 14)
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(12, 12, 12))
                cornerRadius = 40f
                setStroke(2, android.graphics.Color.rgb(255, 205, 90))
            }
            elevation = 12f
            contentDescription = "Leitor 455. TELA lê a tela inteira. TEXTO seleciona uma região para ler e traduzir. VISÃO seleciona uma região para reconhecer objetos e elementos visuais localmente. X encerra. Arraste para mover."
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 12; y = 260 }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX - downX) > 8 || kotlin.math.abs(event.rawY - downY) > 8
                    if (!moved) {
                        val position = event.x / view.width.toFloat()
                        when {
                            position >= 0.91f -> stopSelf()
                            position >= 0.68f -> { selectionModeVision = true; showSelection() }
                            position >= 0.39f -> { selectionModeVision = false; showSelection() }
                            else -> readWholeScreen()
                        }
                    }
                    true
                }
                else -> true
            }
        }
        try { wm.addView(button, params); floatingButton = button }
        catch (e: Exception) { Log.e(TAG, "Não foi possível mostrar o botão flutuante", e) }
    }

    private fun removeFloatingButton() {
        floatingButton?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        floatingButton = null
    }

    private fun showSelection() {
        if (overlay != null) return
        overlay = SelectionView(this) { rect ->
            removeOverlay()
            if (rect != null) handler.postDelayed({ grabFrame(rect, 0) }, 400)
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            type, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        wm.addView(overlay, params)
    }

    private fun removeOverlay() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
    }

    private fun readWholeScreen() {
        if (projection == null) { say("A captura não está ativa. Inicie o leitor novamente."); return }
        grabWholeFrame(0)
    }

    private fun grabWholeFrame(tries: Int) {
        val r = reader
        if (r == null) { say("A captura não está ativa. Inicie o app de novo."); return }
        val image = r.acquireLatestImage()
        if (image == null) {
            if (tries < 6) handler.postDelayed({ grabWholeFrame(tries + 1) }, 250)
            else say("Não consegui capturar a tela. Tente novamente.")
            return
        }
        val full = imageToBitmap(image); image.close(); readText(full)
    }

    private fun grabFrame(rect: RectF, tries: Int) {
        val r = reader
        if (r == null) { say("A captura não está ativa. Inicie o app de novo."); return }
        val image = r.acquireLatestImage()
        if (image == null) {
            if (tries < 5) handler.postDelayed({ grabFrame(rect, tries + 1) }, 300)
            else say("Não consegui capturar a tela.")
            return
        }
        val full = imageToBitmap(image); image.close()
        val selected = cropBitmap(full, rect)
        if (selectionModeVision) analyzeVisual(selected) else readText(selected)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * screenW
        val bmp = Bitmap.createBitmap(screenW + rowPadding / plane.pixelStride, screenH, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        return Bitmap.createBitmap(bmp, 0, 0, screenW, screenH)
    }

    private fun cropBitmap(bmp: Bitmap, rect: RectF): Bitmap {
        val left = rect.left.toInt().coerceIn(0, bmp.width - 1)
        val top = rect.top.toInt().coerceIn(0, bmp.height - 1)
        val right = rect.right.toInt().coerceIn(left + 1, bmp.width)
        val bottom = rect.bottom.toInt().coerceIn(top + 1, bmp.height)
        return Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
    }

    // Análise visual offline: o modelo ML Kit é empacotado no APK; a imagem não é enviada à rede.
    private fun analyzeVisual(bitmap: Bitmap) {
        say("Analisando a região selecionada.")
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        labeler.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { labels ->
                labeler.close()
                val selected = labels.filter { it.confidence >= 0.48f }
                    .sortedByDescending { it.confidence }.take(7)
                if (selected.isEmpty()) {
                    say("Não consegui reconhecer objetos com confiança suficiente nessa região. Tente selecionar uma área maior e mais nítida.")
                } else {
                    val names = selected.map { translateVisualLabel(it.text) }.distinct()
                    val description = when (names.size) {
                        1 -> "Na região selecionada, identifiquei: ${names[0]}."
                        else -> "Na região selecionada, identifiquei estes elementos: ${names.joinToString(", ")}."
                    }
                    say("$description A análise é feita no próprio aparelho e pode não compreender toda a situação ou o contexto da imagem.")
                }
            }
            .addOnFailureListener { error ->
                labeler.close()
                Log.e(TAG, "Falha na análise visual local", error)
                say("Não consegui analisar essa imagem. Tente novamente com uma região mais clara.")
            }
    }

    private fun translateVisualLabel(label: String): String {
        val known = mapOf(
            "Person" to "pessoa", "People" to "pessoas", "Human" to "pessoa",
            "Clothing" to "roupa", "Footwear" to "calçado", "Face" to "rosto",
            "Car" to "carro", "Vehicle" to "veículo", "Bicycle" to "bicicleta",
            "Motorcycle" to "motocicleta", "Dog" to "cachorro", "Cat" to "gato",
            "Animal" to "animal", "Bird" to "pássaro", "Food" to "comida",
            "Plant" to "planta", "Tree" to "árvore", "Flower" to "flor",
            "Building" to "prédio", "House" to "casa", "Furniture" to "móvel",
            "Electronics" to "eletrônico", "Computer" to "computador", "Laptop" to "notebook",
            "Phone" to "celular", "Screen" to "tela", "Text" to "texto",
            "Book" to "livro", "Water" to "água", "Sky" to "céu",
            "Outdoor" to "ambiente externo", "Indoor" to "ambiente interno",
            "Sports" to "esporte", "Sport" to "esporte", "Toy" to "brinquedo",
            "Wheel" to "roda", "Table" to "mesa", "Chair" to "cadeira"
        )
        return known[label] ?: label.replace('_', ' ').lowercase(Locale("pt", "BR"))
    }

    private fun readText(bitmap: Bitmap) {
        val recognizer = when (sourceLang) {
            TranslateLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            TranslateLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                recognizer.close()
                val text = result.text.trim()
                Log.d(TAG, "OCR: $text")
                if (text.isEmpty()) say("Não encontrei texto na área selecionada.") else translate(text)
            }
            .addOnFailureListener { e ->
                recognizer.close(); Log.e(TAG, "Erro no OCR", e); say("Erro ao reconhecer o texto.")
            }
    }

    private fun translate(text: String) {
        val translator = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang).setTargetLanguage(TranslateLanguage.PORTUGUESE).build())
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translated ->
                        translator.close(); Log.d(TAG, "Tradução: $translated")
                        toast("$text\n\n→ $translated"); say(translated)
                    }
                    .addOnFailureListener { e ->
                        translator.close(); Log.e(TAG, "Erro na tradução", e); say("Erro na tradução.")
                    }
            }
            .addOnFailureListener { e ->
                translator.close(); Log.e(TAG, "Falha ao baixar o modelo de tradução", e)
                say("Não consegui baixar o modelo de tradução. Verifique a internet.")
            }
    }

    private fun configurePortugueseVoice() {
        val engine = tts ?: return
        engine.setLanguage(Locale("pt", "BR")); engine.setSpeechRate(1.18f); engine.setPitch(1.0f)
        val brazilianVoice = engine.voices
            ?.filter { it.locale.language == "pt" && it.locale.country.equals("BR", true) }
            ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired })
            ?.firstOrNull()
        if (brazilianVoice != null) engine.voice = brazilianVoice
    }

    private fun say(message: String) {
        handler.post {
            val engine = tts
            if (!ttsReady || engine == null) { toast(message); return@post }
            val remaining = message.trim()
            if (remaining.isEmpty()) return@post
            var start = 0; var first = true
            while (start < remaining.length) {
                var end = (start + 3200).coerceAtMost(remaining.length)
                if (end < remaining.length) {
                    val breakAt = remaining.lastIndexOf(' ', end)
                    if (breakAt > start + 1200) end = breakAt
                }
                val part = remaining.substring(start, end).trim()
                if (part.isNotEmpty()) {
                    engine.speak(part, if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "leitura_$start")
                    first = false
                }
                start = end
                while (start < remaining.length && remaining[start].isWhitespace()) start++
            }
        }
    }

    private fun toast(message: String) {
        handler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }
}