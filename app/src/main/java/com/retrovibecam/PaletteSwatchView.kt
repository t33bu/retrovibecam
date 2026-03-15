package com.retrovibecam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class PaletteSwatchView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var colors: List<Int> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (colors.isEmpty()) {
            paint.color = Color.parseColor("#666666")
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        } else {
            val w = width.toFloat() / colors.size
            colors.forEachIndexed { i, color ->
                paint.color = color
                canvas.drawRect(i * w, 0f, (i + 1) * w, height.toFloat(), paint)
            }
        }
    }
}
