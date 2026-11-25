package com.board.ime.keyboard

/**
 * 键盘布局集合（对齐微信输入法的分层键盘）。
 *
 * 分层：
 * - [letters]       字母层（英文 / 中文共用）
 * - [numbers]       数字层（九宫格小键盘）
 * - [symbolsAscii] / [symbolsCjk]  符号层（微信「符号」第一页）
 * - [symbolsMore]   符号层第二页（中文标点 / 箭头 / 常用图形）
 * - [symbolsExtra]  符号层第三页（货币 / 数学 / 其它）
 *
 * 符号页的翻页键都显示「符号」：第一页 → 第二页 → 第三页 → 第一页。
 *
 * 所有布局都是 4 行，切换面板时键盘高度不变；每行严格等于 10 个宽度单位，
 * 因此布局天然自适应屏幕宽度。布局只是“键位表”，具体显示哪一层由 `InputController` 决定。
 */
object KeyboardLayouts {

    private const val ROW_UNITS = 10f

    /**
     * 数字层左右两列比中间数字列略窄（参考图中 93 : 108）。
     * 中间三列均分剩余宽度。
     */
    private const val PAD_SIDE_UNIT = 1.8f
    private const val PAD_DIGIT_UNIT = (ROW_UNITS - PAD_SIDE_UNIT * 2f) / 3f

    /** 数字层左侧 @ % ？ 三键合并块编号 */
    private const val NUM_PAD_STACK = 1

    /** 符号键（普通 1 单位） */
    private fun s(text: String): KeySpec = KeySpec(KeyType.SYMBOL, text = text)

    // ------------------------------------------------------------ 字母层（微信）

