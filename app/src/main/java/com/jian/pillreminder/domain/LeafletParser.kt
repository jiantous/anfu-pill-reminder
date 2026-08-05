package com.jian.pillreminder.domain

import com.jian.pillreminder.data.MealRelation
import com.jian.pillreminder.data.Schedule
import com.jian.pillreminder.data.TimeOfDay

/**
 * 从药品说明书 / 处方标签的 OCR 文字里抽取用药信息。
 *
 * 说明书排版千差万别，这里只做"高置信度才填"的保守提取：
 * 拿不准的字段一律留空，交由用户在表单里补，绝不猜测后直接落库。
 */
object LeafletParser {

    /** 提取结果。每个字段都可能为 null，表示没识别出来、需要用户自己填。 */
    data class Result(
        val name: String? = null,
        val dosage: Double? = null,
        val unit: String? = null,
        /** 每日服用次数，用于推荐服药时间点。 */
        val timesPerDay: Int? = null,
        val schedule: Schedule? = null,
        val mealRelation: MealRelation? = null,
        /** 推荐的服药时刻（按 timesPerDay 生成的常规作息）。 */
        val suggestedTimes: List<TimeOfDay> = emptyList(),
        /** 每个成功识别的字段附一句"依据"，让用户知道是从哪句话来的。 */
        val evidence: Map<String, String> = emptyMap(),
        /** 药名里自动纠正的形近字，需要在界面上展示给用户核对。 */
        val nameFixes: List<MedNameCorrector.Fix> = emptyList()
    ) {
        val hasAnything: Boolean
            get() = name != null || dosage != null || timesPerDay != null ||
                schedule != null || mealRelation != null
    }

    private val CN_NUM = mapOf(
        "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4, "五" to 5,
        "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10, "半" to 1
    )

    private fun toInt(token: String): Int? =
        token.toIntOrNull() ?: CN_NUM[token]

    private fun toDouble(token: String): Double? =
        token.toDoubleOrNull() ?: when (token) {
            "半" -> 0.5
            else -> CN_NUM[token]?.toDouble()
        }

    /** 剂量单位白名单，避免把随便一个字当单位。 */
    private val UNITS = listOf(
        "片", "粒", "袋", "支", "丸", "滴", "喷", "枚", "毫升", "ml", "mL", "ML",
        "毫克", "mg", "克", "g", "单位", "IU", "iu"
    )

    fun parse(rawLines: List<String>): Result {
        val lines = rawLines.map { it.trim() }.filter { it.isNotBlank() }
        val text = lines.joinToString("\n")
        val flat = text.replace(" ", "")

        val evidence = mutableMapOf<String, String>()

        // 药名先纠形近字（OCR 容易把「氨氯」认成「氨气」这类），纠正记录带给 UI 展示
        val rawName = extractName(lines)
        val nameCorrection = rawName?.let { MedNameCorrector.correct(it) }
        val name = nameCorrection?.text
        if (name != null) {
            evidence["name"] = if (nameCorrection.corrected) {
                "取自：$rawName（已自动纠正）"
            } else {
                "取自：$name"
            }
        }
        val (dosage, unit, doseEv) = extractDosage(flat)
        doseEv?.let { evidence["dosage"] = "依据：$it" }
        // 频次要同时看去空格版和保留空格版：中文写法去空格更好匹配，
        // 而 qd/bid/tid 这类拉丁缩写依赖空格作词边界，去掉空格会粘成 "potid" 而漏识别。
        val (perDay, freqEv) = extractTimesPerDay(flat, text)
        freqEv?.let { evidence["timesPerDay"] = "依据：$it" }
        val (schedule, schedEv) = extractSchedule(flat)
        schedEv?.let { evidence["schedule"] = "依据：$it" }
        val (meal, mealEv) = extractMeal(flat)
        mealEv?.let { evidence["meal"] = "依据：$it" }

        return Result(
            name = name,
            dosage = dosage,
            unit = unit,
            timesPerDay = perDay,
            schedule = schedule,
            mealRelation = meal,
            suggestedTimes = perDay?.let { suggestTimes(it) } ?: emptyList(),
            evidence = evidence,
            nameFixes = nameCorrection?.fixes ?: emptyList()
        )
    }

