package com.board.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 本地中 → 英释义词表（P3 数据质量）单元测试。
 *
 * 覆盖三件事：
 * 1. 明确词 / 多字词有正确的英文；
 * 2. 歧义、多义、依赖上下文的词（尤其单字）**绝不出现误导性英文**；
 * 3. lookup 不到就是 null，绝不猜测；以及不访问 Room / 网络。
 *
 * 数据来自真实打包资源 `assets/chinese_english.tsv`（不是测试里另抄一份），
 * 因此这些用例验证的就是 App 运行时使用的那张表。
 */
class ChineseEnglishLexiconTest {

    @Before
    fun loadShippedLexicon() {
        val file = SHIPPED_ASSET_CANDIDATES.firstOrNull { it.isFile }
        assertNotNull(
            "未找到 assets/$ASSET_NAME（单测工作目录=${System.getProperty("user.dir")}）",
            file,
        )
        ChineseEnglishLexicon.installTsv(file!!.readText(Charsets.UTF_8))
    }

    // ---------------------------------------------------------------- 明确词

    @Test
    fun `clear common words have explicit english`() {
        assertEquals("hello", ChineseEnglishLexicon.lookup("你好"))
        assertEquals("why", ChineseEnglishLexicon.lookup("为什么"))
        assertEquals("apple", ChineseEnglishLexicon.lookup("苹果"))
        assertEquals("bank", ChineseEnglishLexicon.lookup("银行"))
        assertEquals("Beijing", ChineseEnglishLexicon.lookup("北京"))
        assertEquals("China", ChineseEnglishLexicon.lookup("中国"))
        assertEquals("Great Wall", ChineseEnglishLexicon.lookup("长城"))
        assertEquals("mobile phone", ChineseEnglishLexicon.lookup("手机"))
        assertEquals("computer", ChineseEnglishLexicon.lookup("电脑"))
        assertEquals("work", ChineseEnglishLexicon.lookup("工作"))
    }

    @Test
    fun `greetings and expressions have explicit english`() {
        assertEquals("thank you", ChineseEnglishLexicon.lookup("谢谢"))
        assertEquals("goodbye", ChineseEnglishLexicon.lookup("再见"))
        assertEquals("good morning", ChineseEnglishLexicon.lookup("早上好"))
        assertEquals("good evening", ChineseEnglishLexicon.lookup("晚上好"))
        assertEquals("what", ChineseEnglishLexicon.lookup("什么"))
        assertEquals("where", ChineseEnglishLexicon.lookup("哪里"))
        assertEquals("which", ChineseEnglishLexicon.lookup("哪个"))
        assertEquals("how many", ChineseEnglishLexicon.lookup("多少"))
        assertEquals("how", ChineseEnglishLexicon.lookup("怎么"))
        assertEquals("how", ChineseEnglishLexicon.lookup("怎么样"))
        assertEquals("can", ChineseEnglishLexicon.lookup("可以"))
        assertEquals("cannot", ChineseEnglishLexicon.lookup("不可以"))
        assertEquals("okay", ChineseEnglishLexicon.lookup("好的"))
        assertEquals("of course", ChineseEnglishLexicon.lookup("当然"))
    }

    // ---------------------------------------------------------------- 多字词

    @Test
    fun `multi character words are matched`() {
        assertEquals("People's Republic of China", ChineseEnglishLexicon.lookup("中华人民共和国"))
        assertEquals("People's Bank of China", ChineseEnglishLexicon.lookup("人民银行"))
        assertEquals("Chinese people", ChineseEnglishLexicon.lookup("中国人"))
        assertEquals("United States", ChineseEnglishLexicon.lookup("美国"))
        assertEquals("Shanghai", ChineseEnglishLexicon.lookup("上海"))
        assertEquals("restaurant", ChineseEnglishLexicon.lookup("餐厅"))
        assertEquals("yesterday", ChineseEnglishLexicon.lookup("昨天"))
        assertEquals("watermelon", ChineseEnglishLexicon.lookup("西瓜"))
    }

    // ---------------------------------------------------------------- 明确禁止：歧义单字

