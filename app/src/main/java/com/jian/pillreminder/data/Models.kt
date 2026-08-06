package com.jian.pillreminder.data

import kotlinx.serialization.Serializable

/** 用药频率：决定"哪些天要吃"。 */
@Serializable
sealed interface Schedule {
    /** 每天都吃。 */
    @Serializable
    data object Daily : Schedule

    /** 每隔 [intervalDays] 天吃一次（2 = 隔天吃）。 */
    @Serializable
    data class EveryNDays(val intervalDays: Int) : Schedule

    /** 每周固定几天。[daysOfWeek] 用 java.time.DayOfWeek 的 value：1=周一 … 7=周日。 */
    @Serializable
    data class WeekDays(val daysOfWeek: Set<Int>) : Schedule

    /** 周期性服药：连吃 [onDays] 天，停 [offDays] 天，循环。 */
    @Serializable
    data class CycleOnOff(val onDays: Int, val offDays: Int) : Schedule
}

/** 与进餐的关系，仅作提示文案。 */
enum class MealRelation(val label: String) {
    NONE("无要求"),
    BEFORE_MEAL("饭前"),
    WITH_MEAL("随餐"),
    AFTER_MEAL("饭后"),
    EMPTY_STOMACH("空腹")
}

/** 一天中的某个服药时刻，如 08:30。 */
@Serializable
data class TimeOfDay(val hour: Int, val minute: Int) : Comparable<TimeOfDay> {
    override fun compareTo(other: TimeOfDay): Int =
        (hour * 60 + minute).compareTo(other.hour * 60 + other.minute)

    fun format(): String = "%02d:%02d".format(hour, minute)

    val minutesOfDay: Int get() = hour * 60 + minute
}

/** 药品 + 它的用药计划。一条 Medication 就是用户界面上的"一种药"。 */
@Serializable
data class Medication(
    val id: String,
    val name: String,
    /** 单次剂量数值，如 1.0 表示 1 片。 */
    val dosage: Double = 1.0,
    /** 剂量单位：片 / 粒 / mL / 单位 …… */
    val unit: String = "片",
    val note: String = "",
    /** 卡片配色索引，对应 MedColors。 */
    val colorIndex: Int = 0,
    /** 图标索引，对应 MedIcons。 */
    val iconIndex: Int = 0,
    val schedule: Schedule = Schedule.Daily,
    /** 每天要吃的时间点，已排序。 */
    val times: List<TimeOfDay> = listOf(TimeOfDay(8, 0)),
    val mealRelation: MealRelation = MealRelation.NONE,
    /** 疗程开始日期，ISO-8601（yyyy-MM-dd）。也是 EveryNDays / CycleOnOff 的锚点。 */
    val startDate: String,
    /** 疗程结束日期，null 表示长期服用。 */
    val endDate: String? = null,
    /** 是否开启提醒通知。 */
    val remindersEnabled: Boolean = true,
    /** 当前库存剩余数量（按 unit 计）。null 表示不管库存。 */
    val stockRemaining: Double? = null,
    /** 库存低于该值时提醒续药。 */
    val stockThreshold: Double = 5.0,
    /** 已归档（停药）的药不再出现在今日清单。 */
    val archived: Boolean = false,
    /**
     * 示例药品，用于让新用户看到界面长什么样。
     * 界面上会标注「示例」并提供一键清除，避免和真实用药混淆。
     */
    val isSample: Boolean = false,
    /**
     * 暂停到这一天为止（含当天），ISO-8601。null = 没暂停。
     *
     * 用于住院、出差、感冒停药这类临时情况：暂停期内不排闹钟、不上今日清单、
     * 不计入依从率。刻意**不顺延疗程**——endDate 和周期锚点都不动，
     * 恢复后接着原计划走，这样历史统计才解释得通。
     */
    val pausedUntil: String? = null
)

/**
 * 一次被推迟的提醒——「稍后提醒」或用户临时改到的时间。
 *
 * 为什么要持久化：闹钟是一次性的，而 `Reminders.scheduleFor` 开头会无条件
 * `cancelFor` 清掉这条药的所有闹钟槽位，包括延后槽。而 `rescheduleAll`
 * 在每次回到前台、以及每 6 小时的守护任务里都会跑——也就是说「稍后提醒」
 * 之后只要打开一次 App，那个延后闹钟就被静默清掉了，重启同样丢。
 * 存下来，重排时就能原样重建。
 */
