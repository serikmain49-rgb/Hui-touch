package com.huitouch.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Скриншот/изображение игры + две перетаскиваемые точки калибровки A и B.
 *
 * Позиции точек внутри виджета хранятся в НОРМИРОВАННЫХ координатах
 * (0..1 относительно изображения); владелец (OverlayService) сам
 * проецирует их на физическое разрешение экрана — так маппинг не зависит
 * от того, скриншот это или картинка из галереи в любом разрешении.
 */
class ScreenshotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Вызывается при каждом движении точки (нормированные aX, aY, bX, bY). */
    var onPointsChanged: ((aX: Float, aY: Float, bX: Float, bY: Float) -> Unit)? = null

    private var bitmap: Bitmap? = null

    // нормированные координаты точек внутри изображения
    private var aX = 0.25f; private var aY = 0.5f
    private var bX = 0.75f; private var bY = 0.5f

    private var dragging: Int = 0   // 0 = нет, 1 = точка A, 2 = точка B

    private val d = resources.displayMetrics.density
    private val hitRadius = 26f * d

    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        color = 0x88888888.toInt()
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        color = 0xD900E676.toInt()
        pathEffect = DashPathEffect(floatArrayOf(10f * d, 8f * d), 0f)
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f * d
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 13f * d
        isFakeBoldText = true
    }

    fun setBitmap(bmp: Bitmap) {
        bitmap = bmp
        requestLayout()   // высота пересчитывается под новый aspect ratio
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val b = bitmap
        val h = if (b != null && b.width > 0) {
            (w * b.height.toFloat() / b.width).toInt().coerceAtLeast(1)
        } else {
            w * 9 / 16
        }
        setMeasuredDimension(w, h)
    }

    /** Геометрия изображения в пикселях виджета: (top, imgW, imgH). */
    private fun geom(): Triple<Float, Float, Float> {
        val b = bitmap ?: return Triple(0f, 0f, 0f)
        val scale = width.toFloat() / b.width
        val imgH = b.height * scale
        val top = (height - imgH) / 2f
        return Triple(top, width.toFloat(), imgH)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap
        if (b == null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xBBBBBBBB.toInt()
                textSize = 13f * d
            }
            val text = "Нажмите «Скриншот» или «Галерея»\nТочки A/B перетаскиваются пальцем"
            val tw = p.measureText(text)
            canvas.drawText(text, (width - tw) / 2f, height / 2f, p)
            return
        }

        val (top, imgW, imgH) = geom()

        canvas.drawBitmap(b, null, RectF(0f, top, imgW, top + imgH), imgPaint)
        canvas.drawRect(0f, top, imgW, top + imgH, framePaint)

        val ax = aX * imgW
        val ay = top + aY * imgH
        val bx = bX * imgW
        val by = top + bY * imgH

        canvas.drawLine(ax, ay, bx, by, linePaint)
        drawPoint(canvas, ax, ay, 0xFF00E676.toInt(), "A")
        drawPoint(canvas, bx, by, 0xFFFF5252.toInt(), "B")
    }

    private fun drawPoint(canvas: Canvas, x: Float, y: Float, color: Int, label: String) {
        val r = 11f * d
        pointPaint.color = color
        pointPaint.style = Paint.Style.STROKE
        canvas.drawCircle(x, y, r, pointPaint)
        pointPaint.style = Paint.Style.FILL
        pointPaint.alpha = 110
        canvas.drawCircle(x, y, r, pointPaint)
        pointPaint.alpha = 255
        canvas.drawCircle(x, y, 2.5f * d, pointPaint)
        // подпись
        val tx = x + r + 3f * d
        val ty = y - r - 3f * d
        canvas.drawText(label, tx, ty, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        val (top, imgW, imgH) = geom()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val ax = aX * imgW
                val ay = top + aY * imgH
                val bx = bX * imgW
                val by = top + bY * imgH
                if (hypot(event.x - ax, event.y - ay) <= hitRadius) {
                    dragging = 1
                } else if (hypot(event.x - bx, event.y - by) <= hitRadius) {
                    dragging = 2
                }
                if (dragging != 0) parent?.requestDisallowInterceptTouchEvent(true)
                return dragging != 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging == 0) return false
                val nx = (event.x / imgW).coerceIn(0f, 1f)
                val ny = ((event.y - top) / imgH).coerceIn(0f, 1f)
                if (dragging == 1) {
                    aX = nx; aY = ny
                } else {
                    bX = nx; bY = ny
                }
                invalidate()
                onPointsChanged?.invoke(aX, aY, bX, bY)
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                dragging = 0
            }
        }
        return true
    }
}
