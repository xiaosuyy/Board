package com.board.ime.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * D1：`ClipboardRepository` 必须是「唯一共享实例」。
 *
 * 真机缺陷回顾：`BoardInputMethodService` 自己 `new` 了一个 repository，
 * 而 B1 的「本机新增历史」回调挂在 `SyncEngineHolder` 创建的另一个实例上
 * ⇒ 回调永不触发、Android 端永远不产生 `clipboard_log_seq`、远端 log 恒 404。
 *
 * 这里对承载「唯一实例」语义的 [SingleInstance] 做可执行的 JVM 断言。
 */
class SingleInstanceTest {

    @Test
    fun repeatedGetReturnsSameInstance() {
        var created = 0
        val slot = SingleInstance { Any().also { created++ } }
        val first = slot.get()
        val second = slot.get()
        val third = slot.get()
        assertSame("多次 get 必须返回同一个对象", first, second)
        assertSame(first, third)
        assertEquals("工厂只能执行一次", 1, created)
        assertNotNull(slot.installed)
        assertSame(first, slot.installed)
    }

    @Test
    fun installedIsNullBeforeFirstGet() {
        val slot = SingleInstance { Any() }
        assertNull("未 get 之前不允许创建", slot.installed)
        val value = slot.get()
        assertSame(value, slot.installed)
    }

    @Test
    fun concurrentGetCreatesExactlyOne() {
        var created = 0
        val slot = SingleInstance {
            created++
            Any()
        }
        val results = Collections.synchronizedList(mutableListOf<Any>())
        val start = CountDownLatch(1)
        val threads = (1..16).map {
            Thread {
                start.await()
                results.add(slot.get())
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(16, results.size)
        assertEquals("并发下也只能创建一个实例", 1, results.toSet().size)
        assertEquals(1, created)
    }

    @Test
    fun sharedInstanceKeepsCallbackAttached() {
        // 模拟真实装配：先建 repository（由任一入口触发），随后 SyncEngineHolder
        // 把 onLocalItemProduced 挂上——必须是**同一个对象**，回调才能生效。
        val slot = SingleInstance { StringBuilder() }
        val fromIme = slot.get()
        fromIme.append("x")
        val fromEngine = slot.get()
        assertTrue("输入法拿到的实例必须与引擎拿到的完全一致", fromIme === fromEngine)
        assertEquals("x", fromEngine.toString())
    }
}
