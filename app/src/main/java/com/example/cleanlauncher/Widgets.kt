package com.example.cleanlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/** Tiny calculator for Spotlight: "12*(3+4)" -> "84" */
object Calc {
    fun eval(src: String): String? {
        val s = src.replace(" ", "").replace("×", "*").replace("÷", "/")
            .replace("x", "*").replace("X", "*")
        if (s.length < 3) return null
        if (!s.all { it.isDigit() || it in "+-*/().%^" }) return null
        if (s.none { it.isDigit() }) return null
        if (s.drop(1).none { it in "+-*/%^" }) return null
        val v = P(s).parse() ?: return null
        if (v.isNaN() || v.isInfinite()) return null
        return java.math.BigDecimal(v).setScale(8, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()
    }

    private class P(val s: String) {
        var i = 0
        fun parse(): Double? = try {
            val v = expr()
            if (i == s.length) v else null
        } catch (e: Exception) {
            null
        }

        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        fun term(): Double {
            var v = factor()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
                val op = s[i++]
                val r = factor()
                v = when (op) {
                    '*' -> v * r
                    '/' -> v / r
                    else -> v % r
                }
            }
            return v
        }

        fun factor(): Double {
            val v = unary()
            if (i < s.length && s[i] == '^') {
                i++
                return Math.pow(v, factor())
            }
            return v
        }

        fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            return atom()
        }

        fun atom(): Double {
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                if (i >= s.length || s[i] != ')') throw IllegalStateException()
                i++
                return v
            }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) throw IllegalStateException()
            return s.substring(st, i).toDouble()
        }
    }
}

/** Built-in Big Sur style wallpaper (gradient sky + layered ridges) */
class WallpaperView(c: Context) : View(c) {
    var night = false
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val sky = if (night)
            intArrayOf(0xFF0B1026.toInt(), 0xFF2B1B5A.toInt(), 0xFFB5476B.toInt())
        else
            intArrayOf(0xFF3E5FD8.toInt(), 0xFFC06CCB.toInt(), 0xFFFFB27A.toInt())
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.78f, sky, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)

        p.shader = RadialGradient(
            w * 0.72f, h * 0.50f, w * 0.55f,
            intArrayOf(if (night) 0x44FFFFFF.toInt() else 0x66FFE9B8.toInt(), 0x00FFFFFF),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null

        if (night) ridge(canvas, w, h, 0.60f, 0.05f, 0x55120A30.toInt(), 0.4f)
        else ridge(canvas, w, h, 0.60f, 0.05f, 0x553A1E6B.toInt(), 0.4f)
        ridge(canvas, w, h, 0.70f, 0.06f, if (night) 0x99160C3A.toInt() else 0x992B1760.toInt(), 1.7f)
        ridge(canvas, w, h, 0.80f, 0.06f, if (night) 0xCC0E0828.toInt() else 0xCC1C1050.toInt(), 3.1f)
        ridge(canvas, w, h, 0.90f, 0.05f, if (night) 0xFF080418.toInt() else 0xFF120A3A.toInt(), 4.4f)
    }

    private fun ridge(c: Canvas, w: Float, h: Float, base: Float, amp: Float, color: Int, phase: Float) {
        val path = Path()
        path.moveTo(0f, h)
        path.lineTo(0f, h * base)
        val steps = 6
        for (k in 1..steps) {
            val x = w * k / steps
            val y = h * (base + amp * Math.sin((k * 1.7f + phase).toDouble()).toFloat())
            val cx = w * (k - 0.5f) / steps
            val cy = h * (base - amp * 1.3f * Math.cos((k * 1.1f + phase).toDouble()).toFloat())
            path.quadTo(cx, cy, x, y)
        }
        path.lineTo(w, h)
        path.close()
        p.style = Paint.Style.FILL
        p.color = color
        c.drawPath(path, p)
    }
}

/** Launchpad icon for the dock: grey squircle with coloured 3x3 grid */
class LaunchpadGlyph(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = intArrayOf(
        0xFFFF5F57.toInt(), 0xFFFEBC2E.toInt(), 0xFF28C840.toInt(),
        0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF9F0A.toInt(),
        0xFF30D5C8.toInt(), 0xFFFF375F.toInt(), 0xFF64D2FF.toInt()
    )

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.shader = LinearGradient(0f, 0f, 0f, h, 0xFFF1F2F7.toInt(), 0xFF9DA1B2.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, 0f, w, h, w * 0.225f, w * 0.225f, p)
        p.shader = null
        val cell = w * 0.2f
        val gap = w * 0.08f
        val start = (w - (3 * cell + 2 * gap)) / 2f
        for (r in 0 until 3) {
            for (k in 0 until 3) {
                p.color = colors[r * 3 + k]
                val x = start + k * (cell + gap)
                val y = start + r * (cell + gap)
                canvas.drawRoundRect(x, y, x + cell, y + cell, cell * 0.3f, cell * 0.3f, p)
            }
        }
    }
}

