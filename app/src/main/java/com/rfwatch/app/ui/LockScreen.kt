package com.rfwatch.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.MathContext

/**
 * Lock screen that is a working calculator. Typing the PIN digits and pressing "=" unlocks
 * ([checkPin]); any other input simply calculates, so someone else opening "Calculator" sees an
 * ordinary calculator. [onBiometric] (if non-null) offers fingerprint / device-credential unlock.
 */
@Composable
fun CalculatorLockScreen(checkPin: (String) -> Boolean, onUnlocked: () -> Unit, onBiometric: (() -> Unit)?) {
    var display by remember { mutableStateOf("0") }
    var raw by remember { mutableStateOf("") } // exactly what was typed since the last clear / result
    var justEvaluated by remember { mutableStateOf(false) }

    fun press(k: String) {
        when (k) {
            "C" -> { display = "0"; raw = ""; justEvaluated = false }
            "⌫" -> { raw = raw.dropLast(1); display = raw.ifEmpty { "0" } }
            "=" -> {
                if (raw.isNotEmpty() && raw.all { it.isDigit() } && checkPin(raw)) {
                    display = "0"; raw = ""; onUnlocked(); return
                }
                display = evaluate(raw) ?: "Error"
                raw = display.takeIf { it != "Error" }.orEmpty()
                justEvaluated = true
            }
            else -> {
                if (justEvaluated && k.first().isDigit()) raw = ""
                justEvaluated = false
                raw += k
                display = raw
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF1C1C1E)).padding(16.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(
            display, color = Color.White, fontSize = 56.sp, textAlign = TextAlign.End, maxLines = 2,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
        val rows = listOf(
            listOf("C", "⌫", "%", "÷"), listOf("7", "8", "9", "×"), listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"), listOf("0", ".", "=", ""),
        )
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { k ->
                    Box(Modifier.weight(1f).aspectRatio(1.15f).padding(vertical = 5.dp)) {
                        if (k.isNotEmpty()) {
                            val op = k in setOf("÷", "×", "−", "+", "=")
                            Button(
                                onClick = { press(k) },
                                modifier = Modifier.fillMaxSize(),
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (op) Color(0xFFFF9F0A) else if (k in setOf("C", "⌫", "%")) Color(0xFFA5A5A5) else Color(0xFF333333),
                                    contentColor = if (k in setOf("C", "⌫", "%")) Color.Black else Color.White,
                                ),
                            ) { Text(k, fontSize = 26.sp) }
                        }
                    }
                }
            }
        }
        if (onBiometric != null) {
            TextButton(onClick = onBiometric, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("•", color = Color(0xFF555555), fontSize = 20.sp) // deliberately inconspicuous
            }
        }
    }
}

/** Left-to-right evaluation with × ÷ before + − ; returns a trimmed decimal string or null. */
private fun evaluate(expr: String): String? = runCatching {
    if (expr.isBlank()) return "0"
    val tokens = Regex("(\\d+\\.?\\d*|\\.\\d+|[+−×÷%])").findAll(expr).map { it.value }.toMutableList()
    if (tokens.isEmpty()) return null
    val mc = MathContext.DECIMAL64
    // Unary % : turn "n %" into n/100.
    var i = 0
    while (i < tokens.size) {
        if (tokens[i] == "%" && i > 0) { tokens[i - 1] = BigDecimal(tokens[i - 1]).divide(BigDecimal(100), mc).toPlainString(); tokens.removeAt(i) } else i++
    }
    fun pass(ops: Set<String>) {
        var j = 1
        while (j < tokens.size - 1) {
            val op = tokens[j]
            if (op in ops) {
                val a = BigDecimal(tokens[j - 1]); val b = BigDecimal(tokens[j + 1])
                val r = when (op) { "×" -> a.multiply(b, mc); "÷" -> a.divide(b, mc); "+" -> a.add(b, mc); else -> a.subtract(b, mc) }
                tokens[j - 1] = r.toPlainString(); tokens.removeAt(j); tokens.removeAt(j)
            } else j += 2
        }
    }
    pass(setOf("×", "÷")); pass(setOf("+", "−"))
    BigDecimal(tokens.first()).stripTrailingZeros().toPlainString()
}.getOrNull()