    @Test
    fun `ambiguous single characters have no english at all`() {
        // 必须为空：虚词 / 助词 / 介词 / 多义动词 / 依赖上下文
        listOf("使", "时", "事", "市", "是", "的", "了", "在", "和", "还", "等", "试").forEach { word ->
            assertNull("「$word」不应有英文释义", ChineseEnglishLexicon.lookup(word))
        }
    }

    @Test
    fun `forbidden wrong mappings never exist`() {
        // 需求点名禁止的错误映射
        assertNotEquals("use", ChineseEnglishLexicon.lookup("使"))
        assertNotEquals("time", ChineseEnglishLexicon.lookup("时"))
        assertNotEquals("matter", ChineseEnglishLexicon.lookup("事"))
        assertNotEquals("city", ChineseEnglishLexicon.lookup("市"))
        assertNotEquals("be", ChineseEnglishLexicon.lookup("是"))
        assertNotEquals("try", ChineseEnglishLexicon.lookup("试"))
        // 同时也确实是 null（不是“另一个错译”）
        assertNull(ChineseEnglishLexicon.lookup("使"))
    }

    // ---------------------------------------------------------------- 明确单字（允许）

    @Test
    fun `clear single characters have a natural english`() {
        assertEquals("ten", ChineseEnglishLexicon.lookup("十"))
        assertEquals("hand", ChineseEnglishLexicon.lookup("手"))
        assertEquals("person", ChineseEnglishLexicon.lookup("人"))
        assertEquals("heart", ChineseEnglishLexicon.lookup("心"))
        assertEquals("new", ChineseEnglishLexicon.lookup("新"))
        assertEquals("water", ChineseEnglishLexicon.lookup("水"))
        assertEquals("money", ChineseEnglishLexicon.lookup("钱"))
        assertEquals("love", ChineseEnglishLexicon.lookup("爱"))
        assertEquals("book", ChineseEnglishLexicon.lookup("书"))
        assertEquals("home", ChineseEnglishLexicon.lookup("家"))
    }

    // ---------------------------------------------------------------- 固定短语 / 短句

    @Test
    fun `fixed phrases have natural english`() {
        assertEquals("make a wish", ChineseEnglishLexicon.lookup("许愿"))
        assertEquals("you're welcome", ChineseEnglishLexicon.lookup("不客气"))
        assertEquals("good night", ChineseEnglishLexicon.lookup("晚安"))
        assertEquals("what's going on", ChineseEnglishLexicon.lookup("怎么回事"))
        assertEquals("what does it mean", ChineseEnglishLexicon.lookup("什么意思"))
        assertEquals("of course", ChineseEnglishLexicon.lookup("当然可以"))
        assertEquals("wait a moment", ChineseEnglishLexicon.lookup("等一下"))
    }

    @Test
    fun `short sentences have natural english`() {
        assertEquals("I want to eat.", ChineseEnglishLexicon.lookup("我要吃饭"))
        assertEquals("I want to sleep.", ChineseEnglishLexicon.lookup("我要睡觉"))
        assertEquals("I want to drink water.", ChineseEnglishLexicon.lookup("我想喝水"))
        assertEquals("What are you doing?", ChineseEnglishLexicon.lookup("你在干什么"))
        assertEquals("Where are you?", ChineseEnglishLexicon.lookup("你在哪里"))
        assertEquals("I need help.", ChineseEnglishLexicon.lookup("我需要帮助"))
        assertEquals("What is this?", ChineseEnglishLexicon.lookup("这是什么"))
        assertEquals("How much is it?", ChineseEnglishLexicon.lookup("多少钱"))
    }

    @Test
    fun `lookup supports any candidate text length`() {
        // 单字 / 词 / 短语 / 整句 都走同一个 lookup，无长度限制
        assertEquals("ten", ChineseEnglishLexicon.lookup("十"))
        assertEquals("apple", ChineseEnglishLexicon.lookup("苹果"))
        assertEquals("make a wish", ChineseEnglishLexicon.lookup("许愿"))
        assertEquals("I want to eat.", ChineseEnglishLexicon.lookup("我要吃饭"))
        assertEquals("I'm on my way.", ChineseEnglishLexicon.lookup("我在路上了"))
        // 词库没有的长句：null（绝不自动拼接/翻译）
        assertNull(ChineseEnglishLexicon.lookup("这是一句词库里完全没有收录的话"))
        assertNull(ChineseEnglishLexicon.lookup("我真的很想吃一顿特别丰盛的晚餐"))
    }

