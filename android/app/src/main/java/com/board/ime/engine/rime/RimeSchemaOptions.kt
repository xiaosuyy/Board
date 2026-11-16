package com.board.ime.engine.rime

/**
 * Rime 拼写运算分组（pinyin.yaml 里的 12 组），每组单独开关。
 *
 * `key` 必须与 `assets/rime/pinyin.yaml` 里的顶层分组名一致：
 * 运行期生成方案时会按这个 key 读取对应的规则。
 */
enum class RimeFuzzyGroup(
    val key: String,
    val label: String,
    val hint: String,
    val defaultOn: Boolean,
) {
    ZH_Z("zh_z_bufen", "平翘舌不分", "zh/ch/sh ↔ z/c/s（zhi → zi）", true),
    N_L("n_l_bufen", "n / l 不分", "南 ↔ 兰", true),
    R_L("r_l_bufen", "r → l", "肉 → 漏", false),
    R_Y("r_y_bufen", "r → y", "人 → 银", false),
    HU_F("hu_f_buhun", "h / f 混淆", "湖 → 扶", true),
    ENG_ONG("eng_ong_bufen", "eng ↔ ong", "梦 → mong", true),
    EN_ENG("en_eng_bufen", "前后鼻音", "en ↔ eng，in ↔ ing", true),
    ZIANTUAN("ziantuan", "尖团音", "ji → zi，qi → ci，xi → si", false),
    ZHONGGUAN("zhongguan", "中原官话韵母", "bo → beh，ge → geh", false),
    ABBREVIATION("abbreviation", "简拼", "bjdx → 北京大学", true),
    SPELLING_CORRECTION("spelling_correction", "拼写容错", "lue/nue/ju 的规范拼法", true),
    KEY_CORRECTION("key_correction", "按键容错", "zhonguo → 中国", true),
    ;

    companion object {
        fun fromKey(key: String): RimeFuzzyGroup? = entries.firstOrNull { it.key == key }

        fun defaults(): Set<String> = entries.filter { it.defaultOn }.map { it.key }.toSet()
    }
}

/**
 * 双拼方案。schemaId 与 `assets/rime/<schemaId>.schema.yaml` 一一对应；
 * [OFF] 表示全拼（luna_pinyin_simp）。
 */
enum class ShuangpinScheme(
    val schemaId: String,
    val label: String,
) {
    OFF(RimeRuntime.DEFAULT_SCHEMA, "全拼"),
    ZIRANMA("double_pinyin", "自然码"),
    XIAOHE("double_pinyin_flypy", "小鹤双拼"),
    WEIRUAN("double_pinyin_mspy", "微软双拼"),
    SOGOU("double_pinyin_sogou", "搜狗双拼"),
    ZHINENG_ABC("double_pinyin_abc", "智能ABC"),
    ZIGUANG("double_pinyin_ziguang", "紫光双拼"),
    PYJJ("double_pinyin_pyjj", "拼音加加"),
    ;

    val isDoublePinyin: Boolean
        get() = this != OFF

    companion object {
        fun fromName(name: String?): ShuangpinScheme =
            entries.firstOrNull { it.name == name } ?: OFF
    }
}

/** 当前 Rime 方案配置（模糊音 / 繁体 / 双拼），用于生成 schema patch。 */
data class RimeSchemaOptions(
    val fuzzyGroups: Set<String> = RimeFuzzyGroup.defaults(),
    val traditional: Boolean = false,
    val shuangpin: ShuangpinScheme = ShuangpinScheme.OFF,
) {
    /** 当前生效的方案 id（全拼或某个双拼） */
    val schemaId: String
        get() = shuangpin.schemaId

    /**
     * 按固定顺序输出启用的分组。
     *
     * 顺序必须是「模糊音 → 简拼 → 纠错」：模糊音排在简拼之前，
     * 简拼才会带上模糊音（见 pinyin.yaml 顶部说明）。
     */
    fun orderedGroups(): List<RimeFuzzyGroup> =
        RimeFuzzyGroup.entries.filter { it.key in fuzzyGroups }
}
