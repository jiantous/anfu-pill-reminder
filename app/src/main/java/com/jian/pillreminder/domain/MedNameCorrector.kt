package com.jian.pillreminder.domain

/**
 * 药名形近字纠错。
 *
 * OCR 对药名里的生僻字容易认错（实测把「苯磺酸氨氯地平片」认成「苯磺酸氨气地平片」）。
 * 这里用两级策略，都很保守——宁可不改，也不能把对的改错，毕竟是吃药的事：
 *
 * 1. **词组级**：整段替换已知的高频错法（如「氨气」→「氨氯」）。只在错法确实
 *    不是合法药用词时才替换。
 * 2. **药名整体校正**：与常用药名库做编辑距离比对，只有"仅差一个字且长度相同"
 *    才替换，避免把没见过的药名硬套成库里的。
 *
 * 每次修改都记录下来，UI 上要显示给用户核对——绝不静默改药名。
 */
object MedNameCorrector {

    /** 一次纠正的记录，用于在界面上告诉用户"我把什么改成了什么"。 */
    data class Fix(val from: String, val to: String)

    data class Result(val text: String, val fixes: List<Fix>) {
        val corrected: Boolean get() = fixes.isNotEmpty()
    }

    /**
     * 词组级替换表：key 是 OCR 常见的错法，value 是正确写法。
     *
     * 收录原则：错法本身在药品语境下不成词，替换后是常见药用词。
     * 例如「氨气」是化学名词但不会出现在药名里，而「氨氯」是氨氯地平的组成部分。
     */
    private val PHRASE_FIXES: List<Pair<String, String>> = listOf(
        // 氯 / 气 / 氨 / 铵 一组（OCR 最容易混的偏旁）
        "氨气" to "氨氯",
        "氨录" to "氨氯",
        "氯气地平" to "氨氯地平",
        "氯化钾" to "氯化钾",       // 正确写法，占位防止被其它规则误伤
        "氧化钠" to "氯化钠",
        "氧雷他定" to "氯雷他定",
        "氨雷他定" to "氯雷他定",
        "氧沙坦" to "氯沙坦",
        "氧吡格雷" to "氯吡格雷",
        "氧硝西泮" to "氯硝西泮",

        // 磺 / 磷 / 黄
        "苯黄酸" to "苯磺酸",
        "苯磷酸" to "苯磺酸",
        "黄脲" to "磺脲",
        "格列本尿" to "格列本脲",
        "二甲双肌" to "二甲双胍",
        "二甲双抓" to "二甲双胍",
        "二甲双狐" to "二甲双胍",

        // 酯 / 脂（前者是化学结构，后者是脂肪，药名里几乎都是"酯"）
        "硝酸甘脂" to "硝酸甘酯",
        "贝那脂" to "贝那普利",
        "非诺贝脂" to "非诺贝特",

        // 阿司匹林系列
        "阿斯匹林" to "阿司匹林",
        "阿司匹休" to "阿司匹林",
        "阿司匹村" to "阿司匹林",

        // 头孢 / 头抱
        "头抱" to "头孢",
        "头胞" to "头孢",

        // 常见剂型误认
        "缓释斤" to "缓释片",
        "肠溶斤" to "肠溶片",
        "胶襄" to "胶囊",
        "胶袋" to "胶囊",
        "颗立" to "颗粒",
        "颗米立" to "颗粒",
        "分散斤" to "分散片",
        "咀嚼斤" to "咀嚼片",
        "泡腾斤" to "泡腾片",
        "滴丸剂" to "滴丸",

        // 他汀类
        "阿托伐他汁" to "阿托伐他汀",
        "瑞舒伐他汁" to "瑞舒伐他汀",
        "辛伐他汁" to "辛伐他汀",
        "他汁" to "他汀",

        // 普利类
        "培哚普刊" to "培哚普利",
        "依那普刊" to "依那普利",
        "普刊" to "普利",

        // 其它高频
        "美托洛尔" to "美托洛尔",
        "美托格尔" to "美托洛尔",
        "硝苯地干" to "硝苯地平",
        "硝苯地半" to "硝苯地平",
        "奥美拉哇" to "奥美拉唑",
        "奥美拉座" to "奥美拉唑",
        "泮托拉哇" to "泮托拉唑",
        "布洛芳" to "布洛芬",
        "对乙酰氨基酣" to "对乙酰氨基酚",
        "维生紊" to "维生素",
        "维牛素" to "维生素",
        "叶睃" to "叶酸",
        "钙尔奇" to "钙尔奇",
        "碳酸钙D" to "碳酸钙D",
        "甲状腺素纳" to "甲状腺素钠",
        "左甲状腺素纳" to "左甲状腺素钠",
        "华法休" to "华法林",
        "利伐沙研" to "利伐沙班",
        "达比加群酣" to "达比加群酯"
    ).filter { (wrong, right) -> wrong != right }  // 去掉占位用的同名项

