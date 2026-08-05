package com.jian.pillreminder.notify

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jian.pillreminder.MainActivity
import com.jian.pillreminder.R
import com.jian.pillreminder.data.MedRepository
import com.jian.pillreminder.data.Medication
import com.jian.pillreminder.data.TimeOfDay
import com.jian.pillreminder.domain.ScheduleEngine
import java.time.LocalDateTime

object Reminders {

    private const val TAG = "PillReminders"

    /**
     * 服药提醒通道。
     *
     * 带 v2 后缀是因为通道一旦创建，声音/免打扰绕行这类属性就无法再由 App 修改，
     * 只能新建一个通道。v1 用的是 CATEGORY_REMINDER + 默认通知音，
     * 在系统免打扰下会被静音——而晚间那次药恰好撞在很多人的免打扰时段里。
     * v2 改用闹钟音频属性并申请绕过免打扰，语义上也更对：这本来就是闹钟。
     */
    const val CHANNEL_DOSE = "dose_reminders_v2"
    private const val CHANNEL_DOSE_LEGACY = "dose_reminders"
    const val CHANNEL_STOCK = "stock_alerts"

    const val EXTRA_MED_ID = "med_id"
    const val EXTRA_DATE = "dose_date"
    const val EXTRA_HOUR = "dose_hour"
    const val EXTRA_MINUTE = "dose_minute"

    const val ACTION_FIRE = "com.jian.pillreminder.FIRE"
    const val ACTION_TAKEN = "com.jian.pillreminder.TAKEN"
    const val ACTION_SNOOZE = "com.jian.pillreminder.SNOOZE"
    const val ACTION_SKIP = "com.jian.pillreminder.SKIP"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val dose = NotificationChannel(
            CHANNEL_DOSE,
            "服药提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "到点提醒你吃药。按闹钟处理，开启免打扰时也会响。"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 350, 250, 350)
            // 用闹钟音频属性：闹钟类通知在免打扰下默认放行，
            // 而普通提醒类会被静音，晚间那次药最容易因此漏掉。
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
            )
            // 申请绕过免打扰。系统只在用户授予「免打扰权限」后才真正生效，
            // 没授予时设置无效但不会报错，正常提醒不受影响。
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val stock = NotificationChannel(
            CHANNEL_STOCK,
            "库存不足提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "药快吃完时提醒续药" }

        nm.createNotificationChannel(dose)
        nm.createNotificationChannel(stock)

        // 删掉 v1 通道，否则系统通知设置里会并列两个"服药提醒"让人困惑。
        // 用户在 v1 上做过的开关不会迁移，这是换通道的既有代价；
        // 换来的是免打扰下能正常响，对吃药提醒来说更重要。
        runCatching { nm.deleteNotificationChannel(CHANNEL_DOSE_LEGACY) }
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
    }

    /** 稳定的 requestCode：同一药同一时刻始终映射到同一个闹钟。 */
    private fun requestCode(medId: String, time: TimeOfDay): Int =
        (medId.hashCode() * 31 + time.minutesOfDay) and 0x0FFFFFFF

    // ---- 已排闹钟的台账 ----
    //
    // requestCode 由「药 id + 时刻」算出，所以取消闹钟必须用"当初排进去的时刻"，
    // 用改完之后的 times 算不出旧闹钟的 requestCode，旧时间照样会响一次。
    // 这份台账是设备本地状态（换手机后闹钟自然不存在），故存 SharedPreferences
    // 而不是 Medication 字段——否则会跟着备份文件跑到新手机上。

    private const val PREFS = "reminder_alarms"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 台账的存储格式："8:0,20:30"。抽成纯函数便于单元测试。 */
    internal fun encodeTimes(times: List<TimeOfDay>): String =
        times.joinToString(",") { "${it.hour}:${it.minute}" }

