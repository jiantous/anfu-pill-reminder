package com.jian.pillreminder

import com.jian.pillreminder.data.Medication
import com.jian.pillreminder.data.Schedule
import com.jian.pillreminder.data.TimeOfDay
import com.jian.pillreminder.domain.ScheduleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ScheduleEngineTest {

    private fun med(
        schedule: Schedule,
        start: String = "2026-01-01",
        end: String? = null,
        times: List<TimeOfDay> = listOf(TimeOfDay(8, 0))
    ) = Medication(
        id = "m1",
        name = "测试药",
        schedule = schedule,
        times = times,
        startDate = start,
        endDate = end
    )

    @Test
    fun `每天服用在任意日期都到期`() {
        val m = med(Schedule.Daily)
        assertTrue(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-01")))
        assertTrue(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-06-15")))
    }

    @Test
    fun `开始日期之前不提醒`() {
        val m = med(Schedule.Daily, start = "2026-03-01")
        assertFalse(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-02-28")))
        assertTrue(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-03-01")))
    }

    @Test
    fun `结束日期之后不提醒`() {
        val m = med(Schedule.Daily, start = "2026-01-01", end = "2026-01-10")
        assertTrue(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-10")))
        assertFalse(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-11")))
    }

    @Test
    fun `隔天服药只在偶数间隔日到期`() {
        val m = med(Schedule.EveryNDays(2), start = "2026-01-01")
        assertTrue(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-01")))
        assertFalse(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-02")))
        assertTrue(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-03")))
    }

    @Test
    fun `按周服药只在选中的星期到期`() {
        // 2026-01-01 是周四(4)
        val m = med(Schedule.WeekDays(setOf(1, 4)), start = "2026-01-01")
        assertTrue(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-01"))) // 周四
        assertFalse(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-02"))) // 周五
        assertTrue(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-05"))) // 周一
    }

    @Test
    fun `吃三天停两天按周期循环`() {
        val m = med(Schedule.CycleOnOff(onDays = 3, offDays = 2), start = "2026-01-01")
        // 周期长度 5：第 0,1,2 天吃，第 3,4 天停
        assertTrue(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-01")))
        assertTrue(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-03")))
        assertFalse(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-04")))
        assertFalse(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-05")))
        assertTrue(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-06"))) // 新周期开始
    }

    @Test
    fun `已归档的药不再到期`() {
        val m = med(Schedule.Daily).copy(archived = true)
        assertFalse(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-05")))
    }

    @Test
    fun `下一次服药时间取当天更晚的时刻`() {
        val m = med(Schedule.Daily, times = listOf(TimeOfDay(8, 0), TimeOfDay(20, 30)))
        val next = ScheduleEngine.nextOccurrence(m, LocalDateTime.parse("2026-01-05T09:00:00"))
        assertEquals(LocalDateTime.parse("2026-01-05T20:30:00"), next)
    }

    @Test
    fun `当天时刻都过了就顺延到下一个服药日`() {
        val m = med(Schedule.EveryNDays(2), start = "2026-01-01", times = listOf(TimeOfDay(8, 0)))
        val next = ScheduleEngine.nextOccurrence(m, LocalDateTime.parse("2026-01-01T09:00:00"))
        assertEquals(LocalDateTime.parse("2026-01-03T08:00:00"), next)
    }

    @Test
    fun `关闭提醒时不产生下一次闹钟`() {
        val m = med(Schedule.Daily).copy(remindersEnabled = false)
        assertEquals(null, ScheduleEngine.nextOccurrence(m, LocalDateTime.parse("2026-01-01T09:00:00")))
    }

    @Test
    fun `一天多次服药会生成多个清单项`() {
        val m = med(Schedule.Daily, times = listOf(TimeOfDay(8, 0), TimeOfDay(12, 0), TimeOfDay(20, 0)))
        val items = ScheduleEngine.dosesForDate(listOf(m), emptyList(), LocalDate.parse("2026-01-05"))
        assertEquals(3, items.size)
        assertEquals(TimeOfDay(8, 0), items.first().time)
        assertEquals(TimeOfDay(20, 0), items.last().time)
    }
}
