package com.jian.pillreminder.ui

import android.app.Application
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jian.pillreminder.data.AppData
import com.jian.pillreminder.data.DoseStatus
import com.jian.pillreminder.data.BackupManager
import com.jian.pillreminder.data.CsvExporter
import com.jian.pillreminder.data.ImportMode
import com.jian.pillreminder.data.MedRepository
import com.jian.pillreminder.data.Medication
import com.jian.pillreminder.data.TimeOfDay
import com.jian.pillreminder.domain.DoseItem
import com.jian.pillreminder.domain.ScheduleEngine
import com.jian.pillreminder.notify.Reminders
import com.jian.pillreminder.ui.screens.EditDraftState
import com.jian.pillreminder.ui.theme.MedColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class MedViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MedRepository.get(app)

    val data: StateFlow<AppData> = repo.data

    /** 今日清单 / 历史页当前查看的日期。 */
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    /**
     * 用于把"错过"判定刷新。除了 UI 在 ON_RESUME 时主动调用 refreshNow()，
     * 还有一个前台每分钟走一次的心跳：否则 App 挂在前台不动、正好跨过服药
     * 时刻，卡片不会从"待服用"变"已错过"——那正是最需要它变的时候。
     */
    private val _now = MutableStateFlow(LocalDateTime.now())
    val now: StateFlow<LocalDateTime> = _now.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                // 对齐到下一分钟整点再醒：误差不超过半秒，且大部分时间在睡觉
                val nextMinute = LocalDateTime.now().plusMinutes(1)
                    .withSecond(0).withNano(0)
                delay(Duration.between(LocalDateTime.now(), nextMinute).toMillis())
                _now.value = LocalDateTime.now()
            }
        }
    }

    val dosesForSelectedDate: StateFlow<List<DoseItem>> =
        combine(repo.data, _selectedDate) { d, date ->
            ScheduleEngine.dosesForDate(d.medications, d.logs, date, d.doseOverrides)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeMedications: StateFlow<List<Medication>> =
        combine(repo.data, _selectedDate) { d, _ ->
            d.medications.sortedWith(compareBy({ it.archived }, { it.name }))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 库存告警：启用了库存管理且已低于阈值的药。示例药不算——它不会真提醒。 */
    val lowStockMedications: StateFlow<List<Medication>> =
        combine(repo.data, _selectedDate) { d, _ ->
            d.medications.filter { m ->
                !m.archived && !m.isSample &&
                    m.stockRemaining != null && m.stockRemaining <= m.stockThreshold
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refreshNow() {
        _now.value = LocalDateTime.now()
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun medicationById(id: String?): Medication? =
        id?.let { mid -> repo.data.value.medications.firstOrNull { it.id == mid } }

    fun newMedicationDraft(): Medication = Medication(
        id = UUID.randomUUID().toString(),
        name = "",
        startDate = LocalDate.now().toString(),
        // 新药轮着用色板里的颜色，相邻的药不撞色
        colorIndex = repo.data.value.medications.size % MedColors.size
    )

    /**
     * 编辑页的草稿台账。为什么放 ViewModel 而不是 rememberSaveable：
     * 冷启动刻意回今天页（编辑态不恢复）；旋转/系统回收重建时 ViewModel
     * 天然存活，配合 rememberSaveable 的 nav/editingId 能把用户带回编辑页。
     * key = 药品 id（新建统一用 "new"），退出编辑页时清掉。
     */
    private val _editDrafts = mutableStateMapOf<String, EditDraftState>()
    val editDrafts: Map<String, EditDraftState> get() = _editDrafts

    fun editDraftFor(id: String, initial: Medication): EditDraftState =
        _editDrafts.getOrPut(id) { EditDraftState(initial) }

    /**
     * 用新的 initial 强制重建台账项。给 OCR 用：扫描结果到达时表单必须
     * 反映识别内容——getOrPut 的"先建优先"会保住进扫描前的空白草稿，
     * 识别结果反而被丢掉。
     */
    fun resetEditDraft(id: String, initial: Medication): EditDraftState {
        val fresh = EditDraftState(initial)
        _editDrafts[id] = fresh
        return fresh
    }

    fun clearEditDraft(id: String) {
        _editDrafts.remove(id)
    }

    /** 回桌面重开等场景：用户已离开编辑流程，残稿全部作废。 */
    fun clearAllEditDrafts() {
        _editDrafts.clear()
    }

    fun saveMedication(med: Medication) {
        val normalized = med.copy(
            name = med.name.trim(),
            times = med.times.distinct().sorted().ifEmpty { listOf(TimeOfDay(8, 0)) },
            // 用户动手改过并保存 → 说明他要真用这条，转为正式药品。
            // 不清这个标记的话，这条药会一直不排闹钟、永远不提醒。
            isSample = false
        )
        repo.upsertMedication(normalized)
        Reminders.scheduleFor(getApplication(), normalized)
    }

    fun deleteMedication(med: Medication) {
        Reminders.cancelFor(getApplication(), med)
        Reminders.cancelResume(getApplication(), med.id)
        // 通知栏上可能还挂着这条药的提醒，不收掉的话点它没有任何反应
        Reminders.dismissAllFor(getApplication(), med)
        repo.deleteMedication(med.id)
    }

    fun toggleArchived(med: Medication) {
        val updated = med.copy(archived = !med.archived)
        repo.setArchived(med.id, updated.archived)
        if (updated.archived) {
            Reminders.cancelFor(getApplication(), med)
            Reminders.cancelResume(getApplication(), med.id)
            Reminders.dismissAllFor(getApplication(), med)
        } else {
            Reminders.scheduleFor(getApplication(), updated)
            // 重新启用时，如果还在暂停期，恢复闹钟也要补回来
            if (updated.pausedUntil != null) {
                Reminders.scheduleResume(getApplication(), updated, updated.pausedUntil)
            }
        }
    }

    fun setStock(med: Medication, stock: Double?, threshold: Double?) {
        repo.setStock(med.id, stock, threshold)
    }

    /** 点一下切换：待服用 → 已服用 → 待服用。 */
    fun toggleTaken(item: DoseItem) {
        val next = if (item.status == DoseStatus.TAKEN) DoseStatus.PENDING else DoseStatus.TAKEN
        mark(item, next)
    }

    fun markSkipped(item: DoseItem) {
        val next = if (item.status == DoseStatus.SKIPPED) DoseStatus.PENDING else DoseStatus.SKIPPED
        mark(item, next)
    }

    /** 清除一次服药的记录（回到"待服用"），用于明细页撤销。 */
    fun clearDose(item: DoseItem) = mark(item, DoseStatus.PENDING)

    private fun mark(item: DoseItem, status: DoseStatus) {
        // 打卡前先取库存快照：续药提醒要看"跨越"（充足→不足），
        // 扣完再读就已经拿不到旧值了
        val stockBefore = item.medication.stockRemaining

        repo.logDose(
            medicationId = item.medication.id,
            date = item.date.toString(),
            time = item.time,
            status = status,
            nowMillis = System.currentTimeMillis()
        )
        // 这次服药已经有结果了，未触发的延后闹钟（稍后提醒/临时改时间）都作废。
        // 用 clearDoseOutcome 而不是在这里各写一遍：之前就是因为这段清理在
        // ReminderReceiver 和这里各写一份，这份漏了撤延后闹钟，才出现
        // "先点稍后提醒、再在 App 里打卡，闹钟到点照样响"的 bug。
        Reminders.clearDoseOutcome(
            getApplication(), item.medication.id, item.date.toString(), item.time
        )

        if (status == DoseStatus.TAKEN && stockBefore != null) {
            val updated = repo.data.value.medications.firstOrNull { it.id == item.medication.id }
            val stockAfter = updated?.stockRemaining
            if (updated != null && stockAfter != null &&
                Reminders.shouldStockAlert(stockBefore, stockAfter, updated.stockThreshold)
            ) {
                Reminders.showStockAlert(getApplication(), updated)
            }
        }
    }

    fun adherence(days: Int): ScheduleEngine.Adherence {
        val d = repo.data.value
        val end = LocalDate.now()
        val start = end.minusDays((days - 1).toLong())
        return ScheduleEngine.adherence(
            d.medications, d.logs, start, end, LocalDateTime.now(), d.doseOverrides
        )
    }

    /**
     * 连续全勤天数：从今天（或昨天，今天还没服完不打断连胜）往前数，
     * 每天都"全部计划内的药都服了"的连续天数。
     * 同时返回历史最长连胜，给"历史最长 X 天"的展示。
     */
    fun streak(): Pair<Int, Int> {
        val d = repo.data.value
        if (d.medications.none { !it.archived }) return 0 to 0

        fun dayFullyTaken(date: LocalDate): Boolean {
            val items = ScheduleEngine.dosesForDate(d.medications, d.logs, date, d.doseOverrides)
            if (items.isEmpty()) return false
            // 漏服或跳过都不算全勤；主动跳过是用户决定，不算失败但也不算全勤
            return items.all { it.status == DoseStatus.TAKEN }
        }

        val today = LocalDate.now()
        // 今天没服完不打断连胜（从昨天数），但今天全勤会+1
        var current = 0
        var cursor = if (dayFullyTaken(today)) today else today.minusDays(1)
        // 今天没服药计划（空清单）也不打断——往昨天找
        while (cursor >= LocalDate.of(2020, 1, 1)) {
            if (dayFullyTaken(cursor)) {
                current++
                cursor = cursor.minusDays(1)
            } else {
                // 没有计划的日子跳过（不贡献也不打断）：停药日/无计划日
                val items = ScheduleEngine.dosesForDate(d.medications, d.logs, cursor, d.doseOverrides)
                if (items.isEmpty()) {
                    cursor = cursor.minusDays(1)
                    continue
                }
                break
            }
        }

        // 历史最长：从首条记录日扫到今天。数据量大时最多几百次日循环，
        // 每次 dosesForDate 是内存遍历，可接受（统计页非高频路径）
        val earliest = d.logs.minOfOrNull { LocalDate.parse(it.date) } ?: return current to current
        var best = 0
        var run = 0
        var day = earliest
        while (!day.isAfter(today)) {
            if (dayFullyTaken(day)) {
                run++
                if (run > best) best = run
            } else {
                val items = ScheduleEngine.dosesForDate(d.medications, d.logs, day, d.doseOverrides)
                if (items.isNotEmpty()) run = 0
                // 空计划日不断连胜
            }
            day = day.plusDays(1)
        }
        return current to maxOf(best, current)
    }

    /** 某天的完成情况，用于日历上色。 */
    fun dayStatus(date: LocalDate): DayStatus {
        val d = repo.data.value
        val items = ScheduleEngine.dosesForDate(d.medications, d.logs, date, d.doseOverrides)
        if (items.isEmpty()) return DayStatus.NONE
        val taken = items.count { it.status == DoseStatus.TAKEN }
        val nowTime = LocalDateTime.now()
        val missed = items.count { it.status == DoseStatus.PENDING && it.isOverdue(nowTime) }
        val skipped = items.count { it.status == DoseStatus.SKIPPED }
        return when {
            taken == items.size -> DayStatus.ALL_TAKEN
            missed == 0 && skipped == 0 && taken == 0 -> DayStatus.UPCOMING
            taken > 0 -> DayStatus.PARTIAL
            // 全部主动跳过：与依从率口径一致（跳过≠漏服），日历不标红
            skipped == items.size - taken -> DayStatus.SKIPPED
            // 有漏服才标红；部分跳过 + 部分待服（未到点）算部分完成
            missed > 0 -> DayStatus.MISSED
            else -> DayStatus.PARTIAL
        }
    }

    fun rescheduleAllAlarms() = Reminders.rescheduleAll(getApplication())

    fun setSnoozeMinutes(minutes: Int) = repo.setSnoozeMinutes(minutes)

    fun setOngoingNotification(on: Boolean) = repo.setOngoingNotification(on)

    fun setUiScale(scale: Float) = repo.setUiScale(scale)

    fun setRingtoneUri(uri: String?) = repo.setRingtoneUri(uri)

    fun markSetupGuideShown() = repo.markSetupGuideShown()

    fun dismissHealthBanner() = repo.setHealthBannerDismissed(true)

    // ---- 暂停用药 ----

    /**
     * 暂停到 [until]（含当天）。传 null 立即恢复。
     *
     * 设置的同时要撤掉已排的闹钟和已弹出的通知——否则暂停期内旧闹钟照样会响，
     * 通知栏上那条也还挂着。这和 [toggleArchived] 是同一套处理。
     */
    fun setPaused(med: Medication, until: LocalDate?) {
        repo.setPausedUntil(med.id, until?.toString())
        // 用更新后的数据重排：scheduleFor 开头会先 cancelFor 清掉旧闹钟，
        // 然后按 nextOccurrence 排下一次——它会自动跳过暂停期，
        // 所以暂停和恢复都只需要这一次调用。
        val updated = repo.data.value.medications.firstOrNull { it.id == med.id } ?: return
        Reminders.scheduleFor(getApplication(), updated)
        // 通知栏上可能还挂着这条药的提醒，暂停了就该收掉
        if (until != null) Reminders.dismissAllFor(getApplication(), updated)
        // 排/撤恢复提醒：暂停时排到恢复日早上，取消暂停时撤掉
        Reminders.scheduleResume(getApplication(), updated, until?.toString())
    }

    fun isPaused(med: Medication): Boolean = ScheduleEngine.isPausedNow(med)

    // ---- 临时改这次的时间 ----

    /** 把某一次服药挪到今天的另一个时刻。 */
    fun rescheduleDose(item: DoseItem, newTime: TimeOfDay) {
        Reminders.rescheduleOneDose(
            getApplication(), item.medication, item.time, item.date.toString(), newTime
        )
    }

    /** 撤销挪动，回到原定时刻。 */
    fun clearDoseReschedule(item: DoseItem) {
        Reminders.clearOneDoseReschedule(
            getApplication(), item.medication, item.time, item.date.toString()
        )
    }

    // ---- CSV 导出 ----

    /** [days] 为 null 表示导出全部历史。 */
    fun buildCsvContent(days: Int?): String {
        val d = repo.data.value
        val today = LocalDate.now()
        val start = if (days == null) CsvExporter.earliestDate(d, today)
        else today.minusDays((days - 1).toLong())
        return CsvExporter.build(d, start, today)
    }

    fun suggestCsvFileName(): String = CsvExporter.suggestFileName()

    /** 设置页上显示"有多少条记录可导出"。 */
    fun logCount(): Int = repo.data.value.logs.size

    // ---- 备份 ----

    fun buildBackupContent(): String {
        val app = getApplication<Application>()
        val version = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
        }.getOrDefault("")
        return BackupManager.buildBackup(repo.data.value, version)
    }

    fun markBackedUp() = repo.markBackedUp(LocalDate.now().toString())

    fun daysSinceBackup(): Long? = BackupManager.daysSince(repo.data.value.lastBackupDate)

    /** 应用导入的备份，并重排全部闹钟（药品和时间可能都变了）。 */
    fun applyBackup(backup: com.jian.pillreminder.data.BackupFile, mode: ImportMode) {
        // 先取消旧药的闹钟和已弹出的通知，避免被覆盖掉的药还在响
        repo.data.value.medications.forEach {
            Reminders.cancelFor(getApplication(), it)
            Reminders.cancelResume(getApplication(), it.id)
            Reminders.dismissAllFor(getApplication(), it)
        }
        val merged = BackupManager.apply(repo.data.value, backup, mode)
        repo.replaceAll(merged)
        Reminders.rescheduleAll(getApplication())
    }

    // ---- 示例数据 ----
    //
    // 刻意不在首次启动时自动塞入：示例药和真实用药混在一起会让人误判
    // （"我什么时候加过降压药？"），对一个吃药提醒应用来说这是危险的。
    // 改为在空状态里提供入口，由用户主动选择查看，且示例带 isSample 标记：
    // 界面上有「示例」角标、不会排闹钟（见 Reminders.scheduleFor）、可一键清除。

    val hasSampleData: StateFlow<Boolean> =
        repo.data.map { d -> d.medications.any { it.isSample } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 放入示例药品，让用户看到界面长什么样。 */
    fun addSampleData() {
        if (repo.data.value.medications.any { it.isSample }) return
        val today = LocalDate.now().toString()
        listOf(
            Medication(
                id = UUID.randomUUID().toString(),
                name = "维生素 D",
                dosage = 1.0,
                unit = "粒",
                note = "随早餐服用吸收更好",
                colorIndex = 5,
                iconIndex = 1,
                times = listOf(TimeOfDay(8, 0)),
                mealRelation = com.jian.pillreminder.data.MealRelation.WITH_MEAL,
                startDate = today,
                stockRemaining = 30.0,
                stockThreshold = 5.0,
                isSample = true
            ),
            Medication(
                id = UUID.randomUUID().toString(),
                name = "降压药",
                dosage = 1.0,
                unit = "片",
                note = "",
                colorIndex = 0,
                iconIndex = 0,
                times = listOf(TimeOfDay(8, 30), TimeOfDay(20, 30)),
                mealRelation = com.jian.pillreminder.data.MealRelation.AFTER_MEAL,
                startDate = today,
                stockRemaining = 14.0,
                stockThreshold = 6.0,
                isSample = true
            )
        ).forEach { repo.upsertMedication(it) }
    }

    /** 清除全部示例药品及其记录。 */
    fun clearSampleData() {
        repo.data.value.medications.filter { it.isSample }.forEach { med ->
            Reminders.cancelFor(getApplication(), med)
            Reminders.dismissAllFor(getApplication(), med)
            repo.deleteMedication(med.id)
        }
    }
}

enum class DayStatus { NONE, ALL_TAKEN, PARTIAL, MISSED, UPCOMING, SKIPPED }
