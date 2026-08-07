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
import com.jian.pillreminder.data.DeferredReminder
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

    /**
     * 延后提醒（稍后提醒 / 临时改时间）的闹钟槽位。
     *
     * 必须和常规槽位分开：常规闹钟和延后闹钟的 action 都是 [ACTION_FIRE]，
     * 而 PendingIntent 的相等性判断（Intent.filterEquals）**不看 extras**，
     * 只看 requestCode 和组件。同一个 requestCode 会让延后闹钟直接覆盖掉
     * 明天同一时刻的常规闹钟。
     *
     * 掩码取 0x5A5A 是有讲究的：它翻转的位使任意两个 requestCode 的最小异或差为 9638，
     * 而同一种药两个时刻的 requestCode 相差最多 1439（一天的分钟数），
     * 所以延后槽绝不会撞上同一种药另一个时刻的常规槽。
     * 新增掩码时必须保持这个性质（最小异或差 > 1439）。
     */
    private fun deferredRequestCode(medId: String, time: TimeOfDay): Int =
        requestCode(medId, time) xor 0x5A5A

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
            // 延后槽（稍后提醒 / 临时改时间）也要一起撤掉，否则会漏一个闹钟一直响。
            // 撤掉之后由 rescheduleAll 按 AppData.deferredReminders 重建——
            // 这就是延后提醒必须持久化的原因。
            for (code in listOf(requestCode(medId, time), deferredRequestCode(medId, time))) {
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
        val data = repo.data.value
        val meds = data.medications
        // 台账里还留着、但药已经不存在了（被删/被导入覆盖）→ 清掉它的残留闹钟
        val liveIds = meds.map { it.id }.toSet()
        prefs(context).all.keys.filterNot { it in liveIds }.forEach { staleId ->
            cancelFor(context, staleId)
        }
        for (med in meds) scheduleFor(context, med)

        // scheduleFor 里的 cancelFor 会把延后槽一起撤掉，所以必须在它之后重建。
        // 没有这一步，「稍后提醒」和「临时改时间」在每次回到前台或每 6 小时守护任务
        // 跑过之后就会静默失效。
        val medsById = meds.associateBy { it.id }
        val now = System.currentTimeMillis()
        // 不该响的药由 armDeferred 自己拦（示例/已停用/关了提醒），这里不重复判断。
        for (d in data.deferredReminders) {
            if (d.triggerAtMillis <= now) continue
            val med = medsById[d.medicationId] ?: continue
            armDeferred(context, med, d.originalTime, d.date, d.triggerAtMillis)
        }

        ReminderWatchdog.schedule(context)
    }

    /**
     * 延后指定分钟数再提醒一次，并把它记到数据里。
     *
     * 记账是必须的：[scheduleFor] 开头会无条件 [cancelFor]，而 [rescheduleAll]
     * 在每次回到前台和每 6 小时的守护任务里都跑。不记账的话，「稍后提醒」之后
     * 只要打开一次 App，那个闹钟就被静默清掉，重启同样丢——用户以为设了，其实没有。
     *
     * 从广播里调用，所以 [MedRepository.putDeferredReminder] 必须同步落盘。
     */
    fun snooze(context: Context, med: Medication, time: TimeOfDay, date: String, minutes: Int) {
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        MedRepository.get(context).putDeferredReminder(
            DeferredReminder(med.id, date, time, triggerAt),
            syncWrite = true
        )
        armDeferred(context, med, time, date, triggerAt)
    }

    /**
     * 把某一次服药挪到今天的另一个时刻（临时改时间）。
     *
     * 注意 [time] 始终是**原定时刻**——它是这次服药的身份，DoseLog 与通知 id 都靠它。
     * 只有触发时间变了。
     */
    fun rescheduleOneDose(
        context: Context,
        med: Medication,
        time: TimeOfDay,
        date: String,
        newTime: TimeOfDay
    ) {
        val triggerAt = ScheduleEngine.toEpochMillis(
            LocalDateTime.of(
                runCatching { java.time.LocalDate.parse(date) }.getOrNull()
                    ?: java.time.LocalDate.now(),
                java.time.LocalTime.of(newTime.hour, newTime.minute)
            )
        )
        val repo = MedRepository.get(context)
        repo.setDoseOverride(med.id, date, time, newTime)
        repo.putDeferredReminder(DeferredReminder(med.id, date, time, triggerAt))
        armDeferred(context, med, time, date, triggerAt)
    }

    /** 撤销临时改时间，回到原定时刻。 */
    fun clearOneDoseReschedule(context: Context, med: Medication, time: TimeOfDay, date: String) {
        val repo = MedRepository.get(context)
        repo.setDoseOverride(med.id, date, time, null)
        repo.removeDeferredReminder(med.id, date, time)
        cancelDeferred(context, med.id, time)
    }

    /**
     * 把一个延后提醒真正排进系统。已过期的直接跳过。
     *
     * **不该响的药在这里统一拦掉**。[scheduleFor] 和 [rescheduleAll] 各自都有这组
     * 守卫，但 [snooze] 和 [rescheduleOneDose] 是直接进来的——守卫只写在那两处的话，
     * 示例药会排出真闹钟（`isSample` 的全部意义就是"不会真的提醒"），已停用或关了
     * 提醒的药也会因为用户挪一次时间就复活。这里是所有延后闹钟的唯一出口，
     * 放这一处即可覆盖全部入口。
     */
    private fun armDeferred(
        context: Context,
        med: Medication,
        time: TimeOfDay,
        date: String,
        triggerAtMillis: Long
    ) {
        if (triggerAtMillis <= System.currentTimeMillis()) return
        if (med.isSample || med.archived || !med.remindersEnabled) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            deferredRequestCode(med.id, time),
            fireIntent(context, med, time, date),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        }
    }

    /** 撤掉某次服药的延后闹钟（已服用/跳过之后就不该再响）。 */
    fun cancelDeferredFor(context: Context, medId: String, time: TimeOfDay) =
        cancelDeferred(context, medId, time)

    /**
     * 某次服药已经有结果（已服用/跳过）——收尾清理，三处调用点都要做全同一件事：
     * 台账里的延后记录、系统里排着的延后闹钟、通知栏上那条通知，一个都不能漏。
     *
     * 这原本在 ReminderReceiver 的 ACTION_TAKEN/ACTION_SKIP 和 MedViewModel.mark()
     * 里各写一遍，结果 MedViewModel 那份漏了撤延后闹钟——"稍后提醒排了 15 分钟后的
     * 闹钟，App 里点了已服用，15 分钟后还是响了"。收成一个函数，以后不会再有
     * 某条路径漏写一行的问题。
     */
    fun clearDoseOutcome(context: Context, medId: String, date: String, time: TimeOfDay) {
        MedRepository.get(context).removeDeferredReminder(medId, date, time, syncWrite = true)
        cancelDeferredFor(context, medId, time)
        dismissDoseNotification(context, medId, time)
    }

    private fun cancelDeferred(context: Context, medId: String, time: TimeOfDay) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            deferredRequestCode(medId, time),
            Intent(context, ReminderReceiver::class.java).apply { action = ACTION_FIRE },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
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

        // 「提醒不消失」。目标是让漏服变难：提醒响过一次就没了、被划掉或没看见
        // 就等于没提醒。可在设置里关掉。
        //
        // **注意 setOngoing 挡不住手划**。Android 14 起 FLAG_ONGOING_EVENT 只是
        // 给系统的排序提示，不再让通知不可清除；真正划不掉的只有绑前台服务的通知，
        // 而安服没有前台服务权限（也不想为此常驻一个服务）。真机 Android 16 上实测
        // 横扫即消失。所以这个开关的实际作用只有 setAutoCancel(false)：
        // 点通知本体去 App 时不会顺手清掉提醒，避免"看了一眼就当吃过了"。
        // 兜底靠 ReminderWatchdog 的补发，不靠这一条通知赖着不走。
        val ongoing = MedRepository.get(context).data.value.ongoingNotification

        val n = NotificationCompat.Builder(context, CHANNEL_DOSE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("该吃 ${med.name} 了")
            .setContentText("${time.format()} · $dose$mealHint")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // ALARM 而非 REMINDER：REMINDER 类别在系统免打扰下会被拦掉
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // 常驻时不能自动取消，否则点一下通知本体就没了，等于绕过了打卡
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
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
