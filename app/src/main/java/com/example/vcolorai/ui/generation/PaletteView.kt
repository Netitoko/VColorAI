package com.example.vcolorai.ui.generation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class PaletteView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var colors: List<Int> = emptyList()
    private val paint = Paint()

    fun setColors(colors: List<Int>) {
        this.colors = colors
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (colors.isEmpty()) return

        val rectWidth = width / colors.size.toFloat()
        colors.forEachIndexed { index, color ->
            paint.color = color
            canvas.drawRect(index * rectWidth, 0f, (index + 1) * rectWidth, height.toFloat(), paint)
        }
    }
}
