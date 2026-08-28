package com.jian.pillreminder

import com.jian.pillreminder.data.DoseLog
import com.jian.pillreminder.data.DoseOverride
import com.jian.pillreminder.data.DoseStatus
import com.jian.pillreminder.data.IntervalDosing
import com.jian.pillreminder.data.Medication
import com.jian.pillreminder.data.OverrideSource
import com.jian.pillreminder.data.Schedule
import com.jian.pillreminder.data.TimeOfDay
import com.jian.pillreminder.domain.ScheduleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 按小时间隔用药打卡"已服用"后，紧邻下一次要不要自动顺延（ScheduleEngine.cascadeAfterTaken）。
 *
 * 核心约束（已和用户确认）：只顺延紧邻的下一次；跳过不顺延；顺延只影响当天，不跨天累积。
 */
class IntervalCascadeTest {

    private val date = LocalDate.parse("2026-08-28")

    private fun millisAt(hour: Int, minute: Int, day: LocalDate = date): Long =
        LocalDateTime.of(day, java.time.LocalTime.of(hour, minute))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun med(
        intervalDosing: IntervalDosing? = IntervalDosing(TimeOfDay(9, 0), TimeOfDay(21, 0), 2),
        times: List<TimeOfDay> = listOf(9, 11, 13, 15, 17, 19, 21).map { TimeOfDay(it, 0) }
    ) = Medication(
        id = "m1",
        name = "眼药水",
        schedule = Schedule.Daily,
        times = times,
        startDate = "2026-01-01",
        intervalDosing = intervalDosing
    )

    @Test
    fun `普通固定时间的药不受影响`() {
        val m = med(intervalDosing = null)
        val result = ScheduleEngine.cascadeAfterTaken(
            m, date, TimeOfDay(9, 0), millisAt(10, 0), emptyList(), emptyList()
        )
        assertNull(result)
    }

    @Test
    fun `准点打卡不生成顺延`() {
        val m = med()
        val result = ScheduleEngine.cascadeAfterTaken(
            m, date, TimeOfDay(9, 0), millisAt(9, 0), emptyList(), emptyList()
        )
        assertNull(result)
    }

    @Test
    fun `晚点打卡顺延紧邻的下一次`() {
        val m = med()
        val result = ScheduleEngine.cascadeAfterTaken(
            m, date, TimeOfDay(9, 0), millisAt(9, 5), emptyList(), emptyList()
        )
        assertEquals(TimeOfDay(11, 0), result?.originalTime)
        assertEquals(TimeOfDay(11, 5), result?.newTime)
        assertEquals(OverrideSource.CASCADE, result?.source)
    }

    @Test
    fun `早点打卡也会顺延`() {
        val m = med()
        val result = ScheduleEngine.cascadeAfterTaken(
            m, date, TimeOfDay(13, 0), millisAt(12, 30), emptyList(), emptyList()
        )
        assertEquals(TimeOfDay(14, 30), result?.newTime)
    }

    @Test
    fun `当天最后一次打卡不需要顺延`() {
        val m = med()
        val result = ScheduleEngine.cascadeAfterTaken(
            m, date, TimeOfDay(21, 0), millisAt(21, 10), emptyList(), emptyList()
        )
        assertNull(result)
    }

    @Test
    fun `顺延候选跨到第二天时不顺延`() {
        val m = med(
            intervalDosing = IntervalDosing(TimeOfDay(22, 0), TimeOfDay(23, 0), 2),
            times = listOf(TimeOfDay(22, 0), TimeOfDay(23, 0))
        )
        // 22:00 拖到 23:30 才打卡，+2 小时candidate落在次日 1:30
        val result = ScheduleEngine.cascadeAfterTaken(
            m, date, TimeOfDay(22, 0), millisAt(23, 30), emptyList(), emptyList()
        )
        assertNull(result)
    }

    @Test
    fun `顺延候选超过每日结束时间不顺延`() {
        val m = med()
        // 19:00 那次拖到 20:30 打卡，+2 小时落在 22:30，超过 21:00 的结束时间
        val result = ScheduleEngine.cascadeAfterTaken(
            m, date, TimeOfDay(19, 0), millisAt(20, 30), emptyList(), emptyList()
        )
        assertNull(result)
    }

    @Test
    fun `下一次已跳过就不再顺延`() {
        val m = med()
        val logs = listOf(DoseLog(m.id, date.toString(), TimeOfDay(11, 0), DoseStatus.SKIPPED, 1L))
        val result = ScheduleEngine.cascadeAfterTaken(
            m, date, TimeOfDay(9, 0), millisAt(9, 30), logs, emptyList()
        )
        assertNull(result)
    }

    @Test
    fun `下一次已服用就不再顺延`() {
        val m = med()
        val logs = listOf(DoseLog(m.id, date.toString(), TimeOfDay(11, 0), DoseStatus.TAKEN, 1L))
        val result = ScheduleEngine.cascadeAfterTaken(
            m, date, TimeOfDay(9, 0), millisAt(9, 30), logs, emptyList()
        )
        assertNull(result)
    }

    @Test
    fun `下一次已被手动挪过时不覆盖`() {
        val m = med()
        val overrides = listOf(
            DoseOverride(m.id, date.toString(), TimeOfDay(11, 0), TimeOfDay(15, 0), OverrideSource.MANUAL)
        )
        val result = ScheduleEngine.cascadeAfterTaken(
            m, date, TimeOfDay(9, 0), millisAt(9, 30), emptyList(), overrides
        )
        assertNull(result)
    }

    @Test
    fun `下一次已是级联挪过的可以被新一轮覆盖`() {
        val m = med()
        val overrides = listOf(
            DoseOverride(m.id, date.toString(), TimeOfDay(11, 0), TimeOfDay(11, 5), OverrideSource.CASCADE)
        )
        val result = ScheduleEngine.cascadeAfterTaken(
            m, date, TimeOfDay(9, 0), millisAt(9, 20), emptyList(), overrides
        )
        assertEquals(TimeOfDay(11, 20), result?.newTime)
    }

    @Test
    fun `连续两次晚点级联能正确叠加`() {
        val m = med()
        // 第一次：9:00 拖到 9:05 打卡 -> 下一次(11:00) 顺延到 11:05
        val first = ScheduleEngine.cascadeAfterTaken(
            m, date, TimeOfDay(9, 0), millisAt(9, 5), emptyList(), emptyList()
        )
        assertEquals(TimeOfDay(11, 5), first?.newTime)

        // 第二次：11:00 那次本该在 11:05 打卡，结果又晚了 15 分钟，在 11:20 才打卡
        // -> 再下一次(13:00) 顺延到 13:20
        val second = ScheduleEngine.cascadeAfterTaken(
            m, date, TimeOfDay(11, 0), millisAt(11, 20), emptyList(), listOfNotNull(first)
        )
        assertEquals(TimeOfDay(13, 0), second?.originalTime)
        assertEquals(TimeOfDay(13, 20), second?.newTime)
    }
}
