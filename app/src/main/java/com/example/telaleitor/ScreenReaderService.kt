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
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
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
        const val LANG_AUTO = "auto"
        const val LANG_JAPANESE = "ja"
        const val LANG_CHINESE = "zh"
        const val LANG_KOREAN = "ko"
        const val LANG_ENGLISH = "en"
        const val LANG_PORTUGUESE = "pt"
        private const val LANG_UNSUPPORTED = "unsupported"
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
    private var sourceLang: String = LANG_AUTO
    // Mantém a escolha do usuário separada do idioma detectado na leitura atual.
    private var autoDetectEnabled: Boolean = true
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var screenW = 0
    private var screenH = 0
    private var selectionModeVision = false
    private var displayManager: DisplayManager? = null
    private var savedButtonX = 12
    private var savedButtonY = 260
    private var orientationWidth = 0
    private var orientationHeight = 0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                handler.removeCallbacks(rotationRefresh)
                handler.postDelayed(rotationRefresh, 300)
            }
        }
    }

    private val rotationRefresh = Runnable {
        refreshCaptureForRotation()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "Captura encerrada pelo sistema")
            handler.post { stopSelf() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, handler)
        val prefs = getSharedPreferences("aurora_overlay", Context.MODE_PRIVATE)
        savedButtonX = prefs.getInt("button_x", 12)
        savedButtonY = prefs.getInt("button_y", 260)
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
                sourceLang = intent.getStringExtra(EXTRA_LANG) ?: LANG_AUTO
                autoDetectEnabled = sourceLang == LANG_AUTO
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
        displayManager?.unregisterDisplayListener(displayListener)
        handler.removeCallbacks(rotationRefresh)
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
        orientationWidth = screenW
        orientationHeight = screenH
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        reader = r
        virtualDisplay = p.createVirtualDisplay("ScreenReader", screenW, screenH, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)
    }

    // Recria a superfície de captura quando o aparelho muda entre retrato e paisagem.
    private fun refreshCaptureForRotation() {
        if (projection == null) return
        val metrics = resources.displayMetrics
        val newW = metrics.widthPixels
        val newH = metrics.heightPixels
        if (newW <= 0 || newH <= 0 || (newW == orientationWidth && newH == orientationHeight)) return

        screenW = newW
        screenH = newH
        orientationWidth = newW
        orientationHeight = newH

        val oldReader = reader
        val newReader = ImageReader.newInstance(newW, newH, PixelFormat.RGBA_8888, 2)
        reader = newReader
        try {
            virtualDisplay?.resize(newW, newH, metrics.densityDpi)
            virtualDisplay?.setSurface(newReader.surface)
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao ajustar a captura após girar a tela", error)
        }
        try { oldReader?.close() } catch (_: Exception) {}

        removeOverlay()
        removeFloatingMenu()
        floatingButton?.visibility = View.VISIBLE
        floatingButton?.let { button ->
            val params = button.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                params.x = savedButtonX.coerceIn(0, (newW - 54).coerceAtLeast(0))
                params.y = savedButtonY.coerceIn(0, (newH - 54).coerceAtLeast(0))
                try { wm.updateViewLayout(button, params) } catch (_: Exception) {}
                savedButtonX = params.x
                savedButtonY = params.y
            }
        }
        resultPanel?.let { panel ->
            val params = panel.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                params.width = (newW - 32).coerceAtLeast(240)
                params.height = (newH * 0.78f).toInt().coerceAtLeast(240)
                try { wm.updateViewLayout(panel, params) } catch (_: Exception) {}
            }
        }
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

    // Botão principal: toque curto lê a tela; toque longo abre as ferramentas.
    private var floatingMenu: View? = null

    private fun showFloatingButton() {
        if (floatingButton != null || !Settings.canDrawOverlays(this)) return
        val gold = android.graphics.Color.rgb(255, 51, 79)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(54, 54, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = savedButtonX.coerceIn(0, (resources.displayMetrics.widthPixels - 54).coerceAtLeast(0))
                y = savedButtonY.coerceIn(0, (resources.displayMetrics.heightPixels - 54).coerceAtLeast(0))
            }
        val button = TextView(this).apply {
            text = "♛"; textSize = 21f; setTextColor(gold); typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(15, 15, 15))
                cornerRadius = 100f
                setStroke(2, gold)
            }
            elevation = 12f
            contentDescription = "Aurora 455. Toque para selecionar uma área e ouvir a tradução. Toque e segure para abrir as ferramentas. Arraste para mover."
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressed = false
        val longPress = Runnable {
            if (!moved) {
                longPressed = true
                showFloatingMenu(params)
            }
        }
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    longPressed = false
                    handler.postDelayed(longPress, 520)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 7 || kotlin.math.abs(dy) > 7) {
                        moved = true
                        handler.removeCallbacks(longPress)
                        params.x = (startX + dx).coerceIn(0, (resources.displayMetrics.widthPixels - 54).coerceAtLeast(0))
                        params.y = (startY + dy).coerceIn(0, (resources.displayMetrics.heightPixels - 54).coerceAtLeast(0))
                        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (moved) {
                        savedButtonX = params.x
                        savedButtonY = params.y
                        getSharedPreferences("aurora_overlay", Context.MODE_PRIVATE).edit()
                            .putInt("button_x", savedButtonX)
                            .putInt("button_y", savedButtonY)
                            .apply()
                    } else if (!longPressed) {
                        removeFloatingMenu()
                        showSelection()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    true
                }
                else -> true
            }
        }
        try { wm.addView(button, params); floatingButton = button }
        catch (e: Exception) { Log.e(TAG, "Não foi possível mostrar o botão flutuante", e) }
    }

    private fun showFloatingMenu(anchor: WindowManager.LayoutParams) {
        if (floatingMenu != null) return
        val gold = android.graphics.Color.rgb(255, 51, 79)
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(7, 7, 7, 7)
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(17, 17, 17))
                cornerRadius = 18f
                setStroke(2, gold)
            }
            elevation = 14f
        }
        fun action(label: String, description: String, onClick: () -> Unit) {
            val item = TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(android.graphics.Color.WHITE)
                contentDescription = description
                setPadding(18, 0, 12, 0)
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.rgb(32, 32, 32))
                    cornerRadius = 12f
                }
                setOnClickListener { removeFloatingMenu(); onClick() }
            }
            menu.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 46))
            menu.addView(View(this), LinearLayout.LayoutParams(1, 5))
        }
        action("◉  TELA INTEIRA", "Ler e traduzir a tela inteira") { readWholeScreen() }
        action("▣  SELECIONAR TEXTO", "Marcar uma área para ler e traduzir") {
            selectionModeVision = false
            showSelection()
        }
        action("◇  VISÃO", "Identificar elementos e texto em uma área") {
            selectionModeVision = true
            showSelection()
        }
        action("×  ENCERRAR", "Fechar o leitor Aurora 455") { stopSelf() }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val menuWidth = 190
        val menuHeight = 220
        val displayW = resources.displayMetrics.widthPixels
        val displayH = resources.displayMetrics.heightPixels
        val menuX = if (anchor.x + 54 + menuWidth > displayW) anchor.x - menuWidth + 4 else anchor.x
        val menuY = (anchor.y + 58).coerceIn(8, (displayH - menuHeight).coerceAtLeast(8))
        val p = WindowManager.LayoutParams(menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = menuX.coerceIn(0, (displayW - menuWidth).coerceAtLeast(0))
                y = menuY
            }
        try { wm.addView(menu, p); floatingMenu = menu }
        catch (e: Exception) { Log.e(TAG, "Falha ao abrir menu flutuante", e) }
    }

    private fun removeFloatingMenu() {
        floatingMenu?.let { try { wm.removeView(it) } catch(_:Exception) {} }
        floatingMenu=null
    }

    private fun removeFloatingButton() {
        removeFloatingMenu()
        floatingButton?.let { try { wm.removeView(it) } catch(_:Exception) {} }
        floatingButton=null
    }

    private fun showSelection() {
        if (overlay != null) return
        removeFloatingMenu()
        floatingButton?.visibility = View.GONE
        overlay = SelectionView(this) { rect ->
            val selectionView = overlay
            val captureRect = if (rect != null && selectionView != null &&
                selectionView.width > 0 && selectionView.height > 0) {
                RectF(
                    rect.left * screenW / selectionView.width,
                    rect.top * screenH / selectionView.height,
                    rect.right * screenW / selectionView.width,
                    rect.bottom * screenH / selectionView.height
                )
            } else null
            removeOverlay()
            if (captureRect != null) {
                handler.postDelayed({ grabFrame(captureRect, 0) }, 350)
            } else {
                floatingButton?.visibility = View.VISIBLE
            }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            type, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        try {
            wm.addView(overlay, params)
        } catch (error: Exception) {
            Log.e(TAG, "Não foi possível abrir a seleção de texto", error)
            removeOverlay()
            floatingButton?.visibility = View.VISIBLE
            say("Não consegui abrir a seleção. Verifique a permissão de sobreposição do Aurora.")
        }
    }

    private fun removeOverlay() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
    }

    private fun readWholeScreen() {
        if (projection == null) { say("A captura não está ativa. Inicie o leitor novamente."); return }
        floatingButton?.visibility = View.GONE
        handler.postDelayed({ grabWholeFrame(0) }, 180)
    }

    private fun grabWholeFrame(tries: Int) {
        val r = reader
        if (r == null) { say("A captura não está ativa. Inicie o app de novo."); return }
        val image = r.acquireLatestImage()
        if (image == null) {
            if (tries < 6) handler.postDelayed({ grabWholeFrame(tries + 1) }, 250)
            else {
                floatingButton?.visibility = View.VISIBLE
                say("Não consegui capturar a tela. Tente novamente.")
            }
            return
        }
        val full = try {
            imageToBitmap(image)
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao converter a captura da tela em imagem", error)
            floatingButton?.visibility = View.VISIBLE
            say("A captura da tela veio em um formato inesperado. Feche e inicie o leitor novamente.")
            return
        } finally {
            image.close()
        }
        floatingButton?.visibility = View.VISIBLE
        readText(full)
    }

    private fun grabFrame(rect: RectF, tries: Int) {
        val r = reader
        if (r == null) {
            floatingButton?.visibility = View.VISIBLE
            say("A captura não está ativa. Inicie o app de novo.")
            return
        }
        val image = r.acquireLatestImage()
        if (image == null) {
            if (tries < 8) handler.postDelayed({ grabFrame(rect, tries + 1) }, 250)
            else {
                floatingButton?.visibility = View.VISIBLE
                say("Não consegui capturar a tela.")
            }
            return
        }
        val full = try {
            imageToBitmap(image)
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao converter a área capturada", error)
            floatingButton?.visibility = View.VISIBLE
            say("Não consegui processar a imagem capturada. Tente selecionar a área novamente.")
            return
        } finally {
            image.close()
        }
        floatingButton?.visibility = View.VISIBLE
        val selected = try {
            cropBitmap(full, rect)
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao recortar a área selecionada", error)
            full.recycle()
            say("A área selecionada ficou fora da tela. Selecione novamente.")
            return
        }
        if (selected !== full && !full.isRecycled) full.recycle()
        if (selectionModeVision) analyzeVisual(selected) else readText(selected)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        // Use as dimensões reais do frame: displayMetrics pode divergir da superfície
        // capturada em alguns aparelhos/versões do Android.
        val width = image.width
        val height = image.height
        require(width > 0 && height > 0) { "A captura retornou uma imagem vazia." }
        val plane = image.planes.firstOrNull()
            ?: error("A captura não retornou dados de imagem.")
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val paddedWidth = width + (rowPadding / plane.pixelStride).coerceAtLeast(0)
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        try {
            padded.copyPixelsFromBuffer(plane.buffer)
            val output = if (paddedWidth == width) padded
                else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
            if (screenW != width || screenH != height) {
                Log.w(TAG, "Dimensão da captura ajustada: ${screenW}x${screenH} -> ${width}x${height}")
                screenW = width
                screenH = height
            }
            return output
        } catch (error: Exception) {
            if (!padded.isRecycled) padded.recycle()
            throw error
        }
    }

    private fun cropBitmap(bmp: Bitmap, rect: RectF): Bitmap {
        val left = rect.left.toInt().coerceIn(0, bmp.width - 1)
        val top = rect.top.toInt().coerceIn(0, bmp.height - 1)
        val right = rect.right.toInt().coerceIn(left + 1, bmp.width)
        val bottom = rect.bottom.toInt().coerceIn(top + 1, bmp.height)
        return Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
    }

    // Análise visual local combinando rótulos de objetos e OCR no idioma correto.
    // No modo automático, testa os reconhecedores japonês, chinês, coreano e latino.
    private fun analyzeVisual(bitmap: Bitmap) {
        say("Analisando a região selecionada.")
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        var labelsDone = false
        var textDone = false
        var labelNames = emptyList<String>()
        var visibleText = ""

        fun finishIfReady() {
            if (!labelsDone || !textDone) return
            labeler.close()
            if (!bitmap.isRecycled) bitmap.recycle()
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

        fun completeText(value: String) {
            visibleText = value
            textDone = true
            finishIfReady()
        }

        fun scriptScore(value: String): Int {
            var score = 0
            for (ch in value) {
                val cp = ch.code
                when {
                    cp in 0x3040..0x30FF || cp in 0x31F0..0x31FF -> score += 12
                    cp in 0x1100..0x11FF || cp in 0x3130..0x318F || cp in 0xAC00..0xD7AF -> score += 12
                    cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF -> score += 5
                    ch in 'A'..'Z' || ch in 'a'..'z' -> score += 2
                    ch.isDigit() -> score += 1
                }
            }
            return score
        }

        if (autoDetectEnabled) {
            val languages = listOf(LANG_JAPANESE, LANG_CHINESE, LANG_KOREAN, LANG_ENGLISH)
            val outputs = mutableListOf<String>()
            fun probe(index: Int) {
                if (index >= languages.size) {
                    completeText(outputs.maxByOrNull { scriptScore(it) }.orEmpty().trim())
                    return
                }
                val recognizer = createTextRecognizer(languages[index])
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result ->
                        outputs.add(result.textBlocks.joinToString("\n") { it.text.trim() }.trim())
                        recognizer.close()
                        probe(index + 1)
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "OCR visual automático (${languages[index]}) falhou", error)
                        recognizer.close()
                        probe(index + 1)
                    }
            }
            probe(0)
        } else {
            val recognizer = createTextRecognizer(sourceLang)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    completeText(result.textBlocks.joinToString("\n") { it.text.trim() }.trim())
                    recognizer.close()
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Falha ao ler texto na análise visual", error)
                    recognizer.close()
                    completeText("")
                }
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

    // Cria variações de escala e contraste para ajudar o OCR em textos pequenos,
    // fundos coloridos e capturas com pouco contraste.
    private fun prepareBitmapForOcr(
        source: Bitmap,
        grayscale: Boolean = false,
        contrast: Float = if (grayscale) 1.32f else 1.22f
    ): Bitmap {
        // Limite de memória adequado a aparelhos de entrada (ex.: Galaxy A04e).
        val maxDimension = 2200f
        // Permite reduzir capturas grandes até 2200 px; só amplia imagens menores.
        val scale = minOf(1.6f, maxDimension / maxOf(source.width, source.height).toFloat())
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
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

    private fun createTextRecognizer(language: String): TextRecognizer = when (language) {
        LANG_JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        LANG_CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        LANG_KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // Identifica o idioma pelos caracteres e, quando necessário, pelo identificador
    // de idioma do ML Kit. A análise acontece localmente no aparelho.
    private fun identifySourceLanguage(text: String, callback: (String) -> Unit) {
        var kana = 0
        var hangul = 0
        for (ch in text) {
            val cp = ch.code
            if (cp in 0x3040..0x30FF || cp in 0x31F0..0x31FF) kana++
            if (cp in 0x1100..0x11FF || cp in 0x3130..0x318F || cp in 0xAC00..0xD7AF) hangul++
        }
        if (hangul > 0) {
            callback(LANG_KOREAN)
            return
        }
        if (kana > 0) {
            callback(LANG_JAPANESE)
            return
        }

        val identifier = LanguageIdentification.getClient()
        identifier.identifyLanguage(text)
            .addOnSuccessListener { languageTag ->
                identifier.close()
                val translatedCode = languageTag.substringBefore('-').lowercase(Locale.ROOT)
                val supported = setOf(
                    LANG_JAPANESE, LANG_CHINESE, LANG_KOREAN,
                    LANG_ENGLISH, LANG_PORTUGUESE
                )
                // Nunca presume inglês quando o identificador retorna outro idioma
                // (por exemplo, espanhol ou francês): isso gerava traduções erradas.
                val detected = when {
                    translatedCode in supported -> translatedCode
                    else -> LANG_UNSUPPORTED
                }
                Log.d(TAG, "Idioma identificado: tag=$languageTag, destino=$detected")
                callback(detected)
            }
            .addOnFailureListener { error ->
                identifier.close()
                Log.w(TAG, "Identificação de idioma falhou; não vou presumir inglês", error)
                // Hanzi isolado pode ser chinês ou japonês sem kana; não adivinha.
                callback(LANG_UNSUPPORTED)
            }
    }

    // No modo automático, testa os quatro reconhecedores de escrita disponíveis
    // e escolhe o texto que contém mais caracteres legíveis de um alfabeto real.
    private fun readTextAutomatically(bitmap: Bitmap) {
        val languages = listOf(
            LANG_JAPANESE,
            LANG_CHINESE,
            LANG_KOREAN,
            LANG_ENGLISH
        )
        val outputs = mutableListOf<Pair<String, String>>()

        fun scriptScore(text: String): Int {
            var score = 0
            for (ch in text) {
                val cp = ch.code
                when {
                    cp in 0x3040..0x30FF || cp in 0x31F0..0x31FF -> score += 12
                    cp in 0x1100..0x11FF || cp in 0x3130..0x318F || cp in 0xAC00..0xD7AF -> score += 12
                    cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF -> score += 5
                    ch in 'A'..'Z' || ch in 'a'..'z' -> score += 2
                    ch.isDigit() -> score += 1
                }
            }
            return score
        }

        fun probe(index: Int) {
            if (index >= languages.size) {
                val best = outputs.maxByOrNull { scriptScore(it.second) }?.second.orEmpty().trim()
                if (best.isBlank()) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    say("Não encontrei texto legível. Tente selecionar uma área maior ou mais nítida.")
                    return
                }
                if (!bitmap.isRecycled) bitmap.recycle()
                identifySourceLanguage(best) { detected ->
                    sourceLang = detected
                    Log.d(TAG, "Modo automático: idioma=$sourceLang; texto OCR=${best.length} caracteres")
                    // O texto já foi reconhecido: não execute novamente quatro passagens de OCR.
                    translate(best)
                }
                return
            }

            val language = languages[index]
            val recognizer = createTextRecognizer(language)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val found = result.textBlocks.joinToString("\n\n") { block ->
                        block.lines.joinToString("\n") { line -> line.text.trim() }
                    }.trim()
                    outputs.add(language to found)
                    recognizer.close()
                    probe(index + 1)
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Reconhecimento automático ($language) falhou", error)
                    recognizer.close()
                    probe(index + 1)
                }
        }
        probe(0)
    }

    private fun readText(bitmap: Bitmap) {
        if (autoDetectEnabled) {
            readTextAutomatically(bitmap)
        } else {
            readTextWithLanguage(bitmap)
        }
    }

    private fun readTextWithLanguage(bitmap: Bitmap) {
        val recognizer = createTextRecognizer(sourceLang)

        val enhanced = prepareBitmapForOcr(bitmap, false, 1.30f)
        val grayscale = prepareBitmapForOcr(bitmap, true, 1.42f)
        // Três tentativas dão robustez sem quadruplicar o consumo de memória.
        val candidates = listOf(bitmap, enhanced, grayscale)

        fun score(text: String): Int {
            if (text.isBlank()) return Int.MIN_VALUE
            var expectedScript = 0
            var letters = 0
            var digits = 0
            var noisySymbols = 0
            for (ch in text) {
                if (ch.isLetter()) letters++
                if (ch.isDigit()) digits++
                val cp = ch.code
                val isHan = cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF
                val isKana = cp in 0x3040..0x30FF || cp in 0x31F0..0x31FF
                val isHangul = cp in 0x1100..0x11FF || cp in 0x3130..0x318F || cp in 0xAC00..0xD7AF
                val isLatin = cp in 0x0041..0x024F
                val expected = when (sourceLang) {
                    LANG_JAPANESE -> isHan || isKana
                    LANG_CHINESE -> isHan
                    LANG_KOREAN -> isHangul || isHan
                    else -> isLatin
                }
                if (expected) expectedScript++
                if (!ch.isLetterOrDigit() && !ch.isWhitespace() &&
                    ch !in ".,!?;:，。！？、；：()（）「」『』【】-—…'\"") noisySymbols++
            }
            val lineBreaks = text.count { it == '\n' }
            val replacements = text.count { it == '\uFFFD' }
            val controls = text.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
            val repeated = Regex("(.)\\1{4,}").findAll(text).count()
            return letters * 2 + digits + expectedScript * 5 + lineBreaks * 2 -
                noisySymbols * 3 - replacements * 25 - controls * 15 - repeated * 8
        }

        fun finish(bestText: String) {
            recognizer.close()
            if (!enhanced.isRecycled) enhanced.recycle()
            if (!grayscale.isRecycled) grayscale.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
            val finalText = bestText.trim()
            Log.d(TAG, "OCR selecionado ($sourceLang): $finalText")
            if (finalText.isEmpty()) say("Não encontrei texto legível. Tente selecionar uma área maior ou mais nítida.")
            else translate(finalText)
        }

        fun runPass(index: Int, bestText: String, bestScore: Int) {
            if (index >= candidates.size) { finish(bestText); return }
            recognizer.process(InputImage.fromBitmap(candidates[index], 0))
                .addOnSuccessListener { result ->
                    val found = result.textBlocks.joinToString("\n\n") { block ->
                        block.lines.joinToString("\n") { line -> line.text.trim() }
                    }.trim()
                    val value = score(found)
                    Log.d(TAG, "OCR variante ${index + 1}: score=$value, chars=${found.length}")
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

    // Mantém mais contexto por trecho e corrige quebras de linha causadas pelo OCR.
    // Japonês e chinês normalmente não usam espaço entre frases; coreano usa espaços.
    private fun splitParagraph(text: String, limit: Int = 1400): List<String> {
        val usesSpaces = sourceLang != LANG_JAPANESE &&
            sourceLang != LANG_CHINESE
        val normalized = text
            .replace(Regex("[\t\r]+"), " ")
            .replace(Regex("(?<!\n)\n(?!\n)"), if (usesSpaces) " " else "")
            .trim()
        if (normalized.length <= limit) return listOf(normalized)

        // Divide depois da pontuação, sem descartar o sinal que encerra a frase.
        val sentences = normalized.split(Regex("(?<=[.!?。！？；;])"))
            .map { it.trim() }.filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        var current = StringBuilder()

        fun flush() {
            val value = current.toString().trim()
            if (value.isNotEmpty()) out.add(value)
            current = StringBuilder()
        }

        fun splitLongSentence(value: String) {
            var offset = 0
            while (offset < value.length) {
                var endIndex = (offset + limit).coerceAtMost(value.length)
                if (endIndex < value.length) {
                    val boundary = if (usesSpaces) {
                        value.lastIndexOf(' ', endIndex)
                    } else {
                        value.lastIndexOfAny(charArrayOf('、', '，', '；', '。', '！', '？'), endIndex)
                    }
                    if (boundary > offset + limit / 2) endIndex = boundary + 1
                    if (endIndex < value.length && endIndex > offset && value[endIndex - 1].isHighSurrogate()) endIndex--
                }
                if (endIndex <= offset) endIndex = (offset + limit).coerceAtMost(value.length)
                val piece = value.substring(offset, endIndex).trim()
                if (piece.isNotEmpty()) out.add(piece)
                offset = endIndex
                while (offset < value.length && value[offset].isWhitespace()) offset++
            }
        }

        for (sentence in sentences) {
            if (sentence.length > limit) {
                flush()
                splitLongSentence(sentence)
            } else {
                val extra = if (current.isNotEmpty() && usesSpaces) 1 else 0
                if (current.isNotEmpty() && current.length + extra + sentence.length > limit) flush()
                if (current.isNotEmpty() && usesSpaces) current.append(' ')
                current.append(sentence)
            }
        }
        flush()
        return out.filter { it.isNotBlank() }
    }

    // A tradução é executada pelo motor SLIMT nativo. Os modelos são baixados
    // uma vez e ficam no armazenamento privado para uso offline.
    private fun translate(text: String) {
        if (sourceLang == LANG_UNSUPPORTED) {
            say("Não consegui identificar um idioma compatível. No momento, a tradução offline aceita inglês, japonês, coreano e chinês. Para outros idiomas, selecione um idioma compatível ou aguarde suporte a novos modelos.")
            return
        }
        val from = sourceLang.takeIf { it != LANG_AUTO }
        OfflineTranslationClient(this).translate(
            text = text,
            sourceLanguage = from,
            onSuccess = { result ->
                Log.d(TAG, "Tradução offline concluída (\${result.length} caracteres)")
                say(result)
            },
            onError = { message ->
                Log.e(TAG, "Tradução offline indisponível: $message")
                say(message)
            }
        )
    }

    // Painel permite comparar e copiar o texto original e a tradução sem confundi-los.
    private fun showResultPanel(source: String, translated: String, sourceTitle: String = "TEXTO RECONHECIDO (ORIGINAL)", translatedTitle: String = "TRADUÇÃO PARA PORTUGUÊS") {
        handler.post {
            removeResultPanel()
            val gold = android.graphics.Color.rgb(255, 51, 79)
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
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            panel.addView(heading)
            val scroll = ScrollView(this)
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun section(label: String, value: String) {
                body.addView(TextView(this).apply {
                    text = label
                    textSize = 13f
                    setTextColor(gold)
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
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
        engine.setLanguage(Locale("pt", "BR")); engine.setSpeechRate(1.0f); engine.setPitch(1.0f)
        val brazilianVoice = engine.voices
            ?.filter { it.locale.language == "pt" && it.locale.country.equals("BR", true) }
            ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired })
            ?.firstOrNull()
        if (brazilianVoice != null) engine.voice = brazilianVoice
    }

    // Prepara o texto para a voz e divide apenas em limites naturais,
    // evitando cortar palavras e reduzir pausas artificiais entre trechos.
    private fun say(message: String) {
        handler.post {
            val engine = tts
            if (!ttsReady || engine == null) { toast(message); return@post }

            val spoken = message
                .replace("\r\n", "\n")
                .replace(Regex("(?m)^\\s*[-–—]+\\s*$"), " ")
                .replace(Regex("(?<![\\p{L}\\p{N}])[-–—](?![\\p{L}\\p{N}])"), " ")
                .replace(Regex("[–—]"), ", ")
                .replace(Regex("[\\t ]+"), " ")
                .replace(Regex("\\n+"), " ")
                .replace(Regex(" +([,.;:!?])"), "$1")
                .trim()
            if (spoken.isEmpty()) return@post

            val maxLength = TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1000)
            var start = 0
            var first = true
            while (start < spoken.length) {
                var end = (start + maxLength).coerceAtMost(spoken.length)
                if (end < spoken.length) {
                    // Primeiro tenta terminar uma frase; depois, uma palavra.
                    val sentenceBreak = spoken.lastIndexOfAny(
                        charArrayOf('.', '!', '?', ';', ':', ',', '。', '！', '？'),
                        end - 1
                    )
                    if (sentenceBreak > start + maxLength / 2) {
                        end = sentenceBreak + 1
                    } else {
                        val spaceBefore = spoken.lastIndexOf(' ', end - 1)
                        if (spaceBefore > start) {
                            end = spaceBefore
                        } else {
                            val spaceAfter = spoken.indexOf(' ', end)
                            if (spaceAfter > end) end = spaceAfter
                        }
                    }
                    if (end < spoken.length && end > start && spoken[end - 1].isHighSurrogate()) end--
                }

                val part = spoken.substring(start, end).trim()
                if (part.isNotEmpty()) {
                    val result = engine.speak(
                        part,
                        if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                        null,
                        "leitura_$start"
                    )
                    if (result == TextToSpeech.ERROR) {
                        Log.e(TAG, "O mecanismo de voz recusou um trecho da leitura")
                    }
                    first = false
                }
                start = end
                while (start < spoken.length && spoken[start].isWhitespace()) start++
            }
        }
    }

    private fun toast(message: String) {
        handler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }
}