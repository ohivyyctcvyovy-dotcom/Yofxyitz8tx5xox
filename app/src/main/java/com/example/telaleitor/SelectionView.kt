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

/**
 * Seletor de área:
 * - desenhar e mover a moldura;
 * - redimensionar pelos quatro cantos;
 * - botões menores, movíveis separadamente e com posição persistente.
 */
class SelectionView(
    context: Context,
    private val onDone: (RectF?) -> Unit
) : View(context) {
    private val prefs = context.getSharedPreferences("aurora_selection_buttons", Context.MODE_PRIVATE)
    private val selection = RectF()
    private val cancelButton = RectF()
    private val readButton = RectF()
    private var hasSelection = false
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var movedButton = false
    private val startRect = RectF()
    private var activeCorner = Corner.NONE
    private var activeButton = Button.NONE
    private var buttonStart = RectF()

    private enum class Mode { NONE, DRAW, MOVE, RESIZE, MOVE_BUTTON }
    private enum class Corner { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private enum class Button { NONE, CANCEL, READ }

    private val dim = Paint().apply { color = Color.argb(145, 0, 0, 0) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(38, 255, 51, 79) }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.rgb(255, 51, 79)
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 51, 79) }
    private val handleCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 18, 18) }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = android.graphics.Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 23f }
    private val cancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 35, 35) }
    private val confirmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 51, 79) }
    private val buttonText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 18f; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val confirmText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 20f; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif", Typeface.BOLD)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        placeButtons()
    }

    private fun placeButtons() {
        if (width <= 0 || height <= 0) return
        val cancelW = dp(116f)
        val buttonH = dp(46f)
        val readW = dp(78f)
        val gap = dp(12f)
        val defaultY = (height - buttonH - dp(20f)).coerceAtLeast(dp(HEADER_BOTTOM))
        val defaultCancelX = dp(18f)
        val defaultReadX = (width - readW - dp(18f)).coerceAtLeast(defaultCancelX + cancelW + gap)

        val cancelX = (prefs.getFloat("cancel_x", -1f).takeIf { it >= 0f }?.times(width) ?: defaultCancelX)
            .coerceIn(0f, (width - cancelW).coerceAtLeast(0f))
        val cancelY = (prefs.getFloat("cancel_y", -1f).takeIf { it >= 0f }?.times(height) ?: defaultY)
            .coerceIn(dp(HEADER_BOTTOM), (height - buttonH).coerceAtLeast(dp(HEADER_BOTTOM)))
        val readX = (prefs.getFloat("read_x", -1f).takeIf { it >= 0f }?.times(width) ?: defaultReadX)
            .coerceIn(0f, (width - readW).coerceAtLeast(0f))
        val readY = (prefs.getFloat("read_y", -1f).takeIf { it >= 0f }?.times(height) ?: defaultY)
            .coerceIn(dp(HEADER_BOTTOM), (height - buttonH).coerceAtLeast(dp(HEADER_BOTTOM)))
        cancelButton.set(cancelX, cancelY, cancelX + cancelW, cancelY + buttonH)
        readButton.set(readX, readY, readX + readW, readY + buttonH)
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

        canvas.drawRoundRect(cancelButton, dp(10f), dp(10f), cancelPaint)
        canvas.drawRoundRect(readButton, dp(10f), dp(10f), confirmPaint)
        canvas.drawText("CANCELAR", cancelButton.centerX(), cancelButton.centerY() + dp(6f), buttonText)
        canvas.drawText("LER", readButton.centerX(), readButton.centerY() + dp(7f), confirmText)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                movedButton = false

                val hitPadding = dp(12f)
                val cancelHit = RectF(cancelButton).apply { inset(-hitPadding, -hitPadding) }
                val readHit = RectF(readButton).apply { inset(-hitPadding, -hitPadding) }
                activeButton = when {
                    cancelHit.contains(x, y) -> Button.CANCEL
                    readHit.contains(x, y) -> Button.READ
                    else -> Button.NONE
                }
                if (activeButton != Button.NONE) {
                    mode = Mode.MOVE_BUTTON
                    buttonStart.set(if (activeButton == Button.CANCEL) cancelButton else readButton)
                    return true
                }

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
                    Mode.DRAW -> selection.set(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
                    Mode.MOVE -> {
                        val dx = x - downX
                        val dy = y - downY
                        val w = startRect.width()
                        val h = startRect.height()
                        val left = (startRect.left + dx).coerceIn(0f, (width - w).coerceAtLeast(0f))
                        val top = (startRect.top + dy).coerceIn(dp(HEADER_BOTTOM), (height - h).coerceAtLeast(dp(HEADER_BOTTOM)))
                        selection.set(left, top, left + w, top + h)
                    }
                    Mode.RESIZE -> resizeFromCorner(x, y)
                    Mode.MOVE_BUTTON -> {
                        if (kotlin.math.abs(x - downX) > dp(3f) || kotlin.math.abs(y - downY) > dp(3f)) movedButton = true
                        val dx = x - downX
                        val dy = y - downY
                        val target = if (activeButton == Button.CANCEL) cancelButton else readButton
                        val left = (buttonStart.left + dx).coerceIn(0f, (width - buttonStart.width()).coerceAtLeast(0f))
                        val top = (buttonStart.top + dy).coerceIn(dp(HEADER_BOTTOM), (height - buttonStart.height()).coerceAtLeast(dp(HEADER_BOTTOM)))
                        target.set(left, top, left + buttonStart.width(), top + buttonStart.height())
                    }
                    Mode.NONE -> Unit
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.MOVE_BUTTON) {
                    val target = if (activeButton == Button.CANCEL) cancelButton else readButton
                    if (movedButton) {
                        val prefix = if (activeButton == Button.CANCEL) "cancel" else "read"
                        prefs.edit()
                            .putFloat("${prefix}_x", target.left / width.coerceAtLeast(1).toFloat())
                            .putFloat("${prefix}_y", target.top / height.coerceAtLeast(1).toFloat())
                            .apply()
                    } else if (activeButton == Button.CANCEL) {
                        onDone(null)
                    } else if (hasSelection &&
                        selection.width() > MIN_SELECTION && selection.height() > MIN_SELECTION) {
                        onDone(RectF(selection))
                    }
                    mode = Mode.NONE
                    activeButton = Button.NONE
                    invalidate()
                    return true
                }
                if (mode == Mode.DRAW) {
                    selection.set(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
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
                activeButton = Button.NONE
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
        fun near(cx: Float, cy: Float) = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius
        return when {
            near(selection.left, selection.top) -> Corner.TOP_LEFT
            near(selection.right, selection.top) -> Corner.TOP_RIGHT
            near(selection.left, selection.bottom) -> Corner.BOTTOM_LEFT
            near(selection.right, selection.bottom) -> Corner.BOTTOM_RIGHT
            else -> Corner.NONE
        }
    }

    private fun resizeFromCorner(x: Float, y: Float) {
        when (activeCorner) {
            Corner.TOP_LEFT -> {
                selection.left = x.coerceIn(0f, startRect.right - MIN_SELECTION)
                selection.top = y.coerceIn(dp(HEADER_BOTTOM), startRect.bottom - MIN_SELECTION)
            }
            Corner.TOP_RIGHT -> {
                selection.right = x.coerceIn(startRect.left + MIN_SELECTION, width.toFloat())
                selection.top = y.coerceIn(dp(HEADER_BOTTOM), startRect.bottom - MIN_SELECTION)
            }
            Corner.BOTTOM_LEFT -> {
                selection.left = x.coerceIn(0f, startRect.right - MIN_SELECTION)
                selection.bottom = y.coerceIn(startRect.top + MIN_SELECTION, height.toFloat())
            }
            Corner.BOTTOM_RIGHT -> {
                selection.right = x.coerceIn(startRect.left + MIN_SELECTION, width.toFloat())
                selection.bottom = y.coerceIn(startRect.top + MIN_SELECTION, height.toFloat())
            }
            Corner.NONE -> Unit
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val HANDLE_RADIUS = 17f
        private const val MIN_SELECTION = 30f
        private const val HEADER_BOTTOM = 118f
    }
}
