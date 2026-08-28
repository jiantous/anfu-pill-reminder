package com.jian.pillreminder.domain

import com.jian.pillreminder.data.DoseLog
import com.jian.pillreminder.data.DoseOverride
import com.jian.pillreminder.data.DoseStatus
import com.jian.pillreminder.data.IntervalDosing
import com.jian.pillreminder.data.Medication
import com.jian.pillreminder.data.OverrideSource
import com.jian.pillreminder.data.Schedule
import com.jian.pillreminder.data.TimeOfDay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 今日清单里的一项：某种药在某个时刻的一次服用。 */
data class DoseItem(
    val medication: Medication,
    val date: LocalDate,
    /**
     * 原定时刻，**这次服药的身份**。DoseLog、通知 id、闹钟 requestCode 都由它推导，
     * 即使用户把这次挪到了别的时间，它也不变。
     */
    val time: TimeOfDay,
    val status: DoseStatus,
    val actedAtMillis: Long? = null,
    /** 被临时挪到的时刻，null = 没挪过。只影响显示与响铃，不影响身份。 */
    val movedTo: TimeOfDay? = null
) {
    val key: String get() = "${medication.id}|$date|${time.format()}"

    /** 实际该提醒/显示的时刻：挪过就用新的，否则用原定的。 */
    val effectiveTime: TimeOfDay get() = movedTo ?: time

    /** 该时刻是否已过（用于把未处理的过期项标成"已错过"样式）。 */
    fun isOverdue(now: LocalDateTime): Boolean =
        status == DoseStatus.PENDING &&
            LocalDateTime.of(
                date,
                java.time.LocalTime.of(effectiveTime.hour, effectiveTime.minute)
            ).isBefore(now)
}

object ScheduleEngine {

    /**
     * [date] 是否落在暂停期内。暂停到 pausedUntil 当天为止（含当天）。
     *
     * 日期解析失败时按"没暂停"处理：宁可多提醒一次，也不能因为一个坏字段
     * 让人整段时间收不到吃药提醒。
     */
    fun isPausedOn(med: Medication, date: LocalDate): Boolean {
        val until = med.pausedUntil ?: return false
        val end = runCatching { LocalDate.parse(until) }.getOrNull() ?: return false
        return !date.isAfter(end)
    }

    /** 相对今天是否处于暂停中，用于卡片上显示「已暂停」。 */
    fun isPausedNow(med: Medication, today: LocalDate = LocalDate.now()): Boolean =
        isPausedOn(med, today)