    // ---------------------------------------------------------------- 保留的稳定单字

    @Test
    fun `only stable pronouns keep a single character gloss`() {
        assertEquals("I", ChineseEnglishLexicon.lookup("我"))
        assertEquals("you", ChineseEnglishLexicon.lookup("你"))
        assertEquals("he", ChineseEnglishLexicon.lookup("他"))
        assertEquals("she", ChineseEnglishLexicon.lookup("她"))
        assertEquals("who", ChineseEnglishLexicon.lookup("谁"))
    }

    // ---------------------------------------------------------------- 绝不猜测

    @Test
    fun `unknown word returns null and is never guessed`() {
        assertNull(ChineseEnglishLexicon.lookup("中华人民共和国万岁"))
        assertNull(ChineseEnglishLexicon.lookup("饕餮"))
        assertNull(ChineseEnglishLexicon.lookup("这是一个很长的句子"))
    }

    @Test
    fun `blank lookup returns null`() {
        assertNull(ChineseEnglishLexicon.lookup(""))
        assertNull(ChineseEnglishLexicon.lookup(" "))
    }

    @Test
    fun `repeated lookup has no side effects`() {
        repeat(5) {
            assertEquals("apple", ChineseEnglishLexicon.lookup("苹果"))
            assertNull(ChineseEnglishLexicon.lookup("使"))
        }
        val first = listOf(Candidate(text = "苹果")).withEnglishGloss()
        val second = listOf(Candidate(text = "苹果")).withEnglishGloss()
        assertEquals(first.single().englishGloss, second.single().englishGloss)
    }

    // ---------------------------------------------------------------- 候选链路

    @Test
    fun `candidate text is preserved when gloss attached`() {
        val annotated = listOf(Candidate(text = "苹果")).withEnglishGloss()
        assertEquals("苹果", annotated.single().text)
        assertEquals("apple", annotated.single().englishGloss)
    }

    @Test
    fun `gloss does not change candidate index or order`() {
        val original = listOf(
            Candidate(text = "你好"),
            Candidate(text = "为什么"),
            Candidate(text = "苹果"),
            Candidate(text = "中华人民共和国"),
        )
        val annotated = original.withEnglishGloss()
        assertEquals(original.map { it.text }, annotated.map { it.text })
        annotated.forEachIndexed { index, candidate ->
            assertEquals(original[index].text, candidate.text)
            assertEquals(original[index].label, candidate.label)
            assertEquals(original[index].enabled, candidate.enabled)
            assertEquals(original[index].isHint, candidate.isHint)
        }
        assertEquals(2, annotated.indexOfFirst { it.text == "苹果" })
    }

    @Test
    fun `required candidate list maps to expected glosses in order`() {
        val annotated = listOf(
            Candidate(text = "你好"),
            Candidate(text = "为什么"),
            Candidate(text = "苹果"),
            Candidate(text = "不存在词"),
        ).withEnglishGloss()
        assertEquals(
            listOf(
                "你好" to "hello",
                "为什么" to "why",
                "苹果" to "apple",
                "不存在词" to null,
            ),
            annotated.map { it.text to it.englishGloss },
        )
    }

    @Test
    fun `ambiguous candidates stay single line in the list`() {
        val original = listOf(
            Candidate(text = "使"),
            Candidate(text = "时"),
            Candidate(text = "是"),
            Candidate(text = "的"),
        )
        val annotated = original.withEnglishGloss()
        assertSame("全无释义时应返回原列表（不产生多余分配）", original, annotated)
        assertTrue(annotated.all { it.englishGloss == null })
    }

    @Test
    fun `existing gloss is never overwritten`() {
        val annotated = listOf(Candidate(text = "苹果", englishGloss = "已释义")).withEnglishGloss()
        assertEquals("已释义", annotated.single().englishGloss)
    }