    // ---- 药名 ----

    private val NAME_LABEL = Regex("""(?:药品名称|商品名称|通用名称|通用名|商品名|品名)\s*[:：]?\s*(.{2,30})""")
    private val NAME_NOISE = Regex("""[（(【\[].*""")

    private fun extractName(lines: List<String>): String? {
        // ① 优先找带标签的行，如"药品名称：阿司匹林肠溶片"
        for (line in lines) {
            NAME_LABEL.find(line)?.let { m ->
                val v = m.groupValues[1].replace(NAME_NOISE, "").trim()
                if (v.length in 2..30) return v
            }
        }
        // ② 标签在上一行、名字在下一行的排版
        for ((i, line) in lines.withIndex()) {
            if (Regex("""^(?:药品名称|通用名称|通用名|商品名)\s*[:：]?$""").matches(line)) {
                lines.getOrNull(i + 1)?.let { next ->
                    val v = next.replace(NAME_NOISE, "").trim()
                    if (v.length in 2..30) return v
                }
            }
        }
        // ③ 兜底：找以常见剂型结尾的短行（片/胶囊/颗粒…），这类几乎一定是药名
        val dosageForm = Regex(""".{2,20}(?:片|胶囊|颗粒|口服液|软膏|滴眼液|注射液|丸|散|栓|贴|喷雾剂|糖浆)$""")
        return lines.firstOrNull { it.length <= 22 && dosageForm.matches(it) }
    }

    // ---- 单次剂量 ----

    private fun extractDosage(flat: String): Triple<Double?, String?, String?> {
        val unitAlt = UNITS.joinToString("|") { Regex.escape(it) }
        // "每次1片" / "一次2粒" / "每次0.5g"
        val p1 = Regex("""(?:每次|一次|每回)\s*([0-9]+(?:\.[0-9]+)?|[一二两三四五六七八九十半])\s*($unitAlt)""")
        p1.find(flat)?.let { m ->
            val v = toDouble(m.groupValues[1])
            if (v != null) return Triple(v, normalizeUnit(m.groupValues[2]), m.value)
        }
        // "1片，每日3次" —— 剂量在频次前面
        val p2 = Regex("""([0-9]+(?:\.[0-9]+)?|[一二两三四五六七八九十半])\s*($unitAlt)\s*[，,、]?\s*(?:每日|每天|一日|1日)""")
        p2.find(flat)?.let { m ->
            val v = toDouble(m.groupValues[1])
            if (v != null) return Triple(v, normalizeUnit(m.groupValues[2]), m.value)
        }
        return Triple(null, null, null)
    }

    private fun normalizeUnit(u: String): String = when (u.lowercase()) {
        "ml" -> "mL"
        "mg" -> "mg"
        "g" -> "g"
        "iu" -> "IU"
        else -> u
    }

    // ---- 每日次数 ----

    private fun extractTimesPerDay(flat: String, spaced: String): Pair<Int?, String?> {
        // "每日三次" / "一日2次" / "每天1次"
        Regex("""(?:每日|每天|一日|1日)\s*([0-9]+|[一二两三四五六七八九十])\s*次""").find(flat)?.let { m ->
            toInt(m.groupValues[1])?.let { n -> if (n in 1..8) return n to m.value }
        }
        // 医嘱缩写 qd/bid/tid/qid —— 在保留空格的原文里匹配，词边界才有效
        Regex("""(?<![A-Za-z])(qd|bid|tid|qid)(?![A-Za-z])""", RegexOption.IGNORE_CASE)
            .find(spaced)?.let { m ->
                val n = when (m.groupValues[1].lowercase()) {
                    "qd" -> 1; "bid" -> 2; "tid" -> 3; "qid" -> 4; else -> null
                }
                if (n != null) return n to m.value
            }
        // "每8小时1次" → 24/8 = 3 次
        Regex("""每\s*([0-9]+|[一二三四六八十]+)\s*(?:小时|h)\s*(?:一|1)?\s*次?""").find(flat)?.let { m ->
            toInt(m.groupValues[1])?.let { h ->
                if (h in 1..24) {
                    val n = (24 / h).coerceIn(1, 8)
                    return n to m.value
                }
            }
        }
        // "睡前服用" 这类隐含每天一次
        if (Regex("""睡前|临睡前""").containsMatchIn(flat)) return 1 to "睡前服用"
        return null to null
    }

