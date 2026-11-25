package com.board.ime.dictionary

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `RimeDictionaryBridge` 生成的 Rime 补丁内容回归测试。
 *
 * 锁死候选排序修复：额外词源必须 `initial_quality` 为负（排在基础常用词之后），
 * 且显式开启用户词典学习；避免以后又被改回会把生僻词排到前面的配置。
 */
class RimeDictionaryBridgePatchTest {

    @Test
    fun `extra dictionary patch lowers quality and enables user learning`() {
        val dir = java.nio.file.Files.createTempDirectory("board-rime-patch").toFile()
        val source = DictionarySource(
            id = 1L,
            sourceId = "abcdef1234567890",
            name = "test.dict.txt",
            path = "/tmp/does-not-matter",
            hash = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
            entryCount = 100,
            ready = true,
        )

        assertTrue("写出补丁失败", RimeDictionaryBridge.applySchemaPatch(dir, listOf(source)))
        val text = java.io.File(dir, "luna_pinyin_simp.custom.yaml").readText()

        assertTrue("必须与 UI 候选上限一致", text.contains("menu/page_size: 200"))
        assertTrue("必须显式开启用户词典学习", text.contains("\"translator/enable_user_dict\": true"))
        // 用户导入的大词库必须为负，排在基础常用词之后（board_modern 是内置补充词库，
        // 语义与 board_common 同类，允许同权竞争，不在此约束内）。
        assertTrue("额外词源质量必须为负", text.contains("initial_quality: -1000"))
    }

    @Test
    fun `patch keeps custom code alphabet and recognizer`() {
        val dir = java.nio.file.Files.createTempDirectory("board-rime-patch2").toFile()
        RimeDictionaryBridge.applySchemaPatch(dir, emptyList())
        val text = java.io.File(dir, "luna_pinyin_simp.custom.yaml").readText()
        assertTrue(text.contains("\"speller/alphabet\""))
        assertTrue(text.contains("recognizer/patterns/custom_code"))
    }

    @Test
    fun `patch enables completion and sentence`() {
        val dir = java.nio.file.Files.createTempDirectory("board-rime-patch3").toFile()
        RimeDictionaryBridge.applySchemaPatch(dir, emptyList())
        val text = java.io.File(dir, "luna_pinyin_simp.custom.yaml").readText()

        assertTrue("需要前缀补全", text.contains("\"translator/enable_completion\": true"))
        assertTrue("需要整句候选", text.contains("\"translator/enable_sentence\": true"))
        // 模糊音 / 简拼 / 纠错自 v10 起由 RimeSchemaBuilder 写入影子 base schema
        // （用户目录 luna_pinyin.schema.yaml），不再由本 patch 写 speller/algebra。
    }

    @Test
    fun `patch makes user phrases rank above base dictionary`() {
        val dir = java.nio.file.Files.createTempDirectory("board-rime-patch4").toFile()
        RimeDictionaryBridge.applySchemaPatch(dir, emptyList())
        val text = java.io.File(dir, "luna_pinyin_simp.custom.yaml").readText()
        // 基础词库词频来自 essay.txt（最大约 4.8e6），用户自定义词 / 常用语必须排在它之前
        assertTrue(
            "用户词必须优先于基础词库",
            text.contains("\"custom_phrase/initial_quality\": 10000000"),
        )
    }

    @Test
    fun `patch wires board_common supplementary dictionary`() {
        val dir = java.nio.file.Files.createTempDirectory("board-rime-patch5").toFile()
        RimeDictionaryBridge.applySchemaPatch(dir, emptyList())
        val text = java.io.File(dir, "luna_pinyin_simp.custom.yaml").readText()
        assertTrue("必须挂接 board_common 词源", text.contains("script_translator@board_common"))
        assertTrue("board_common 必须指向 board_common 词典", text.contains("dictionary: board_common"))
        assertTrue("board_common 必须列入 schema/dependencies（否则不编译 table.bin）", text.contains("- board_common"))
        assertTrue("必须挂接 board_modern 词源", text.contains("script_translator@board_modern"))
        assertTrue("board_modern 必须指向 board_modern 词典", text.contains("dictionary: board_modern"))
        assertTrue("board_modern 必须列入 schema/dependencies（否则不编译 table.bin）", text.contains("- board_modern"))
    }
}
