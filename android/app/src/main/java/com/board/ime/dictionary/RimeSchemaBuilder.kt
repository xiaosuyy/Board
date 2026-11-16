package com.board.ime.dictionary

import android.util.Log
import com.board.ime.engine.rime.RimeFuzzyGroup
import com.board.ime.engine.rime.RimeSchemaOptions
import com.board.ime.engine.rime.RimeRuntime
import com.board.ime.engine.rime.ShuangpinScheme
import java.io.File

/**
 * 按用户设置生成 Rime 方案文件。
 *
 * ## 为什么要写「影子 schema」
 *
 * 模糊音 / 简拼 / 纠错是 `speller/algebra` 里的拼写运算。Rime 的 `.custom.yaml`
 * 只能把新规则**追加**到已有列表末尾，而双拼方案的键位转换（xform 链）就在同一列表里：
 * 追加在末尾等于对双拼码做模糊，不会生效；全拼方案自带的 3 组规则又无法通过 patch 删除。
 *
 * 所以这里把共享目录里的 base schema 复制到用户目录（Rime 会优先读取用户目录的同名文件），
 * 把**选中的分组规则直接内联**进 `speller/algebra`：
 * - 全拼（`luna_pinyin.schema.yaml`）：用选中规则替换原有 algebra（原有内容就是那 3 组）；
 * - 双拼（`double_pinyin*.schema.yaml`）：把规则插在键位转换链**之前**。
 *
 * 用户目录 schema 带 [MARKER] 标记，每次设置变化都会重写；
 * 如果用户自己放了同名文件（没有标记），则不动它。
 */
object RimeSchemaBuilder {

    private const val TAG = "BoardRimeSchema"

    const val MARKER = RimeDictionaryBridge.PATCH_MARKER

    /** 全拼 base schema（luna_pinyin_simp 通过 __include 复用它） */
    private const val LUNA_BASE = "luna_pinyin.schema.yaml"

    /**
     * 写入本次设置对应的全部 Rime 配置：
     * 影子 base schema + 方案 patch + default.custom.yaml（方案列表）。
     *
     * 所有文件都只写到 Rime 用户目录；共享目录（assets 释放出来的）保持只读。
     */
    fun applyAll(
        sharedDir: File,
        userDir: File,
        options: RimeSchemaOptions,
        sources: List<DictionarySource>,
    ): Boolean {
        val rules = readGroupRules(sharedDir, options)
        var ok = writeLunaBase(sharedDir, userDir, rules)
        ok = RimeDictionaryBridge.applySchemaPatch(userDir, sources, options) && ok
        if (options.shuangpin.isDoublePinyin) {
            ok = writeDoublePinyinBase(sharedDir, userDir, options.shuangpin, rules) && ok
            ok = RimeDictionaryBridge.applyDoublePinyinPatch(
                userDir,
                options.shuangpin,
                sources,
                options,
            ) && ok
        }
        ok = writeDefaultSchemaList(userDir, options) && ok
        Log.i(
            TAG,
            "Rime 方案配置：双拼=${options.shuangpin.label} 繁体=${options.traditional} " +
                "分组=${options.orderedGroups().joinToString(",") { it.key }} ok=$ok",
        )
        return ok
    }

    // ------------------------------------------------------------ base schema

    /** 全拼：替换 algebra（基础 schema 的 algebra 只包含简拼 / 纠错 3 组）。 */
    private fun writeLunaBase(sharedDir: File, userDir: File, rules: List<String>): Boolean {
        val source = File(sharedDir, LUNA_BASE)
        if (!source.isFile) {
            Log.w(TAG, "缺少 ${source.name}，跳过全拼 algebra 生成")
            return false
        }
        val text = runCatching { source.readText() }.getOrNull() ?: return false
        val patched = replaceAlgebra(text, rules) ?: run {
            Log.w(TAG, "${LUNA_BASE} 未找到 speller/algebra")
            return false
        }
        return writeShadow(userDir, LUNA_BASE, patched)
    }

    /** 双拼：把模糊音 / 简拼 / 纠错规则插到键位转换链之前。 */
    private fun writeDoublePinyinBase(
        sharedDir: File,
        userDir: File,
        scheme: ShuangpinScheme,
        rules: List<String>,
    ): Boolean {
        val name = "${scheme.schemaId}.schema.yaml"
        val source = File(sharedDir, name)
        if (!source.isFile) {
            Log.w(TAG, "缺少双拼方案 $name")
            return false
        }
        val text = runCatching { source.readText() }.getOrNull() ?: return false
        val patched = prependToAlgebra(text, rules) ?: return false
        return writeShadow(userDir, name, patched)
    }

    private fun writeShadow(userDir: File, name: String, text: String): Boolean {
        val target = File(userDir, name)
        if (target.isFile) {
            val existing = runCatching { target.readText() }.getOrNull().orEmpty()
            if (!existing.contains(MARKER)) {
                Log.w(TAG, "$name 是用户自己的文件，不覆盖")
                return false
            }
        }
        return try {
            val body = if (text.startsWith(MARKER)) text else "$MARKER\n$text"
            target.writeText(body)
            Log.i(TAG, "已写入影子方案：$name")
            true
        } catch (throwable: Throwable) {
            Log.e(TAG, "写影子方案 $name 失败：${throwable.message}", throwable)
            false
        }
    }