/** Tiny menu-bar battery icon */
class BatteryView(c: Context) : View(c) {
    private var level = 100
    private var charging = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(l: Int, ch: Boolean) {
        level = l
        charging = ch
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bodyW = w - h * 0.18f
        p.style = Paint.Style.STROKE
        p.strokeWidth = h * 0.09f
        p.color = 0xCCFFFFFF.toInt()
        canvas.drawRoundRect(RectF(h * 0.05f, h * 0.05f, bodyW - h * 0.05f, h - h * 0.05f), h * 0.22f, h * 0.22f, p)
        p.style = Paint.Style.FILL
        p.color = when {
            charging -> 0xFF30D158.toInt()
            level <= 15 -> 0xFFFF453A.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        val inset = h * 0.17f
        val fillW = (bodyW - 2f * inset) * level.coerceIn(0, 100) / 100f
        canvas.drawRoundRect(RectF(inset, inset, inset + fillW, h - inset), h * 0.1f, h * 0.1f, p)
        p.color = 0xCCFFFFFF.toInt()
        canvas.drawRoundRect(RectF(bodyW, h * 0.35f, w, h * 0.65f), h * 0.1f, h * 0.1f, p)
    }
}

/** Dock that magnifies icons under the finger (macOS style) */
class DockLayout(c: Context) : LinearLayout(c) {
    var magnifyOn = true

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (magnifyOn) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> magnify(e.x)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
            }
        }
        return super.dispatchTouchEvent(e)
    }

    private fun magnify(x: Float) {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val cx = v.left + v.width / 2f
            val d = abs(x - cx) / (v.width * 1.7f)
            val s = 1f + 0.5f * max(0f, 1f - d)
            v.animate().cancel()
            v.pivotX = v.width / 2f
            v.pivotY = v.height.toFloat()
            v.scaleX = s
            v.scaleY = s
        }
    }

    private fun reset() {
        for (i in 0 until childCount) {
            getChildAt(i).animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(OvershootInterpolator(2.2f)).start()
        }
    }
}

/** Rounded app icon with optional red badge (count) and running dot */
class IconView(c: Context, private val size: Int, topPad: Int) : FrameLayout(c) {
    val img = ImageView(c)
    private val badge = TextView(c)
    private val dot = View(c)
    private val box = FrameLayout(c)

    init {
        val d = c.resources.displayMetrics.density
        clipChildren = false
        box.clipChildren = false

        img.scaleType = ImageView.ScaleType.FIT_XY
        img.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, v.width * 0.225f)
            }
        }
        img.clipToOutline = true
        img.elevation = 3f * d
        box.addView(img, FrameLayout.LayoutParams(size, size))

        badge.setTextColor(0xFFFFFFFF.toInt())
        badge.textSize = 11f
        badge.gravity = Gravity.CENTER
        badge.setTypeface(null, Typeface.BOLD)
        badge.background = GradientDrawable().apply {
            setColor(0xFFFF3B30.toInt())
            cornerRadius = 20f * d
        }
        badge.minWidth = (18 * d).toInt()
        badge.minHeight = (18 * d).toInt()
        badge.setPadding((5 * d).toInt(), 0, (5 * d).toInt(), 0)
        badge.elevation = 6f * d
        badge.visibility = View.GONE
        val blp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
        blp.topMargin = (-5 * d).toInt()
        blp.rightMargin = (-5 * d).toInt()
        box.addView(badge, blp)

        val boxLp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        boxLp.topMargin = topPad
        addView(box, boxLp)

        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xDDFFFFFF.toInt())
        }
        dot.visibility = View.GONE
        val dlp = FrameLayout.LayoutParams((4 * d).toInt(), (4 * d).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        dlp.bottomMargin = (3 * d).toInt()
        addView(dot, dlp)
    }

    fun bind(icon: Drawable, count: Int, running: Boolean) {
        img.setImageDrawable(icon)
        if (count > 0) {
            badge.text = if (count > 99) "99+" else count.toString()
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }
        dot.visibility = if (running) View.VISIBLE else View.GONE
    }
}
