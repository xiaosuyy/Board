package com.board.ime.keyboard

/**
 * 键盘布局集合（对齐微信输入法的分层键盘）。
 *
 * 分层：
 * - [letters]       字母层（英文 / 中文共用）
 * - [numbers]       数字层（微信「123」）
 * - [symbolsAscii] / [symbolsCjk]  符号层（微信「符号」第一页）
 * - [symbolsMore]   符号层第二页（「更多符号」）
 *
 * 五套布局全部是 4 行，切换面板时键盘高度不变；每行严格等于 10 个宽度单位，
 * 因此布局天然自适应屏幕宽度。布局只是“键位表”，具体显示哪一层由 `InputController` 决定。
 */
object KeyboardLayouts {

    private const val ROW_UNITS = 10f

    private val STRIP_UNIT = ROW_UNITS / 9f

    /** 顶部符号条：微信数字 / 符号层顶上那一行（`# % & + …… 《 》 ｜ ✕`）。 */
    private fun stripRow(): KeyRow = KeyRow(
        keys = listOf(
            sym("#"), sym("%"), sym("&"), sym("+"), sym("……"), sym("《"), sym("》"), sym("｜"),
            KeySpec(KeyType.NOT_IMPLEMENTED, text = "✕", label = "✕", units = STRIP_UNIT),
        ),
    )

    private fun sym(text: String): KeySpec =
        KeySpec(KeyType.SYMBOL, text = text, units = STRIP_UNIT)

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
            // 微信底行：123 · ，。 · 空格 · 中/英 · 搜索
            KeyRow(
                keys = listOf(
                    KeySpec(KeyType.TO_NUMBERS, label = "123", units = 1.3f),
                    KeySpec(KeyType.PUNCTUATION, units = 1.3f),
                    KeySpec(KeyType.SPACE, units = 4.1f),
                    KeySpec(KeyType.LANGUAGE, units = 1.6f),
                    KeySpec(KeyType.ENTER, units = 1.7f),
                ),
            ),
        ),
    )

    // ------------------------------------------------------------ 数字层（微信 123）

    val numbers: KeyboardLayout = KeyboardLayout(
        listOf(
            stripRow(),
            KeyRow(keys = listOf("-", "/", ":", "~", "(", ")", "…", "@", "“", "”").map { s(it) }),
            KeyRow(
                keys = buildList {
                    add(KeySpec(KeyType.TO_SYMBOLS, label = "符号", units = 1.5f))
                    listOf("。", ",", "、", "?", "!", ".").forEach {
                        add(KeySpec(KeyType.SYMBOL, text = it, units = 7f / 6f))
                    }
                    add(KeySpec(KeyType.BACKSPACE, units = 1.5f))
                },
            ),
            // 微信底行：← 12/34 空格 换行 搜索（表情键按需求去掉）
            KeyRow(
                keys = listOf(
                    KeySpec(KeyType.TO_LETTERS, label = "←", units = 1.4f),
                    KeySpec(KeyType.NUMBER_PAGE, label = "12/34", units = 1.4f),
                    KeySpec(KeyType.SPACE, units = 3.6f),
                    KeySpec(KeyType.ENTER_NEWLINE, label = "换行", units = 1.6f),
                    KeySpec(KeyType.ENTER, units = 2.0f),
                ),
            ),
        ),
    )

    // ------------------------------------------------------------ 数字层第二页（微信 34：九宫格数字小键盘）

    private fun padKey(text: String): KeySpec = KeySpec(KeyType.CHARACTER, text = text, units = 2f)
    private fun padSym(text: String): KeySpec = KeySpec(KeyType.SYMBOL, text = text, units = 2f)

    /**
     * 数字层第二页：对齐微信「34」的九宫格数字小键盘
     * （左列 @ % - +、中间 1-9、右列 ⌫ / 空格，底部 ← 符号 0 . 搜索）。
     * 微信右列还有表情键，按需求已去掉，末列用 `+` 补齐。
     */
    val numbersPad: KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(
                keys = listOf(
                    padSym("@"), padKey("1"), padKey("2"), padKey("3"),
                    KeySpec(KeyType.BACKSPACE, units = 2f),
                ),
            ),
            KeyRow(
                keys = listOf(
                    padSym("%"), padKey("4"), padKey("5"), padKey("6"),
                    KeySpec(KeyType.SPACE, units = 2f),
                ),
            ),
            KeyRow(
                keys = listOf(
                    padSym("-"), padKey("7"), padKey("8"), padKey("9"), padSym("+"),
                ),
            ),
            KeyRow(
                keys = listOf(
                    KeySpec(KeyType.TO_LETTERS, label = "←", units = 2f),
                    KeySpec(KeyType.TO_SYMBOLS, label = "符号", units = 2f),
                    padKey("0"),
                    KeySpec(KeyType.SYMBOL, text = ".", units = 2f),
                    KeySpec(KeyType.ENTER, units = 2f),
                ),
            ),
        ),
    )

    // ------------------------------------------------------------ 符号层（微信 符号）

    /** 微信「符号」层：英文 / 中文共用同一套（原「中文标点」层并入）。 */
    val symbolsAscii: KeyboardLayout = wechatSymbols()
    val symbolsCjk: KeyboardLayout = symbolsAscii

    private fun wechatSymbols(): KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(keys = listOf("_", "—", ";", "｜", "%", "+", "-", "×", "÷", "=").map { s(it) }),
            KeyRow(keys = listOf("【", "】", "{", "}", "《", "》", "#", "$", "&", "·").map { s(it) }),
            KeyRow(
                keys = buildList {
                    add(KeySpec(KeyType.TO_NUMBERS, label = "123", units = 1.5f))
                    listOf("「", "」", "<", ">", "^", "*").forEach {
                        add(KeySpec(KeyType.SYMBOL, text = it, units = 7f / 6f))
                    }
                    add(KeySpec(KeyType.BACKSPACE, units = 1.5f))
                },
            ),
            // 微信符号层底部是分类标签；Board 落成功能行（← 中/英 / 更多 / 空格 / 搜索）
            symbolBottomRow(KeyType.TO_MORE_SYMBOLS),
        ),
    )

    // ------------------------------------------------------------ 更多符号（第二页）

    val symbolsMore: KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(keys = listOf("%", "&", "<", ">", "°", "±", "×", "÷", "§", "•").map { s(it) }),
            KeyRow(keys = listOf("€", "£", "¥", "¢", "©", "®", "™", "¶", "¿", "¡").map { s(it) }),
            KeyRow(
                keys = buildList {
                    add(KeySpec(KeyType.TO_MAIN_SYMBOLS, units = 1.5f))
                    listOf("^", "`", "|", "\\", "/", "~", "\"").forEach { add(s(it)) }
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
        val all = listOf(letters, numbers, numbersPad, symbolsAscii, symbolsCjk, symbolsMore)
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
class KeyboardLayout(val rows: List<KeyRow>) {

    val rowCount: Int
        get() = rows.size
}
