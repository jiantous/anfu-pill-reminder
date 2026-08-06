package com.jian.pillreminder.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * 单文件 JSON 持久化。读写都在 IO 线程，UI 只观察 [data]。
 * 数据存在 App 私有目录，卸载 App 才会清除。
 */
class MedRepository private constructor(private val file: File) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data.asStateFlow()

    private fun loadBlocking(): AppData =
        runCatching {
            if (file.exists() && file.length() > 0) {
                pruneStale(migrate(json.decodeFromString<AppData>(file.readText())))
            } else {
                // 全新安装：直接标成当前版本，不需要迁移
                AppData(schemaVersion = CURRENT_SCHEMA)
            }
        }.getOrElse { AppData(schemaVersion = CURRENT_SCHEMA) }

    /**
     * 清掉过期的临时状态。每次启动都跑（不像 migrate 只跑一次）。
     *
     * 临时改时间和延后提醒都是单次的，只对某一天有效。不清理的话
     * 这两个列表会随使用无限增长，而且过期的延后提醒会在重排时被错误地重新排上。
     */
    private fun pruneStale(data: AppData, today: LocalDate = LocalDate.now()): AppData {
        // 昨天以前的临时改时间已经没有意义
        val keepFrom = today.minusDays(1).toString()
        val overrides = data.doseOverrides.filter { it.date >= keepFrom }
        // 触发时刻已过的延后提醒：要么已经响过，要么错过了，都不该再排
        val nowMillis = System.currentTimeMillis()
        val deferred = data.deferredReminders.filter { it.triggerAtMillis > nowMillis }

        if (overrides.size == data.doseOverrides.size &&
            deferred.size == data.deferredReminders.size
        ) {
            return data
        }
        val cleaned = data.copy(doseOverrides = overrides, deferredReminders = deferred)
        persist(cleaned, sync = true)
        return cleaned
    }

    /**
     * 老数据格式升级。每一步都要能在"已经是新版"的数据上安全跳过，
     * 因为迁移结果要等到下一次写盘才落地，中间可能被反复读到。
     */
    private fun migrate(loaded: AppData): AppData {
        if (loaded.schemaVersion >= CURRENT_SCHEMA) return loaded

        var data = loaded

        // v0 → v1：图标表从 Material 通用图标换成按剂型分类的手绘图标，
        // 下标含义变了，得按语义重新映射，否则存量药品会显示成别的形状。
        if (data.schemaVersion < 1) {
            data = data.copy(
                medications = data.medications.map {
                    it.copy(iconIndex = mapLegacyIconIndex(it.iconIndex))
                }
            )
        }

        val migrated = data.copy(schemaVersion = CURRENT_SCHEMA)
        // 立刻落盘，避免每次启动都重复迁移
        persist(migrated, sync = true)
        return migrated
    }

    private fun writeToDisk(snapshot: AppData) {
        runCatching {
            file.parentFile?.mkdirs()
            // 先写临时文件再改名，避免写一半崩溃导致数据损坏
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(snapshot))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }.onFailure { android.util.Log.e("PillRepo", "写入数据失败", it) }
    }

    private fun persist(snapshot: AppData, sync: Boolean) {
        if (sync) {
            // BroadcastReceiver 场景：onReceive 返回后进程可能立刻被回收，
            // 异步写会丢数据，必须在返回前落盘。
            synchronized(this) { writeToDisk(snapshot) }
        } else {
            scope.launch { writeLock.withLock { writeToDisk(snapshot) } }
        }
    }

    private fun update(sync: Boolean = false, transform: (AppData) -> AppData) {
        val updated = transform(_data.value)
        _data.value = updated
        persist(updated, sync)
    }

    // ---- 药品 ----

    fun upsertMedication(med: Medication) = update { d ->
        val idx = d.medications.indexOfFirst { it.id == med.id }
        val list = if (idx >= 0) d.medications.toMutableList().also { it[idx] = med }
        else d.medications + med
        d.copy(medications = list)
    }

    fun deleteMedication(id: String) = update { d ->
        d.copy(
            medications = d.medications.filterNot { it.id == id },
            logs = d.logs.filterNot { it.medicationId == id },
            // 一并清掉临时状态，否则会留下指向已删药品的孤儿记录
            doseOverrides = d.doseOverrides.filterNot { it.medicationId == id },
            deferredReminders = d.deferredReminders.filterNot { it.medicationId == id }
        )
    }

    fun setArchived(id: String, archived: Boolean) = update { d ->
        d.copy(medications = d.medications.map { if (it.id == id) it.copy(archived = archived) else it })
    }

    // ---- 服药记录 ----

    /**
     * 记录一次服药结果。同一个 (药, 日期, 时刻) 只保留最新一条。
     * 标记为已服用时，若该药启用了库存管理则自动扣减剂量。
     */
    fun logDose(
        medicationId: String,
        date: String,
        time: TimeOfDay,
        status: DoseStatus,
        nowMillis: Long,
        /** 从通知栏/广播调用时传 true，确保返回前落盘。 */
        syncWrite: Boolean = false
    ) = update(sync = syncWrite) { d ->
        val filtered = d.logs.filterNot {
            it.medicationId == medicationId && it.date == date && it.time == time
        }
        val previous = d.logs.firstOrNull {
            it.medicationId == medicationId && it.date == date && it.time == time
        }

        val newLogs = if (status == DoseStatus.PENDING) filtered else
            filtered + DoseLog(medicationId, date, time, status, nowMillis)

        // 库存调整：进入 TAKEN 扣减，离开 TAKEN 回补，避免反复点击算错
        val wasTaken = previous?.status == DoseStatus.TAKEN
        val isTaken = status == DoseStatus.TAKEN
        val meds = if (wasTaken == isTaken) d.medications else d.medications.map { m ->
            if (m.id != medicationId || m.stockRemaining == null) m
            else {
                val delta = if (isTaken) -m.dosage else m.dosage
                m.copy(stockRemaining = (m.stockRemaining + delta).coerceAtLeast(0.0))
            }
        }

        d.copy(logs = newLogs, medications = meds)
    }

    /** 手动设置库存（补药后使用）。 */
    fun setStock(medicationId: String, stock: Double?, threshold: Double? = null) = update { d ->
        d.copy(medications = d.medications.map { m ->
            if (m.id != medicationId) m
            else m.copy(
                stockRemaining = stock,
                stockThreshold = threshold ?: m.stockThreshold
            )
        })
    }

    fun setSnoozeMinutes(minutes: Int) = update { it.copy(snoozeMinutes = minutes) }

    fun setOngoingNotification(on: Boolean) = update { it.copy(ongoingNotification = on) }

    // ---- 暂停用药 ----

    /** [until] 为 null 表示立即恢复。 */
    fun setPausedUntil(medicationId: String, until: String?) = update { d ->
        d.copy(medications = d.medications.map {
            if (it.id == medicationId) it.copy(pausedUntil = until) else it
        })
    }

    // ---- 临时改时间 ----

    /**
     * 把某一次服药挪到 [newTime]。同一次服药重复挪动只保留最后一次。
     * [newTime] 传 null 表示撤销挪动、恢复原定时刻。
     */
    fun setDoseOverride(
        medicationId: String,
        date: String,
        originalTime: TimeOfDay,
        newTime: TimeOfDay?
    ) = update { d ->
        val rest = d.doseOverrides.filterNot {
            it.medicationId == medicationId && it.date == date && it.originalTime == originalTime
        }
        d.copy(
            doseOverrides = if (newTime == null) rest
            else rest + DoseOverride(medicationId, date, originalTime, newTime)
        )
    }

    // ---- 延后提醒 ----

    /**
     * 记下一个待触发的延后提醒。同一次服药只保留最新的一个。
     * 从广播里调用时务必传 [syncWrite]，否则进程被回收就丢了。
     */
    fun putDeferredReminder(reminder: DeferredReminder, syncWrite: Boolean = false) =
        update(sync = syncWrite) { d ->
            val rest = d.deferredReminders.filterNot { it.key == reminder.key }
            d.copy(deferredReminders = rest + reminder)
        }

    /** 延后提醒已触发或已被处理，移除它。 */
    fun removeDeferredReminder(
        medicationId: String,
        date: String,
        originalTime: TimeOfDay,
        syncWrite: Boolean = false
    ) = update(sync = syncWrite) { d ->
        d.copy(deferredReminders = d.deferredReminders.filterNot {
            it.medicationId == medicationId && it.date == date && it.originalTime == originalTime
        })
    }

    fun markSetupGuideShown() = update { it.copy(setupGuideShown = true) }

    fun setHealthBannerDismissed(dismissed: Boolean) =
        update { it.copy(healthBannerDismissed = dismissed) }

    // ---- 备份 ----

    fun setBackupFolder(uri: String?) = update { it.copy(backupFolderUri = uri) }

    fun markBackedUp(date: String) = update { it.copy(lastBackupDate = date) }

    fun setBackupReminderDismissed(dismissed: Boolean) =
        update { it.copy(backupReminderDismissed = dismissed) }

    /** 导入备份：整体替换内存与磁盘上的数据，同步落盘确保不丢。 */
    fun replaceAll(newData: AppData) = update(sync = true) { newData }

    companion object {
        /** 当前数据格式版本，见 [AppData.schemaVersion]。 */
        const val CURRENT_SCHEMA = 1

        @Volatile
        private var instance: MedRepository? = null

        fun get(context: Context): MedRepository =
            instance ?: synchronized(this) {
                instance ?: MedRepository(File(context.applicationContext.filesDir, "pill_data.json"))
                    .also { it._data.value = it.loadBlocking(); instance = it }
            }
    }
}
