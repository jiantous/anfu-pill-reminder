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
    }

    fun checks(context: Context): List<Check> = listOf(
        notificationCheck(context),
        exactAlarmCheck(context),
        batteryCheck(context)
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

    /** 部分厂商（小米/华为/OPPO/vivo 等）另有自启动白名单，只能引导用户手动找。 */
    fun vendorHint(): String? {
        val brand = Build.MANUFACTURER.lowercase()
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") ->
                "小米手机还需在「设置 → 应用设置 → 授权管理 → 自启动管理」里允许安服自启动。"
            brand.contains("huawei") || brand.contains("honor") ->
                "华为/荣耀手机还需在「设置 → 应用 → 应用启动管理」里把安服改为手动管理，并勾选允许后台活动。"
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
                "OPPO/一加手机还需在「设置 → 电池 → 应用耗电管理」里允许安服后台运行。"
            brand.contains("vivo") || brand.contains("iqoo") ->
                "vivo 手机还需在「设置 → 电池 → 后台耗电管理」里允许安服高耗电。"
            brand.contains("meizu") ->
                "魅族手机还需在「设置 → 应用管理 → 权限管理」里允许安服后台运行。"
            brand.contains("samsung") ->
                "三星手机建议在「设置 → 应用 → 安服 → 电池」里选择「不受限制」。"
            brand.contains("sony") ->
                "索尼手机建议在「设置 → 电池 → 电池优化」里把安服设为「不优化」，并关闭 STAMINA 模式对它的限制。"
            else -> null
        }
    }
}
