package com.example.telaleitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Seleção redimensionável: o usuário confirma a área antes de iniciar OCR/tradução. */
class SelectionView(
    context: Context,
    private val onDone: (RectF?) -> Unit
) : View(context) {
    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f
    private var hasSelection = false
    private var scale = 1f
    private var sliderActive = false

    private val dim = Paint().apply { color = Color.argb(155, 0, 0, 0) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(42, 255, 205, 90) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.rgb(255, 205, 90)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 27f
    }
    private val button = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(24, 24, 24)
    }
    private val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 205, 90)
    }
    private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 30f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.drawText("Marque a área do texto", 28f, 82f, text)
        canvas.drawText("Arraste o dedo para criar a seleção", 28f, 126f, small)

        if (hasSelection) {
            val r = currentRect()
            canvas.drawRoundRect(r, 12f, 12f, fill)
            canvas.drawRoundRect(r, 12f, 12f, stroke)
            val handle = 18f
            canvas.drawCircle(r.left, r.top, handle, gold)
            canvas.drawCircle(r.right, r.bottom, handle, gold)
            canvas.drawText("Área selecionada", 28f, height - 250f, small)
        }

        val controlTop = height - 210f
        val sliderLeft = 38f
        val sliderRight = width - 38f
        canvas.drawRoundRect(sliderLeft, controlTop, sliderRight, controlTop + 12f, 8f, 8f, button)
        val sliderX = sliderLeft + ((scale - 0.65f) / (1.75f - 0.65f)) * (sliderRight - sliderLeft)
        canvas.drawRoundRect(sliderLeft, controlTop, sliderX, controlTop + 12f, 8f, 8f, gold)
        canvas.drawCircle(sliderX, controlTop + 6f, 17f, gold)
        canvas.drawText("Tamanho da seleção: ${(scale * 100).toInt()}%", 38f, controlTop - 18f, small)

        val cancel = RectF(24f, height - 145f, width * 0.32f, height - 55f)
        val resize = RectF(width * 0.36f, height - 145f, width * 0.64f, height - 55f)
        val confirm = RectF(width * 0.68f, height - 145f, width - 24f, height - 55f)
        canvas.drawRoundRect(cancel, 18f, 18f, button)
        canvas.drawRoundRect(resize, 18f, 18f, button)
        canvas.drawRoundRect(confirm, 18f, 18f, gold)
        canvas.drawText("CANCELAR", cancel.centerX(), cancel.centerY() + 10f, center.apply { color = Color.WHITE })
        canvas.drawText("AJUSTAR", resize.centerX(), resize.centerY() + 10f, center)
        canvas.drawText("LER / TRADUZIR", confirm.centerX(), confirm.centerY() + 10f, center.apply { color = Color.BLACK })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val controlTop = height - 210f
        val sliderLeft = 38f
        val sliderRight = width - 38f
        val cancel = RectF(24f, height - 145f, width * 0.32f, height - 55f)
        val resize = RectF(width * 0.36f, height - 145f, width * 0.64f, height - 55f)
        val confirm = RectF(width * 0.68f, height - 145f, width - 24f, height - 55f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (hasSelection && event.y in (controlTop - 35f)..(controlTop + 35f)) {
                    sliderActive = true
                    updateScale(event.x, sliderLeft, sliderRight)
                } else if (hasSelection && event.y > height - 160f) {
                    // Os botões são tratados em ACTION_UP.
                } else {
                    startX = event.rawX
                    startY = event.rawY
                    curX = startX
                    curY = startY
                    hasSelection = false
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (sliderActive) updateScale(event.x, sliderLeft, sliderRight)
                else if (!hasSelection) {
                    curX = event.rawX
                    curY = event.rawY
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (sliderActive) {
                    sliderActive = false
                    return true
                }
                if (event.y > height - 160f) {
                    when {
                        cancel.contains(event.x, event.y) -> onDone(null)
                        resize.contains(event.x, event.y) && hasSelection -> {
                            scale = if (scale < 1.74f) (scale + 0.15f).coerceAtMost(1.75f) else 0.65f
                            invalidate()
                        }
                        confirm.contains(event.x, event.y) && hasSelection -> {
                            val r = currentRect()
                            onDone(if (r.width() > 24 && r.height() > 24) r else null)
                        }
                    }
                    return true
                }
                curX = event.rawX
                curY = event.rawY
                hasSelection = kotlin.math.abs(curX - startX) > 30 && kotlin.math.abs(curY - startY) > 30
                scale = 1f
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                sliderActive = false
                return true
            }
        }
        return true
    }

    private fun updateScale(x: Float, left: Float, right: Float) {
        val ratio = ((x - left) / (right - left)).coerceIn(0f, 1f)
        scale = 0.65f + ratio * 1.10f
        invalidate()
    }

    private fun currentRect(): RectF {
        val cx = (startX + curX) / 2f
        val cy = (startY + curY) / 2f
        val halfW = kotlin.math.abs(curX - startX) * scale / 2f
        val halfH = kotlin.math.abs(curY - startY) * scale / 2f
        return RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }
}