    internal fun decodeTimes(raw: String?): List<TimeOfDay> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",").mapNotNull { token ->
            val parts = token.split(":")
            val h = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val m = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (h != null && m != null && h in 0..23 && m in 0..59) TimeOfDay(h, m) else null
        }
    }

    /** 读出某药当前已排进系统的时刻。 */
    private fun scheduledTimes(context: Context, medId: String): List<TimeOfDay> =
        decodeTimes(prefs(context).getString(medId, null))

    private fun setScheduledTimes(context: Context, medId: String, times: List<TimeOfDay>) {
        val editor = prefs(context).edit()
        if (times.isEmpty()) editor.remove(medId)
        else editor.putString(medId, encodeTimes(times))
        // 用 commit 而非 apply：排闹钟常发生在 BroadcastReceiver 里，
        // 台账没落盘就丢了会重新出现"旧闹钟取消不掉"的问题。文件很小，开销可忽略。
        editor.commit()
    }

    private fun fireIntent(context: Context, med: Medication, time: TimeOfDay, date: String) =
        Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_MED_ID, med.id)
            putExtra(EXTRA_DATE, date)
            putExtra(EXTRA_HOUR, time.hour)
            putExtra(EXTRA_MINUTE, time.minute)
        }

    /** 为一种药的所有时刻安排"下一次"闹钟。 */
    fun scheduleFor(context: Context, med: Medication) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        // 先按台账清掉旧闹钟（可能包含已被用户删掉的时刻）
        cancelFor(context, med)
        if (!med.remindersEnabled || med.archived || med.isSample) return

        val scheduled = mutableListOf<TimeOfDay>()
        val now = LocalDateTime.now()
        for (time in med.times) {
            // 针对每个时刻单独找它的下一次发生日
            val single = med.copy(times = listOf(time))
            val next = ScheduleEngine.nextOccurrence(single, now) ?: continue
            val triggerAt = ScheduleEngine.toEpochMillis(next)

            val pi = PendingIntent.getBroadcast(
                context,
                requestCode(med.id, time),
                fireIntent(context, med, time, next.toLocalDate().toString()),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val ok = runCatching {
                if (canScheduleExact(context)) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    // 没有精确闹钟权限时退化为可延迟闹钟，仍然会提醒，只是时间不精准
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            }.isSuccess
            if (ok) scheduled += time
        }
        setScheduledTimes(context, med.id, scheduled)
    }

    /**
     * 取消某药的全部闹钟。
     * 按台账里"当初排进去的时刻"取消，同时也覆盖 med.times，
     * 这样即使台账丢了（旧版本升级上来）也能尽量清干净。
     */
    fun cancelFor(context: Context, med: Medication) = cancelFor(context, med.id, med.times)

    fun cancelFor(context: Context, medId: String, alsoTimes: List<TimeOfDay> = emptyList()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val targets = (scheduledTimes(context, medId) + alsoTimes).distinct()
        for (time in targets) {
            // 顺延提醒（snooze）用的是 requestCode xor 0x5A5A，也要一起撤掉
            for (code in listOf(requestCode(medId, time), requestCode(medId, time) xor 0x5A5A)) {
                val pi = PendingIntent.getBroadcast(
                    context,
                    code,
                    Intent(context, ReminderReceiver::class.java).apply { action = ACTION_FIRE },
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pi != null) {
                    am.cancel(pi)
                    pi.cancel()
                }
            }
        }
        setScheduledTimes(context, medId, emptyList())
    }

    /** 重排全部药品的闹钟（开机后、时区变化后、数据变更后调用）。 */
    fun rescheduleAll(context: Context) {
        val repo = MedRepository.get(context)
        val meds = repo.data.value.medications
        // 台账里还留着、但药已经不存在了（被删/被导入覆盖）→ 清掉它的残留闹钟
        val liveIds = meds.map { it.id }.toSet()
        prefs(context).all.keys.filterNot { it in liveIds }.forEach { staleId ->
            cancelFor(context, staleId)
        }
        for (med in meds) scheduleFor(context, med)
        ReminderWatchdog.schedule(context)
    }

    /** 延后指定分钟数再提醒一次。 */
    fun snooze(context: Context, med: Medication, time: TimeOfDay, date: String, minutes: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode(med.id, time) xor 0x5A5A,
            fireIntent(context, med, time, date),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            if (canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun notificationId(medId: String, time: TimeOfDay): Int = requestCode(medId, time)

    fun showDoseNotification(context: Context, med: Medication, time: TimeOfDay, date: String) {
        ensureChannels(context)
        if (!hasNotificationPermission(context)) {
            android.util.Log.w(TAG, "没有通知权限，跳过提醒: ${med.name}")
            return
        }
        android.util.Log.i(TAG, "准备发送提醒: ${med.name} @ ${time.format()}")

        fun action(act: String, label: String, code: Int): NotificationCompat.Action {
            val i = Intent(context, ReminderReceiver::class.java).apply {
                action = act
                putExtra(EXTRA_MED_ID, med.id)
                putExtra(EXTRA_DATE, date)
                putExtra(EXTRA_HOUR, time.hour)
                putExtra(EXTRA_MINUTE, time.minute)
            }
            val pi = PendingIntent.getBroadcast(
                context, notificationId(med.id, time) xor code, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Action.Builder(0, label, pi).build()
        }

        val openApp = PendingIntent.getActivity(
            context, notificationId(med.id, time),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dose = formatDosage(med.dosage) + med.unit
        val mealHint = if (med.mealRelation.label == "无要求") "" else " · ${med.mealRelation.label}"

        val n = NotificationCompat.Builder(context, CHANNEL_DOSE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("该吃 ${med.name} 了")
            .setContentText("${time.format()} · $dose$mealHint")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // ALARM 而非 REMINDER：REMINDER 类别在系统免打扰下会被拦掉
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioManager.STREAM_ALARM
            )
            .setVibrate(longArrayOf(0, 350, 250, 350))
            .addAction(action(ACTION_TAKEN, "已服用", 0x11))
            .addAction(action(ACTION_SNOOZE, "稍后提醒", 0x22))
            .addAction(action(ACTION_SKIP, "跳过", 0x33))
            .also { if (med.note.isNotBlank()) it.setStyle(NotificationCompat.BigTextStyle().bigText("$dose$mealHint\n${med.note}")) }
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(med.id, time), n)
        }.onFailure { android.util.Log.e(TAG, "发送服药通知失败: ${med.name}", it) }
    }

    fun dismissDoseNotification(context: Context, medId: String, time: TimeOfDay) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationId(medId, time))
        }
    }

    /**
     * 收掉某药所有还挂在通知栏上的通知（删除/停用药品时调用）。
     * 不收的话，用户点那条通知的「已服用」时 Receiver 查不到药，
     * 通知不消失也没反应，看起来像卡住了。
     */
    fun dismissAllFor(context: Context, med: Medication) {
        val nm = NotificationManagerCompat.from(context)
        // 台账里的时刻覆盖"排过但用户刚改掉"的情况，med.times 覆盖当前设置
        val times = (scheduledTimes(context, med.id) + med.times).distinct()
        for (time in times) {
            runCatching { nm.cancel(notificationId(med.id, time)) }
        }
        // 库存告警用的是另一个 id
        runCatching { nm.cancel(med.id.hashCode() and 0x0FFFFFFF) }
    }

    fun showStockAlert(context: Context, med: Medication) {
        ensureChannels(context)
        if (!hasNotificationPermission(context)) return
        val remaining = med.stockRemaining ?: return

        val n = NotificationCompat.Builder(context, CHANNEL_STOCK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${med.name} 快用完了")
            .setContentText("仅剩 ${formatDosage(remaining)}${med.unit}，记得续药")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, med.id.hashCode(),
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(med.id.hashCode() and 0x0FFFFFFF, n)
        }
    }

    fun formatDosage(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