    /**
     * 判断某药在 [date] 这天是否需要服用（只看频率与疗程，不看是否已服）。
     *
     * 这里是"这次服药算不算数"的唯一闸口——[dosesForDate] 和 [nextOccurrence] 都走它，
     * 所以暂停判断放在这里，一处生效四件事：不排闹钟、不上今日清单、
     * 不计入依从率、日历不上色。
     */
    fun isDueOn(med: Medication, date: LocalDate): Boolean {
        if (med.archived) return false
        if (isPausedOn(med, date)) return false

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

    /**
     * 生成某一天的完整服药清单（含已服/已跳过状态），按时间排序。
     *
     * [overrides] 里的临时改时间只改变显示与排序用的时刻，不改变身份：
     * 状态查询依旧按原定时刻去 logs 里找。
     */
    fun dosesForDate(
        meds: List<Medication>,
        logs: List<DoseLog>,
        date: LocalDate,
        overrides: List<DoseOverride> = emptyList()
    ): List<DoseItem> {
        val dateStr = date.toString()
        val logIndex = logs.filter { it.date == dateStr }.associateBy { "${it.medicationId}|${it.time.format()}" }
        val overrideIndex = overrides.filter { it.date == dateStr }
            .associateBy { "${it.medicationId}|${it.originalTime.format()}" }

        return meds
            .filter { isDueOn(it, date) }
            .flatMap { med ->
                med.times.map { t ->
                    val idKey = "${med.id}|${t.format()}"
                    val log = logIndex[idKey]
                    DoseItem(
                        medication = med,
                        date = date,
                        time = t,
                        status = log?.status ?: DoseStatus.PENDING,
                        actedAtMillis = log?.actedAtMillis,
                        movedTo = overrideIndex[idKey]?.newTime
                    )
                }
            }
            // 按实际提醒时刻排序，挪走的那条才会出现在清单里它该在的位置
            .sortedWith(compareBy({ it.effectiveTime.minutesOfDay }, { it.medication.name }))
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

    /**
     * 按 [config] 生成一天内的服药时刻表：[IntervalDosing.startTime] 起，
     * 每隔 [IntervalDosing.intervalHours] 小时一次，直到 [IntervalDosing.endTime]（含）为止。
     *
     * 结束不晚于开始是非法配置——正常情况下 UI 会挡住，这里防御性地退化成只在
     * 开始时刻提醒一次，而不是返回空列表（Medication.times 不允许为空）。
     */
    fun intervalGridTimes(config: IntervalDosing): List<TimeOfDay> {
        val startMin = config.startTime.minutesOfDay
        val endMin = config.endTime.minutesOfDay
        if (endMin <= startMin) return listOf(config.startTime)

        val stepMinutes = config.intervalHours.coerceAtLeast(1) * 60
        val out = mutableListOf<TimeOfDay>()
        var m = startMin
        while (m <= endMin) {
            out += TimeOfDay(m / 60, m % 60)
            m += stepMinutes
        }
        return out
    }

    /**
     * 按间隔用药的药打卡"已服用"后，算出紧邻的下一次要不要跟着顺延，顺延到哪。
     *
     * 只顺延**紧邻的下一个**未处理时刻，不会跳过它去动更后面的——这正是"跳过不顺延"
     * 的实现方式：下一个时刻一旦已经是 TAKEN/SKIPPED，级联到这里就停了。
     *
     * 只对开启了 [Medication.intervalDosing] 的药生效，普通固定时间的药不受影响。
     *
     * @return 需要新增/覆盖的 override；不需要顺延时返回 null。
     */
    fun cascadeAfterTaken(
        med: Medication,
        date: LocalDate,
        takenTime: TimeOfDay,
        takenAtMillis: Long,
        logs: List<DoseLog>,
        overrides: List<DoseOverride>
    ): DoseOverride? {
        val config = med.intervalDosing ?: return null

        val sorted = med.times.sorted()
        val idx = sorted.indexOf(takenTime)
        if (idx < 0 || idx == sorted.lastIndex) return null
        val nextSlot = sorted[idx + 1]

        val dateStr = date.toString()
        val nextStatus = logs.firstOrNull {
            it.medicationId == med.id && it.date == dateStr && it.time == nextSlot
        }?.status ?: DoseStatus.PENDING
        if (nextStatus != DoseStatus.PENDING) return null

        val existing = overrides.firstOrNull {
            it.medicationId == med.id && it.date == dateStr && it.originalTime == nextSlot
        }
        if (existing != null && existing.source == OverrideSource.MANUAL) return null

        val actual = LocalDateTime.ofInstant(Instant.ofEpochMilli(takenAtMillis), ZoneId.systemDefault())
        val candidate = actual.plusHours(config.intervalHours.toLong())
        if (candidate.toLocalDate() != date) return null

        val candidateTime = TimeOfDay(candidate.hour, candidate.minute)
        if (candidateTime.minutesOfDay > config.endTime.minutesOfDay) return null
        if (candidateTime == nextSlot) return null

        return DoseOverride(med.id, dateStr, nextSlot, candidateTime, OverrideSource.CASCADE)
    }

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

    /**
     * 统计 [start]..[end] 区间（含端点）的依从情况。
     *
     * [overrides] 会影响"错过还是待服用"的判定：挪到晚上的那次，中午还不算错过。
     */
    fun adherence(
        meds: List<Medication>,
        logs: List<DoseLog>,
        start: LocalDate,
        end: LocalDate,
        now: LocalDateTime,
        overrides: List<DoseOverride> = emptyList()
    ): Adherence {
        var taken = 0
        var skipped = 0
        var missed = 0
        var upcoming = 0
        var d = start
        while (!d.isAfter(end)) {
            for (item in dosesForDate(meds, logs, d, overrides)) {
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