    /**
     * 替换 `speller/algebra` 的整个内容（保留 `speller:` 里其他键）。
     * 找不到 speller/algebra 时返回 null。
     */
    private fun replaceAlgebra(text: String, rules: List<String>): String? {
        val (lines, start, end) = algebraRange(text) ?: return null
        val out = ArrayList<String>(lines.size + rules.size)
        // start 指向 algebra: 的下一行，原 algebra: 行由这里重新生成（避免重复的键）
        out += lines.subList(0, start - 1)
        if (rules.isEmpty()) {
            out += "  algebra: []"
        } else {
            out += "  algebra:"
            rules.forEach { out += "    - $it" }
        }
        out += lines.subList(end, lines.size)
        return out.joinToString("\n")
    }

    /** 在 `speller/algebra` 已有内容**之前**插入规则（双拼键位链保留）。 */
    private fun prependToAlgebra(text: String, rules: List<String>): String? {
        if (rules.isEmpty()) return text
        val (lines, start, _) = algebraRange(text) ?: return null
        val out = ArrayList<String>(lines.size + rules.size)
        out += lines.subList(0, start - 1)
        // `algebra: []` 是流式空列表，改成块状才能追加规则行
        out += "  algebra:"
        rules.forEach { out += "    - $it" }
        out += lines.subList(start, lines.size)
        return out.joinToString("\n")
    }

    /**
     * 定位 speller 段里的 `algebra:` 行以及它的内容范围。
     * @return (lines, contentStart, contentEnd)；`contentStart` 是 `algebra:` 的下一行
     */
    private fun algebraRange(text: String): Triple<List<String>, Int, Int>? {
        val lines = text.lines()
        val speller = lines.indexOfFirst { it.trimEnd() == "speller:" }
        if (speller < 0) return null
        var alg = -1
        for (i in speller + 1 until lines.size) {
            val line = lines[i]
            if (line.isNotEmpty() && !line.startsWith("  ")) break
            val trimmed = line.trimEnd()
            if (trimmed == "  algebra:" || trimmed == "  algebra: []") {
                alg = i
                break
            }
        }
        if (alg < 0) return null
        var end = alg + 1
        while (end < lines.size && (lines[end].startsWith("    ") || lines[end].isBlank())) end++
        // 内容后的空行不属于 algebra 块，避免把下一个小节前的空行吞掉
        while (end > alg + 1 && lines[end - 1].isBlank()) end--
        return Triple(lines, alg + 1, end)
    }

    // ------------------------------------------------------------ pinyin.yaml

    /**
     * 从共享目录的 `pinyin.yaml` 读取所选分组的规则行（原样保留，含引号 / 行内注释）。
     * 分组顺序由 [RimeSchemaOptions.orderedGroups] 决定（模糊音 → 简拼 → 纠错）。
     */
    private fun readGroupRules(sharedDir: File, options: RimeSchemaOptions): List<String> {
        val file = File(sharedDir, "pinyin.yaml")
        val lines = runCatching { file.readLines() }.getOrElse {
            Log.w(TAG, "读取 pinyin.yaml 失败：${it.message}")
            return emptyList()
        }
        val rules = ArrayList<String>()
        options.orderedGroups().forEach { group ->
            val idx = lines.indexOfFirst { it.trimEnd() == "${group.key}:" }
            if (idx < 0) {
                Log.w(TAG, "pinyin.yaml 里没有分组 ${group.key}")
                return@forEach
            }
            var i = idx + 1
            while (i < lines.size) {
                val line = lines[i]
                if (line.isNotEmpty() && !line.startsWith(" ")) break
                val trimmed = line.trimStart()
                if (line.startsWith("    - ")) {
                    rules += trimmed.removePrefix("- ")
                }
                i++
            }
        }
        return rules
    }

    // ------------------------------------------------------------ schema list

    /**
     * 写 `default.custom.yaml`：只声明实际打包 + 当前启用的方案。
     *
     * schema_list 决定部署哪些方案；把 7 个双拼全部列进去会让首次部署多编译 7 份 prism，
     * 所以只列当前选中的那一个（切换方案时重写并重新部署）。
     */
    private fun writeDefaultSchemaList(userDir: File, options: RimeSchemaOptions): Boolean {
        val body = buildString {
            append(MARKER).append('\n')
            append("patch:\n")
            append("  schema_list:\n")
            append("    - schema: ").append(RimeRuntime.DEFAULT_SCHEMA).append('\n')
            append("    - schema: luna_pinyin\n")
            if (options.shuangpin.isDoublePinyin) {
                append("    - schema: ").append(options.shuangpin.schemaId).append('\n')
            }
        }
        return try {
            File(userDir, "default.custom.yaml").writeText(body)
            true
        } catch (throwable: Throwable) {
            Log.e(TAG, "写 default.custom.yaml 失败：${throwable.message}", throwable)
            false
        }
    }
}
