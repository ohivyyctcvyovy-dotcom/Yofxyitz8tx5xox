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

/** Sobreposição para selecionar uma região da tela com o dedo. */
class SelectionView(
    context: Context,
    private val onDone: (RectF?) -> Unit
) : View(context) {
    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f
    private var dragging = false

    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val fill = Paint().apply { color = Color.argb(45, 255, 205, 90) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.rgb(255, 205, 90)
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        if (dragging) {
            val r = currentRect()
            canvas.drawRect(r, fill)
            canvas.drawRect(r, stroke)
        } else {
            canvas.drawText("Arraste o dedo para selecionar a região", 32f, 140f, hint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX; startY = event.rawY
                curX = startX; curY = startY; dragging = true; invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                curX = event.rawX; curY = event.rawY; invalidate()
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                val r = currentRect()
                onDone(if (r.width() > 30 && r.height() > 30) r else null)
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; onDone(null) }
        }
        return true
    }

    private fun currentRect() = RectF(min(startX, curX), min(startY, curY), max(startX, curX), max(startY, curY))
}