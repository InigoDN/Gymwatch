package com.example.diamantizador

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class CircularProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var progress: Float = 0f
    private val rect = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#1F232D")
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00E676")
    }

    fun setRingState(progressFraction: Float, ringColorHex: String) {
        progress = progressFraction.coerceIn(0f, 1f)
        arcPaint.color = Color.parseColor(ringColorHex)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val stroke = size * 0.038f
        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke

        val pad = stroke / 2f + 2f
        rect.set(pad, pad, width - pad, height - pad)

        canvas.drawOval(rect, trackPaint)
        if (progress > 0f) {
            canvas.drawArc(rect, -90f, 360f * progress, false, arcPaint)
        }
    }
}