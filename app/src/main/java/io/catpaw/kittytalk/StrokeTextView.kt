package io.catpaw.kittytalk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * 支持描边的 TextView：排除规则开启时文字加一圈描边，
 * 保证深色模式下浅蓝卡片背景上文字依然清晰可读。
 * 浅色模式下文字本身是深色，描边与字色同色，视觉无副作用。
 */
class StrokeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var outlineWidth = 0f
    private var outlineColor = 0

    /** 开启描边：宽度按 dp 换算，颜色默认黑色 */
    fun setOutline(widthDp: Float, color: Int = android.graphics.Color.BLACK) {
        outlineWidth = widthDp * resources.displayMetrics.density
        outlineColor = color
        invalidate()
    }

    /** 关闭描边 */
    fun clearOutline() {
        outlineWidth = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (outlineWidth > 0f && !text.isNullOrEmpty()) {
            val layout = layout
            if (layout != null) {
                val stroke = Paint(paint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = outlineWidth
                    color = outlineColor
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                }
                val padLeft = compoundPaddingLeft.toFloat()
                val padTop = compoundPaddingTop.toFloat()
                for (i in 0 until layout.lineCount) {
                    val base = layout.getLineBaseline(i) + padTop
                    val start = layout.getLineStart(i)
                    val end = layout.getLineEnd(i)
                    canvas.drawText(text.substring(start, end), padLeft + layout.getLineLeft(i), base, stroke)
                }
            }
        }
        super.onDraw(canvas)
    }
}
