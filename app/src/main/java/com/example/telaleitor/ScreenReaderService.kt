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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
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
import android.view.View
import android.view.ViewGroup
import android.graphics.Typeface
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
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
    private var floatingExpanded = false
    private var resultPanel: View? = null
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
        removeResultPanel()
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

    // Botão compacto: fica como um pequeno ícone e só abre as ações quando tocado.
    private fun showFloatingButton() {
        if (floatingButton != null || !Settings.canDrawOverlays(this)) return
        floatingExpanded = false
        val button = TextView(this).apply {
            text = "♛"
            textSize = 20f
            setTextColor(android.graphics.Color.rgb(255, 205, 90))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(15, 15, 15))
                cornerRadius = 100f
                setStroke(2, android.graphics.Color.rgb(255, 205, 90))
            }
            elevation = 12f
            contentDescription = "Aurora 455. Toque para abrir as ferramentas ou arraste para mover."
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(54, 54, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 12; y = 260 }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 5 || kotlin.math.abs(dy) > 5) {
                        params.x = startX + dx; params.y = startY + dy
                        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX - downX) > 8 || kotlin.math.abs(event.rawY - downY) > 8
                    if (!moved) {
                        if (!floatingExpanded) {
                            floatingExpanded = true
                            button.text = "TELA\nTEXTO\nVISÃO\n×"
                            button.textSize = 12f
                            button.setTextColor(android.graphics.Color.WHITE)
                            button.setPadding(10, 8, 10, 8)
                            params.width = 86; params.height = 190
                            button.contentDescription = "TELA lê a tela. TEXTO seleciona uma área para reconhecer e traduzir. VISÃO identifica elementos. X encerra."
                            try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                        } else {
                            val fraction = event.y / view.height.toFloat().coerceAtLeast(1f)
                            when {
                                fraction < 0.27f -> { floatingExpanded = false; button.text = "♛"; button.textSize = 20f; button.setTextColor(android.graphics.Color.rgb(255,205,90)); button.setPadding(0,0,0,0); params.width=54; params.height=54; wm.updateViewLayout(view,params); readWholeScreen() }
                                fraction < 0.52f -> { floatingExpanded = false; collapseFloatingButton(button, params); selectionModeVision = false; showSelection() }
                                fraction < 0.77f -> { floatingExpanded = false; collapseFloatingButton(button, params); selectionModeVision = true; showSelection() }
                                else -> stopSelf()
                            }
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

    private fun collapseFloatingButton(button: TextView, params: WindowManager.LayoutParams) {
        button.text = "♛"
        button.textSize = 20f
        button.setTextColor(android.graphics.Color.rgb(255, 205, 90))
        button.setPadding(0, 0, 0, 0)
        params.width = 54; params.height = 54
        try { wm.updateViewLayout(button, params) } catch (_: Exception) {}
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

    // Análise visual local combinando rótulos de objetos e leitura do texto visível.
    // Isto identifica elementos e palavras, mas não inventa uma narrativa que o modelo não detectou.
    private fun analyzeVisual(bitmap: Bitmap) {
        say("Analisando a região selecionada.")
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        val recognizer = when (sourceLang) {
            TranslateLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            TranslateLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            TranslateLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        var labelsDone = false
        var textDone = false
        var labelNames = emptyList<String>()
        var visibleText = ""
        var failed = false
        fun finishIfReady() {
            if (!labelsDone || !textDone) return
            labeler.close()
            recognizer.close()
            val parts = mutableListOf<String>()
            if (labelNames.isNotEmpty()) parts.add("Elementos que consegui identificar: ${labelNames.joinToString(", ")}.")
            if (visibleText.isNotBlank()) parts.add("Texto visível na imagem: ${visibleText.take(900)}.")
            val description = parts.joinToString(" ")
            if (description.isBlank()) {
                say("Não consegui identificar elementos com segurança nessa área. Tente selecionar uma região maior, mais nítida e sem movimento.")
            } else {
                say("$description A análise é feita no aparelho. Ela identifica elementos e texto, mas não compreende com segurança toda a ação ou o contexto do vídeo.")
                showResultPanel("ANÁLISE VISUAL\nElementos: ${labelNames.joinToString(", ").ifBlank { "não identificados" }}\n\nTexto detectado:\n${visibleText.ifBlank { "nenhum texto legível" }}", description, "ANÁLISE DA IMAGEM", "DESCRIÇÃO DOS ELEMENTOS")
            }
        }
        labeler.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { labels ->
                labelNames = labels.filter { it.confidence >= 0.30f }
                    .sortedByDescending { it.confidence }.take(8)
                    .map { translateVisualLabel(it.text) }.distinct()
                labelsDone = true
                finishIfReady()
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Falha nos rótulos visuais", error)
                labelsDone = true
                finishIfReady()
            }
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                visibleText = result.textBlocks.joinToString("\n") { it.text.trim() }.trim()
                textDone = true
                finishIfReady()
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Falha ao ler texto na análise visual", error)
                textDone = true
                finishIfReady()
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

    // Gera uma versão ampliada em tons de cinza para comparar com a original e a colorida.
    private fun prepareBitmapForOcr(source: Bitmap, grayscale: Boolean = false): Bitmap {
        val maxDimension = 2600f
        val scale = minOf(2.0f, maxDimension / maxOf(source.width, source.height).toFloat()).coerceAtLeast(1.0f)
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val contrast = if (grayscale) 1.32f else 1.22f
        val offset = 128f * (1f - contrast)
        val matrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, offset, 0f, contrast, 0f, 0f, offset,
            0f, 0f, contrast, 0f, offset, 0f, 0f, 0f, 1f, 0f
        ))
        if (grayscale) matrix.setSaturation(0f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        Canvas(out).drawBitmap(source, Rect(0, 0, source.width, source.height), Rect(0, 0, w, h), paint)
        return out
    }

    private fun readText(bitmap: Bitmap) {
        val recognizer = when (sourceLang) {
            TranslateLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            TranslateLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            TranslateLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        val enhanced = prepareBitmapForOcr(bitmap, false)
        val grayscale = prepareBitmapForOcr(bitmap, true)
        val candidates = listOf(bitmap, enhanced, grayscale)

        fun score(text: String): Int {
            if (text.isBlank()) return Int.MIN_VALUE
            val useful = text.count { it.isLetterOrDigit() }
            val lines = text.count { it == '\n' }
            val replacements = text.count { it == '\uFFFD' }
            val controls = text.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
            val repeated = Regex("(.)\\1{4,}").findAll(text).count()
            return useful * 4 + lines * 3 - replacements * 25 - controls * 15 - repeated * 8
        }
        fun finish(bestText: String) {
            recognizer.close()
            enhanced.recycle()
            grayscale.recycle()
            val finalText = bestText.trim()
            Log.d(TAG, "OCR selecionado: $finalText")
            if (finalText.isEmpty()) say("Não encontrei texto legível. Tente selecionar uma área maior ou mais nítida.")
            else translate(finalText)
        }
        fun runPass(index: Int, bestText: String, bestScore: Int) {
            if (index >= candidates.size) { finish(bestText); return }
            recognizer.process(InputImage.fromBitmap(candidates[index], 0))
                .addOnSuccessListener { result ->
                    val found = result.textBlocks.joinToString("\n\n") { it.text.trim() }.trim()
                    val value = score(found)
                    if (value > bestScore) runPass(index + 1, found, value)
                    else runPass(index + 1, bestText, bestScore)
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Tentativa OCR ${index + 1} falhou", error)
                    runPass(index + 1, bestText, bestScore)
                }
        }
        runPass(0, "", Int.MIN_VALUE)
    }

    // Textos extensos são traduzidos em partes menores, mantendo os parágrafos.
    private fun splitParagraph(text: String, limit: Int = 700): List<String> {
        if (text.length <= limit) return listOf(text)
        val sentences = text.split(Regex("(?<=[.!?。！？；;])\\s*")).filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        var current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) { out.add(current.toString().trim()); current = StringBuilder() }
        }
        for (sentence in sentences) {
            if (sentence.length > limit) {
                flush()
                var i = 0
                while (i < sentence.length) {
                    var j = (i + limit).coerceAtMost(sentence.length)
                    if (j < sentence.length && sentence[j - 1].isHighSurrogate()) j--
                    if (j <= i) j = (i + limit).coerceAtMost(sentence.length)
                    out.add(sentence.substring(i, j))
                    i = j
                }
            } else {
                if (current.isNotEmpty() && current.length + sentence.length > limit) flush()
                if (current.isNotEmpty() && sourceLang == TranslateLanguage.ENGLISH) current.append(' ')
                current.append(sentence)
            }
        }
        flush()
        return out.filter { it.isNotBlank() }
    }

    private fun translate(text: String) {
        val translator = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang).setTargetLanguage(TranslateLanguage.PORTUGUESE).build())
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                val paragraphs = text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
                val translatedParagraphs = mutableListOf<String>()
                fun fail(error: Exception) {
                    translator.close()
                    Log.e(TAG, "Falha na tradução local", error)
                    showResultPanel(text, "Não foi possível concluir a tradução. O texto original foi preservado.")
                    say("Não consegui concluir a tradução. O texto reconhecido está na tela.")
                }
                fun nextParagraph(p: Int) {
                    if (p >= paragraphs.size) {
                        translator.close()
                        val result = translatedParagraphs.joinToString("\n\n")
                        showResultPanel(text, result)
                        say(result)
                        return
                    }
                    val parts = splitParagraph(paragraphs[p])
                    val translatedParts = mutableListOf<String>()
                    fun nextPart(i: Int) {
                        if (i >= parts.size) {
                            val joiner = if (sourceLang == TranslateLanguage.ENGLISH) " " else ""
                            translatedParagraphs.add(translatedParts.joinToString(joiner))
                            nextParagraph(p + 1)
                            return
                        }
                        translator.translate(parts[i])
                            .addOnSuccessListener { translatedParts.add(it); nextPart(i + 1) }
                            .addOnFailureListener { fail(it) }
                    }
                    nextPart(0)
                }
                nextParagraph(0)
            }
            .addOnFailureListener { error ->
                translator.close()
                Log.e(TAG, "Modelo de tradução indisponível", error)
                showResultPanel(text, "O modelo ainda não está instalado. Conecte-se uma vez para baixá-lo; depois poderá traduzir offline.")
                say("O modelo de tradução precisa ser baixado uma vez.")
            }
    }

    // Painel permite comparar e copiar o texto original e a tradução sem confundi-los.
    private fun showResultPanel(source: String, translated: String, sourceTitle: String = "TEXTO RECONHECIDO (ORIGINAL)", translatedTitle: String = "TRADUÇÃO PARA PORTUGUÊS") {
        handler.post {
            removeResultPanel()
            val gold = android.graphics.Color.rgb(255, 205, 90)
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 18, 20, 18)
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.rgb(13, 13, 13))
                    cornerRadius = 24f
                    setStroke(2, gold)
                }
            }
            val heading = TextView(this).apply {
                text = "AURORA 455  •  RESULTADO"
                textSize = 16f
                setTextColor(gold)
                typeface = Typeface.DEFAULT_BOLD
            }
            panel.addView(heading)
            val scroll = ScrollView(this)
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun section(label: String, value: String) {
                body.addView(TextView(this).apply {
                    text = label
                    textSize = 13f
                    setTextColor(gold)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 14, 0, 5)
                })
                body.addView(TextView(this).apply {
                    text = value
                    textSize = 15f
                    setTextColor(android.graphics.Color.WHITE)
                    setTextIsSelectable(true)
                    setPadding(0, 0, 0, 14)
                })
            }
            section(sourceTitle, source)
            section(translatedTitle, translated)
            scroll.addView(body)
            panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            panel.addView(Button(this).apply {
                text = "FECHAR"
                setTextColor(android.graphics.Color.BLACK)
                backgroundTintList = android.content.res.ColorStateList.valueOf(gold)
                setOnClickListener { removeResultPanel() }
            })
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(
                (screenW - 36).coerceAtLeast(280), (screenH * 0.78f).toInt().coerceAtLeast(420),
                type, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }
            try { wm.addView(panel, params); resultPanel = panel }
            catch (e: Exception) { Log.e(TAG, "Falha ao abrir painel de resultado", e); toast("Tradução concluída.") }
        }
    }

    private fun removeResultPanel() {
        resultPanel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        resultPanel = null
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