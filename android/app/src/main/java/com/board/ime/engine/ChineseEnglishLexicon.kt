package com.board.ime.engine

/**
 * Board 本地中 → 英释义词表（P3）。
 *
 * ## 定位
 * 只服务候选栏的「候选英文释义」显示，**不是**词库系统，也不参与任何输入逻辑：
 * - 只读：对外只有 [lookup]，运行时数据由 [install] 一次性装入；
 * - 纯内存：装入后查询 O(1)，不读文件、不访问数据库、不联网；
 * - 不修改 Rime：不碰 schema、词典权重、`page_size`、候选排序。
 *
 * ## 数据质量原则（重要）
 * 只有中文本身含义**明确、稳定、常用**时才收录英文；歧义大 / 多义 / 依赖上下文的词
 * 一律不收录，[lookup] 返回 null，候选栏不显示英文。
 * **宁可不显示，也绝不显示误导性英文**（例如 `使 -> use`、`是 -> be` 这类一律不允许存在）。
 *
 * ## 数据来源
 * 词表来自人工确认的本地资源 `assets/chinese_english.tsv`（见 [ChineseEnglishLexiconLoader]）。
 * 它由 Board 项目自行整理、随源码分发，**不是**第三方词典数据，也不需要额外 NOTICE。
 * 运行时不加载任何在线翻译；缺词直接返回 null（绝不猜测 / 绝不机器翻译）。
 *
 * ## 架构
 * 候选生成链路在进入 UI 前调用 [withEnglishGloss]（内部走 [lookup]），
 * 因此 UI 层（CandidateBar）只读候选自带的数据，不做任何词典查询。
 */
object ChineseEnglishLexicon {

    /**
     * 当前生效的 `中文 -> 英文` 表。
     *
     * 由资源加载器在进程内装入一次；[install] 采用**整表替换**，配合 `@Volatile`
     * 保证读线程要么看到旧表、要么看到新表，不会看到半张表。未装入时为空表，
     * [lookup] 一律返回 null（候选栏等价于不显示英文）。
     */
    @Volatile
    private var byWord: Map<String, String> = emptyMap()

    /** 当前词条数量（用于日志 / 诊断） */
    val size: Int get() = byWord.size

    /** 整表替换（由资源加载器或测试调用） */
    fun install(entries: Map<String, String>) {
        byWord = entries
    }

    /** 从 TSV 文本装入（解析见 [ChineseEnglishTsv]） */
    fun installTsv(text: String) {
        install(ChineseEnglishTsv.parse(text))
    }

    /**
     * 查一个中文词的英文释义。
     *
     * - 命中返回英文，未命中 / 空串返回 null；**绝不做任何猜测或在线翻译**；
     * - 纯内存 Map 查询，O(1)，无副作用。
     */
    fun lookup(chineseWord: String): String? {
        if (chineseWord.isEmpty()) return null
        val gloss = byWord[chineseWord] ?: return null
        return gloss.ifBlank { null }
    }
}

/**
 * 给候选批量附加本地英文释义（纯内存查询）。
 *
 * 保证：
 * - **顺序完全不变**，只在原 [Candidate] 上补 `englishGloss` 字段；
 * - `text` / `label` / `enabled` / `isHint` 均不变，因此候选下标与点击映射不受影响；
 * - 已有释义（非 null）或提示项不加释义，原样保留；
 * - 没有明确释义的候选保持 `englishGloss == null`（不显示英文，不做任何猜测）。
 *
 * 当没有任何候选被补上释义时，返回原列表本身（不产生新的分配）。
 */
fun List<Candidate>.withEnglishGloss(): List<Candidate> {
    if (isEmpty()) return this
    var changed = false
    val annotated = ArrayList<Candidate>(size)
    for (candidate in this) {
        if (candidate.isHint || candidate.englishGloss != null) {
            annotated += candidate
            continue
        }
        val gloss = ChineseEnglishLexicon.lookup(candidate.text)
        if (gloss == null) {
            annotated += candidate
        } else {
            annotated += candidate.copy(englishGloss = gloss)
            changed = true
        }
    }
    return if (changed) annotated else this
}
