package com.example.telaleitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Seletor de área sem controles deslizantes:
 * - primeiro arrasto desenha a região;
 * - arrastar dentro da região move a moldura;
 * - arrastar qualquer canto ajusta largura e altura diretamente.
 */
class SelectionView(
    context: Context,
    private val onDone: (RectF?) -> Unit
) : View(context) {
    private val selection = RectF()
    private var hasSelection = false
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private val startRect = RectF()
    private var activeCorner = Corner.NONE

    private enum class Mode { NONE, DRAW, MOVE, RESIZE }
    private enum class Corner { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private val dim = Paint().apply { color = Color.argb(145, 0, 0, 0) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(38, 255, 205, 90) }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.rgb(255, 205, 90)
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 205, 90) }
    private val handleCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 18, 18) }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 23f }
    private val cancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 35, 35) }
    private val confirmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 205, 90) }
    private val buttonText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val confirmText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 24f; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.drawText("Marque a área para leitura", 22f, 58f, title)
        canvas.drawText(
            if (hasSelection) "Arraste a moldura ou puxe um dos cantos"
            else "Arraste o dedo sobre o texto que deseja ouvir",
            22f, 96f, hint
        )

        if (hasSelection || mode == Mode.DRAW) {
            canvas.drawRoundRect(selection, 9f, 9f, fill)
            canvas.drawRoundRect(selection, 9f, 9f, outline)
            drawHandle(canvas, selection.left, selection.top)
            drawHandle(canvas, selection.right, selection.top)
            drawHandle(canvas, selection.left, selection.bottom)
            drawHandle(canvas, selection.right, selection.bottom)
        }

        val top = height - BUTTON_BOTTOM_GAP
        val cancel = cancelRect(top)
        val confirm = confirmRect(top)
        canvas.drawRoundRect(cancel, 14f, 14f, cancelPaint)
        canvas.drawRoundRect(confirm, 14f, 14f, confirmPaint)
        canvas.drawText("CANCELAR", cancel.centerX(), cancel.centerY() + 8f, buttonText)
        canvas.drawText("LER", confirm.centerX(), confirm.centerY() + 8f, confirmText)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (y >= height - BUTTON_HIT_AREA) return true
                downX = x
                downY = y
                startRect.set(selection)
                if (!hasSelection) {
                    mode = Mode.DRAW
                    selection.set(x, y, x, y)
                } else {
                    activeCorner = hitCorner(x, y)
                    mode = when {
                        activeCorner != Corner.NONE -> Mode.RESIZE
                        selection.contains(x, y) -> Mode.MOVE
                        else -> Mode.DRAW
                    }
                    if (mode == Mode.DRAW) {
                        selection.set(x, y, x, y)
                        hasSelection = false
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.DRAW -> selection.set(
                        min(downX, x), min(downY, y), max(downX, x), max(downY, y)
                    )
                    Mode.MOVE -> {
                        val dx = x - downX
                        val dy = y - downY
                        val w = startRect.width()
                        val h = startRect.height()
                        val left = (startRect.left + dx).coerceIn(0f, (width - w).coerceAtLeast(0f))
                        val top = (startRect.top + dy).coerceIn(HEADER_BOTTOM, (height - BUTTON_BOTTOM_GAP - h).coerceAtLeast(HEADER_BOTTOM))
                        selection.set(left, top, left + w, top + h)
                    }
                    Mode.RESIZE -> resizeFromCorner(x, y)
                    Mode.NONE -> Unit
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (y >= height - BUTTON_HIT_AREA) {
                    val top = height - BUTTON_BOTTOM_GAP
                    if (cancelRect(top).contains(x, y)) {
                        onDone(null)
                    } else if (confirmRect(top).contains(x, y) && hasSelection &&
                        selection.width() > MIN_SELECTION && selection.height() > MIN_SELECTION) {
                        onDone(RectF(selection))
                    }
                    mode = Mode.NONE
                    return true
                }
                if (mode == Mode.DRAW) {
                    selection.set(
                        min(downX, x), min(downY, y), max(downX, x), max(downY, y)
                    )
                    hasSelection = selection.width() > MIN_SELECTION && selection.height() > MIN_SELECTION
                }
                mode = Mode.NONE
                activeCorner = Corner.NONE
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                activeCorner = Corner.NONE
                invalidate()
                return true
            }
        }
        return true
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, HANDLE_RADIUS, handle)
        canvas.drawCircle(x, y, 9f, handleCenter)
    }

    private fun hitCorner(x: Float, y: Float): Corner {
        val radius = HANDLE_RADIUS * 2.1f
        fun near(cx: Float, cy: Float) =
            (x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius
        return when {
            near(selection.left, selection.top) -> Corner.TOP_LEFT
            near(selection.right, selection.top) -> Corner.TOP_RIGHT
            near(selection.left, selection.bottom) -> Corner.BOTTOM_LEFT
            near(selection.right, selection.bottom) -> Corner.BOTTOM_RIGHT
            else -> Corner.NONE
        }
    }

    private fun resizeFromCorner(x: Float, y: Float) {
        val minX: Float
        val maxX: Float
        val minY: Float
        val maxY: Float
        when (activeCorner) {
            Corner.TOP_LEFT -> {
                minX = 0f; maxX = startRect.right - MIN_SELECTION
                minY = HEADER_BOTTOM; maxY = startRect.bottom - MIN_SELECTION
                selection.left = x.coerceIn(minX, maxX)
                selection.top = y.coerceIn(minY, maxY)
            }
            Corner.TOP_RIGHT -> {
                minX = startRect.left + MIN_SELECTION; maxX = width.toFloat()
                minY = HEADER_BOTTOM; maxY = startRect.bottom - MIN_SELECTION
                selection.right = x.coerceIn(minX, maxX)
                selection.top = y.coerceIn(minY, maxY)
            }
            Corner.BOTTOM_LEFT -> {
                minX = 0f; maxX = startRect.right - MIN_SELECTION
                minY = startRect.top + MIN_SELECTION; maxY = height - BUTTON_BOTTOM_GAP
                selection.left = x.coerceIn(minX, maxX)
                selection.bottom = y.coerceIn(minY, maxY)
            }
            Corner.BOTTOM_RIGHT -> {
                minX = startRect.left + MIN_SELECTION; maxX = width.toFloat()
                minY = startRect.top + MIN_SELECTION; maxY = height - BUTTON_BOTTOM_GAP
                selection.right = x.coerceIn(minX, maxX)
                selection.bottom = y.coerceIn(minY, maxY)
            }
            Corner.NONE -> Unit
        }
    }

    private fun cancelRect(top: Float) = RectF(18f, top, width * 0.36f, height - 18f)
    private fun confirmRect(top: Float) = RectF(width * 0.40f, top, width - 18f, height - 18f)

    companion object {
        private const val HANDLE_RADIUS = 17f
        private const val MIN_SELECTION = 30f
        private const val BUTTON_BOTTOM_GAP = 100f
        private const val BUTTON_HIT_AREA = 120f
        private const val HEADER_BOTTOM = 118f
    }
}
