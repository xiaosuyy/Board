package com.board.ime.engine

/**
 * 罗马字 → 平假名 转换（Hepburn）。
 *
 * 用于日语输入的**显示与整串提交**：Rime 内部以罗马字为编码做候选匹配，
 * 编辑区展示时把罗马字转成平假名，与主流日语输入法一致。
 *
 * 规则：
 * - 清音 / 浊音 / 半浊音 / 拗音 按 Hepburn 对应表；
 * - `n` 后接元音 → な行假名（なにぬねの），`n` 后接 y / 辅音 / 词尾 → ん；
 * - 两个相同辅音（促音）→ っ；
 * - 拗音优先长匹配（sha / kya / tsu / chi）。
 */
object JapaneseRomanizer {

    private val map = mapOf(
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "sa" to "さ", "shi" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "ta" to "た", "chi" to "ち", "tsu" to "つ", "te" to "て", "to" to "と",
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "ha" to "は", "hi" to "ひ", "fu" to "ふ", "he" to "へ", "ho" to "ほ",
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "ya" to "や", "yu" to "ゆ", "yo" to "よ",
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "wa" to "わ", "wo" to "を",
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "za" to "ざ", "ji" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ",
        "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
        "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
    )

    private val vowels = setOf('a', 'i', 'u', 'e', 'o')

    /** 把整串小写罗马字转成平假名；无法识别的字符原样跳过 */
    fun toKana(romaji: CharSequence): String {
        val s = romaji.toString().lowercase()
        val out = StringBuilder(s.length)
        var i = 0
        val n = s.length
        while (i < n) {
            val ch = s[i]
            if (ch == 'n') {
                if (i + 1 < n) {
                    val next = s[i + 1]
                    when {
                        next == '\'' -> { out.append('ん'); i += 2; continue }
                        next in vowels -> {
                            map["n$next"]?.let { out.append(it) }
                            i += 2
                            continue
                        }
                        next == 'y' || next == 'n' -> { out.append('ん'); i += 1; continue }
                        else -> { out.append('ん'); i += 1; continue }
                    }
                } else {
                    out.append('ん')
                    i += 1
                    continue
                }
            }
            // 促音：两个相同辅音
            if (i + 1 < n && ch == s[i + 1] && ch !in vowels && ch != 'n') {
                out.append('っ')
                i += 1
                continue
            }
            var matched = false
            for (len in intArrayOf(3, 2, 1)) {
                if (i + len <= n) {
                    val key = s.substring(i, i + len)
                    val kana = map[key]
                    if (kana != null) {
                        out.append(kana)
                        i += len
                        matched = true
                        break
                    }
                }
            }
            if (!matched) i += 1
        }
        return out.toString()
    }
}
