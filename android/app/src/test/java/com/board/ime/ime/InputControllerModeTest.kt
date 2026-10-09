package com.board.ime.ime

import com.board.ime.engine.BasicEngine
import com.board.ime.engine.InputMode
import com.board.ime.engine.RimeEngine
import com.board.ime.keyboard.KeySpec
import com.board.ime.keyboard.KeyType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 输入层保持单元测试（P3：修「输数字又跳回字母层」）。
 *
 * 系统在同一次输入里「重启输入」（onStartInputView restarting=true）时，必须保留当前层；
 * 真正进入新输入框（restarting=false）时才回到字母层。
 */
class InputControllerModeTest {

    private fun controller(layer: InputMode) = InputController(RimeEngine(BasicEngine()), layer)

    @Test
    fun `restart keeps the number layer`() {
        val controller = controller(InputMode.CHINESE)
        controller.onKey(KeySpec(KeyType.TO_NUMBERS))
        assertEquals(InputMode.NUMBER, controller.mode)

        controller.onStartInput(null, null, keepLayer = true)
        assertEquals(InputMode.NUMBER, controller.mode)
    }

    @Test
    fun `new input returns to the preferred letter layer`() {
        val controller = controller(InputMode.CHINESE)
        controller.onKey(KeySpec(KeyType.TO_NUMBERS))
        assertEquals(InputMode.NUMBER, controller.mode)

        controller.onStartInput(null, null, keepLayer = false)
        assertEquals(InputMode.CHINESE, controller.mode)
    }

    @Test
    fun `restart keeps the symbol layer`() {
        val controller = controller(InputMode.CHINESE)
        controller.onKey(KeySpec(KeyType.TO_SYMBOLS))
        assertEquals(InputMode.SYMBOL, controller.mode)

        controller.onStartInput(null, null, keepLayer = true)
        assertEquals(InputMode.SYMBOL, controller.mode)
    }
}
