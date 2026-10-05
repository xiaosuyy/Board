package com.board.ime.dictionary

import com.board.ime.database.entity.DictionaryEntry
import com.board.ime.engine.BasicEngine
import com.board.ime.engine.EngineKey
import com.board.ime.engine.InputMode
import com.board.ime.engine.RimeEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * P0：自定义码映射「即时刷新」链路单测。
 *
 * 模拟真实接线（不依赖 Android 框架）：
 *   数据变化（entries）→ DictionaryRepository.buildNumericCodeMap → 监听回调 →
 *   RimeEngine.setNumericCustomPhrases → 当前会话候选立即变化。
 *
 * 真实接线中，通知由 `DictionaryRepository.syncCustomPhrasesToRime()` 在写完
 * custom_phrase.txt 之后发出（见 `DictionaryChangeNotifier.notifyChanged()`）。
 * 这些用例只走 NUMBER 路径，不触及 librime（单测里 RimeRuntime 无会话，直接 fallback）。
 */
class DictionaryChangeNotifierTest {

    /** 模拟数据库里的自定义词集合 */
    private val entries = mutableListOf<DictionaryEntry>()

    private lateinit var engine: RimeEngine
    private lateinit var listener: () -> Unit

    @Before
    fun setUp() {
        engine = RimeEngine(BasicEngine())
        engine.mode = InputMode.NUMBER
        // 模拟 BoardInputMethodService 注册的刷新动作
        listener = {
            engine.setNumericCustomPhrases(DictionaryRepository.buildNumericCodeMap(entries.toList()))
        }
        DictionaryChangeNotifier.register(listener)
    }

    @After
    fun tearDown() {
        DictionaryChangeNotifier.unregister(listener)
    }

    private fun RimeEngine.feed(text: String): RimeEngine {
        text.forEach { handleKey(EngineKey.Letter(it)) }
        return this
    }

    private fun candidatesOf(): List<String> = engine.candidates().map { it.text }

    // 1
    @Test
    fun `new numeric code becomes visible immediately after data change`() {
        engine.feed("123")
        assertEquals(listOf("123"), candidatesOf())

        entries += DictionaryEntry(word = "某词", code = "123")
        DictionaryChangeNotifier.notifyChanged() // 模拟 custom_phrase 同步完成后的通知

        // 同一会话、未切层、未重新进入输入框
        assertEquals(listOf("123", "某词"), candidatesOf())
    }

    // 2
    @Test
    fun `deleted numeric code disappears immediately`() {
        entries += DictionaryEntry(word = "某词", code = "123")
        DictionaryChangeNotifier.notifyChanged()
        engine.feed("123")
        assertEquals(listOf("123", "某词"), candidatesOf())

        entries.clear()
        DictionaryChangeNotifier.notifyChanged()
        assertEquals(listOf("123"), candidatesOf())
    }

    // 3
    @Test
    fun `disabled numeric code disappears immediately`() {
        entries += DictionaryEntry(word = "某词", code = "123")
        DictionaryChangeNotifier.notifyChanged()
        engine.feed("123")
        assertEquals(listOf("123", "某词"), candidatesOf())

        entries.clear()
        entries += DictionaryEntry(word = "某词", code = "123", enabled = false)
        DictionaryChangeNotifier.notifyChanged()
        assertEquals(listOf("123"), candidatesOf())
    }

    // 4
    @Test
    fun `changed code drops old key and adds new key`() {
        entries += DictionaryEntry(word = "某词", code = "111")
        DictionaryChangeNotifier.notifyChanged()
        engine.feed("111")
        assertEquals(listOf("111", "某词"), candidatesOf())

        engine.reset()
        entries.clear()
        entries += DictionaryEntry(word = "某词", code = "222")
        DictionaryChangeNotifier.notifyChanged()

        engine.feed("111")
        assertEquals(listOf("111"), candidatesOf()) // 旧 code 不再命中

        engine.reset()
        engine.feed("222")
        assertEquals(listOf("222", "某词"), candidatesOf())
    }

