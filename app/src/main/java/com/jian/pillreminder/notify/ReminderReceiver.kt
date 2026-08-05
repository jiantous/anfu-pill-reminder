package com.jian.pillreminder.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jian.pillreminder.data.DoseStatus
import com.jian.pillreminder.data.MedRepository
import com.jian.pillreminder.data.TimeOfDay
import java.time.LocalDate

/**
 * 处理三类广播：
 *  - 闹钟到点（ACTION_FIRE）→ 弹通知，并把该时刻的下一次闹钟排上
 *  - 通知栏按钮（TAKEN / SNOOZE / SKIP）
 *  - 开机 / 时间变更 → 重排所有闹钟
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.i("PillReceiver", "收到广播: ${intent.action}")
        val repo = MedRepository.get(context)

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON",
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            ReminderWatchdog.ACTION_WATCHDOG -> {
                Reminders.ensureChannels(context)
                Reminders.rescheduleAll(context)
                return
            }
        }

        val medId = intent.getStringExtra(Reminders.EXTRA_MED_ID) ?: return
        val date = intent.getStringExtra(Reminders.EXTRA_DATE) ?: LocalDate.now().toString()
        val hour = intent.getIntExtra(Reminders.EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(Reminders.EXTRA_MINUTE, -1)
        if (hour < 0 || minute < 0) return
        val time = TimeOfDay(hour, minute)

        val med = repo.data.value.medications.firstOrNull { it.id == medId }
        if (med == null) {
            // 药已经被删了（或被导入的备份覆盖掉了）。此时通知可能还挂在通知栏，
            // 直接 return 会让用户点「已服用」毫无反应，看起来像卡死。
            // 所以这里主动收掉通知并清理掉它残留的闹钟。
            android.util.Log.w("PillReceiver", "药品已不存在 id=$medId，收掉残留通知")
            Reminders.dismissDoseNotification(context, medId, time)
            Reminders.cancelFor(context, medId)
            return
        }

        when (intent.action) {
            Reminders.ACTION_FIRE -> {
                Reminders.showDoseNotification(context, med, time, date)
                // 排下一次：闹钟是一次性的，触发后必须重排
                Reminders.scheduleFor(context, med)
            }

            Reminders.ACTION_TAKEN -> {
                repo.logDose(medId, date, time, DoseStatus.TAKEN, System.currentTimeMillis(), syncWrite = true)
                Reminders.dismissDoseNotification(context, medId, time)
                checkStock(context, medId)
            }

            Reminders.ACTION_SKIP -> {
                repo.logDose(medId, date, time, DoseStatus.SKIPPED, System.currentTimeMillis(), syncWrite = true)
                Reminders.dismissDoseNotification(context, medId, time)
            }

            Reminders.ACTION_SNOOZE -> {
                Reminders.snooze(context, med, time, date, repo.data.value.snoozeMinutes)
                Reminders.dismissDoseNotification(context, medId, time)
            }
        }
    }

    /** 扣减库存后如果低于阈值就提醒续药。 */
    private fun checkStock(context: Context, medId: String) {
        val med = MedRepository.get(context).data.value.medications.firstOrNull { it.id == medId } ?: return
        val remaining = med.stockRemaining ?: return
        if (remaining <= med.stockThreshold) Reminders.showStockAlert(context, med)
    }
}
