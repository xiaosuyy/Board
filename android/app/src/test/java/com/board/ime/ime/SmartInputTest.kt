package com.board.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** 日期 / 时间 / 星期 / 计算器 / 金额：纯函数，固定时钟即可断言。 */
class SmartInputTest {

    private val now = LocalDateTime.of(2026, 11, 14, 15, 52, 7)

    private fun displays(raw: String): List<String> =
        SmartInput.suggestions(raw, now).map { it.display }

    @Test
    fun `riqi gives today in three forms`() {
        assertEquals(
            listOf("2026年11月14日", "2026-11-14", "11月14日"),
            displays("riqi"),
        )
        assertEquals(displays("riqi"), displays("rq"))
    }

    @Test
    fun `mingtian is tomorrow`() {
        assertEquals("2026年11月15日", displays("mingtian").first())
    }

    @Test
    fun `shijian gives the current time`() {
        assertEquals(listOf("15:52", "15:52:07", "15点52分"), displays("sj"))
    }

    @Test
    fun `xingqi gives the weekday`() {
        assertEquals(listOf("星期六", "周六", "星期六 2026-11-14"), displays("xingqi"))
    }

    @Test
    fun `unknown input has no smart candidates`() {
        assertEquals(emptyList<String>(), displays("nihao"))
        assertEquals(emptyList<String>(), displays("ri"))
    }

    @Test
    fun `calculator evaluates expression`() {
        assertEquals("7", SmartInput.expression("v1+2*3", now).first().commit)
        assertEquals("9", SmartInput.expression("v(1+2)*3", now).first().commit)
        assertEquals("2.5", SmartInput.expression("v10/4", now).first().commit)
        assertEquals("0.3", SmartInput.expression("v0.1+0.2", now).first().commit)
    }

    @Test
    fun `invalid expression yields nothing`() {
        assertEquals(emptyList<SmartInput.Suggestion>(), SmartInput.expression("v1+", now))
        assertEquals(emptyList<SmartInput.Suggestion>(), SmartInput.expression("v1/0", now))
        assertEquals(emptyList<SmartInput.Suggestion>(), SmartInput.expression("v", now))
        assertEquals(emptyList<SmartInput.Suggestion>(), SmartInput.expression("nihao", now))
    }

    @Test
    fun `a single number gives RMB uppercase then reading`() {
        // 输入 v12345.67 直接给「大写金额」+「数字读法」两个候选
        val list = SmartInput.expression("v12345.67", now)
        assertEquals(2, list.size)
        assertEquals("壹万贰仟叁佰肆拾伍元陆角柒分", list[0].commit)
        assertEquals("一万二千三百四十五点六七", list[1].commit)
    }

    @Test
    fun `rmb zero handling`() {
        assertEquals("壹佰元零伍分", SmartInput.formatRmb("100.05".toBigDecimal()))
        assertEquals("壹拾元整", SmartInput.formatRmb("10".toBigDecimal()))
        assertEquals("零元整", SmartInput.formatRmb("0".toBigDecimal()))
        assertEquals("壹万零壹元整", SmartInput.formatRmb("10001".toBigDecimal()))
    }

    @Test
    fun `amount reading keeps decimal digits`() {
        assertEquals("一百点零五", SmartInput.formatAmount("100.05".toBigDecimal()))
        assertEquals("一万二千三百四十五点六七", SmartInput.formatAmount("12345.67".toBigDecimal()))
    }
}
