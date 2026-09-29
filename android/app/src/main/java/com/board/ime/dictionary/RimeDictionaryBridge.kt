package com.board.ime.dictionary

import android.util.Log
import com.board.ime.engine.rime.RimeRuntime
import java.io.BufferedWriter
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * 大型词库与 Rime 之间的桥。
 *
 * ## 三段式
 * 1. **长期存储**：`imported/<sha256>.dict.yaml.gz`（见 [DictionaryStore]）
 * 2. **部署**：解压成 `<user_dir>/imported/<逻辑名>.dict.yaml` —— 注意部署时会**重写头部**，
 *    把 `name:` 改成逻辑 dictionary 名，这样 schema 里的 `dictionary: <逻辑名>` 才能对上
 * 3. **schema 挂接**：写 `luna_pinyin_simp.custom.yaml`（Rime 官方 patch 机制），
 *    把大型词典作为**额外词源**追加进 translators，不替换任何原有内容
 *
 * ## 目录约定
 * 逻辑 dictionary 名和物理文件是两个概念：
 * - 逻辑名：`board_import_<hash前12>`（喂给 Rime 的 `dictionary:`）
 * - 物理文件：`<hash>.dict.yaml.gz`（长期存储）；部署时生成 `<逻辑名>.dict.yaml`（临时）
 *
 * ## 安全
 * - 部署成功 **且 `table.bin` 已生成** 才删临时文件、才把 `ready` 置 1
 * - 任何一步失败：gz 不动、`build/` 不动、userdb 不动、原有 schema 不被覆盖
 * - `custom.yaml` 只在不存在（或就是 Board 生成的）时才写，绝不覆盖用户自己的 patch
 */
object RimeDictionaryBridge {

    private const val TAG = "BoardDictRime"

    /** Board 的 patch 文件标记：用于判断这个 custom.yaml 是不是我们写的 */
    const val PATCH_MARKER = "# Managed by Board (auto-generated, do not edit)"

    /** 默认方案（当前唯一启用） */
    const val DEFAULT_SCHEMA = "luna_pinyin_simp"

    /**
     * 自动部署的条目上限。
     *
     * 60 万条让 librime 构建索引时内存与耗时都不可控，所以超过这个规模
     * 不在「同步/启动」路径里自动部署，由用户点「部署到 Rime」显式触发。
     */
    const val AUTO_DEPLOY_MAX_ENTRIES = 120_000

    /**
     * **仅用于显式部署**的规模上限（比自动部署宽松得多）。
     *
     * 超过它时仍然允许尝试，但会在日志里给出明确提示 —— 60 万条属于这个区间。
     */
    const val MANUAL_DEPLOY_WARN_ENTRIES = 1_000_000

    fun directory(userDir: File): File = File(userDir, "imported")

    /**
     * 逻辑 dictionary 名：由内容 SHA-256 派生，稳定且合法
     * （只含小写字母 / 数字 / 下划线，符合 Rime 命名规则）。
     */
    fun logicalDictionaryName(source: DictionarySource): String {
        val suffix = source.hash.take(12).ifBlank { source.remoteId.filter { it.isLetterOrDigit() } }
        return "board_import_${suffix.lowercase()}"
    }

    /** 部署时生成的物理文件名（临时；名字必须与逻辑名一致，Rime 靠它找词典） */
    fun rimeDictName(source: DictionarySource): String = logicalDictionaryName(source) + ".dict.yaml"

    /**
     * 临时工作文件放在**用户目录根**（与 luna_pinyin.dict.yaml 同级）。
     *
     * 放在 `imported/` 子目录里时 librime 没有为它建表（实测），
     * 根目录是 Rime 词典的传统位置，最保险。
     */
    fun rimeDictFile(userDir: File, source: DictionarySource): File =
        File(userDir, rimeDictName(source))

    fun isStaged(userDir: File, source: DictionarySource): Boolean =
        rimeDictFile(userDir, source).isFile

    /** Rime 的部署产物路径（用于确认 table.bin 真的生成了） */
    fun tableFile(userDir: File, source: DictionarySource): File =
        File(File(userDir, "build"), logicalDictionaryName(source) + ".table.bin")

