package com.board.ime.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 本地中英释义 TSV 资源的**结构 / 质量**检查（P3）。
 *
 * 直接校验真实打包文件 `assets/chinese_english.tsv`，覆盖需求要求的自动检查：
 * 1. duplicate Chinese key
 * 2. empty Chinese
 * 3. empty English
 * 4. malformed TSV（不是恰好一个 TAB）
 * 5. accidental English-only key
 * 6. whitespace-only key
 * 7. forbidden ambiguous mappings（使/时/事/市/是/的/了/在/和/还/试/把/被/将/等）
 *
 * 单字规则（P3 调整）：不再“一刀切禁止单字”，而是**只允许明确单字**（见 [ALLOWED_SINGLE]）；
 * 歧义 / 依赖上下文的单字仍然禁止（见 [FORBIDDEN_SINGLE]）。
 * 另外约束：英文不含 `/` 同义堆叠；词条数 >= 5000；支持任意长度的词 / 短语 / 短句。
 */
class ChineseEnglishResourceQualityTest {

    /** 数据行（去掉空行与 `#` 注释行） */
    private lateinit var dataLines: List<String>

    @Before
    fun loadResource() {
        val file = ASSET_CANDIDATES.firstOrNull { it.isFile }
        assertNotNull(
            "未找到 assets/$ASSET_NAME（单测工作目录=${System.getProperty("user.dir")}）",
            file,
        )
        val text = file!!.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        dataLines = text.lineSequence()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .toList()
    }

    private fun key(line: String): String = line.substringBefore('\t').trim()

    private fun value(line: String): String = line.substringAfter('\t').trim()

    @Test
    fun `entry count is at least 5000`() {
        assertTrue("有效词条仅 ${dataLines.size} 条，需 >= 5000", dataLines.size >= 5000)
    }

    @Test
    fun `no duplicate chinese keys`() {
        val duplicates = dataLines.groupBy { key(it) }.filterValues { it.size > 1 }.keys
        assertTrue("存在重复中文 key：$duplicates", duplicates.isEmpty())
    }

    @Test
    fun `every data line has exactly one tab`() {
        val malformed = dataLines.filter { it.count { ch -> ch == '\t' } != 1 }
        assertTrue("存在格式错误（TAB 数量不为 1）的行：$malformed", malformed.isEmpty())
    }

    @Test
    fun `no empty key or value and no whitespace only key`() {
        val bad = dataLines.filter { key(it).isEmpty() || value(it).isEmpty() }
        assertTrue("存在空 key / 空 value 的行：$bad", bad.isEmpty())
    }

    @Test
    fun `keys are chinese with no whitespace`() {
        dataLines.forEach { line ->
            val k = key(line)
            assertTrue("key 不含中文：$line", k.any { it in '\u4e00'..'\u9fff' })
            assertFalse("key 含空白：$line", k.any { it.isWhitespace() })
        }
    }

    @Test
    fun `forbidden ambiguous keys are absent`() {
        val present = dataLines.map { key(it) }.filter { it in FORBIDDEN_SINGLE }
        assertTrue("存在被禁止的歧义 key：$present", present.isEmpty())
    }

    @Test
    fun `single character keys are limited to the explicit allow list`() {
        val bad = dataLines.map { key(it) }.filter { it.length == 1 && it !in ALLOWED_SINGLE }
        assertTrue("出现未允许的单字 key：$bad", bad.isEmpty())
    }

    @Test
    fun `clear single characters are present`() {
        val keys = dataLines.map { key(it) }.toSet()
        listOf("十", "手", "人", "心", "新", "水", "钱", "爱").forEach { word ->
            assertTrue("缺少明确单字：$word", word in keys)
        }
    }

    @Test
    fun `longer sentence-like keys are supported`() {
        val keys = dataLines.map { key(it) }.toSet()
        assertTrue("缺少短句 key：我要吃饭", "我要吃饭" in keys)
        assertTrue("缺少短句 key：你在干什么", "你在干什么" in keys)
        // 词条允许比 4 字更长（短句）
        assertTrue("缺少更长的句子 key：我在路上了", "我在路上了" in keys)
    }

    @Test
    fun `english values do not stuff multiple synonyms`() {
        val bad = dataLines.filter { value(it).contains('/') || value(it).contains('、') }
        assertTrue("英文释义疑似堆叠多个同义词：$bad", bad.isEmpty())
    }

    @Test
    fun `every data line is comment free`() {
        assertTrue(dataLines.none { key(it).startsWith("#") })
    }

    @Test
    fun `parser yields exactly the data lines and skips comments`() {
        val text = ASSET_CANDIDATES.first { it.isFile }.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        val parsed = ChineseEnglishTsv.parse(text)
        assertTrue("解析条数与数据行不一致：${parsed.size} vs ${dataLines.size}", parsed.size == dataLines.size)
        assertTrue(parsed.keys.none { it.startsWith("#") })
    }

    private companion object {
        const val ASSET_NAME = "chinese_english.tsv"

        val ASSET_CANDIDATES = listOf(
            java.io.File("src/main/assets/$ASSET_NAME"),
            java.io.File("android/app/src/main/assets/$ASSET_NAME"),
            java.io.File("../app/src/main/assets/$ASSET_NAME"),
        )

        /** 允许出现的明确单字（含义稳定、可独立对应常用英文） */
        val ALLOWED_SINGLE: Set<String> = setOf(
            "一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "零", "百", "千", "万", "亿",
            "我", "你", "他", "她", "谁",
            "人", "手", "心", "头", "眼", "耳", "鼻", "口", "牙", "脚", "腿", "血", "骨",
            "天", "水", "火", "山", "海", "河", "湖", "雨", "雪", "风", "云", "星", "月", "日", "光", "土", "石", "木", "花", "草", "树",
            "家", "门", "书", "车", "钱", "饭", "茶", "酒", "药", "桌", "椅", "床", "灯", "钟", "伞", "包", "鞋", "帽", "衣", "笔", "纸", "刀", "碗", "杯", "瓶", "锅",
            "猫", "狗", "鱼", "鸟", "马", "牛", "羊", "猪", "鸡", "鸭", "虎", "龙",
            "新", "大", "小", "多", "高", "低", "长", "短", "快", "慢", "冷", "热", "真", "假", "好", "坏", "美", "丑", "香", "甜", "苦", "辣", "酸", "咸",
            "上", "下", "中", "东", "南", "西", "北", "左", "右", "前", "后", "里", "外", "内", "近", "远",
            "爱", "吃", "喝", "看", "听", "说", "走", "跑", "写", "读", "笑", "哭", "睡", "坐", "站", "来", "去", "给", "买", "卖", "唱", "跳", "飞", "游",
            "红", "黄", "蓝", "绿", "黑", "白", "灰", "紫", "粉",
            "圆", "方", "尖", "平", "直", "弯", "空", "满", "轻", "重", "厚", "薄", "宽", "窄", "深", "浅", "粗", "细", "硬", "软", "钝",
            "早", "晚",
        )

        /** 明确禁止的单字：歧义 / 多义 / 依赖上下文 */
        val FORBIDDEN_SINGLE: Set<String> = setOf(
            "是", "时", "事", "市", "使", "的", "了", "在", "和", "还", "试", "把", "被", "将", "等",
        )
    }
}
