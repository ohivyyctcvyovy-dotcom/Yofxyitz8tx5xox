package com.example.telaleitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Seleção persistente: o primeiro arrasto marca a área.
 * Depois, o pequeno puxador redondo no canto inferior direito ajusta o tamanho
 * ao arrastar para cima/baixo. Não há barra deslizante.
 */
class SelectionView(
    context: Context,
    private val onDone: (RectF?) -> Unit
) : View(context) {
    private var startX=0f
    private var startY=0f
    private var endX=0f
    private var endY=0f
    private var hasSelection=false
    private var drawing=false
    private var resizing=false
    private var resizeStartY=0f
    private var resizeStartScale=1f
    private var scale=1f

    private val dim=Paint().apply { color=Color.argb(150,0,0,0) }
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.argb(38,255,205,90) }
    private val outline=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=3.5f; color=Color.rgb(255,205,90) }
    private val handle=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(255,205,90) }
    private val handleInner=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(20,20,20) }
    private val title=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=34f; typeface=android.graphics.Typeface.DEFAULT_BOLD }
    private val hint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=25f }
    private val button=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(25,25,25) }
    private val gold=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(255,205,90) }
    private val buttonText=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=25f; textAlign=Paint.Align.CENTER; typeface=android.graphics.Typeface.DEFAULT_BOLD }
    private val confirmText=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=23f; textAlign=Paint.Align.CENTER; typeface=android.graphics.Typeface.DEFAULT_BOLD }

    override fun onDraw(c:Canvas) {
        super.onDraw(c)
        c.drawRect(0f,0f,width.toFloat(),height.toFloat(),dim)
        c.drawText("Marque a área desejada",24f,64f,title)
        c.drawText(if(hasSelection) "Puxe a bolinha para cima/baixo para ajustar" else "Arraste o dedo para desenhar a marcação",24f,104f,hint)
        if(hasSelection || drawing) {
            val r=currentRect()
            c.drawRoundRect(r,10f,10f,fill); c.drawRoundRect(r,10f,10f,outline)
            c.drawCircle(r.right,r.bottom,23f,handle); c.drawCircle(r.right,r.bottom,10f,handleInner)
            c.drawText("${(scale*100).toInt()}%",(r.left+12f).coerceAtLeast(12f),(r.top-12f).coerceAtLeast(142f),hint)
        }
        val top=height-112f
        val cancel=RectF(18f,top,width*0.34f,height-22f)
        val confirm=RectF(width*0.38f,top,width-18f,height-22f)
        c.drawRoundRect(cancel,16f,16f,button); c.drawRoundRect(confirm,16f,16f,gold)
        c.drawText("CANCELAR",cancel.centerX(),cancel.centerY()+9f,buttonText)
        c.drawText("LER / TRADUZIR",confirm.centerX(),confirm.centerY()+8f,confirmText)
    }

    override fun onTouchEvent(e:MotionEvent):Boolean {
        val cancel=RectF(18f,height-112f,width*0.34f,height-22f)
        val confirm=RectF(width*0.38f,height-112f,width-18f,height-22f)
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if(e.y>=height-125f) return true
                if(hasSelection && nearHandle(e.x,e.y)) {
                    resizing=true; resizeStartY=e.y; resizeStartScale=scale
                } else if(!hasSelection) {
                    startX=e.x.coerceIn(0f,width.toFloat()); startY=e.y.coerceIn(0f,height-135f)
                    endX=startX; endY=startY; drawing=true
                }
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if(resizing) {
                    // Movimento vertical: puxar para cima aumenta; para baixo reduz.
                    val delta=(resizeStartY-e.y)/maxDimension()
                    scale=(resizeStartScale+delta*1.25f).coerceIn(0.55f,2.0f)
                } else if(drawing) {
                    endX=e.x.coerceIn(0f,width.toFloat()); endY=e.y.coerceIn(0f,height-135f)
                }
                invalidate(); return true
            }
            MotionEvent.ACTION_UP -> {
                if(resizing) { resizing=false; invalidate(); return true }
                if(e.y>=height-125f) {
                    if(cancel.contains(e.x,e.y)) onDone(null)
                    else if(confirm.contains(e.x,e.y) && hasSelection) {
                        val r=currentRect()
                        onDone(if(r.width()>24f && r.height()>24f) r else null)
                    }
                    return true
                }
                if(drawing) {
                    endX=e.x.coerceIn(0f,width.toFloat()); endY=e.y.coerceIn(0f,height-135f)
                    drawing=false
                    hasSelection=abs(endX-startX)>28f && abs(endY-startY)>28f
                    scale=1f
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { drawing=false; resizing=false; invalidate(); return true }
        }
        return true
    }

    private fun nearHandle(x:Float,y:Float):Boolean {
        val r=currentRect(); val dx=x-r.right; val dy=y-r.bottom
        return dx*dx+dy*dy <= 58f*58f
    }

    private fun maxDimension():Float = height.toFloat().coerceAtLeast(1f)

    private fun currentRect():RectF {
        val cx=(startX+endX)/2f; val cy=(startY+endY)/2f
        val halfW=abs(endX-startX)*scale/2f; val halfH=abs(endY-startY)*scale/2f
        val l=(cx-halfW).coerceAtLeast(0f); val t=(cy-halfH).coerceAtLeast(0f)
        val r=(cx+halfW).coerceAtMost(width.toFloat()); val b=(cy+halfH).coerceAtMost((height-125f).coerceAtLeast(1f))
        return RectF(l,t,r,b)
    }
}