    /**
     * 常用药名库，用于"仅差一字"的整体校正。
     * 不求全，只放高频慢性病用药——覆盖面不足时宁可不纠正。
     */
    private val KNOWN_NAMES: List<String> = listOf(
        "苯磺酸氨氯地平片", "氨氯地平片", "硝苯地平缓释片", "硝苯地平控释片",
        "美托洛尔缓释片", "酒石酸美托洛尔片", "比索洛尔片",
        "缬沙坦胶囊", "氯沙坦钾片", "厄贝沙坦片", "替米沙坦片",
        "培哚普利叔丁胺片", "依那普利片", "卡托普利片",
        "阿托伐他汀钙片", "瑞舒伐他汀钙片", "辛伐他汀片",
        "阿司匹林肠溶片", "硫酸氢氯吡格雷片",
        "二甲双胍片", "盐酸二甲双胍片", "格列美脲片", "格列本脲片",
        "阿卡波糖片", "西格列汀片",
        "左甲状腺素钠片", "甲巯咪唑片",
        "奥美拉唑肠溶胶囊", "泮托拉唑钠肠溶片", "雷贝拉唑钠肠溶片",
        "氯雷他定片", "西替利嗪片",
        "布洛芬缓释胶囊", "对乙酰氨基酚片",
        "碳酸钙D3片", "维生素D滴剂", "叶酸片", "维生素C片", "维生素B12片",
        "华法林钠片", "利伐沙班片", "达比加群酯胶囊",
        "别嘌醇片", "非布司他片", "秋水仙碱片",
        "复方甘草片", "枸橼酸铋钾片"
    )

    /** 仅供测试：校验药名库自身不含"互相纠错"的一对。 */
    internal fun knownNamesForTest(): List<String> = KNOWN_NAMES

    fun correct(raw: String): Result {
        if (raw.isBlank()) return Result(raw, emptyList())

        val fixes = mutableListOf<Fix>()
        var text = raw

        // 第一级：词组替换
        for ((wrong, right) in PHRASE_FIXES) {
            if (text.contains(wrong)) {
                text = text.replace(wrong, right)
                fixes += Fix(wrong, right)
            }
        }

        // 第二级：与已知药名做"仅差一字"校正
        //
        // 两道闸门，缺一个都会把对的药名改错：
        //   ① 本身就是库里的合法药名 → 绝不动。否则「硝苯地平缓释片」会被改成
        //      「硝苯地平控释片」——两者都真实存在，剂型不同，改错是用药风险。
        //   ② 差一字的候选超过一个 → 无法判断该选哪个，一律不改。
        if (fixes.isEmpty() && text.length >= 4 && text !in KNOWN_NAMES) {
            val candidates = KNOWN_NAMES.filter { known ->
                known.length == text.length && known != text && hammingDistance(known, text) == 1
            }
            if (candidates.size == 1) {
                val candidate = candidates[0]
                fixes += Fix(text, candidate)
                text = candidate
            }
        }

        return Result(text, fixes)
    }

    /** 等长字符串的逐位差异数。 */
    private fun hammingDistance(a: String, b: String): Int {
        if (a.length != b.length) return Int.MAX_VALUE
        var d = 0
        for (i in a.indices) {
            if (a[i] != b[i]) {
                d++
                if (d > 1) return d  // 提前退出，我们只关心"是否只差 1"
            }
        }
        return d
    }
}
