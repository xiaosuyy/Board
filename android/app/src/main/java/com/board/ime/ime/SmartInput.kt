package com.board.ime.ime

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 输入端的小工具：日期 / 时间 / 星期 / 计算器 / 大写金额。
 *
 * 纯函数、无 Android 依赖，方便单元测试。所有结果都是**候选**：
 * 用户选择后才上屏，不会自动替换正在输入的拼音。
 */
object SmartInput {

    data class Suggestion(val display: String, val commit: String)

    /** 拼音命令 → 候选（首选后面紧跟真实日期 / 时间 / 星期） */
    fun suggestions(raw: String, now: LocalDateTime = LocalDateTime.now()): List<Suggestion> =
        when (raw.trim().lowercase()) {
            "riqi", "rq", "jintian", "jt" -> dates(now.toLocalDate())
            "mingtian", "mt" -> dates(now.toLocalDate().plusDays(1))
            "zuotian", "zt" -> dates(now.toLocalDate().minusDays(1))
            "shijian", "sj", "xianzai", "xz" -> times(now)
            "xingqi", "xq", "zhouji" -> weekdays(now.toLocalDate())
            else -> emptyList()
        }

    /**
     * `v` + 表达式：`v1+2*3` → 7；只打一个数 `v12345.67` → 大写金额 + 数字读法。
     * 表达式非法时返回空列表（不弹错误、不影响正常输入）。
     */
    fun expression(raw: String, now: LocalDateTime = LocalDateTime.now()): List<Suggestion> {
        val body = raw.trim().removePrefix("v").removePrefix("V").trim()
        if (body.isEmpty() || body.length > MAX_EXPRESSION_LENGTH) return emptyList()

        val number = body.toBigDecimalOrNull()
        if (number != null) {
            val rmb = formatRmb(number)
            val amount = formatAmount(number)
            return listOf(Suggestion(rmb, rmb), Suggestion(amount, amount))
        }
        val value = Calculator.evaluate(body) ?: return emptyList()
        val plain = value.stripTrailingZeros().toPlainString()
        // 计算器结果放第一；金额 / 读法只在结果是纯数字时才有意义，这里不重复给
        return listOf(Suggestion(plain, plain), Suggestion("= $plain", plain))
    }

    // ------------------------------------------------------------ 日期 / 时间

    private fun dates(date: LocalDate): List<Suggestion> = listOf(
        Suggestion(formatChineseDate(date), formatChineseDate(date)),
        Suggestion(formatIsoDate(date), formatIsoDate(date)),
        Suggestion("${date.monthValue}月${date.dayOfMonth}日", "${date.monthValue}月${date.dayOfMonth}日"),
    )

    private fun times(now: LocalDateTime): List<Suggestion> {
        val hm = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        val hms = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val chinese = "${now.hour}点${now.minute}分"
        return listOf(Suggestion(hm, hm), Suggestion(hms, hms), Suggestion(chinese, chinese))
    }

    private fun weekdays(date: LocalDate): List<Suggestion> {
        val full = weekdayText(date)
        val short = "周" + full.last()
        return listOf(
            Suggestion(full, full),
            Suggestion(short, short),
            Suggestion("$full ${formatIsoDate(date)}", "$full ${formatIsoDate(date)}"),
        )
    }

    private fun weekdayText(date: LocalDate): String = when (date.dayOfWeek.value) {
        1 -> "星期一"
        2 -> "星期二"
        3 -> "星期三"
        4 -> "星期四"
        5 -> "星期五"
        6 -> "星期六"
        else -> "星期日"
    }

    private fun formatChineseDate(date: LocalDate): String =
        "${date.year}年${date.monthValue}月${date.dayOfMonth}日"

    private fun formatIsoDate(date: LocalDate): String =
        "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)

    // ------------------------------------------------------------ 金额

    private val UPPER_DIGITS = "零壹贰叁肆伍陆柒捌玖"
    private val LOWER_DIGITS = "零一二三四五六七八九"
    private val SECTION_UNITS = arrayOf("", "万", "亿", "万亿")
    private val PLACE_UNITS = arrayOf("", "拾", "佰", "仟")
    private val LOWER_PLACE_UNITS = arrayOf("", "十", "百", "千")

    /** 人民币大写：12345.67 → 壹万贰仟叁佰肆拾伍元陆角柒分 */
    fun formatRmb(value: BigDecimal): String {
        val normalized = value.setScale(2, RoundingMode.DOWN)
        if (normalized.signum() < 0) return formatRmb(normalized.negate()) + "（负）"

        val integer = normalized.toBigInteger().toLong()
        val cents = normalized.subtract(BigDecimal(integer)).movePointRight(2).toLong()
        val jiao = cents / 10
        val fen = cents % 10

        val sb = StringBuilder()
        sb.append(sections(integer, UPPER_DIGITS, PLACE_UNITS)).append('元')
        if (jiao == 0L && fen == 0L) {
            sb.append('整')
        } else {
            if (jiao > 0) sb.append(UPPER_DIGITS[jiao.toInt()]).append('角')
            if (fen > 0) {
                if (jiao == 0L && integer > 0) sb.append('零')
                sb.append(UPPER_DIGITS[fen.toInt()]).append('分')
            }
        }
        return sb.toString()
    }

