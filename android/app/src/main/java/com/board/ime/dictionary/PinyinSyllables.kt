package com.board.ime.dictionary

/**
 * 拼音音节表 + 音节切分（部署期使用，**不在输入热路径**）。
 *
 * ## 为什么需要它
 *
 * librime 的 `script_translator` 词典里，`code` 列必须是**空格分隔的音节**：
 * 官方 `luna_pinyin.dict.yaml` 就是 `㕷啦 → pa la`、`㥄遽 → ling ju`，单音节才连写。
 *
 * 而导入进来的词库（Board 导出的、外部词库）`code` 是**连写**的：
 * `abagaqiweidangxiao`、`jiuzhuan`、`poyumeijie`。
 * 直接喂给 Rime，词典编译器会把整串当成"一个音节"塞进 prism ——
 * 60 万条就是 60 万个"音节"，prism 会膨胀到无法接受，而且真机实测候选对不上。
 *
 * ## 音节表来源
 *
 * 从官方词典 `assets/rime/luna_pinyin.dict.yaml` 的 `code` 列去重提取，共 420 个，
 * **保证与 Rime 自己用的音节集合完全一致**（含 `biang`/`cei`/`fiao`/`lv`/`nve` 等生僻音节）。
 *
 * 切分算法是 DP 求"音节数最少"的方案（等价于从左到右最长匹配），
 * 切不开（含数字、非拼音、已带空格）时**原样返回**，绝不因为切分失败丢词条。
 */
object PinyinSyllables {

    /** 420 个合法音节（与官方 luna_pinyin 词典一致），空格分隔 */
    private const val TABLE =
        "a ai an ang ao ba bai ban bang bao bei ben beng bi bian biang biao bie bin bing bo bu " +
            "ca cai can cang cao ce cei cen ceng cha chai chan chang chao che chen cheng chi chong " +
            "chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo da dai " +
            "dan dang dao de dei den deng di dian diao die din ding diu dong dou du duan dui dun duo " +
            "e eh ei en eng er fa fan fang fei fen feng fiao fo fong fou fu ga gai gan gang gao ge " +
            "gei gen geng gong gou gu gua guai guan guang gui gun guo ha hai han hang hao he hei hen " +
            "heng hong hou hu hua huai huan huang hui hun huo ji jia jian jiang jiao jie jin jing " +
            "jiong jiu ju juan jue jun ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan " +
            "kuang kui kun kuo la lai lan lang lao le lei leng li lia lian liang liao lie lin ling " +
            "liu lo long lou lu luan lun luo lv lvan lve ma mai man mang mao me mei men meng mi mian " +
            "miao mie min ming miu mo mou mu na nai nan nang nao ne nei nen neng ni nia nian niang " +
            "niao nie nin ning niu nong nou nu nuan nun nuo nv nve o ou pa pai pan pang pao pei pen " +
            "peng pi pia pian piao pie pin ping po pou pu qi qia qian qiang qiao qie qin qing qiong " +
            "qiu qu quan que qun ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo sa sai " +
            "san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua " +
            "shuai shuan shuang shui shun shuo si song sou su suan sui sun suo ta tai tan tang tao te " +
            "teng ti tian tiao tie ting tong tou tu tuan tui tun tuo wa wai wan wang wei wen weng wo " +
            "wong wu xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun ya yai yan yang " +
            "yao ye yi yin ying yo yong you yu yuan yue yun za zai zan zang zao ze zei zen zeng zha " +
            "zhai zhan zhang zhao zhe zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun " +
            "zhuo zi zong zou zu zuan zui zun zuo"

    /** 全部合法音节 */
    val all: Set<String> by lazy {
        TABLE.split(' ').filter { it.isNotEmpty() }.toHashSet()
    }

    private val longest: Int by lazy { all.maxOf { it.length } }

    /**
     * 把连写拼音切成空格分隔音节的 code（`jiuzhuan` → `jiu zhuan`）。
     *
     * 只处理**纯小写字母**的 code；已经带空格、含数字/符号、或切不开的一律原样返回。
     */
    fun segment(code: String): String {
        val raw = code.trim()
        if (raw.isEmpty()) return raw
        val s = raw.lowercase()
        if (s.length > MAX_SEGMENT_LENGTH) return code
        if (s.any { it < 'a' || it > 'z' }) return code

        val n = s.length
        val reachable = BooleanArray(n + 1)
        val take = IntArray(n + 1)
        reachable[n] = true
        for (i in n - 1 downTo 0) {
            var len = minOf(longest, n - i)
            while (len >= 1) {
                if (reachable[i + len] && all.contains(s.substring(i, i + len))) {
                    reachable[i] = true
                    take[i] = len
                    break
                }
                len--
            }
        }
        // 切不开：说明不是标准拼音（人名、外语、生僻拼法），保留原样，由 Rime 自己处理
        if (!reachable[0]) return code

        val sb = StringBuilder(s.length + 8)
        var i = 0
        while (i < n) {
            val len = take[i]
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s, i, i + len)
            i += len
        }
        return sb.toString()
    }

    /** 保险丝：超长 code 不做 DP（避免异常数据拖慢部署） */
    private const val MAX_SEGMENT_LENGTH = 64
}
