package com.board.ime.ime

import com.board.ime.engine.Candidate
import com.board.ime.engine.EngineKey
import com.board.ime.engine.EngineResult
import com.board.ime.engine.InputEngine
import com.board.ime.engine.InputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 候选栏的 emoji 推荐（第三个候选）。
 *
 * 插入后其余候选整体后移一位，所以选词下标必须换算回引擎下标；
 * 选中推荐时要丢弃拼音组词，只上屏表情（或「整句 + 表情」）。
 */
class InputControllerEmojiCandidateTest {

    private class FakeEngine(private val words: List<String>) : InputEngine {
        override val id: String = "fake"
        override val displayName: String = "Fake"

        override var mode: InputMode = InputMode.CHINESE

        var picked = -1
            private set

        var resetCount = 0
            private set

        override val composing: CharSequence
            get() = if (words.isEmpty()) "" else "test"

        override fun candidates(): List<Candidate> = words.map { Candidate(text = it) }

        override fun handleKey(key: EngineKey): EngineResult = EngineResult.Ignored

        override fun pickCandidate(index: Int): EngineResult {
            picked = index
            return EngineResult.Ignored
        }

        override fun reset() {
            resetCount++
        }
    }

    private val suggestion = InputController.EmojiCandidate("🍕", "🍕", "披萨🍕")

    @Test
    fun `suggestion is inserted as the third candidate`() {
        val engine = FakeEngine(listOf("一", "二", "三", "四"))
        val controller = InputController(engine, InputMode.CHINESE)
        controller.emojiSuggest = { suggestion }

        val list = controller.candidates
        assertEquals(
            listOf("一", "二", "🍕", "三", "四"),
            list.map { it.text },
        )
    }

    @Test
    fun `picking a shifted candidate maps back to the engine index`() {
        val engine = FakeEngine(listOf("一", "二", "三", "四"))
        val controller = InputController(engine, InputMode.CHINESE)
        controller.emojiSuggest = { suggestion }

        // 显示下标 3 = 引擎下标 2
        controller.pickCandidate(3)
        assertEquals(2, engine.picked)
    }

    @Test
    fun `picking the suggestion discards the composition and commits the emoji`() {
        val engine = FakeEngine(listOf("一", "二", "三", "四"))
        val controller = InputController(engine, InputMode.CHINESE)
        var committedEmoji: String? = null
        controller.emojiSuggest = { suggestion }
        controller.onEmojiCommitted = { committedEmoji = it }

        controller.pickCandidate(2)
        assertEquals("🍕", committedEmoji)
        assertEquals(1, engine.resetCount)
        assertEquals(-1, engine.picked)
    }

    @Test
    fun `suggestion goes to the end when there are fewer than three candidates`() {
        val engine = FakeEngine(listOf("一"))
        val controller = InputController(engine, InputMode.CHINESE)
        controller.emojiSuggest = { suggestion }

        assertEquals(
            listOf("一", "🍕"),
            controller.candidates.map { it.text },
        )
    }

    @Test
    fun `no suggestion when there are no candidates`() {
        val engine = FakeEngine(emptyList())
        val controller = InputController(engine, InputMode.CHINESE)
        var called = false
        controller.emojiSuggest = {
            called = true
            suggestion
        }

        assertEquals(emptyList<Candidate>(), controller.candidates)
        assertFalse(called)
    }
}