    // ---- 用药频率（隔天 / 每周 / 周期） ----

    private fun extractSchedule(flat: String): Pair<Schedule?, String?> {
        Regex("""隔日|隔天|每隔一[日天]""").find(flat)?.let {
            return Schedule.EveryNDays(2) to it.value
        }
        Regex("""每隔\s*([0-9]+|[一二两三四五六七]) *[日天]""").find(flat)?.let { m ->
            toInt(m.groupValues[1])?.let { n ->
                if (n in 2..30) return Schedule.EveryNDays(n) to m.value
            }
        }
        Regex("""每周\s*([0-9]+|[一二两三四五六七])\s*次""").find(flat)?.let { m ->
            toInt(m.groupValues[1])?.let { n ->
                if (n == 1) return Schedule.EveryNDays(7) to m.value
            }
        }
        // "服21天停7天" 这类周期
        Regex("""(?:服|吃|用)\s*([0-9]+|[一二三四五六七八九十]+)\s*[日天]\s*[，,、]?\s*(?:停|间隔)\s*([0-9]+|[一二三四五六七八九十]+)\s*[日天]""")
            .find(flat)?.let { m ->
                val on = toInt(m.groupValues[1]); val off = toInt(m.groupValues[2])
                if (on != null && off != null && on in 1..365 && off in 0..365) {
                    return Schedule.CycleOnOff(on, off) to m.value
                }
            }
        return null to null
    }

    // ---- 与进餐关系 ----

    private fun extractMeal(flat: String): Pair<MealRelation?, String?> {
        val rules = listOf(
            Regex("""空腹""") to MealRelation.EMPTY_STOMACH,
            Regex("""饭前|餐前|进餐前|食前""") to MealRelation.BEFORE_MEAL,
            Regex("""饭后|餐后|进餐后|食后""") to MealRelation.AFTER_MEAL,
            Regex("""随餐|与食物同服|进餐时""") to MealRelation.WITH_MEAL
        )
        for ((re, rel) in rules) {
            re.find(flat)?.let { return rel to it.value }
        }
        return null to null
    }

    // ---- 按每日次数推荐服药时刻（常规作息） ----

    fun suggestTimes(n: Int): List<TimeOfDay> = when (n.coerceIn(1, 8)) {
        1 -> listOf(TimeOfDay(8, 0))
        2 -> listOf(TimeOfDay(8, 0), TimeOfDay(20, 0))
        3 -> listOf(TimeOfDay(8, 0), TimeOfDay(13, 0), TimeOfDay(19, 0))
        4 -> listOf(TimeOfDay(8, 0), TimeOfDay(12, 0), TimeOfDay(16, 0), TimeOfDay(20, 0))
        5 -> listOf(TimeOfDay(7, 0), TimeOfDay(11, 0), TimeOfDay(14, 0), TimeOfDay(17, 30), TimeOfDay(21, 0))
        6 -> listOf(TimeOfDay(7, 0), TimeOfDay(10, 0), TimeOfDay(13, 0), TimeOfDay(16, 0), TimeOfDay(19, 0), TimeOfDay(22, 0))
        else -> (0 until n).map { i ->
            val minutes = 7 * 60 + i * (15 * 60 / (n - 1).coerceAtLeast(1))
            TimeOfDay(minutes / 60, (minutes % 60) / 15 * 15)
        }
    }
}
