package com.jian.pillreminder

import com.jian.pillreminder.data.AppData
import com.jian.pillreminder.data.CsvExporter
import com.jian.pillreminder.data.DoseLog
import com.jian.pillreminder.data.DoseOverride
import com.jian.pillreminder.data.DoseStatus
import com.jian.pillreminder.data.MealRelation
import com.jian.pillreminder.data.Medication
import com.jian.pillreminder.data.TimeOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 服药记录 CSV 导出。
 *
 * 两件事最容易错，所以锁死：
 * 一是**漏服必须出现在报表里**——只导 logs 会得到一份看起来依从率 100% 的假报表，
 * 恰好把最该被看见的漏服藏掉了；
 * 二是药名/备注是用户自由输入的，含逗号引号换行时不转义会把整行结构冲掉。
 */
class CsvExporterTest {

    private val now = LocalDateTime.of(2026, 8, 5, 12, 0)

    private fun med(
        id: String,
        name: String,
        times: List<TimeOfDay> = listOf(TimeOfDay(8, 0)),
        note: String = "",
        dosage: Double = 1.0
    ) = Medication(
        id = id, name = name, times = times, note = note,
        dosage = dosage, startDate = "2026-08-01"
    )

    private fun dataOf(
        meds: List<Medication>,
        logs: List<DoseLog> = emptyList(),
        overrides: List<DoseOverride> = emptyList()
    ) = AppData(medications = meds, logs = logs, doseOverrides = overrides)

    private fun rows(csv: String) =
        csv.removePrefix("﻿").trim().split("\r\n")

    // ---- 结构 ----