    /**
     * 部署前准备：把 gz 解压成 Rime 能读的 `<逻辑名>.dict.yaml`。
     *
     * **头部会被重写**：用逻辑 dictionary 名作为 `name:`，正文（词条）从 gz 里流式拷贝，
     * 跳过 gz 自己的旧头部。全程流式，不把 20MB 文本读进内存。
     *
     * @return 解压出的文件；失败返回 null（gz 不动）
     */
    fun stageForDeploy(userDir: File, source: DictionarySource, importedDir: File): File? {
        val stored = File(source.path)
        if (!stored.isFile) {
            Log.w(TAG, "数据源文件不存在：${stored.absolutePath}")
            return null
        }
        val target = rimeDictFile(userDir, source)
        target.parentFile?.mkdirs()
        val logical = logicalDictionaryName(source)

        return try {
            if (DictionaryStore.isCompressedStore(stored)) {
                GZIPInputStream(stored.inputStream().buffered(), BUFFER).bufferedReader().use { reader ->
                    BufferedWriter(target.writer(), BUFFER).use { writer ->
                        writer.write(header(logical, source))
                        var inBody = false
                        var entries = 0
                        reader.forEachLine { line ->
                            if (!inBody) {
                                if (line.trim() == "...") inBody = true
                                return@forEachLine
                            }
                            if (line.isNotBlank()) {
                                writer.write(line)
                                writer.write("\n")
                                entries++
                            }
                        }
                        Log.i(TAG, "已解压待部署：${target.name}（$entries 条，${target.length()} B）")
                    }
                }
            } else {
                // 兼容未迁移的旧文件：直接拷贝
                stored.copyTo(target, overwrite = true)
            }
            if (target.length() <= 0L) {
                target.delete()
                return null
            }
            target
        } catch (throwable: Throwable) {
            Log.e(TAG, "解压失败：${throwable.message}", throwable)
            runCatching { target.delete() }
            null
        }
    }

    /** 清理临时解压出来的工作文件（**gz 保留**） */
    fun removeStaged(userDir: File, source: DictionarySource) {
        val target = rimeDictFile(userDir, source)
        runCatching { if (target.exists()) target.delete() }
    }

    /** 逻辑词典的 YAML 头 */
    private fun header(logicalName: String, source: DictionarySource): String = buildString {
        append("---\n")
        append("name: ").append(logicalName).append('\n')
        append("version: \"").append(source.version).append("\"\n")
        append("sort: by_weight\n")
        append("use_preset_vocabulary: false\n")
        append("columns:\n  - text\n  - code\n  - weight\n")
        append("...\n")
    }

    fun canAutoDeploy(source: DictionarySource): Boolean =
        source.entryCount in 1..AUTO_DEPLOY_MAX_ENTRIES

    fun isRimeReady(): Boolean = RimeRuntime.userDataDir() != null

    /**
     * 写 schema patch：把大型词典作为**额外词源**追加到 translators。
     *
     * 使用 Rime 官方机制 —— `luna_pinyin_simp.schema.yaml` 里本来就有
     * `__patch: - luna_pinyin_simp.custom:/patch?`，所以我们只写这个 custom 文件，
     * **不动原始 schema**，升级 Rime / 重新释放资源后修改依然有效。
     *
     * @return true = patch 已就位（或本来就是我们写的）；false = 存在用户自己的同文件，未覆盖
     */
    fun applySchemaPatch(userDir: File, sources: List<DictionarySource>): Boolean {
        val file = File(userDir, "$DEFAULT_SCHEMA.custom.yaml")
        if (file.isFile) {
            val existing = runCatching { file.readText() }.getOrDefault("")
            if (!existing.contains(PATCH_MARKER)) {
                Log.w(TAG, "已存在用户自己的 ${file.name}，不覆盖（请手动加入词典引用）")
                return false
            }
        }
        val body = buildString {
            append(PATCH_MARKER).append('\n')
            append("patch:\n")
            if (sources.isEmpty()) {
                append("  # 没有需要挂接的大型词库\n")
            } else {
                append("  # 追加「额外词源」：不动原来的 script_translator / luna_pinyin\n")
                append("  \"engine/translators/+\":\n")
                sources.forEach { source ->
                    append("    - script_translator@").append(translatorName(source)).append('\n')
                }
                sources.forEach { source ->
                    append("  ").append(translatorName(source)).append(":\n")
                    append("    dictionary: ").append(logicalDictionaryName(source)).append('\n')
                    append("    enable_completion: true\n")
                    append("    enable_sentence: false\n")
                    // 不建 userdb、不参与编码学习：它是静态词源，避免污染学习数据
                    append("    enable_user_dict: false\n")
                    append("    enable_encoder: false\n")
                    // 质量给负值：默认词库的候选仍然排在前面
                    append("    initial_quality: -1\n")
                    append("    comment_format:\n")
                    append("      - xform/.+//\n")
                }
            }
        }
        return try {
            file.writeText(body)
            Log.i(TAG, "已写入 schema patch：${file.name}（${sources.size} 个额外词源）")
            true
        } catch (throwable: Throwable) {
            Log.e(TAG, "写 schema patch 失败：${throwable.message}", throwable)
            false
        }
    }

    /** translator 实例名（Rime 里 `table_translator@xxx` 的 xxx） */
    private fun translatorName(source: DictionarySource): String =
        "board_extra_" + source.hash.take(12).ifBlank { source.id.toString() }

    private const val BUFFER = 64 * 1024

    /** 文件名安全化（保留给设置页展示用） */
    fun baseName(source: DictionarySource): String {
        val raw = source.name
            .removeSuffix(".dict.txt")
            .removeSuffix(".dict.yaml")
            .removeSuffix(".dict")
            .substringBeforeLast('.')
        val safe = raw.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_')
        return safe.ifBlank { "board_${source.remoteId}" }
    }
}
