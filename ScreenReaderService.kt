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
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
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
    private var sourceLang: String = TranslateLanguage.JAPANESE
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var screenW = 0
    private var screenH = 0

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
            if (ttsReady) tts?.language = Locale("pt", "BR")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Precisa chamar startForeground logo no início
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
                if (Build.VERSION.SDK_INT >= 34) {
                    p.registerCallback(projectionCallback, handler)
                }
                setupCapture()
                showSelection()
            }
            ACTION_SELECT -> {
                if (projection == null) toast("Inicie a leitura pelo app primeiro")
                else showSelection()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        releaseCapture()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun readDataExtra(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA)
        }

    // ---------- Notificação ----------

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val selectPi = PendingIntent.getService(
            this, 1, Intent(this, ScreenReaderService::class.java).setAction(ACTION_SELECT), flags
        )
        val stopPi = PendingIntent.getService(
            this, 2, Intent(this, ScreenReaderService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Leitor de tela ativo")
            .setContentText("Toque em Selecionar área para escolher o texto")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_crop, "Selecionar área", selectPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Encerrar", stopPi)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Leitor de tela", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    // ---------- Captura contínua (uma vez por permissão) ----------

    private fun setupCapture() {
        val p = projection ?: return
        val metrics = resources.displayMetrics
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels

        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        reader = r
        virtualDisplay = p.createVirtualDisplay(
            "ScreenReader", screenW, screenH, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
    }

    private fun releaseCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        val p = projection
        projection = null
        if (p != null) {
            if (Build.VERSION.SDK_INT >= 34) {
                try { p.unregisterCallback(projectionCallback) } catch (_: Exception) {}
            }
            p.stop()
        }
    }

    // ---------- Seleção da área ----------

    private fun showSelection() {
        if (overlay != null) return
        overlay = SelectionView(this) { rect ->
            removeOverlay()
            if (rect != null) {
                // Espera a tela de seleção sumir antes de capturar o frame
                handler.postDelayed({ grabFrame(rect, 0) }, 400)
            }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(overlay, params)
    }

    private fun removeOverlay() {
        overlay?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        overlay = null
    }

    // ---------- Leitura do frame ----------

    private fun grabFrame(rect: RectF, tries: Int) {
        val r = reader
        if (r == null) {
            say("A captura não está ativa. Inicie o app de novo.")
            return
        }
        val image = r.acquireLatestImage()
        if (image == null) {
            if (tries < 5) handler.postDelayed({ grabFrame(rect, tries + 1) }, 300)
            else say("Não consegui capturar a tela.")
            return
        }
        val full = imageToBitmap(image)
        image.close()
        readText(cropBitmap(full, rect))
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * screenW
        val bmp = Bitmap.createBitmap(
            screenW + rowPadding / plane.pixelStride, screenH, Bitmap.Config.ARGB_8888
        )
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

    // ---------- OCR ----------

    private fun readText(bitmap: Bitmap) {
        val recognizer = when (sourceLang) {
            TranslateLanguage.JAPANESE ->
                TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            TranslateLanguage.KOREAN ->
                TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            else ->
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                recognizer.close()
                val text = result.text.trim()
                Log.d(TAG, "OCR: $text")
                if (text.isEmpty()) say("Não encontrei texto na área selecionada.")
                else translate(text)
            }
            .addOnFailureListener { e ->
                recognizer.close()
                Log.e(TAG, "Erro no OCR", e)
                say("Erro ao reconhecer o texto.")
            }
    }

    // ---------- Tradução ----------

    private fun translate(text: String) {
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(TranslateLanguage.PORTUGUESE)
                .build()
        )

        // Baixa o modelo do idioma se ainda não estiver no aparelho
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translated ->
                        translator.close()
                        Log.d(TAG, "Tradução: $translated")
                        toast("$text\n\n→ $translated")
                        say(translated)
                    }
                    .addOnFailureListener { e ->
                        translator.close()
                        Log.e(TAG, "Erro na tradução", e)
                        say("Erro na tradução.")
                    }
            }
            .addOnFailureListener { e ->
                translator.close()
                Log.e(TAG, "Falha ao baixar o modelo de tradução", e)
                say("Não consegui baixar o modelo de tradução. Verifique a internet.")
            }
    }

    // ---------- Saída ----------

    private fun say(message: String) {
        handler.post {
            if (ttsReady) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "leitura")
            else toast(message)
        }
    }

    private fun toast(message: String) {
        handler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }
}