    @Test
    fun `带 UTF8 BOM_否则 Excel 打开中文表头是乱码`() {
        val csv = CsvExporter.build(dataOf(listOf(med("a", "维生素 D"))),
            LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5), now)
        assertTrue("必须以 BOM 开头", csv.startsWith("﻿"))
    }

    @Test
    fun `表头字段固定`() {
        val csv = CsvExporter.build(dataOf(emptyList()),
            LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5), now)
        assertEquals(
            "日期,计划时间,药品名称,剂量,单位,状态,实际操作时间,与进餐关系,备注",
            rows(csv).first()
        )
    }

    @Test
    fun `用 CRLF 换行_兼容 Excel`() {
        val csv = CsvExporter.build(dataOf(listOf(med("a", "药"))),
            LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5), now)
        assertTrue(csv.contains("\r\n"))
    }

    // ---- 状态判定：漏服必须出现 ----

    @Test
    fun `漏服会出现在报表里_即使 logs 里没有它`() {
        // 8:00 该吃，没有任何 log，而"现在"已是 12:00 → 漏服
        val csv = CsvExporter.build(
            dataOf(listOf(med("a", "降压药"))),
            LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5), now
        )
        val body = rows(csv).drop(1)
        assertEquals(1, body.size)
        assertTrue("应标为漏服，实际：${body[0]}", body[0].contains("漏服"))
    }

    @Test
    fun `还没到时间的不算漏服`() {
        val csv = CsvExporter.build(
            dataOf(listOf(med("a", "夜间药", times = listOf(TimeOfDay(22, 0))))),
            LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5), now
        )
        val body = rows(csv).drop(1)
        assertTrue("22:00 还没到，实际：${body[0]}", body[0].contains("未到时间"))
        assertFalse(body[0].contains("漏服"))
    }

    @Test
    fun `已服用与主动跳过分别标注`() {
        val meds = listOf(
            med("a", "甲", times = listOf(TimeOfDay(8, 0))),
            med("b", "乙", times = listOf(TimeOfDay(9, 0)))
        )
        val logs = listOf(
            DoseLog("a", "2026-08-05", TimeOfDay(8, 0), DoseStatus.TAKEN, 1785900000000),
            DoseLog("b", "2026-08-05", TimeOfDay(9, 0), DoseStatus.SKIPPED, 1785903600000)
        )
        val body = rows(CsvExporter.build(dataOf(meds, logs),
            LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5), now)).drop(1)

        assertTrue(body.any { it.contains("甲") && it.contains("已服用") })
        assertTrue(body.any { it.contains("乙") && it.contains("主动跳过") })
    }

    // ---- 转义 ----

    @Test
    fun `含逗号的药名被引号包裹`() {
        val csv = CsvExporter.build(
            dataOf(listOf(med("a", "复方甲,乙片"))),
            LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5), now
        )
        assertTrue("含逗号必须加引号", csv.contains("\"复方甲,乙片\""))
    }

    @Test
    fun `含双引号的字段_引号写两遍`() {
        val csv = CsvExporter.build(
            dataOf(listOf(med("a", "药", note = "服后忌\"浓茶\""))),
            LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5), now
        )
        assertTrue("内部引号应转义成两个", csv.contains("\"服后忌\"\"浓茶\"\"\""))
    }

    @Test
    fun `含换行的备注不会把行结构冲掉`() {
        val csv = CsvExporter.build(
            dataOf(listOf(med("a", "药", note = "第一行\n第二行"))),
            LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5), now
        )
        // 引号内的换行是合法的 CSV，但数据行数不该因此变多
        assertTrue(csv.contains("\"第一行\n第二行\""))
    }

    @Test
    fun `普通字段不加多余引号`() {
        val csv = CsvExporter.build(
            dataOf(listOf(med("a", "维生素D"))),
            LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5), now
        )
        assertTrue(csv.contains(",维生素D,"))
    }

    // ---- 临时改时间 ----

    @Test
    fun `挪过时间的会标出原定与改到的时刻`() {
        val overrides = listOf(
            DoseOverride("a", "2026-08-05", TimeOfDay(8, 0), TimeOfDay(11, 30))
        )
        val csv = CsvExporter.build(
            dataOf(listOf(med("a", "药")), overrides = overrides),
            LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5), now
        )
        assertTrue("应同时含原定和改到的时刻", csv.contains("08:00（改到 11:30）"))
    }

    // ---- 区间与边界 ----

    @Test
    fun `按日期区间导出_含起止两端`() {
        val csv = CsvExporter.build(
            dataOf(listOf(med("a", "药"))),
            LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 5), now
        )
        val body = rows(csv).drop(1)
        assertEquals("3 天各一条", 3, body.size)
        assertTrue(body[0].startsWith("2026-08-03"))
        assertTrue(body[2].startsWith("2026-08-05"))
    }

    @Test
    fun `没有药品时只有表头`() {
        val csv = CsvExporter.build(dataOf(emptyList()),
            LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5), now)
        assertEquals(1, rows(csv).size)
    }

    @Test
    fun `暂停期内的药不出现在报表里`() {
        val paused = med("a", "药").copy(pausedUntil = "2026-08-10")
        val csv = CsvExporter.build(dataOf(listOf(paused)),
            LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5), now)
        assertEquals("暂停中不该有数据行", 1, rows(csv).size)
    }

    // ---- 文件名与区间推导 ----

    @Test
    fun `文件名带时间戳且是 csv 后缀`() {
        val name = CsvExporter.suggestFileName(LocalDateTime.of(2026, 8, 5, 21, 46))
        assertEquals("安服记录_2026-08-05_2146.csv", name)
    }

    @Test
    fun `最早日期取记录与疗程开始中更早的那个`() {
        val data = dataOf(
            listOf(med("a", "药").copy(startDate = "2026-07-01")),
            listOf(DoseLog("a", "2026-06-15", TimeOfDay(8, 0), DoseStatus.TAKEN, 1L))
        )
        assertEquals(
            LocalDate.of(2026, 6, 15),
            CsvExporter.earliestDate(data, LocalDate.of(2026, 8, 5))
        )
    }

    @Test
    fun `没有任何数据时最早日期回退到今天`() {
        val today = LocalDate.of(2026, 8, 5)
        assertEquals(today, CsvExporter.earliestDate(dataOf(emptyList()), today))
    }

    @Test
    fun `剂量为整数时不显示小数点`() {
        val csv = CsvExporter.build(
            dataOf(listOf(med("a", "药", dosage = 1.0))),
            LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5), now
        )
        assertTrue("1.0 应显示为 1", csv.contains(",1,片,"))
    }

    @Test
    fun `半片这类小数保留`() {
        val csv = CsvExporter.build(
            dataOf(listOf(med("a", "药", dosage = 2.5))),
            LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5), now
        )
        assertTrue(csv.contains(",2.5,片,"))
    }

    @Test
    fun `进餐关系与备注都被导出`() {
        val m = med("a", "药", note = "随温水").copy(mealRelation = MealRelation.AFTER_MEAL)
        val csv = CsvExporter.build(dataOf(listOf(m)),
            LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5), now)
        assertTrue(csv.contains("饭后"))
        assertTrue(csv.contains("随温水"))
    }
}
