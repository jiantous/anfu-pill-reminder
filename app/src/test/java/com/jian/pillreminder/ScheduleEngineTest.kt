package com.jian.pillreminder

import com.jian.pillreminder.data.DoseLog
import com.jian.pillreminder.data.DoseOverride
import com.jian.pillreminder.data.DoseStatus
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

    // ---- 暂停用药 ----
    //
    // isDueOn 是"这次服药算不算数"的唯一闸口，暂停判断放在那里，
    // 一处生效四件事：不排闹钟、不上今日清单、不计入依从率、日历不上色。下面逐条锁死。

    @Test
    fun `暂停期内不需要服用`() {
        val m = med(Schedule.Daily).copy(pausedUntil = "2026-01-10")
        assertFalse(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-05")))
    }

    @Test
    fun `暂停到当天也算暂停_含端点`() {
        val m = med(Schedule.Daily).copy(pausedUntil = "2026-01-05")
        assertFalse("pausedUntil 当天仍在暂停中", ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-05")))
    }

    @Test
    fun `暂停结束后第一天就恢复`() {
        val m = med(Schedule.Daily).copy(pausedUntil = "2026-01-05")
        assertTrue(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-06")))
    }

    @Test
    fun `暂停期内不排闹钟_但暂停结束后恢复`() {
        val m = med(Schedule.Daily, times = listOf(TimeOfDay(8, 0))).copy(pausedUntil = "2026-01-10")
        val next = ScheduleEngine.nextOccurrence(m, LocalDateTime.parse("2026-01-05T07:00:00"))
        // 不是 null——暂停只是跳过这几天，之后仍要提醒
        assertEquals(LocalDateTime.parse("2026-01-11T08:00:00"), next)
    }

    @Test
    fun `暂停期内不上今日清单`() {
        val m = med(Schedule.Daily).copy(pausedUntil = "2026-01-10")
        val items = ScheduleEngine.dosesForDate(listOf(m), emptyList(), LocalDate.parse("2026-01-05"))
        assertTrue(items.isEmpty())
    }

    @Test
    fun `暂停期内不计入依从率`() {
        val m = med(Schedule.Daily, times = listOf(TimeOfDay(8, 0))).copy(pausedUntil = "2026-01-10")
        val stat = ScheduleEngine.adherence(
            listOf(m), emptyList(),
            LocalDate.parse("2026-01-03"), LocalDate.parse("2026-01-08"),
            LocalDateTime.parse("2026-01-09T00:00:00")
        )
        assertEquals("暂停期不该产生应服项", 0, stat.totalDue)
    }

    @Test
    fun `没设暂停时不受影响`() {
        val m = med(Schedule.Daily)
        assertFalse(ScheduleEngine.isPausedOn(m, LocalDate.parse("2026-01-05")))
        assertTrue(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-05")))
    }

    @Test
    fun `暂停日期是坏数据时按没暂停处理`() {
        // 宁可多提醒一次，也不能因为一个坏字段让人整段时间收不到吃药提醒
        val m = med(Schedule.Daily).copy(pausedUntil = "不是日期")
        assertFalse(ScheduleEngine.isPausedOn(m, LocalDate.parse("2026-01-05")))
        assertTrue(ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-05")))
    }

    @Test
    fun `暂停不顺延疗程_结束日期不变`() {
        // 刻意的设计：endDate 和周期锚点都不动，恢复后接着原计划走，
        // 否则历史统计会变得没法解释
        val m = med(Schedule.Daily, end = "2026-01-08", times = listOf(TimeOfDay(8, 0)))
            .copy(pausedUntil = "2026-01-05")
        assertTrue("暂停结束后到疗程末仍要吃", ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-08")))
        assertFalse("疗程结束就是结束，不因暂停延后", ScheduleEngine.isDueOn(m, LocalDate.parse("2026-01-09")))
    }

    // ---- 临时改时间 ----
    //
    // 最容易错的地方：身份键必须留在原定时刻。改成新时刻的话，
    // dosesForDate 再也匹配不上已有的 DoseLog，那条记录就成孤儿、永远显示"已错过"。

    @Test
    fun `临时改时间只改显示时刻_身份仍是原定时刻`() {
        val m = med(Schedule.Daily, times = listOf(TimeOfDay(8, 0)))
        val overrides = listOf(DoseOverride(m.id, "2026-01-05", TimeOfDay(8, 0), TimeOfDay(11, 30)))
        val item = ScheduleEngine.dosesForDate(
            listOf(m), emptyList(), LocalDate.parse("2026-01-05"), overrides
        ).single()

        assertEquals("身份必须是原定时刻", TimeOfDay(8, 0), item.time)
        assertEquals(TimeOfDay(11, 30), item.movedTo)
        assertEquals("实际提醒用新时刻", TimeOfDay(11, 30), item.effectiveTime)
        assertTrue("key 由原定时刻推导", item.key.endsWith("08:00"))
    }

    @Test
    fun `挪过时间后仍能匹配到原有的打卡记录`() {
        val m = med(Schedule.Daily, times = listOf(TimeOfDay(8, 0)))
        val logs = listOf(DoseLog(m.id, "2026-01-05", TimeOfDay(8, 0), DoseStatus.TAKEN, 1L))
        val overrides = listOf(DoseOverride(m.id, "2026-01-05", TimeOfDay(8, 0), TimeOfDay(11, 30)))
        val item = ScheduleEngine.dosesForDate(
            listOf(m), logs, LocalDate.parse("2026-01-05"), overrides
        ).single()
        assertEquals("状态查询按原定时刻，不能因为挪动就丢了", DoseStatus.TAKEN, item.status)
    }

    @Test
    fun `挪动后按新时刻排序`() {
        val m = med(Schedule.Daily, times = listOf(TimeOfDay(8, 0), TimeOfDay(20, 0)))
        // 把早上那次挪到 21:00，它就该排到晚上那次之后
        val overrides = listOf(DoseOverride(m.id, "2026-01-05", TimeOfDay(8, 0), TimeOfDay(21, 0)))
        val items = ScheduleEngine.dosesForDate(
            listOf(m), emptyList(), LocalDate.parse("2026-01-05"), overrides
        )
        assertEquals(TimeOfDay(20, 0), items.first().time)
        assertEquals(TimeOfDay(8, 0), items.last().time)
    }

    @Test
    fun `别的日期的挪动不影响今天`() {
        val m = med(Schedule.Daily, times = listOf(TimeOfDay(8, 0)))
        val overrides = listOf(DoseOverride(m.id, "2026-01-06", TimeOfDay(8, 0), TimeOfDay(11, 30)))
        val item = ScheduleEngine.dosesForDate(
            listOf(m), emptyList(), LocalDate.parse("2026-01-05"), overrides
        ).single()
        assertEquals(null, item.movedTo)
    }

    @Test
    fun `是否过期按实际提醒时刻判断`() {
        val m = med(Schedule.Daily, times = listOf(TimeOfDay(8, 0)))
        val overrides = listOf(DoseOverride(m.id, "2026-01-05", TimeOfDay(8, 0), TimeOfDay(21, 0)))
        val item = ScheduleEngine.dosesForDate(
            listOf(m), emptyList(), LocalDate.parse("2026-01-05"), overrides
        ).single()
        // 原定 8:00 已过，但挪到了 21:00，此刻 12:00 还没到
        assertFalse("挪到晚上就不该算错过", item.isOverdue(LocalDateTime.parse("2026-01-05T12:00:00")))
        assertTrue(item.isOverdue(LocalDateTime.parse("2026-01-05T22:00:00")))
    }

    // ---- 库存测算 stockRunOutDate ----

    /** 带库存的 med 工厂：其余参数同 med()。 */
    private fun stocked(
        stock: Double?,
        schedule: Schedule,
        start: String = "2026-01-01",
        end: String? = null,
        times: List<TimeOfDay> = listOf(TimeOfDay(8, 0)),
        dosage: Double = 1.0
    ) = Medication(
        id = "m1",
        name = "测试药",
        schedule = schedule,
        times = times,
        startDate = start,
        endDate = end,
        dosage = dosage,
        stockRemaining = stock
    )

    @Test
    fun `每天一片吃七天正好第八天用完`() {
        // 7 片，从 1/2 起每天 1 片：2~8 号每天扣 1，第 7 片在 8 号吃掉
        val m = stocked(7.0, Schedule.Daily, start = "2026-01-01")
        assertEquals(
            LocalDate.parse("2026-01-08"),
            ScheduleEngine.stockRunOutDate(m, LocalDate.parse("2026-01-02"))
        )
    }

    @Test
    fun `没设库存测不出`() {
        val m = stocked(null, Schedule.Daily)
        assertEquals(null, ScheduleEngine.stockRunOutDate(m, LocalDate.parse("2026-01-02")))
    }

    @Test
    fun `每天三次按次数加速消耗`() {
        // 每天 3 次 × 1 片 = 3 片/天，9 片从 1/2 起吃 3 天，1/4 用完
        val m = stocked(
            9.0, Schedule.Daily, start = "2026-01-01",
            times = listOf(TimeOfDay(8, 0), TimeOfDay(13, 0), TimeOfDay(20, 0))
        )
        assertEquals(
            LocalDate.parse("2026-01-04"),
            ScheduleEngine.stockRunOutDate(m, LocalDate.parse("2026-01-02"))
        )
    }

    @Test
    fun `隔天服药消耗减半`() {
        // 隔天 1 片（锚点 1/1），从 1/2 起的服药日是 1/3、1/5、1/7、1/9，
        // 4 片在 1/9 用完
        val m = stocked(4.0, Schedule.EveryNDays(2), start = "2026-01-01")
        assertEquals(
            LocalDate.parse("2026-01-09"),
            ScheduleEngine.stockRunOutDate(m, LocalDate.parse("2026-01-02"))
        )
    }

    @Test
    fun `剂量二的单次消耗翻倍`() {
        // 每天 2 片，4 片从 1/2 起吃 2 天，1/3 用完
        val m = stocked(4.0, Schedule.Daily, start = "2026-01-01", dosage = 2.0)
        assertEquals(
            LocalDate.parse("2026-01-03"),
            ScheduleEngine.stockRunOutDate(m, LocalDate.parse("2026-01-02"))
        )
    }

    @Test
    fun `疗程先于库存结束则测不出`() {
        // 30 片每天 1 片能吃一个月，但疗程 1/10 就结束——不是库存够，是停药了
        val m = stocked(30.0, Schedule.Daily, start = "2026-01-01", end = "2026-01-10")
        assertEquals(
            "疗程结束不该报出用完日期",
            null,
            ScheduleEngine.stockRunOutDate(m, LocalDate.parse("2026-01-02"))
        )
    }

    @Test
    fun `暂停期不消耗库存`() {
        // 暂停到 1/5（含当天），从 1/2 起 1/2~1/5 不吃，4 片落在 1/6~1/9
        val m = stocked(4.0, Schedule.Daily, start = "2026-01-01")
            .copy(pausedUntil = "2026-01-05")
        assertEquals(
            LocalDate.parse("2026-01-09"),
            ScheduleEngine.stockRunOutDate(m, LocalDate.parse("2026-01-02"))
        )
    }

    @Test
    fun `吃四百天还没吃完则测不出`() {
        // 500 片每天 1 片，超过 400 天上限
        val m = stocked(500.0, Schedule.Daily, start = "2026-01-01")
        assertEquals(null, ScheduleEngine.stockRunOutDate(m, LocalDate.parse("2026-01-02")))
    }
}
