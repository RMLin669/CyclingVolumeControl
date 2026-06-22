package com.example.cyclingvolumecontrol

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class SpeedVolumeChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // 数据坐标（由外部读写）
    var maxSpeedX = 40f
        set(value) { field = value; invalidate() }
    var point1Speed = 5f
        set(value) { field = value; invalidate() }
    var point1Vol = 20f
        set(value) { field = value; invalidate() }
    var point2Speed = 30f
        set(value) { field = value; invalidate() }
    var point2Vol = 100f
        set(value) { field = value; invalidate() }

    /** 任一点位置变化时回调 */
    var onPointsChanged: ((p1Speed: Float, p1Vol: Float, p2Speed: Float, p2Vol: Float) -> Unit)? = null

    // dp 换算
    private val dp get() = resources.displayMetrics.density
    private fun dp(v: Float) = v * dp

    // 主题色（支持外部覆盖）
    var primaryColorOverride: Int? = null
        set(value) { field = value; invalidate() }

    private val primaryColor: Int
        get() {
            primaryColorOverride?.let { return it }
            val tv = TypedValue()
            context.theme.resolveAttribute(android.R.attr.colorPrimary, tv, true)
            return tv.data
        }

    private val isDark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    private val axisColor get() = if (isDark) Color.parseColor("#999999") else Color.parseColor("#666666")
    private val gridColor get() = if (isDark) Color.parseColor("#2A2A2A") else Color.parseColor("#E8E8E8")
    private val surfaceColor get() = if (isDark) Color.parseColor("#1C1C1C") else Color.parseColor("#FAFAFA")

    // Paint 对象
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val flatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pointBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = Color.WHITE; isFakeBoldText = true
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.argb(40, 0, 0, 0)
    }
    private val chartBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 图表区域（在 onSizeChanged 中计算）
    private var cLeft = 0f
    private var cTop = 0f
    private var cRight = 0f
    private var cBottom = 0f

    // 触摸状态：0=无，1=point1，2=point2
    private var dragging = 0

    private val touchRadius get() = dp(40f)
    private val rNormal get() = dp(10f)
    private val rDragging get() = dp(16f)
    private val rGlow get() = dp(24f)

    // 坐标换算
    private fun sx(speed: Float) = cLeft + (speed.coerceIn(0f, maxSpeedX) / maxSpeedX) * (cRight - cLeft)
    private fun sy(vol: Float) = cBottom - (vol.coerceIn(0f, 100f) / 100f) * (cBottom - cTop)
    private fun xs(x: Float) = ((x - cLeft) / (cRight - cLeft) * maxSpeedX).coerceIn(0f, maxSpeedX)
    private fun yv(y: Float) = ((cBottom - y) / (cBottom - cTop) * 100f).coerceIn(0f, 100f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cLeft   = dp(46f)
        cTop    = dp(20f)
        cRight  = w - dp(10f)
        cBottom = h - dp(36f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resolveSize(dp(240f).toInt(), heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        val ac = axisColor
        val gc = gridColor
        val pc = primaryColor
        val dimAlpha = if (!isEnabled) 90 else 255

        // ── 绘图区背景 ──
        chartBgPaint.color = surfaceColor; chartBgPaint.alpha = dimAlpha
        canvas.drawRoundRect(RectF(cLeft, cTop, cRight, cBottom), dp(8f), dp(8f), chartBgPaint)

        axisPaint.color = ac; axisPaint.alpha = dimAlpha; axisPaint.strokeWidth = dp(1.2f)
        gridPaint.color = gc; gridPaint.alpha = dimAlpha; gridPaint.strokeWidth = dp(0.6f)
        textPaint.textSize = dp(10f); textPaint.color = ac; textPaint.alpha = dimAlpha

        // ── 网格 Y（0/25/50/75/100%）──
        for (vol in listOf(0, 25, 50, 75, 100)) {
            val y = sy(vol.toFloat())
            canvas.drawLine(cLeft, y, cRight, y, gridPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${vol}%", cLeft - dp(4f), y + dp(4f), textPaint)
        }

        // ── 网格 X ──
        val step = when {
            maxSpeedX <= 30  -> 5
            maxSpeedX <= 60  -> 10
            maxSpeedX <= 120 -> 20
            else             -> 25
        }
        var s = 0
        while (s <= maxSpeedX.toInt()) {
            val x = sx(s.toFloat())
            canvas.drawLine(x, cTop, x, cBottom, gridPaint)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(s.toString(), x, cBottom + dp(15f), textPaint)
            s += step
        }

        // ── 轴 ──
        axisPaint.strokeWidth = dp(1.5f)
        canvas.drawLine(cLeft, cTop, cLeft, cBottom, axisPaint)
        canvas.drawLine(cLeft, cBottom, cRight, cBottom, axisPaint)

        // ── 轴标签 ──
        textPaint.textSize = dp(11f)
        textPaint.isFakeBoldText = true
        canvas.drawText("速度 km/h", (cLeft + cRight) / 2f, cBottom + dp(28f), textPaint)
        canvas.save()
        canvas.rotate(-90f, dp(10f), (cTop + cBottom) / 2f)
        canvas.drawText("音量 %", dp(10f), (cTop + cBottom) / 2f + dp(4f), textPaint)
        canvas.restore()
        textPaint.isFakeBoldText = false

        // ── 两点屏幕坐标 ──
        val x1 = sx(point1Speed); val y1 = sy(point1Vol)
        val x2 = sx(point2Speed); val y2 = sy(point2Vol)

        // 按 X 位置区分左右
        val (lx, ly, rx, ry) = if (x1 <= x2)
            floatArrayOf(x1, y1, x2, y2) else floatArrayOf(x2, y2, x1, y1)

        // ── 曲线下方渐变填充 ──
        val fillAlpha = if (!isEnabled) 20 else 40
        val fillPath = Path().apply {
            moveTo(cLeft, cBottom)
            lineTo(cLeft, ly)
            lineTo(lx, ly)
            lineTo(rx, ry)
            lineTo(cRight, ry)
            lineTo(cRight, cBottom)
            close()
        }
        val gradient = LinearGradient(
            0f, cTop, 0f, cBottom,
            intArrayOf(
                pc and 0x00FFFFFF or ((fillAlpha * 4) shl 24),
                pc and 0x00FFFFFF or ((fillAlpha / 2) shl 24)
            ),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        fillPaint.shader = gradient
        canvas.drawPath(fillPath, fillPaint)
        fillPaint.shader = null

        // ── 折线 ──
        val lineAlpha = if (!isEnabled) 90 else 220
        flatPaint.color = pc; flatPaint.alpha = (lineAlpha * 0.45f).toInt()
        flatPaint.strokeWidth = dp(2f)
        linePaint.color = pc; linePaint.alpha = lineAlpha; linePaint.strokeWidth = dp(3f)

        // 左水平线
        canvas.drawLine(cLeft, ly, lx, ly, flatPaint)
        // 中间斜线
        canvas.drawLine(lx, ly, rx, ry, linePaint)
        // 右水平线
        canvas.drawLine(rx, ry, cRight, ry, flatPaint)

        // ── 控制点光晕 ──
        val glowAlpha = if (!isEnabled) 20 else 50
        glowPaint.color = pc and 0x00FFFFFF or (glowAlpha shl 24)
        val rg1 = if (dragging == 1) rGlow * 1.3f else rGlow
        val rp2 = if (dragging == 2) rGlow * 1.3f else rGlow
        canvas.drawCircle(x1, y1, rg1, glowPaint)
        canvas.drawCircle(x2, y2, rp2, glowPaint)

        // ── 控制点 ──
        pointFillPaint.color = pc; pointFillPaint.alpha = dimAlpha
        pointBorderPaint.strokeWidth = dp(2.5f); pointBorderPaint.alpha = dimAlpha

        val r1 = if (dragging == 1) rDragging else rNormal
        val r2 = if (dragging == 2) rDragging else rNormal
        canvas.drawCircle(x1, y1, r1, pointFillPaint)
        canvas.drawCircle(x1, y1, r1, pointBorderPaint)
        canvas.drawCircle(x2, y2, r2, pointFillPaint)
        canvas.drawCircle(x2, y2, r2, pointBorderPaint)

        // ── 标签 ──
        drawLabel(canvas, x1, y1, r1, point1Speed, point1Vol, pc)
        drawLabel(canvas, x2, y2, r2, point2Speed, point2Vol, pc)
    }

    private fun drawLabel(canvas: Canvas, cx: Float, cy: Float, r: Float,
                          speed: Float, vol: Float, color: Int) {
        val line1 = "%.1f km/h".format(speed)
        val line2 = "%.0f%%".format(vol)
        val fs = dp(12f)
        labelTextPaint.textSize = fs
        val lh = fs * 1.35f
        val padH = dp(10f)
        val padV = dp(6f)
        val w = maxOf(
            labelTextPaint.measureText(line1),
            labelTextPaint.measureText(line2)
        ) + padH * 2
        val h = lh * 2 + padV * 2
        var left = cx - w / 2f
        val gap = dp(10f)
        val topIfAbove = cy - r - gap - h
        val top = if (topIfAbove >= cTop) topIfAbove else cy + r + gap

        // 不超出边界
        left = left.coerceIn(dp(2f), (cRight - w).coerceAtLeast(dp(2f)))
        val rect = RectF(left, top, left + w, top + h)

        // 标签阴影
        canvas.drawRoundRect(
            RectF(rect.left + dp(1.5f), rect.top + dp(2f), rect.right + dp(1.5f), rect.bottom + dp(2f)),
            dp(8f), dp(8f), labelShadowPaint
        )
        // 标签背景
        labelBgPaint.color = color
        canvas.drawRoundRect(rect, dp(8f), dp(8f), labelBgPaint)
        // 标签文字
        canvas.drawText(line1, rect.centerX(), top + padV + lh * 0.85f, labelTextPaint)
        canvas.drawText(line2, rect.centerX(), top + padV + lh * 1.85f, labelTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        val tx = event.x; val ty = event.y
        val x1 = sx(point1Speed); val y1 = sy(point1Vol)
        val x2 = sx(point2Speed); val y2 = sy(point2Vol)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val d1 = hypot((tx - x1).toDouble(), (ty - y1).toDouble()).toFloat()
                val d2 = hypot((tx - x2).toDouble(), (ty - y2).toDouble()).toFloat()
                dragging = when {
                    d1 <= touchRadius && d1 <= d2 -> 1
                    d2 <= touchRadius              -> 2
                    else                           -> 0
                }
                if (dragging != 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                }
                return dragging != 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging == 1) {
                    point1Speed = xs(tx); point1Vol = yv(ty)
                    onPointsChanged?.invoke(point1Speed, point1Vol, point2Speed, point2Vol)
                    invalidate()
                } else if (dragging == 2) {
                    point2Speed = xs(tx); point2Vol = yv(ty)
                    onPointsChanged?.invoke(point1Speed, point1Vol, point2Speed, point2Vol)
                    invalidate()
                }
                return dragging != 0
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = 0
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }
}
