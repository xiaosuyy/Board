package com.board.ime.keyboard

/**
 * 键盘布局集合。
 *
 * 采用分层设计（ABC / 123 / #+= / 更多符号），不会把所有符号塞进主键盘：
 *
 * - [letters]       字母层（英文 / 中文共用）
 * - [numbers]       数字层
 * - [symbolsAscii]  符号层 · 英文常用
 * - [symbolsCjk]    符号层 · 中文标点
 * - [symbolsMore]   更多符号（第二页，中英共用）
 *
 * 每行都严格等于 10 个宽度单位，因此布局天然自适应屏幕宽度。
 * 布局只是“键位表”，具体显示哪一层由 `InputController` 决定。
 */
object KeyboardLayouts {

    private const val ROW_UNITS = 10f

    // ------------------------------------------------------------ 字母层

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
            KeyRow(
                keys = listOf(
                    KeySpec(KeyType.LANGUAGE, units = 1.2f),
                    KeySpec(KeyType.TO_NUMBERS, units = 1.4f),
                    KeySpec(KeyType.COMMA, units = 0.9f),
                    KeySpec(KeyType.SPACE, units = 4.0f),
                    KeySpec(KeyType.PERIOD, units = 0.9f),
                    KeySpec(KeyType.ENTER, units = 1.6f),
                ),
            ),
        ),
    )

    // ------------------------------------------------------------ 数字层

    val numbers: KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(keys = "1234567890".map { KeySpec(KeyType.CHARACTER, text = it.toString()) }),
            KeyRow(
                keys = listOf("-", "/", ":", ";", "(", ")", "&", "@", "\"", "'")
                    .map { KeySpec(KeyType.SYMBOL, text = it) },
            ),
            KeyRow(
                keys = buildList {
                    add(KeySpec(KeyType.TO_SYMBOLS, units = 1.5f))
                    listOf(".", ",", "?", "!", "+", "=", "*")
                        .forEach { add(KeySpec(KeyType.SYMBOL, text = it)) }
                    add(KeySpec(KeyType.BACKSPACE, units = 1.5f))
                },
            ),
            KeyRow(
                keys = listOf(
                    KeySpec(KeyType.TO_LETTERS, units = 1.3f),
                    KeySpec(KeyType.COMMA, units = 0.9f),
                    KeySpec(KeyType.LANGUAGE, units = 1.2f),
                    KeySpec(KeyType.SPACE, units = 4.2f),
                    KeySpec(KeyType.PERIOD, units = 0.9f),
                    KeySpec(KeyType.ENTER, units = 1.5f),
                ),
            ),
        ),
    )

    // ------------------------------------------------------------ 符号层 · 英文

    val symbolsAscii: KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(
                keys = listOf("!", "?", ":", ";", "\"", "'", "(", ")", "*", "&")
                    .map { KeySpec(KeyType.SYMBOL, text = it) },
            ),
            KeyRow(
                keys = listOf(",", ".", "-", "_", "+", "=", "[", "]", "{", "}")
                    .map { KeySpec(KeyType.SYMBOL, text = it) },
            ),
            KeyRow(
                keys = buildList {
                    add(KeySpec(KeyType.TO_MORE_SYMBOLS, units = 1.5f))
                    listOf("/", "\\", "|", "~", "^", "#", "$")
                        .forEach { add(KeySpec(KeyType.SYMBOL, text = it)) }
                    add(KeySpec(KeyType.BACKSPACE, units = 1.5f))
                },
            ),
            symbolBottomRow(),
        ),
    )

    // ------------------------------------------------------------ 符号层 · 中文标点

    val symbolsCjk: KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(
                keys = listOf("，", "。", "！", "？", "：", "；", "、", "——", "……", "·")
                    .map { KeySpec(KeyType.SYMBOL, text = it) },
            ),
            KeyRow(
                keys = listOf("（", "）", "【", "】", "《", "》", "“", "”", "‘", "’")
                    .map { KeySpec(KeyType.SYMBOL, text = it) },
            ),
            KeyRow(
                keys = buildList {
                    add(KeySpec(KeyType.TO_MORE_SYMBOLS, units = 1.5f))
                    listOf("￥", "$", "&", "@", "#", "%", "°")
                        .forEach { add(KeySpec(KeyType.SYMBOL, text = it)) }
                    add(KeySpec(KeyType.BACKSPACE, units = 1.5f))
                },
            ),
            symbolBottomRow(),
        ),
    )

    // ------------------------------------------------------------ 更多符号（第二页）

    val symbolsMore: KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(
                keys = listOf("%", "&", "<", ">", "°", "±", "×", "÷", "§", "•")
                    .map { KeySpec(KeyType.SYMBOL, text = it) },
            ),
            KeyRow(
                keys = listOf("€", "£", "¥", "¢", "©", "®", "™", "¶", "¿", "¡")
                    .map { KeySpec(KeyType.SYMBOL, text = it) },
            ),
            KeyRow(
                keys = buildList {
                    add(KeySpec(KeyType.TO_MAIN_SYMBOLS, units = 1.5f))
                    listOf("^", "`", "|", "\\", "/", "~", "\"")
                        .forEach { add(KeySpec(KeyType.SYMBOL, text = it)) }
                    add(KeySpec(KeyType.BACKSPACE, units = 1.5f))
                },
            ),
            symbolBottomRow(),
        ),
    )

    /** 数字层 / 符号层共用的底部行：ABC + 123 + 语言 + 空格 + 回车 */
    private fun symbolBottomRow(): KeyRow = KeyRow(
        keys = listOf(
            KeySpec(KeyType.TO_LETTERS, units = 1.3f),
            KeySpec(KeyType.TO_NUMBERS, units = 1.3f),
            KeySpec(KeyType.LANGUAGE, units = 1.2f),
            KeySpec(KeyType.SPACE, units = 4.6f),
            KeySpec(KeyType.ENTER, units = 1.6f),
        ),
    )

    init {
        // 布局是手工维护的常量表，这里在类加载时做一次断言，避免以后改错单位。
        val all = listOf(letters, numbers, symbolsAscii, symbolsCjk, symbolsMore)
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
