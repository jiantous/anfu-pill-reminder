package com.jian.pillreminder.domain

import com.jian.pillreminder.data.DoseLog
import com.jian.pillreminder.data.DoseStatus
import com.jian.pillreminder.data.Medication
import com.jian.pillreminder.data.Schedule
import com.jian.pillreminder.data.TimeOfDay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 今日清单里的一项：某种药在某个时刻的一次服用。 */
data class DoseItem(
    val medication: Medication,
    val date: LocalDate,
    val time: TimeOfDay,
    val status: DoseStatus,
    val actedAtMillis: Long? = null
) {
    val key: String get() = "${medication.id}|$date|${time.format()}"

    /** 该时刻是否已过（用于把未处理的过期项标成"已错过"样式）。 */
    fun isOverdue(now: LocalDateTime): Boolean =
        status == DoseStatus.PENDING &&
            LocalDateTime.of(date, java.time.LocalTime.of(time.hour, time.minute)).isBefore(now)
}

object ScheduleEngine {

    /** 判断某药在 [date] 这天是否需要服用（只看频率与疗程，不看是否已服）。 */
    fun isDueOn(med: Medication, date: LocalDate): Boolean {
        if (med.archived) return false

        val start = runCatching { LocalDate.parse(med.startDate) }.getOrNull() ?: return false
        if (date.isBefore(start)) return false
        med.endDate?.let { e ->
            val end = runCatching { LocalDate.parse(e) }.getOrNull()
            if (end != null && date.isAfter(end)) return false
        }

        val daysSinceStart = java.time.temporal.ChronoUnit.DAYS.between(start, date)

        return when (val s = med.schedule) {
            is Schedule.Daily -> true
            is Schedule.EveryNDays -> {
                val n = s.intervalDays.coerceAtLeast(1)
                daysSinceStart % n == 0L
            }
            is Schedule.WeekDays -> date.dayOfWeek.value in s.daysOfWeek
            is Schedule.CycleOnOff -> {
                val on = s.onDays.coerceAtLeast(1)
                val off = s.offDays.coerceAtLeast(0)
                val period = on + off
                if (period <= 0) true else (daysSinceStart % period) < on
            }
        }
    }

    /** 生成某一天的完整服药清单（含已服/已跳过状态），按时间排序。 */
    fun dosesForDate(
        meds: List<Medication>,
        logs: List<DoseLog>,
        date: LocalDate
    ): List<DoseItem> {
        val dateStr = date.toString()
        val logIndex = logs.filter { it.date == dateStr }.associateBy { "${it.medicationId}|${it.time.format()}" }

        return meds
            .filter { isDueOn(it, date) }
            .flatMap { med ->
                med.times.map { t ->
                    val log = logIndex["${med.id}|${t.format()}"]
                    DoseItem(
                        medication = med,
                        date = date,
                        time = t,
                        status = log?.status ?: DoseStatus.PENDING,
                        actedAtMillis = log?.actedAtMillis
                    )
                }
            }
            .sortedWith(compareBy({ it.time.minutesOfDay }, { it.medication.name }))
    }

    /**
     * 计算某药在 [from] 之后的下一次服药时间点，最多向后找 [maxLookaheadDays] 天。
     * 用于安排系统闹钟。
     */
    fun nextOccurrence(
        med: Medication,
        from: LocalDateTime,
        maxLookaheadDays: Long = 400
    ): LocalDateTime? {
        if (med.archived || !med.remindersEnabled || med.times.isEmpty()) return null

        var date = from.toLocalDate()
        var checked = 0L
        while (checked <= maxLookaheadDays) {
            if (isDueOn(med, date)) {
                for (t in med.times.sorted()) {
                    val candidate = LocalDateTime.of(date, java.time.LocalTime.of(t.hour, t.minute))
                    if (candidate.isAfter(from)) return candidate
                }
            }
            // 疗程已结束就不用再往后找了
            med.endDate?.let { e ->
                val end = runCatching { LocalDate.parse(e) }.getOrNull()
                if (end != null && date.isAfter(end)) return null
            }
            date = date.plusDays(1)
            checked++
        }
        return null
    }

    fun toEpochMillis(dt: LocalDateTime): Long =
        dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 依从率统计结果。 */
    data class Adherence(
        val taken: Int,
        val skipped: Int,
        val missed: Int,
        val upcoming: Int
    ) {
        val totalDue: Int get() = taken + skipped + missed
        /** 按时服药率：已服 / (已服 + 跳过 + 错过)。没有应服项时返回 null。 */
        val rate: Float? get() = if (totalDue == 0) null else taken.toFloat() / totalDue
    }

    /** 统计 [start]..[end] 区间（含端点）的依从情况。 */
    fun adherence(
        meds: List<Medication>,
        logs: List<DoseLog>,
        start: LocalDate,
        end: LocalDate,
        now: LocalDateTime
    ): Adherence {
        var taken = 0
        var skipped = 0
        var missed = 0
        var upcoming = 0
        var d = start
        while (!d.isAfter(end)) {
            for (item in dosesForDate(meds, logs, d)) {
                when (item.status) {
                    DoseStatus.TAKEN -> taken++
                    DoseStatus.SKIPPED -> skipped++
                    DoseStatus.PENDING ->
                        if (item.isOverdue(now)) missed++ else upcoming++
                }
            }
            d = d.plusDays(1)
        }
        return Adherence(taken, skipped, missed, upcoming)
    }

    /** 人类可读的频率描述，用于卡片副标题。 */
    fun describeSchedule(med: Medication): String = when (val s = med.schedule) {
        is Schedule.Daily -> "每天"
        is Schedule.EveryNDays ->
            if (s.intervalDays <= 1) "每天" else "每 ${s.intervalDays} 天一次"
        is Schedule.WeekDays -> {
            if (s.daysOfWeek.size == 7) "每天"
            else s.daysOfWeek.sorted().joinToString("、", prefix = "每周") { weekdayName(it) }
        }
        is Schedule.CycleOnOff -> "吃 ${s.onDays} 天停 ${s.offDays} 天"
    }

    fun weekdayName(value: Int): String = when (value) {
        1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; 7 -> "日"
        else -> "?"
    }
}