    @Test
    fun `hint candidates never get a gloss`() {
        val annotated = listOf(Candidate.hint("引擎未接入")).withEnglishGloss()
        assertNull(annotated.single().englishGloss)
        assertTrue(annotated.single().isHint)
    }

    // ---------------------------------------------------------------- 资源规模 / 架构

    // ---------------------------------------------------------------- P3 Stage 3：真实用户反馈词

    @Test
    fun `user reported common words are covered`() {
        assertEquals("collect", ChineseEnglishLexicon.lookup("收集"))
        assertEquals("task", ChineseEnglishLexicon.lookup("任务"))
        assertEquals("watch", ChineseEnglishLexicon.lookup("手表"))
        assertEquals("gloves", ChineseEnglishLexicon.lookup("手套"))
        assertEquals("surgery", ChineseEnglishLexicon.lookup("手术"))
        assertEquals("finger", ChineseEnglishLexicon.lookup("手指"))
        assertEquals("handicraft", ChineseEnglishLexicon.lookup("手工"))
        assertEquals("weather", ChineseEnglishLexicon.lookup("天气"))
        assertEquals("question", ChineseEnglishLexicon.lookup("问题"))
        assertEquals("answer", ChineseEnglishLexicon.lookup("答案"))
        assertEquals("make a wish", ChineseEnglishLexicon.lookup("许愿"))
        assertEquals("hand", ChineseEnglishLexicon.lookup("手"))
    }

    @Test
    fun `shipped lexicon contains the curated entries`() {
        // P3：词库必须达到 >= 5000 条有效词条
        assertTrue("词库有效条目不足 5000：${ChineseEnglishLexicon.size}", ChineseEnglishLexicon.size >= 5000)
        assertEquals("banana", ChineseEnglishLexicon.lookup("香蕉"))
        assertEquals("hospital", ChineseEnglishLexicon.lookup("医院"))
        assertEquals("school", ChineseEnglishLexicon.lookup("学校"))
        assertEquals("company", ChineseEnglishLexicon.lookup("公司"))
        assertEquals("store", ChineseEnglishLexicon.lookup("商店"))
        assertEquals("Japan", ChineseEnglishLexicon.lookup("日本"))
        assertEquals("English", ChineseEnglishLexicon.lookup("英语"))
        assertEquals("airplane", ChineseEnglishLexicon.lookup("飞机"))
        assertEquals("Monday", ChineseEnglishLexicon.lookup("星期一"))
        assertEquals("tomorrow", ChineseEnglishLexicon.lookup("明天"))
    }

    @Test
    fun `lexicon declares no room or database dependency`() {
        val forbidden = listOf("androidx.room", "com.board.ime.database", "Dao", "Database")
        assertTrue(lexiconMemberTypes().none { type -> forbidden.any { type.startsWith(it) } })
    }

    @Test
    fun `lexicon declares no network or file dependency`() {
        val forbidden = listOf("java.net", "okhttp", "java.io")
        assertTrue(lexiconMemberTypes().none { type -> forbidden.any { type.startsWith(it) } })
        assertEquals("apple", ChineseEnglishLexicon.lookup("苹果"))
        assertFalse(ChineseEnglishLexicon.lookup("苹果").isNullOrBlank())
    }

    /** 词表对象自身声明的字段 / 方法返回 / 参数类型（用于架构约束断言） */
    private fun lexiconMemberTypes(): List<String> {
        val clazz = ChineseEnglishLexicon::class.java
        val fields = clazz.declaredFields.map { it.type.name }
        val returns = clazz.declaredMethods.map { it.returnType.name }
        val params = clazz.declaredMethods.flatMap { method -> method.parameterTypes.map { it.name } }
        return fields + returns + params
    }

    private companion object {
        const val ASSET_NAME = "chinese_english.tsv"

        /** 单测工作目录可能是模块目录（android/app）或仓库根目录 */
        val SHIPPED_ASSET_CANDIDATES = listOf(
            java.io.File("src/main/assets/$ASSET_NAME"),
            java.io.File("android/app/src/main/assets/$ASSET_NAME"),
            java.io.File("../app/src/main/assets/$ASSET_NAME"),
        )
    }
}
