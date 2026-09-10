package com.jian.pillreminder.notify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 「提醒能不能准时响」的体检。
 *
 * 服药提醒最怕的不是代码写错，而是被系统的省电策略掐掉。这里把影响到点提醒的
 * 系统开关集中检测，并提供跳转到对应设置页的 Intent —— 注意 Android 不允许
 * App 自行授予这些权限，只能引导用户确认，所以每一项都是「检测 + 跳转」。
 */
object ReminderHealth {

    /** 一项检查。 */
    data class Check(
        val id: String,
        val title: String,
        /** 为什么需要它，用大白话说清后果。 */
        val why: String,
        val granted: Boolean,
        /** 是否影响提醒的"能不能响"（true）还是只影响"准不准时"（false）。 */
        val critical: Boolean,
        /** 用户点按钮时的动作类型。 */
        val action: Action,
        /**
         * 跳转后还需要用户手动选什么。为 null 表示系统会直接弹确认框、点一下即可。
         * 部分 ROM（实测索尼）屏蔽了「一键豁免」确认框，只能跳设置页 + 文字指路。
         */
        val manualStep: String? = null
    )

    sealed interface Action {
        /** 走运行时权限申请（POST_NOTIFICATIONS）。 */
        data object RequestNotificationPermission : Action
        /** 打开一个系统设置页。 */
        data class OpenSettings(val intents: List<Intent>) : Action
        /** 纯展示项：无需用户操作，只显示当前状态。 */
        data object None : Action
    }

    fun checks(context: Context): List<Check> = listOf(
        notificationCheck(context),
        exactAlarmCheck(context),
        batteryCheck(context),
        armedTodayCheck(context)
    )

    /** 全部就绪时不再打扰用户。 */
    fun allGranted(context: Context): Boolean = checks(context).all { it.granted }

    fun pendingChecks(context: Context): List<Check> = checks(context).filterNot { it.granted }

    // ---- 1. 通知权限 ----

    private fun notificationCheck(context: Context) = Check(
        id = "notification",
        title = "允许发送通知",
        why = "没有这项，到点了不会有任何提示。",
        granted = Reminders.hasNotificationPermission(context) && notificationsEnabled(context),
        critical = true,
        action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !Reminders.hasNotificationPermission(context)
        ) {
            Action.RequestNotificationPermission
        } else {
            Action.OpenSettings(listOf(appNotificationSettings(context)))
        }
    )

    /** 权限给了但用户在系统设置里关了总开关，同样收不到通知。 */
    private fun notificationsEnabled(context: Context): Boolean =
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(true)

    // ---- 2. 精确闹钟 ----

    private fun exactAlarmCheck(context: Context) = Check(
        id = "exact_alarm",
        title = "允许精确闹钟",
        why = "没有这项，提醒可能延迟几分钟到几十分钟才响。",
        granted = Reminders.canScheduleExact(context),
        critical = false,
        action = Action.OpenSettings(
            buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(packageUri(context))
                    )
                }
                add(appDetailsSettings(context))
            }
        ),
        manualStep = "若没有直接弹出开关，请在页面里找到「闹钟和提醒」并打开。"
    )

    // ---- 3. 电池优化豁免 ----

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return runCatching {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }

    private fun batteryCheck(context: Context) = Check(
        id = "battery",
        title = "电池用量设为「无限制」",
        why = "手机省电时会冻结后台应用，提醒可能被延后甚至跳过整天。",
        granted = isIgnoringBatteryOptimizations(context),
        critical = true,
        // 不用 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS：实测部分 ROM（索尼 Android 16）
        // 会启动该 Activity 但不显示任何界面直接结束，表现为"点了没反应"。
        // 改为跳可靠打开的设置页，并用 manualStep 明确告诉用户到了要选什么。
        action = Action.OpenSettings(
            buildList {
                add(appDetailsSettings(context))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        ),
        manualStep = "在打开的页面里点「电池」，选择「无限制」或「不优化」。"
    )

    // ---- 4. 今日提醒已就绪（只读） ----

    /**
     * 展示今天还有几次提醒待响。数字由 ScheduleEngine 算（和今日清单同源），
     * 不是直接查 AlarmManager——Android 没有列出自家闹钟的 API。这里只让用户
     * 重启后能一眼确认"提醒在不在账上"，实际闹钟由 [rescheduleAll] 全量重排兜底。
     */
    private fun armedTodayCheck(context: Context): Check {
        val today = java.time.LocalDate.now()
        val data = com.jian.pillreminder.data.MedRepository.get(context).data.value
        // 只数会真的提醒的药：示例药不排闹钟，算进去会和实际响铃数对不上
        val realMeds = data.medications.filterNot { it.isSample || it.archived || !it.remindersEnabled }
        val doses = com.jian.pillreminder.domain.ScheduleEngine.dosesForDate(
            realMeds, data.logs, today, data.doseOverrides
        )
        val pending = doses.count { it.status == com.jian.pillreminder.data.DoseStatus.PENDING }
        val text = when {
            pending > 0 -> "今天还有 $pending 次提醒待响"
            else -> "今日无待响提醒"
        }
        return Check(
            id = "armed_today",
            title = "今日提醒",
            why = text,
            granted = true,
            critical = false,
            action = Action.None
        )
    }

    // ---- 通用 Intent ----

    private fun appNotificationSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            appDetailsSettings(context)
        }

    fun appDetailsSettings(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(packageUri(context))

    /**
     * 构造 package: 形式的 URI。
     * 不能用 Uri.parse("package:$pkg")——实测系统收到的 data 会退化成只有 "package:"
     * （scheme 之后的部分丢失），设置页因不知道目标应用而静默退出，表现为"点了没反应"。
     * Uri.fromParts 显式给出 scheme-specific part，包名才能正确传过去。
     */
    private fun packageUri(context: Context): Uri =
        Uri.fromParts("package", context.packageName, null)

    /**
     * 依次尝试候选 Intent，第一个能打开的就用它。
     * 部分厂商 ROM 会屏蔽某些系统页面，所以必须逐个降级，而不是硬上第一个。
     */
    fun launchFirstAvailable(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            val result = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (result.isSuccess) {
                android.util.Log.i(TAG, "已打开设置页: ${intent.action}")
                return true
            }
            android.util.Log.w(TAG, "打不开 ${intent.action}，尝试下一个", result.exceptionOrNull())
        }
        android.util.Log.e(TAG, "所有候选设置页都打不开")
        return false
    }

    private const val TAG = "PillHealth"

    /**
     * 厂商省电机制提示。
     *
     * 曾经按品牌写过具体路径（小米自启动、华为应用启动管理……），但各品牌
     * 菜单随系统版本频繁变动，写死的路径反而让用户在新系统上找不到入口
     * （有用户反馈华为/荣耀找不到）。改为通用提示：说清目标，让用户用
     * 设置搜索直达——这在所有品牌上都成立。
     */
    fun vendorHint(): String =
        "部分手机（小米/华为/OPPO/vivo 等）还有单独的自启动管理。" +
            "可在手机设置的搜索框里搜「自启动」或「后台运行」，找到后允许安服在后台运行。" +
            "若上面的电池设置已设为不优化，这里没找到也可以不设。"
}
