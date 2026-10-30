package com.mycalc.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.math.BigDecimal
import java.math.RoundingMode

private const val MINUS = '\u2212'
private const val TIMES = '\u00D7'
private const val DIV = '\u00F7'
private const val S_MINUS = "\u2212"
private const val S_TIMES = "\u00D7"
private const val S_DIV = "\u00F7"
private const val S_BACK = "\u2190"

private class Parser(val s: String) {
    var i = 0

    fun parse(): Double {
        val v = expr()
        if (i != s.length) throw IllegalArgumentException()
        return v
    }

    private fun expr(): Double {
        var v = term()
        while (i < s.length && (s[i] == '+' || s[i] == MINUS)) {
            val op = s[i]
            i++
            val r = term()
            v = if (op == '+') v + r else v - r
        }
        return v
    }

    private fun term(): Double {
        var v = factor()
        while (i < s.length && (s[i] == TIMES || s[i] == DIV)) {
            val op = s[i]
            i++
            val r = factor()
            if (op == TIMES) {
                v *= r
            } else {
                if (r == 0.0) throw ArithmeticException()
                v /= r
            }
        }
        return v
    }

    private fun factor(): Double {
        if (i < s.length && s[i] == MINUS) {
            i++
            return -factor()
        }
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
        if (start == i) throw IllegalArgumentException()
        var v = s.substring(start, i).toDouble()
        while (i < s.length && s[i] == '%') {
            i++
            v /= 100.0
        }
        return v
    }
}

class MainActivity : AppCompatActivity() {

    private var expr = ""
    private var hist = ""
    private var justEvaluated = false
    private lateinit var display: TextView
    private lateinit var history: TextView
    private val ops = setOf('+', MINUS, TIMES, DIV)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#101214"))
        root.setPadding(dp(12), dp(12), dp(12), dp(16))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.VERTICAL
        top.gravity = Gravity.BOTTOM or Gravity.END
        top.setPadding(dp(12), 0, dp(12), dp(8))

        history = TextView(this)
        history.textSize = 18f
        history.setTextColor(Color.parseColor("#78909C"))
        history.gravity = Gravity.END
        history.maxLines = 1
        top.addView(history, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))

        display = TextView(this)
        display.setTextColor(Color.WHITE)
        display.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        display.maxLines = 1
        display.setAutoSizeTextTypeUniformWithConfiguration(24, 64, 2, TypedValue.COMPLEX_UNIT_SP)
        top.addView(display, LinearLayout.LayoutParams(match, dp(90)))
        root.addView(top, LinearLayout.LayoutParams(match, 0, 1.1f))

        val rows = listOf(
            listOf("C", S_BACK, "%", S_DIV),
            listOf("7", "8", "9", S_TIMES),
            listOf("4", "5", "6", S_MINUS),
            listOf("1", "2", "3", "+"),
            listOf("0", ".", "=")
        )
        for (row in rows) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            for (k in row) {
                val lp = LinearLayout.LayoutParams(0, match, if (k == "0") 2f else 1f)
                lp.setMargins(dp(5), dp(5), dp(5), dp(5))
                r.addView(key(k), lp)
            }
            root.addView(r, LinearLayout.LayoutParams(match, 0, 1f))
        }
        setContentView(root)
    }

    private fun key(label: String): View {
        val tv = TextView(this)
        tv.text = label
        tv.gravity = Gravity.CENTER
        tv.textSize = 28f
        tv.setTextColor(Color.WHITE)
        val color = when (label) {
            "=", "+", S_MINUS, S_TIMES, S_DIV -> Color.parseColor("#FF9F1A")
            "C", S_BACK, "%" -> Color.parseColor("#3C4046")
            else -> Color.parseColor("#2A2D31")
        }
        val shape = GradientDrawable()
        shape.setColor(color)
        shape.cornerRadius = dp(22).toFloat()
        tv.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
        tv.setOnClickListener { press(label) }
        return tv
    }

    private fun press(label: String) {
        when (label) {
            "C" -> {
                expr = ""
                hist = ""
                justEvaluated = false
            }
            S_BACK -> {
                if (justEvaluated) {
                    expr = ""
                    justEvaluated = false
                } else {
                    expr = expr.dropLast(1)
                }
            }
            "=" -> equalsPressed()
            "." -> addDot()
            "%" -> {
                if (!justEvaluated && expr.isNotEmpty() && expr.last().isDigit()) expr += "%"
            }
            "+", S_MINUS, S_TIMES, S_DIV -> addOp(label[0])
            else -> addDigit(label)
        }
        render()
    }

    private fun currentNumber(): String = expr.takeLastWhile { it.isDigit() || it == '.' }

    private fun addDigit(d: String) {
        if (justEvaluated) {
            expr = ""
            hist = ""
            justEvaluated = false
        }
        if (expr.length >= 30) return
        if (currentNumber() == "0") expr = expr.dropLast(1)
        expr += d
    }

    private fun addDot() {
        if (justEvaluated) {
            expr = ""
            hist = ""
            justEvaluated = false
        }
        if (expr.length >= 30) return
        val cur = currentNumber()
        if (cur.contains('.')) return
        expr += if (cur.isEmpty()) "0." else "."
    }

    private fun addOp(c: Char) {
        if (justEvaluated) {
            justEvaluated = false
            hist = ""
            if (expr == "Error") expr = ""
        }
        if (expr.isEmpty()) {
            if (c == MINUS) expr = c.toString()
            return
        }
        if (expr.last() in ops) {
            if (expr.length == 1) return
            expr = expr.dropLast(1) + c
        } else {
            expr += c
        }
    }

    private fun equalsPressed() {
        // Secret code: digits only, then "=" opens the vault instead of calculating.
        if (expr.length in 4..8 && expr.all { it.isDigit() } && Passcode.verify(this, expr)) {
            expr = ""
            hist = ""
            startActivity(Intent(this, VaultActivity::class.java))
            finish()
            return
        }
        var e = expr
        while (e.isNotEmpty() && e.last() in ops) e = e.dropLast(1)
        if (e.isEmpty() || justEvaluated) return
        try {
            val v = Parser(e).parse()
            if (v.isNaN() || v.isInfinite() || Math.abs(v) > 1e15) throw ArithmeticException()
            hist = "$e ="
            expr = format(v)
        } catch (ex: Exception) {
            hist = ""
            expr = "Error"
        }
        justEvaluated = true
    }

    private fun format(v: Double): String {
        var bd = BigDecimal(v).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros()
        if (bd.scale() < 0) bd = bd.setScale(0)
        val s = bd.toPlainString()
        return if (s == "-0") "0" else s
    }

    private fun render() {
        display.text = if (expr.isEmpty()) "0" else expr
        history.text = hist
    }

    // Leaving the app (Home, Recents, screen lock) closes it and removes it from Recent Apps.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isFinishing) finishAndRemoveTask()
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) finishAndRemoveTask()
    }
}