    val letters: KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(keys = "qwertyuiop".map { KeySpec(KeyType.CHARACTER, text = it.toString()) }),
            KeyRow(
                keys = "asdfghjkl".map { KeySpec(KeyType.CHARACTER, text = it.toString()) },
                leadingPadUnits = 0.5f,
                trailingPadUnits = 0.5f,
            ),
            KeyRow(
                keys = buildList {
                    add(KeySpec(KeyType.SHIFT, units = 1.5f))
                    "zxcvbnm".forEach { add(KeySpec(KeyType.CHARACTER, text = it.toString())) }
                    add(KeySpec(KeyType.BACKSPACE, units = 1.5f))
                },
            ),
            // 底行：123 · 符号 · 空格 · 中/EN · 搜索
            // 已删掉原来的「，。」标点键（逗号点按 / 句号上滑），
            // 「符号」左移到原标点键的位置，空格键吸收腾出的宽度，
            // 整行仍为 10 单位不变。
            KeyRow(
                keys = listOf(
                    KeySpec(KeyType.TO_NUMBERS, label = "123", units = 1.15f),
                    KeySpec(KeyType.TO_SYMBOLS, label = "符号", units = 1.4f),
                    KeySpec(KeyType.SPACE, units = 4.65f),
                    KeySpec(KeyType.LANGUAGE, units = 1.4f),
                    KeySpec(KeyType.ENTER, units = 1.4f),
                ),
            ),
        ),
    )

    // ------------------------------------------------------------ 数字层（九宫格小键盘）

    private fun padKey(text: String): KeySpec = KeySpec(KeyType.CHARACTER, text = text, units = PAD_DIGIT_UNIT)

    /** 左侧竖条上的符号键（同一 [NUM_PAD_STACK] 合并成一块，中间只有细分隔线） */
    private fun padSide(text: String): KeySpec =
        KeySpec(KeyType.SYMBOL, text = text, units = PAD_SIDE_UNIT, stackId = NUM_PAD_STACK)

    /** 右列等未合并的普通符号键 */
    private fun padSym(text: String): KeySpec = KeySpec(KeyType.SYMBOL, text = text, units = PAD_SIDE_UNIT)

    /**
     * 数字层：九宫格数字小键盘。
     *
     * 左列 @ % ？、中间 1-9、右列 ⌫ / 空格 / +，底部 ← 符号 0 . 搜索。
     * 参考图左列是 @ % - + 四个小格；按需求这里左列只保留
     * @ % ？三个键（+ 仍在右列第三行，不放进左列）。
     */
    val numbers: KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(
                keys = listOf(
                    padSide("@"), padKey("1"), padKey("2"), padKey("3"),
                    KeySpec(KeyType.BACKSPACE, units = PAD_SIDE_UNIT),
                ),
            ),
            KeyRow(
                keys = listOf(
                    padSide("%"), padKey("4"), padKey("5"), padKey("6"),
                    KeySpec(KeyType.SPACE, units = PAD_SIDE_UNIT),
                ),
            ),
            KeyRow(
                keys = listOf(
                    // 全角「？」的字形在字宽内偏左，显示用半角 ? 才能和 @ % 对齐；上屏仍是全角
                    KeySpec(
                        KeyType.SYMBOL,
                        text = "？",
                        label = "?",
                        units = PAD_SIDE_UNIT,
                        stackId = NUM_PAD_STACK,
                    ),
                    padKey("7"), padKey("8"), padKey("9"), padSym("+"),
                ),
            ),
            KeyRow(
                keys = listOf(
                    KeySpec(KeyType.TO_LETTERS, label = "←", units = PAD_SIDE_UNIT),
                    KeySpec(KeyType.TO_SYMBOLS, label = "符号", units = PAD_DIGIT_UNIT),
                    padKey("0"),
                    KeySpec(KeyType.SYMBOL, text = ".", units = PAD_DIGIT_UNIT),
                    KeySpec(KeyType.ENTER, units = PAD_SIDE_UNIT),
                ),
            ),
        ),
        ignoresFunctionKeyRatio = true,
    )

    // ------------------------------------------------------------ 九键层（T9）

    /** 九键左侧标点竖条合并块编号（，。？ 连成一张圆角卡片，不含感叹号） */
    private const val T9_PUNCT_STACK = 2

    /** 九键数字键：中间三列，宽度与数字层一致（左右列窄、中间均分） */
    private fun t9Digit(text: String, letters: String? = null): KeySpec =
        KeySpec(KeyType.T9_DIGIT, text = text, label = text, subLabel = letters, units = PAD_DIGIT_UNIT)

    /**
     * 九键（T9）拼音层（对齐搜狗 / 讯飞九键）：
     * - 左列竖条：，。？（不含感叹号）
     * - 中间 3×3：1 分词 / 2–9 字母组（不显示数字，不切数字面板就打不出数字）
     * - 右列：退格 / 重输 / ！
     * - 底部：符 · 123 · 空格 · 中/英 · 回车（每个键对齐上方列宽，回车不再单独加宽）
     * 整行仍为 10 单位，自适应宽度。
     */
    val t9: KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(
                keys = listOf(
                    KeySpec(KeyType.SYMBOL, text = "，", units = PAD_SIDE_UNIT, stackId = T9_PUNCT_STACK),
                    t9Digit("1", "分词"),
                    t9Digit("2", "ABC"),
                    t9Digit("3", "DEF"),
                    KeySpec(KeyType.BACKSPACE, units = PAD_SIDE_UNIT),
                ),
            ),
            KeyRow(
                keys = listOf(
                    KeySpec(KeyType.SYMBOL, text = "。", units = PAD_SIDE_UNIT, stackId = T9_PUNCT_STACK),
                    t9Digit("4", "GHI"),
                    t9Digit("5", "JKL"),
                    t9Digit("6", "MNO"),
                    KeySpec(KeyType.RESET, units = PAD_SIDE_UNIT),
                ),
            ),
            KeyRow(
                keys = listOf(
                    KeySpec(KeyType.SYMBOL, text = "？", units = PAD_SIDE_UNIT, stackId = T9_PUNCT_STACK),
                    t9Digit("7", "PQRS"),
                    t9Digit("8", "TUV"),
                    t9Digit("9", "WXYZ"),
                    KeySpec(KeyType.SYMBOL, text = "！", units = PAD_SIDE_UNIT),
                ),
            ),
            KeyRow(
                keys = listOf(
                    KeySpec(KeyType.TO_SYMBOLS, label = "符", units = PAD_SIDE_UNIT),
                    KeySpec(KeyType.TO_NUMBERS, label = "123", units = PAD_DIGIT_UNIT),
                    KeySpec(KeyType.SPACE, units = PAD_DIGIT_UNIT),
                    KeySpec(KeyType.LANGUAGE, units = PAD_DIGIT_UNIT),
                    KeySpec(KeyType.ENTER, units = PAD_SIDE_UNIT),
                ),
            ),
        ),
        ignoresFunctionKeyRatio = true,
    )

    // ------------------------------------------------------------ 符号层（微信 符号）

    /** 微信「符号」层：英文 / 中文共用同一套（原「中文标点」层并入）。 */
    val symbolsAscii: KeyboardLayout = wechatSymbols()
    val symbolsCjk: KeyboardLayout = symbolsAscii

    private fun wechatSymbols(): KeyboardLayout = symbolsMainWithRecent(emptyList())

    /**
     * 符号第一页：把「最近使用」的符号置顶到第一行前面（对齐搜狗 / 讯飞）。
     *
     * - 固定第一行是 10 个最常用中文标点；最近使用里命中这些的**不再重复置顶**
     * - 真正置顶的是第二 / 三页才会出现的符号（箭头、货币、数学等），
     *   它们被提到第一行后，固定标点顺延，凑满整行 10 个
     */
    fun symbolsMainWithRecent(recent: List<String>): KeyboardLayout {
        val base = listOf("，", "。", "？", "！", "、", "；", "：", "～", "…", "—")
        val recents = recent
            .map { it.trim() }
            .filter { it.length == 1 && it !in base }
            .distinct()
            .take(RECENT_SYMBOL_COUNT)
        val firstRow = (recents + base).take(10).map { s(it) }
        return KeyboardLayout(
            listOf(
                KeyRow(keys = firstRow),
                KeyRow(keys = listOf("“", "”", "‘", "’", "（", "）", "【", "】", "《", "》").map { s(it) }),
                KeyRow(
                    keys = buildList {
                        add(KeySpec(KeyType.TO_NUMBERS, label = "123", units = 1.5f))
                        listOf("·", "￥", "×", "÷", "°", "±").forEach {
                            add(KeySpec(KeyType.SYMBOL, text = it, units = 7f / 6f))
                        }
                        add(KeySpec(KeyType.BACKSPACE, units = 1.5f))
                    },
                ),
                // 常用符号优先（对齐搜狗/讯飞）：第一页即最常用的中文标点，
                // 西文编程符号 / 箭头 / 货币 / 数学放后续两页，不再把半角符号压在首屏。
                symbolBottomRow(KeyType.TO_MORE_SYMBOLS),
            ),
        )
    }

    /** 最近使用符号最多置顶几个 */
    private const val RECENT_SYMBOL_COUNT = 5

    // ------------------------------------------------------------ 符号第二页（中文标点）

    /**
     * 第二页：中文标点 + 箭头 / 常用图形。
     * 原先这一页的 % & < > × ÷ ^ 与第一页重复，按需求换成日常中文标点。
     */
    val symbolsMore: KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(keys = listOf("→", "←", "↑", "↓", "★", "☆", "✓", "℃", "℉", "§").map { s(it) }),
            KeyRow(keys = listOf("＋", "－", "×", "÷", "＝", "≈", "≠", "≤", "≥", "∞").map { s(it) }),
            KeyRow(
                keys = buildList {
                    add(KeySpec(KeyType.TO_NUMBERS, label = "123", units = 1.5f))
                    listOf("€", "£", "¥", "$", "¢", "©", "®").forEach { add(s(it)) }
                    add(KeySpec(KeyType.BACKSPACE, units = 1.5f))
                },
            ),
            symbolBottomRow(KeyType.TO_EXTRA_SYMBOLS),
        ),
    )

    // ------------------------------------------------------------ 符号第三页（更多符号）

    /** 第三页：货币 / 数学 / 西文杂项。 */
    val symbolsExtra: KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(keys = listOf("_", "-", "+", "=", "%", "&", "#", "$", "@", "^").map { s(it) }),
            KeyRow(keys = listOf("{", "}", "<", ">", "|", "\\", "/", "~", "`", "\"").map { s(it) }),
            KeyRow(
                keys = buildList {
                    add(KeySpec(KeyType.TO_NUMBERS, label = "123", units = 1.5f))
                    listOf("§", "¶", "•", "†", "¿", "¡", "½").forEach { add(s(it)) }
                    add(KeySpec(KeyType.BACKSPACE, units = 1.5f))
                },
            ),
            symbolBottomRow(KeyType.TO_MAIN_SYMBOLS),
        ),
    )

    /** 数字 / 符号层共用的底部行：← + 中/英 + 翻页 + 空格 + 搜索 */
    private fun symbolBottomRow(pageToggle: KeyType): KeyRow = KeyRow(
        keys = listOf(
            KeySpec(KeyType.TO_LETTERS, label = "←", units = 1.3f),
            KeySpec(KeyType.LANGUAGE, units = 1.4f),
            KeySpec(pageToggle, units = 1.5f),
            KeySpec(KeyType.SPACE, units = 3.4f),
            KeySpec(KeyType.ENTER, units = 2.4f),
        ),
    )

    init {
        // 布局是手工维护的常量表，这里在类加载时做一次断言，避免以后改错单位。
        val all = listOf(letters, t9, numbers, symbolsAscii, symbolsCjk, symbolsMore, symbolsExtra)
        all.forEach { layout ->
            layout.rows.forEachIndexed { index, row ->
                check(kotlin.math.abs(row.totalUnits - ROW_UNITS) < 0.001f) {
                    "键盘布局第 ${index + 1} 行宽度单位不是 $ROW_UNITS：${row.totalUnits}"
                }
            }
        }
    }
}

/** 一套键盘布局：若干行按键 */
class KeyboardLayout(
    val rows: List<KeyRow>,
    /**
     * 功能键宽度倍率是否忽略。
     * 数字九宫格按固定列宽排布，倍率参与会把各行的功能键各自伸缩、
     * 导致上下列对不齐，所以数字层固定按基础单位布局。
     */
    val ignoresFunctionKeyRatio: Boolean = false,
) {

    val rowCount: Int
        get() = rows.size
}