    // 5
    @Test
    fun `changed word updates candidate text without stale entry`() {
        entries += DictionaryEntry(word = "旧词", code = "123")
        DictionaryChangeNotifier.notifyChanged()
        engine.feed("123")
        assertEquals(listOf("123", "旧词"), candidatesOf())

        entries.clear()
        entries += DictionaryEntry(word = "新词", code = "123")
        DictionaryChangeNotifier.notifyChanged()
        assertEquals(listOf("123", "新词"), candidatesOf()) // 旧词不残留
    }

    // 6
    @Test
    fun `non numeric custom codes never enter the numeric map`() {
        entries += DictionaryEntry(word = "甲", code = "abc")
        entries += DictionaryEntry(word = "乙", code = "@")
        entries += DictionaryEntry(word = "丙", code = "1a2")
        entries += DictionaryEntry(word = "丁", code = "")
        entries += DictionaryEntry(word = "戊", code = null)
        DictionaryChangeNotifier.notifyChanged()

        engine.feed("123")
        assertEquals(listOf("123"), candidatesOf())
        assertFalse(candidatesOf().contains("甲"))
    }

    // 7
    @Test
    fun `plain digits keep original behaviour after refresh`() {
        entries += DictionaryEntry(word = "某词", code = "15823")
        DictionaryChangeNotifier.notifyChanged()

        engine.feed("123")
        assertEquals(listOf("123"), candidatesOf())
        engine.reset()

        engine.feed("1582") // 前缀不命中
        assertEquals(listOf("1582"), candidatesOf())
    }

    // 8
    @Test
    fun `chinese mode is not affected by the refresh`() {
        entries += DictionaryEntry(word = "某词", code = "123")
        DictionaryChangeNotifier.notifyChanged()

        val chinese = RimeEngine(BasicEngine())
        chinese.mode = InputMode.CHINESE
        chinese.setNumericCustomPhrases(DictionaryRepository.buildNumericCodeMap(entries.toList()))
        chinese.feed("123")
        assertFalse(chinese.candidates().map { it.text }.contains("某词"))
    }

    // 9
    @Test
    fun `english mode is not affected by the refresh`() {
        entries += DictionaryEntry(word = "某词", code = "123")
        DictionaryChangeNotifier.notifyChanged()

        val english = RimeEngine(BasicEngine())
        english.mode = InputMode.ENGLISH
        english.setNumericCustomPhrases(DictionaryRepository.buildNumericCodeMap(entries.toList()))
        english.feed("123")
        assertFalse(english.candidates().map { it.text }.contains("某词"))
    }

    // 10
    @Test
    fun `at routing in number mode is not affected by the refresh`() {
        entries += DictionaryEntry(word = "某词", code = "123")
        DictionaryChangeNotifier.notifyChanged()
        engine.feed("123")
        engine.reset()

        // NUMBER 模式下的 @ 仍走 Rime 路径（单测无会话 → Ignored），不产生数字自定义候选
        engine.handleKey(EngineKey.Punctuation("@"))
        assertFalse(candidatesOf().contains("某词"))
    }

    // 11
    @Test
    fun `notify without any active listener does not crash`() {
        DictionaryChangeNotifier.unregister(listener) // 模拟没有活跃输入法
        DictionaryChangeNotifier.notifyChanged()      // 应为空操作
        DictionaryChangeNotifier.unregister(listener) // 再次注销也安全
    }

    // 12
    @Test
    fun `register notify unregister mechanics`() {
        var calls = 0
        val counter: () -> Unit = { calls++ }
        DictionaryChangeNotifier.register(counter)
        DictionaryChangeNotifier.notifyChanged()
        assertEquals(1, calls)

        DictionaryChangeNotifier.unregister(counter)
        DictionaryChangeNotifier.notifyChanged()
        assertEquals(1, calls)
    }

    // 13
    @Test
    fun `unregistering a stale listener keeps the current one`() {
        var oldCalls = 0
        var newCalls = 0
        val oldListener: () -> Unit = { oldCalls++ }
        val newListener: () -> Unit = { newCalls++ }

        DictionaryChangeNotifier.register(oldListener)
        DictionaryChangeNotifier.register(newListener)
        DictionaryChangeNotifier.unregister(oldListener) // 不是当前监听 → 不应清掉新监听

        DictionaryChangeNotifier.notifyChanged()
        assertEquals(0, oldCalls)
        assertEquals(1, newCalls)
    }
}
