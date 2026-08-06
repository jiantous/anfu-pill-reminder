package com.jian.pillreminder.data

import com.jian.pillreminder.domain.ScheduleEngine
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 把服药记录导成 CSV，方便拉进 Excel 或给医生看。
 *
 * **行源是 [ScheduleEngine.dosesForDate] 而不是 [AppData.logs]**：
 * logs 里只有用户操作过的（已服用/跳过），漏服和未到时间的根本不落盘。
 * 直接导 logs 会得到一份"看起来依从率 100%"的假报表——恰好把最该看见的漏服藏掉了。
 */
object CsvExporter {

    /** 表头。顺序一经发布就别改，别人可能已经写了处理脚本。 */
    private val HEADER = listOf(
        "日期", "计划时间", "药品名称", "剂量", "单位",
        "状态", "实际操作时间", "与进餐关系", "备注"
    )

    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 文件名与备份保持同一套风格。 */
    fun suggestFileName(now: LocalDateTime = LocalDateTime.now()): String =
        "安服记录_${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"))}.csv"

    private fun statusLabel(item: com.jian.pillreminder.domain.DoseItem, now: LocalDateTime): String =
        when (item.status) {
            DoseStatus.TAKEN -> "已服用"
            DoseStatus.SKIPPED -> "主动跳过"
            DoseStatus.PENDING -> if (item.isOverdue(now)) "漏服" else "未到时间"
        }

    /**
     * 转义一个 CSV 字段。
     *
     * 药名和备注是用户自由输入的，可能含逗号、引号、换行——不转义会把整行结构冲掉。
     * 按 RFC 4180：需要时用双引号包裹，内部的双引号写两遍。
     */
    private fun escape(raw: String): String {
        val needsQuote = raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val body = raw.replace("\"", "\"\"")
        return if (needsQuote) "\"$body\"" else body
    }

    /**
     * 生成 CSV 文本。
     *
     * @param start 起始日期（含）
     * @param end 结束日期（含）
     * @param now 用于判定"漏服"还是"未到时间"，可注入便于测试
     */
    fun build(
        data: AppData,
        start: LocalDate,
        end: LocalDate,
        now: LocalDateTime = LocalDateTime.now()
    ): String {
        val sb = StringBuilder()
        // UTF-8 BOM：没有它，Excel 会把中文表头认成乱码。
        // 其它工具（pandas、numbers）都能正确忽略 BOM，所以加上是净收益。
        // 写成转义形式而不是隐形字符，免得被编辑器悄悄吃掉。
        sb.append('\uFEFF')
        sb.append(HEADER.joinToString(",")).append("\r\n")

        var d = start
        while (!d.isAfter(end)) {
            for (item in ScheduleEngine.dosesForDate(
                data.medications, data.logs, d, data.doseOverrides
            )) {
                val med = item.medication
                val actedAt = item.actedAtMillis?.let {
                    LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(it), ZoneId.systemDefault()
                    ).format(TIMESTAMP)
                } ?: ""
                val row = listOf(
                    item.date.toString(),
                    // 挪过时间的标出来，否则看报表的人会疑惑为什么和计划不符
                    if (item.movedTo != null) "${item.time.format()}（改到 ${item.movedTo.format()}）"
                    else item.time.format(),
                    med.name,
                    formatNumber(med.dosage),
                    med.unit,
                    statusLabel(item, now),
                    actedAt,
                    med.mealRelation.label,
                    med.note
                )
                sb.append(row.joinToString(",") { escape(it) }).append("\r\n")
            }
            d = d.plusDays(1)
        }
        return sb.toString()
    }

    /** 1.0 显示成 1，2.5 保持 2.5。 */
    private fun formatNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    /** 有记录的最早日期，用于"全部导出"。没有记录时回退到今天。 */
    fun earliestDate(data: AppData, today: LocalDate = LocalDate.now()): LocalDate {
        val fromLogs = data.logs.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        val fromMeds = data.medications.mapNotNull {
            runCatching { LocalDate.parse(it.startDate) }.getOrNull()
        }
        return (fromLogs + fromMeds).minOrNull() ?: today
    }
}
