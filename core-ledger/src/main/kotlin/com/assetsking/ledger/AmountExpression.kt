package com.assetsking.ledger

/**
 * 记账数字键盘的即时计算（REQ 编辑器§12）：支持 + − × ÷，例如 "38.5+12" → 50.5。
 *
 * 文法（无括号，乘法除法先于加减）：
 *   expr   := term (('+'|'−'|'-') term)*
 *   term   := factor (('×'|'*'|'÷'|'/') factor)*
 *   factor := number
 * 解析失败或除零返回 null，调用方保持原表达式让用户改。
 */
object AmountExpression {

    /** 计算表达式的分结果；null = 表达式不完整或非法（含除零）。 */
    fun evaluate(expr: String): Double? {
        if (expr.isBlank()) return null
        var i = 0
        fun skip() { while (i < expr.length && expr[i].isWhitespace()) i++ }
        fun number(): Double? {
            skip()
            val start = i
            var dots = 0
            while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                if (expr[i] == '.') {
                    dots++
                    if (dots > 1) return null
                }
                i++
            }
            if (i == start || expr.substring(start, i) == ".") return null
            return expr.substring(start, i).toDoubleOrNull()
        }
        fun term(): Double? {
            var v = number() ?: return null
            while (true) {
                skip()
                val op = expr.getOrNull(i)
                if (op != '×' && op != '*' && op != '÷' && op != '/') return v
                i++
                val rhs = number() ?: return null
                if ((op == '÷' || op == '/') && rhs == 0.0) return null
                v = if (op == '÷' || op == '/') v / rhs else v * rhs
            }
        }
        var v = term() ?: return null
        while (true) {
            skip()
            val op = expr.getOrNull(i)
            if (op != '+' && op != '−' && op != '-') return v
            i++
            val rhs = term() ?: return null
            v = if (op == '-' || op == '−') v - rhs else v + rhs
        }
    }
}