    /** 数字读法：12345.67 → 一万二千三百四十五点六七 */
    fun formatAmount(value: BigDecimal): String {
        val normalized = value.stripTrailingZeros()
        if (normalized.signum() < 0) return "负" + formatAmount(normalized.negate())

        val plain = normalized.toPlainString()
        val parts = plain.split('.')
        val integer = parts[0].toLongOrNull() ?: return plain
        val sb = StringBuilder(sections(integer, LOWER_DIGITS, LOWER_PLACE_UNITS))
        if (parts.size == 2) {
            sb.append('点')
            parts[1].forEach { ch -> sb.append(LOWER_DIGITS[ch - '0']) }
        }
        return sb.toString()
    }

    /**
     * 按「四位一节」读整数。
     *
     * 零的处理：连续 0 只读一个「零」；节末尾的 0 不读；
     * 小节不足千位时（如 10001）要补读一个「零」。
     */
    private fun sections(number: Long, digits: String, places: Array<String>): String {
        if (number == 0L) return digits[0].toString()

        val groups = ArrayList<Int>(4)
        var rest = number
        while (rest > 0) {
            groups += (rest % 10000).toInt()
            rest /= 10000
        }

        val sb = StringBuilder()
        var zeroPending = false
        for (index in groups.indices.reversed()) {
            val group = groups[index]
            if (group == 0) {
                if (sb.isNotEmpty()) zeroPending = true
                continue
            }
            if (sb.isNotEmpty() && (zeroPending || group < 1000)) {
                // 已有内容且当前小节最高位是 0 → 补一个「零」
                if (sb.last() != digits[0]) sb.append(digits[0])
            }
            sb.append(group(group, digits, places)).append(SECTION_UNITS[index])
            zeroPending = false
        }
        return sb.toString()
    }

    private fun group(value: Int, digits: String, places: Array<String>): String {
        val sb = StringBuilder()
        var rest = value
        var zeroPending = false
        for (place in 3 downTo 0) {
            val digit = rest / POW10[place]
            rest %= POW10[place]
            if (digit == 0) {
                if (sb.isNotEmpty()) zeroPending = true
                continue
            }
            if (zeroPending) {
                sb.append(digits[0])
                zeroPending = false
            }
            sb.append(digits[digit]).append(places[place])
        }
        return sb.toString()
    }

    /** 按下标取位权：place 3 = 千位 */
    private val POW10 = intArrayOf(1, 10, 100, 1000)

    private const val MAX_EXPRESSION_LENGTH = 48

    // ------------------------------------------------------------ 计算器

    /** 四则运算 + 括号 + 小数。除零 / 语法错误返回 null。 */
    object Calculator {

        fun evaluate(expression: String): BigDecimal? = runCatching {
            val parser = Parser(expression)
            val value = parser.expression()
            if (parser.hasMore()) null else value
        }.getOrNull()

        private class Parser(private val text: String) {
            private var index = 0

            fun hasMore(): Boolean {
                skipSpaces()
                return index < text.length
            }

            fun expression(): BigDecimal {
                var value = term()
                while (true) {
                    skipSpaces()
                    value = when (peek()) {
                        '+' -> { index++; value + term() }
                        '-' -> { index++; value - term() }
                        else -> return value
                    }
                }
            }

            private fun term(): BigDecimal {
                var value = factor()
                while (true) {
                    skipSpaces()
                    value = when (peek()) {
                        '*' -> { index++; value * factor() }
                        '/' -> {
                            index++
                            val divisor = factor()
                            if (divisor.signum() == 0) throw ArithmeticException("divide by zero")
                            value.divide(divisor, 10, RoundingMode.HALF_UP)
                        }

                        else -> return value
                    }
                }
            }

            private fun factor(): BigDecimal {
                skipSpaces()
                return when (val ch = peek()) {
                    '(' -> {
                        index++
                        val value = expression()
                        skipSpaces()
                        if (peek() != ')') throw IllegalArgumentException("missing )")
                        index++
                        value
                    }

                    '-' -> {
                        index++
                        factor().negate()
                    }

                    '+' -> {
                        index++
                        factor()
                    }

                    else -> {
                        if (!ch.isDigit() && ch != '.') throw IllegalArgumentException("unexpected $ch")
                        val start = index
                        while (index < text.length && (text[index].isDigit() || text[index] == '.')) index++
                        BigDecimal(text.substring(start, index))
                    }
                }
            }

            private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

            private fun skipSpaces() {
                while (index < text.length && text[index] == ' ') index++
            }
        }
    }
}
