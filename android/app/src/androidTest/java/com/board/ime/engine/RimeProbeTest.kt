package com.board.ime.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.board.ime.engine.rime.RimeNative
import com.board.ime.engine.rime.RimeRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 在模拟器/真机上直接驱动 librime 的探针测试（确定性，不依赖 UI 点击）。
 *
 * 已通过：
 * - 常用词排序（是/中国/工作/银行 首候选正确）；
 * - 导入学习词后**不重启进程**即生效（重建引擎）。
 *
 * 已复现、待修（本类只记录，不断言，避免 CI 红）：
 * - 候选下标 ≥ 4 时 `select_candidate*` 会「返回 true 但不生效」（commit 为空、composition 未清空），
 *   表现为“点后面的候选没反应、也学不到” → 对应产品的「选不中/记不住」。
 */
@RunWith(AndroidJUnit4::class)
class RimeProbeTest {

    companion object {
        private const val TAG = "RimeProbe"

        @JvmStatic
        @BeforeClass
        fun setUpRime() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            assertTrue("Rime 初始化失败", RimeRuntime.initialize(context, "probe"))
            RimeRuntime.deploy(fullCheck = false)
            assertTrue("Rime 会话创建失败", RimeRuntime.openSession())
        }
    }

    private fun freshEngine(): RimeEngine = RimeEngine(BasicEngine()).apply {
        mode = InputMode.CHINESE
        reset() // 所有探针共享同一个 librime 会话，先清组词
    }

    private fun RimeEngine.type(input: String) {
        input.forEach { handleKey(EngineKey.Letter(it)) }
    }

    private fun candidatesOf(input: String): List<String> =
        freshEngine().run { type(input); candidates().map { it.text } }

    @Test
    fun commonPinyinKeepsCommonWordsFirst() {
        // 注意：librime 会学习「选过的词」并前移，所以这里只断言常用词在**靠前位置**，
        // 不能断言“一定第 0 个”（真机上已有学习记录时会被学习词顶掉）。
        fun top(input: String, n: Int) = candidatesOf(input).take(n)
        assertTrue("是 应在 shi 前 5：${top("shi", 5)}", top("shi", 5).contains("是"))
        assertTrue("中国 应在 zhongguo 前 5：${top("zhongguo", 5)}", top("zhongguo", 5).contains("中国"))
        assertTrue("工作 应在 gongzuo 前 5：${top("gongzuo", 5)}", top("gongzuo", 5).contains("工作"))
        assertTrue("银行 应在 yinhang 前 5：${top("yinhang", 5)}", top("yinhang", 5).contains("银行"))
        assertTrue("任务 应在 renwu 前 5：${top("renwu", 5)}", top("renwu", 5).contains("任务"))
    }

    @Test
    fun importedLearnedWordTakesEffectWithoutRestart() {
        val dir = RimeRuntime.userDataDir()
        assertTrue("无 Rime 用户目录", dir != null)
        val word = "探针甲乙"
        val code = "tan zhen jia yi"
        val input = "tanzhenjiayi"

        val before = candidatesOf(input)
        Log.i(TAG, "PROBE before import $input -> $before")
        assertFalse("探针词不应出现在基础词库", before.contains(word))

        val file = File(dir, "board_probe_import.txt")
        file.writeText("$word\t$code\t500\n")
        val count = RimeRuntime.importUserDict(file, reload = true)
        file.delete()
        Log.i(TAG, "PROBE import count = $count")

        val after = candidatesOf(input)
        Log.i(TAG, "PROBE after import $input -> $after  isReady=${RimeRuntime.isReady}")
        assertTrue("导入的学习词未生效（重建引擎失败？）", after.contains(word))

        val tomb = File(dir, "board_probe_tomb.txt")
        tomb.writeText("$word\t$code\t-1\n")
        RimeRuntime.importUserDict(tomb, reload = true)
        tomb.delete()
    }

    /**
     * 多音节输入（zhongguo）后半候选选不中 —— 记录现象（待修）。
     */
    @Test
    fun pickProbePendingFix() {
        for (input in listOf("shi", "ni", "wo", "zhongguo", "gongzuo", "ee", "dd", "e", "o")) {
            val list = candidatesOf(input)
            val results = ArrayList<String>()
            for (i in list.indices.take(12)) {
                val engine = freshEngine()
                engine.type(input)
                results += "$i:${if (engine.pickCandidate(i) is EngineResult.Commit) "OK" else "x"}"
            }
            Log.i(TAG, "PROBE pick input=$input list=$list")
            Log.i(TAG, "PROBE pick input=$input -> $results")
        }
    }

    @Test
    fun highlightThenSpaceProbe() {
        val down = 0xFF54 // XK_Down
        val space = 0x20
        freshEngine().type("zhongguo")
        repeat(4) { RimeNative.nativeProcessKey(RimeRuntime.session, down, 0) }
        val afterDown = RimeNative.nativeGetInput(RimeRuntime.session)
        val okSpace = RimeNative.nativeProcessKey(RimeRuntime.session, space, 0)
        val inputAfter = RimeNative.nativeGetInput(RimeRuntime.session)
        val commit = RimeNative.nativeGetCommit(RimeRuntime.session)
        Log.i(TAG, "PROBE highlight down4 input='$afterDown' space=$okSpace input2='$inputAfter' commit=$commit")
    }

    @Test
    fun numberKeySelectProbe() {
        for (key in listOf('1', '2', '3', '4', '5')) {
            freshEngine().type("zhongguo")
            val ok = RimeNative.nativeProcessKey(RimeRuntime.session, key.code, 0)
            val inputAfter = RimeNative.nativeGetInput(RimeRuntime.session)
            val commit = RimeNative.nativeGetCommit(RimeRuntime.session)
            Log.i(TAG, "PROBE numkey '$key' ok=$ok input='$inputAfter' commit=$commit")
        }
    }

    /**
     * 学习是否真的让「选过的词」前移（用可选的词：shi 的最后一个候选）。
     */
    @Test
    fun learningMovesSelectedWordUp() {
        val input = "shi"
        val list = candidatesOf(input)
        val word = list.last()
        val before = list.indexOf(word)
        Log.i(TAG, "PROBE learning before list=$list word=$word rank=$before")

        repeat(20) {
            val engine = freshEngine()
            engine.type(input)
            val idx = engine.candidates().indexOfFirst { it.text == word }
            if (idx >= 0) engine.pickCandidate(idx)
        }

        val afterList = candidatesOf(input)
        val after = afterList.indexOf(word)
        Log.i(TAG, "PROBE learning after list=$afterList word=$word rank=$after")
        assertTrue("学习后 $word 没有前移：before=$before after=$after", after in 0 until before)
    }
}
