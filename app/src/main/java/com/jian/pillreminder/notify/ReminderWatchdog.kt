package com.jian.pillreminder.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 闹钟守护。
 *
 * 服药提醒用的是"一次性闹钟 + 响过之后再排下一次"，链式结构。
 * 只要中间有一环被系统吞掉（Doze 深度休眠、ROM 内存清理、用户强制停止应用），
 * 这条链就断了，之后**永远不会再提醒**，直到用户主动打开 App 才会重排。
 *
 * 而依赖提醒的人恰恰不会主动打开 App —— 他就是因为没收到提醒才漏了药。
 *
 * 所以这里额外挂一个每天跑几次的守护闹钟，唯一职责是无条件 rescheduleAll()，
 * 把断掉的链重新接上。它刻意用不精确闹钟（setInexactRepeating）：
 * 被系统延迟几十分钟完全没关系，省电也不需要精确闹钟权限。
 */
object ReminderWatchdog {

    const val ACTION_WATCHDOG = "com.jian.pillreminder.WATCHDOG"

    private const val REQUEST_CODE = 0x7ED06

    /** 每 6 小时检查一次。频率再低会让链条断裂后的空窗期过长。 */
    private const val INTERVAL_MILLIS = AlarmManager.INTERVAL_HALF_DAY / 2

    private fun pendingIntent(context: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java).apply { action = ACTION_WATCHDOG },
            flags or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * 确保守护闹钟已挂上。可以重复调用：
     * 已存在时 FLAG_UPDATE_CURRENT 会覆盖同一个闹钟，不会越挂越多。
     */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        runCatching {
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + INTERVAL_MILLIS,
                INTERVAL_MILLIS,
                pi
            )
        }
    }
}
