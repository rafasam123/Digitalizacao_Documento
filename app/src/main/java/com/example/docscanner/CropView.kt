package com.example.docscanner

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.pow
import kotlin.math.sqrt

class CropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val touchTolerance = 60f
    private val linePaint = Paint().apply { color = Color.GREEN; strokeWidth = 8f; style = Paint.Style.STROKE }
    private val pointPaint = Paint().apply { color = Color.GREEN; strokeWidth = 30f; style = Paint.Style.FILL }
    
    private var imagePoints: Array<PointF>? = null
    private var draggingPointIndex: Int = -1
    private val pointMatrix = Matrix()
    private val inversePointMatrix = Matrix()
    private var isEditable = true

    fun setEditable(editable: Boolean) {
        this.isEditable = editable
        invalidate()
    }

    fun setPoints(corners: Array<PointF>) {
        imagePoints = corners
        setEditable(true)
    }

    fun getPoints(): Array<PointF>? = imagePoints

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isEditable) return // Só desenha bordas se estiver editando

        val currentImagePoints = imagePoints ?: return
        
        // Mapeia coordenadas da imagem para a tela
        this.imageMatrix.invert(inversePointMatrix)
        pointMatrix.set(this.imageMatrix)
        val screenPoints = FloatArray(currentImagePoints.size * 2)
        currentImagePoints.forEachIndexed { i, point ->
            screenPoints[i * 2] = point.x
            screenPoints[i * 2 + 1] = point.y
        }
        pointMatrix.mapPoints(screenPoints)

        for (i in 0 until screenPoints.size / 2) {
            val p1_x = screenPoints[i * 2]
            val p1_y = screenPoints[i * 2 + 1]
            val next_i = (i + 1) % (screenPoints.size / 2)
            val p2_x = screenPoints[next_i * 2]
            val p2_y = screenPoints[next_i * 2 + 1]
            canvas.drawLine(p1_x, p1_y, p2_x, p2_y, linePaint)
            canvas.drawPoint(p1_x, p1_y, pointPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditable) return super.onTouchEvent(event)

        val currentImagePoints = imagePoints ?: return false
        
        // Mapeia toque da tela para coordenadas da imagem
        val touchPoint = floatArrayOf(event.x, event.y)
        inversePointMatrix.mapPoints(touchPoint)
        val imageTouchPoint = PointF(touchPoint[0], touchPoint[1])

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                for (i in currentImagePoints.indices) {
                    if (getDistance(currentImagePoints[i], imageTouchPoint) < touchTolerance) {
                        draggingPointIndex = i
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingPointIndex != -1) {
                    currentImagePoints[draggingPointIndex] = imageTouchPoint
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> { draggingPointIndex = -1 }
        }
        return true
    }
    
    private fun getDistance(p1: PointF, p2: PointF): Float {
        return sqrt((p1.x - p2.x).pow(2) + (p1.y - p2.y).pow(2))
    }
}
