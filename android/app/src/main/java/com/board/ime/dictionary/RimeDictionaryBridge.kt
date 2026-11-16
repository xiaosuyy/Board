package com.board.ime.dictionary

import android.util.Log
import com.board.ime.engine.rime.RimeRuntime
import com.board.ime.engine.rime.RimeSchemaOptions
import com.board.ime.engine.rime.ShuangpinScheme
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
     * **占位方案**文件名：`<逻辑名>.schema.yaml`。
     *
     * librime 的 `schema/dependencies` 里列的名字，deployer 会当作**方案**去找
     * `<名字>.schema.yaml`；找到就把该方案的 `translator.dictionary` 预编译成
     * `build/<名字>.table.bin`（官方 `stroke` 就是这么被编译出来的），
     * 找不到只打印 `missing input schema; skipped unsatisfied dependency`。
     *
     * 真机 + librime INFO 日志实测：只给 `.dict.yaml` 时 3 个导入词库全部被 skip，
     * deploy 里**没有任何** `board_import_*.table.bin`。所以这个占位方案是必需的。
     *
     * 它**不会**出现在方案列表里（列表由 `schema_list` 决定），只是词典的挂载点。
     */
    fun rimeSchemaName(source: DictionarySource): String = logicalDictionaryName(source) + ".schema.yaml"

    fun rimeSchemaFile(userDir: File, source: DictionarySource): File =
        File(userDir, rimeSchemaName(source))

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
                                writer.write(normalizeEntry(line))
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
            // 占位方案必须一起就位，否则 deployer 会把词典当成「缺失的方案」跳过
            writePlaceholderSchema(userDir, source)
            target
        } catch (throwable: Throwable) {
            Log.e(TAG, "解压失败：${throwable.message}", throwable)
            runCatching { target.delete() }
            null
        }
    }

    /**
     * 写「占位方案」`<逻辑名>.schema.yaml`，只为让 librime 在部署阶段预编译它的词典。
     *
     * 内容刻意做成一具备最小可用结构的方案（speller + segmentors + script_translator），
     * 万一被 Rime 当成真方案编译也不会报错；但它**不在** `schema_list` 里，用户看不到。
     */
    private fun writePlaceholderSchema(userDir: File, source: DictionarySource) {
        val logical = logicalDictionaryName(source)
        val body = buildString {
            append("---\n")
            append("# Board 自动生成：仅是导入词库的挂载点，用于在部署阶段预编译词典。\n")
            append("# 它不在 schema_list 里，不会出现在方案列表中；请不要手工修改。\n")
            // 注意：Rime 方案文件要求 schema_id / name / version / dependencies 都放在 `schema:` 段里
            // （真机实测：写在顶层会被 deployer 判为 invalid schema definition 而整个编译失败）
            append("schema:\n")
            append("  schema_id: ").append(logical).append('\n')
            append("  name: \"导入词库 ").append(logical).append("\"\n")
            append("  version: \"").append(source.version).append("\"\n")
            append("  author:\n    - Board\n")
            append("  description: |\n    Board 导入词库的挂载点，不用于日常输入。\n")
            append("  dependencies: []\n")
            append("translator:\n")
            append("  dictionary: ").append(logical).append('\n')
            append("  enable_completion: false\n")
            append("  enable_sentence: false\n")
            append("  enable_encoder: false\n")
            append("  enable_user_dict: false\n")
            append("speller:\n")
            append("  alphabet: zyxwvutsrqponmlkjihgfedcba\n")
            append("  delimiter: \" '\"\n")
            append("engine:\n")
            append("  processors:\n")
            append("    - ascii_composer\n")
            append("    - recognizer\n")
            append("    - key_binder\n")
            append("    - speller\n")
            append("    - punctuator\n")
            append("    - selector\n")
            append("    - navigator\n")
            append("    - express_editor\n")
            append("  segmentors:\n")
            append("    - ascii_segmentor\n")
            append("    - matcher\n")
            append("    - abc_segmentor\n")
            append("    - punct_segmentor\n")
            append("    - fallback_segmentor\n")
            append("  translators:\n")
            append("    - punct_translator\n")
            append("    - script_translator\n")
            append("...\n")
        }
        val file = rimeSchemaFile(userDir, source)
        runCatching { file.writeText(body) }
            .onFailure { Log.w(TAG, "写占位方案失败：${file.name}（${it.message}）") }
    }

    /**
     * 把原始词库行规整成 Rime 需要的 `词条 <TAB> 编码 <TAB> 词频`。
     *
     * 真机实测：导入进来的词库有两种排布
     *   · `词条\t编码\t词频`（Board 自己导出的）
     *   · `编码\t词条\tzh-CN\t\t词频`（外部词库：编码在前，还多一个语言列）
     * 如果部署时只改头部、正文照抄，Rime 会把拉丁拼音当成"词条"、中文当成"编码"，
     * 整份词典作废 —— 表现为 deploy 报成功但 build/ 里**没有任何产物**。
     * 所以这里按内容判断列序并归一化（已存的 gz 因此不用重新导入即可生效）。
     */
    private fun normalizeEntry(line: String): String {
        val fields = line.split('\t')
        if (fields.size < 3) return line
        val first = fields[0].trim()
        val second = fields[1].trim()
        val firstIsCjk = first.any { it.code in 0x4E00..0x9FFF }
        val secondIsCjk = second.any { it.code in 0x4E00..0x9FFF }
        // 编码在前（第一个字段不是汉字、第二个是汉字）→ 交换
        val text = if (!firstIsCjk && secondIsCjk) second else first
        val rawCode = if (!firstIsCjk && secondIsCjk) first else second
        // 词频取最后一个能解析成整数的字段（避开 zh-CN 之类的列）
        val weight = fields.lastOrNull { it.trim().toIntOrNull() != null }?.trim()?.toIntOrNull() ?: 0
        // code 必须是空格分隔的音节，否则（真机实测）prism 会把整串当成一个音节
        val code = PinyinSyllables.segment(rawCode)
        return "$text\t$code\t${if (weight > 0) weight else weightForEntry(code)}"
    }

    /**
     * 外部词库普遍不给词频（整列都是 0）。原样写 0 的话，扩展词库内部就完全没有排序依据，
     * 专名 / 古籍 / 生僻长词会和日常词平起平坐地被随机排在一起。
     *
     * 这里用**音节数**做一个很轻的近似：短词通常是日常词，长词多是专名 / 古语 / 书名，
     * 正好对应「常用扩展词 > 低频扩展词」。真实词频存在时（> 0）优先用真实值。
     */
    private fun weightForEntry(code: String): Int =
        ExtraDictWeighting.weightForSyllables(code.count { it == ' ' } + 1)
    /** 清理临时解压出来的工作文件（**gz 保留**）：词典本体 + 占位方案 */
    fun removeStaged(userDir: File, source: DictionarySource) {
        runCatching { rimeDictFile(userDir, source).takeIf { it.exists() }?.delete() }
        runCatching { rimeSchemaFile(userDir, source).takeIf { it.exists() }?.delete() }
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
     * 模糊音分组与简体 / 繁体由 [options] 决定（12 组单独开关）。
     * 注意：模糊音规则本身由用户目录的 `luna_pinyin.schema.yaml` 影子文件注入
     * （见 [RimeSchemaBuilder]），这里不再写 `speller/algebra`。
     *
     * @return true = patch 已就位（或本来就是我们写的）；false = 存在用户自己的同文件，未覆盖
     */
    fun applySchemaPatch(
        userDir: File,
        sources: List<DictionarySource>,
        options: RimeSchemaOptions = RimeSchemaOptions(),
    ): Boolean {
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
            // 单页候选数（Rime 一次生成多少候选，≠ UI 可见数量）。
            // ⚠️ 用户目录里的同名文件会**顶掉**资产目录里的那一份（Rime 不会把两份 patch 合并），
            // 资产版 luna_pinyin_simp.custom.yaml 里写的也是 menu/page_size: 50；
            // 这里必须显式重复这个设置，否则会掉回 default.yaml 的默认值 5。
            // UI 可横向滑动查看整页候选；这里与 CandidateBar.MAX_CANDIDATES 保持一致。
            append("  menu/page_size: 50\n")
            // 简体 / 繁体：luna_pinyin.schema 的开关 2 是字形选择（0=傳統漢字，1=简化字）。
            append("  switches/@2/reset: ").append(if (options.traditional) 0 else 1).append('\n')
            appendTranslatorSettings()
            // 模糊音 + 简拼 + 纠错：由影子 base schema 注入，这里只保留说明
            // （见 RimeSchemaBuilder.replaceAlgebra）。
            // 特殊自定义编码（`@` 等）需要能进入 composing，见下方 alphabet / recognizer。
            // ------------------------------------------------------------------
            append("  \"speller/alphabet\": \"zyxwvutsrqponmlkjihgfedcba@0123456789._-\"\n")
            append("  \"recognizer/patterns/custom_code\": \"^[0-9@][0-9A-Za-z@._-]*$\"\n")
            appendBoardTranslators(sources)
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

    /**
     * 双拼方案的 patch：与全拚共用 board_common / 导入词库 / 用户词，
     * 并切换简体开关。拼写运算（键位 + 模糊音）由影子 schema 提供。
     */
    fun applyDoublePinyinPatch(
        userDir: File,
        scheme: ShuangpinScheme,
        sources: List<DictionarySource>,
        options: RimeSchemaOptions,
    ): Boolean {
        if (!scheme.isDoublePinyin) return true
        val file = File(userDir, "${scheme.schemaId}.custom.yaml")
        if (file.isFile) {
            val existing = runCatching { file.readText() }.getOrDefault("")
            if (!existing.contains(PATCH_MARKER)) {
                Log.w(TAG, "已存在用户自己的 ${file.name}，不覆盖")
                return false
            }
        }
        val body = buildString {
            append(PATCH_MARKER).append('\n')
            append("patch:\n")
            append("  menu/page_size: 50\n")
            // 双拼方案的开关 2 是 simplification（0=漢字，1=汉字）
            append("  switches/@2/reset: ").append(if (options.traditional) 0 else 1).append('\n')
            appendTranslatorSettings()
            // 字母表保留方案自己的分号（微软 / 搜狗 / 紫光用 ; 表示 ing），再并入自定义编码字符
            append("  \"speller/alphabet\": \"zyxwvutsrqponmlkjihgfedcba;@0123456789._-\"\n")
            append("  \"recognizer/patterns/custom_code\": \"^[0-9@][0-9A-Za-z@._-]*$\"\n")
            append("  custom_phrase:\n")
            append("    dictionary: \"\"\n")
            append("    user_dict: custom_phrase\n")
            append("    db_class: stabledb\n")
            append("    enable_completion: false\n")
            append("    enable_sentence: false\n")
            append("    initial_quality: 1\n")
            // 只写一个 `engine/translators/+` 列表：custom_phrase + board_common + 导入词库
            // （YAML 里重复的同一个 key 会互相覆盖）
            append("  \"engine/translators/+\":\n")
            append("    - table_translator@custom_phrase\n")
            append("    - script_translator@board_common\n")
            sources.forEach { source ->
                append("    - script_translator@").append(translatorName(source)).append('\n')
            }
            appendBoardTranslators(sources, includeTranslatorList = false)
        }
        return try {
            file.writeText(body)
            Log.i(TAG, "已写入双拼 patch：${file.name}")
            true
        } catch (throwable: Throwable) {
            Log.e(TAG, "写双拼 patch 失败：${throwable.message}", throwable)
            false
        }
    }

    /** 用户词优先 / 学习 / 整句 / 前缀补全 —— 两种方案共用。 */
    private fun StringBuilder.appendTranslatorSettings() {
        // 显式开启用户词典学习：选过的候选会被记录并加权，越用越靠前。
        append("  \"translator/enable_user_dict\": true\n")
        // 整句候选：xiangquzijieshangban → 想去字节上班。
        append("  \"translator/enable_sentence\": true\n")
        // 前缀补全：输入前缀即可给出更长的词 / 词组。
        append("  \"translator/enable_completion\": true\n")
        // 用户词优先：custom_phrase 的权重明显高于基础词频（约 4.8e6）。
        append("  \"custom_phrase/initial_quality\": 10000000\n")
    }

    /** board_common 现代常用词 + 用户导入的大型词库。 */
    private fun StringBuilder.appendBoardTranslators(
        sources: List<DictionarySource>,
        includeTranslatorList: Boolean = true,
    ) {
        // 现代常用词补充词源（essay.txt 高频 2–4 字词）
        if (includeTranslatorList) {
            append("  \"engine/translators/+\":\n")
            append("    - script_translator@board_common\n")
            sources.forEach { source ->
                append("    - script_translator@").append(translatorName(source)).append('\n')
            }
        }
        append("  board_common:\n")
        append("    dictionary: board_common\n")
        append("    enable_completion: false\n")
        append("    enable_sentence: false\n")
        append("    enable_user_dict: false\n")
        append("    enable_encoder: false\n")
        append("    initial_quality: 1\n")
        append("    comment_format:\n")
        append("      - xform/.+//\n")
        // 词典必须列入 schema/dependencies，deployer 才会预编译成 table.bin
        append("  \"schema/dependencies/+\":\n")
        append("    - board_common\n")
        if (sources.isNotEmpty()) {
            sources.forEach { source ->
                append("    - ").append(logicalDictionaryName(source)).append('\n')
            }
            sources.forEach { source ->
                append("  ").append(translatorName(source)).append(":\n")
                append("    dictionary: ").append(logicalDictionaryName(source)).append('\n')
                append("    enable_completion: false\n")
                append("    enable_sentence: false\n")
                append("    enable_user_dict: false\n")
                append("    enable_encoder: false\n")
                append("    initial_quality: ").append(EXTRA_QUALITY).append('\n')
                append("    comment_format:\n")
                append("      - xform/.+//\n")
            }
        }
    }

    /** translator 实例名（Rime 里 `table_translator@xxx` 的 xxx） */
    private fun translatorName(source: DictionarySource): String =
        "board_extra_" + source.hash.take(12).ifBlank { source.id.toString() }

    /**
     * 额外词源的候选质量（`initial_quality`）。
     *
     * 产品优先级：**用户学习词 > 基础常用候选 > 常用扩展词 > 低频扩展词**。
     *
     * 取一个**明显为负**的值（见 [ExtraDictWeighting.INITIAL_QUALITY]）：这样任何基础
     * 词库候选都会排在扩展词之前，扩展词只负责**扩大覆盖面**。
     * 早先用 0 时，扩展词自带的伪词频（100~1500）会压过基础常用词（essay 中位约 265），
     * 表现就是“打某个拼音时生僻词/专名排在前面”。
     */
    private const val EXTRA_QUALITY = ExtraDictWeighting.INITIAL_QUALITY

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