@Serializable
data class DeferredReminder(
    val medicationId: String,
    /** 原定服药日期，ISO-8601。 */
    val date: String,
    /** 原定时刻，这次服药的身份键。 */
    val originalTime: TimeOfDay,
    /** 推迟到的绝对时间戳（毫秒）。用绝对时间而非"再等 N 分钟"，重排时才不会一直往后顺延。 */
    val triggerAtMillis: Long
) {
    val key: String get() = "$medicationId|$date|${originalTime.format()}"
}

/**
 * 某一次服药被临时挪到了别的时刻。
 *
 * 只影响这一次，不动 [Medication.times]。
 *
 * **[originalTime] 是这次服药的身份**：DoseLog、通知 id、闹钟 requestCode 全都由它推导。
 * 挪动的只有实际响铃时间（[newTime]）。若把身份也改成新时刻，
 * ScheduleEngine.dosesForDate 就再也匹配不上这条记录，它会变成孤儿、永远显示"已错过"。
 */
@Serializable
data class DoseOverride(
    val medicationId: String,
    /** 计划服药日期，ISO-8601。 */
    val date: String,
    /** 原定时刻，这次服药的身份键。 */
    val originalTime: TimeOfDay,
    /** 实际要提醒的时刻。 */
    val newTime: TimeOfDay
) {
    /** 与 DoseLog.key 同构，便于两边对照。 */
    val key: String get() = "$medicationId|$date|${originalTime.format()}"
}

/** 一次服药的状态。 */
enum class DoseStatus { PENDING, TAKEN, SKIPPED }

/**
 * 一条服药记录。只有用户操作过（吃了/跳过）才会落盘；
 * 未操作的"待服用"由计划实时推导，不存储。
 */
@Serializable
data class DoseLog(
    val medicationId: String,
    /** 计划服药日期，ISO-8601（yyyy-MM-dd）。 */
    val date: String,
    /** 计划服药时刻。 */
    val time: TimeOfDay,
    val status: DoseStatus,
    /** 实际操作时间戳（毫秒），用于显示"实际 08:42 服用"。 */
    val actedAtMillis: Long
) {
    /** 唯一标识一次计划内的服药。 */
    val key: String get() = "$medicationId|$date|${time.format()}"
}

/**
 * 「稍后提醒」可选的延后分钟数。
 *
 * 和默认值放在一起，因为 [DEFAULT_SNOOZE_MINUTES] 必须是这个表的成员——
 * 不是的话设置页会一个档位都不高亮。
 */
val SNOOZE_OPTIONS = listOf(5, 15, 30, 60)

/** 默认延后档位。 */
const val DEFAULT_SNOOZE_MINUTES = 15

/** 整个 App 的持久化状态。 */
@Serializable
data class AppData(
    /**
     * 数据格式版本，用于一次性迁移。
     *
     * 0（或字段缺失）= 首版：iconIndex 指向旧的 Material 图标表
     * 1 = iconIndex 指向按剂型分类的新图标表
     *
     * 老文件里没有这个字段，反序列化时取默认值 0，正好能识别出"需要迁移"。
     */
    val schemaVersion: Int = 0,
    val medications: List<Medication> = emptyList(),
    val logs: List<DoseLog> = emptyList(),
    /** 临时挪过时刻的单次服药。过期的会在启动时清掉，见 MedRepository。 */
    val doseOverrides: List<DoseOverride> = emptyList(),
    /** 尚未触发的延后提醒（稍后提醒 / 临时改时间）。过期的会在启动时清掉。 */
    val deferredReminders: List<DeferredReminder> = emptyList(),
    /** 「稍后提醒」延后多少分钟。取值来自 [SNOOZE_OPTIONS]。 */
    val snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES,
    /**
     * 服药通知是否常驻（不处理就不消失）。
     *
     * 开启后通知不能被划掉，只有点「已服用」「跳过」才会消失——
     * 漏服的主因就是提醒响过一次就没了。默认开启，可在设置里关掉。
     */
    val ongoingNotification: Boolean = true,
    /** 是否已经走过首次的「提醒设置」引导。 */
    val setupGuideShown: Boolean = false,
    /** 用户选择了不再提示提醒相关的系统设置。 */
    val healthBannerDismissed: Boolean = false,
    /** 上次成功导出备份的日期（ISO yyyy-MM-dd），null 表示从未备份。 */
    val lastBackupDate: String? = null,
    /** 用户授权的备份文件夹 URI（通常指向云盘的同步目录），null 表示还没选。 */
    val backupFolderUri: String? = null,
    /** 用户关闭了"很久没备份"的提醒。 */
    val backupReminderDismissed: Boolean = false
)
