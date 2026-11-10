package com.jian.pillreminder.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jian.pillreminder.data.MealRelation
import com.jian.pillreminder.data.Medication
import com.jian.pillreminder.data.Schedule
import com.jian.pillreminder.data.TimeOfDay
import com.jian.pillreminder.domain.ScheduleEngine
import com.jian.pillreminder.notify.Reminders
import com.jian.pillreminder.ui.components.MedBadge
import com.jian.pillreminder.ui.components.DatePickerDialog
import com.jian.pillreminder.ui.components.TimePickerDialog
import com.jian.pillreminder.ui.components.MedIcons
import com.jian.pillreminder.ui.components.suggestIconForUnit
import com.jian.pillreminder.ui.theme.MedColors
import com.jian.pillreminder.ui.theme.medColorAt
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal fun sanitizeDosageInput(raw: String): String {
    val result = StringBuilder()
    var hasDecimalPoint = false
    raw.forEach { char ->
        when {
            char.isDigit() -> result.append(char)
            char == '.' && !hasDecimalPoint -> {
                if (result.isEmpty()) result.append('0')
                result.append(char)
                hasDecimalPoint = true
            }
        }
    }
    return result.toString()
}

internal fun parsePositiveDosage(text: String): Double? =
    text.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

/** 用药频率的四档（EditDraftState 持有当前档）。 */
enum class FreqTab(val label: String) {
    DAILY("每天"), WEEKLY("按周"), INTERVAL("间隔"), CYCLE("周期")
}

/**
 * 编辑表单的可变状态容器。活过旋转/系统回收（由 ViewModel 的台账持有），
 * 让填了一半的长表单不会因重建而悄悄丢掉。
 */
class EditDraftState(initial: Medication) {
    var med by mutableStateOf(initial)
    var freqTab by mutableStateOf(
        when (initial.schedule) {
            is Schedule.Daily -> FreqTab.DAILY
            is Schedule.WeekDays -> FreqTab.WEEKLY
            is Schedule.EveryNDays -> FreqTab.INTERVAL
            is Schedule.CycleOnOff -> FreqTab.CYCLE
        }
    )
    // 各频率模式的独立输入，切 tab 时不互相清空
    var weekDays by mutableStateOf(
        (initial.schedule as? Schedule.WeekDays)?.daysOfWeek ?: setOf(1, 2, 3, 4, 5)
    )
    var intervalDays by mutableStateOf(
        ((initial.schedule as? Schedule.EveryNDays)?.intervalDays ?: 2).toString()
    )
    var cycleOn by mutableStateOf(
        ((initial.schedule as? Schedule.CycleOnOff)?.onDays ?: 21).toString()
    )
    var cycleOff by mutableStateOf(
        ((initial.schedule as? Schedule.CycleOnOff)?.offDays ?: 7).toString()
    )
    var dosageText by mutableStateOf(
        com.jian.pillreminder.notify.Reminders.formatDosage(initial.dosage)
    )
    var stockText by mutableStateOf(
        initial.stockRemaining?.let { com.jian.pillreminder.notify.Reminders.formatDosage(it) } ?: ""
    )
    var thresholdText by mutableStateOf(
        com.jian.pillreminder.notify.Reminders.formatDosage(initial.stockThreshold)
    )
    var hasEndDate by mutableStateOf(initial.endDate != null)
    var endDateText by mutableStateOf(initial.endDate ?: "")
    var startDateText by mutableStateOf(initial.startDate)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMedicationScreen(
    initial: Medication,
    isNew: Boolean,
    onSave: (Medication) -> Unit,
    onDelete: (() -> Unit)?,
    onBack: () -> Unit,
    /** 点「拍说明书识别」时调用；为 null 时不显示该入口。 */
    onScanLeaflet: (() -> Unit)? = null,
    /** OCR 识别到但需要覆盖到频率区的信息（预填时用）。 */
    prefillNote: String? = null,
    /** 表单草稿（活过旋转/系统回收，见 MedViewModel.editDraftFor）。 */
    draftState: EditDraftState
) {
    // 表单主体状态全部在 draftState 里，旋转/系统回收重建不丢
    val draft = draftState.med
    val freqTab = draftState.freqTab
    val weekDays = draftState.weekDays
    val intervalDays = draftState.intervalDays
    val cycleOn = draftState.cycleOn
    val cycleOff = draftState.cycleOff
    val dosageText = draftState.dosageText
    val stockText = draftState.stockText
    val thresholdText = draftState.thresholdText
    val hasEndDate = draftState.hasEndDate
    // 结束日期初始值：只对"本来就设了结束日期"的药取 initial 值。
    // 曾经的默认值"今天+1个月"有个坑：编辑老药时随手开了又没填，保存后
    // 疗程被悄悄改成一个月——用户根本不知道自己改了什么。现在开着开关
    // 但没日期就不给保存，逼着用户明确选一天。
    val endDateText = draftState.endDateText
    val startDateText = draftState.startDateText

    fun updateMed(m: Medication) { draftState.med = m }
    fun updateFreqTab(t: FreqTab) { draftState.freqTab = t }
    fun updateWeekDays(w: Set<Int>) { draftState.weekDays = w }
    fun updateIntervalDays(v: String) { draftState.intervalDays = v }
    fun updateCycleOn(v: String) { draftState.cycleOn = v }
    fun updateCycleOff(v: String) { draftState.cycleOff = v }
    fun updateDosageText(v: String) { draftState.dosageText = v }
    fun updateStockText(v: String) { draftState.stockText = v }
    fun updateThresholdText(v: String) { draftState.thresholdText = v }
    fun updateHasEndDate(b: Boolean) { draftState.hasEndDate = b }
    fun updateEndDateText(v: String) { draftState.endDateText = v }
    fun updateStartDateText(v: String) { draftState.startDateText = v }

    var showTimePicker by remember { mutableStateOf(false) }
    var editingTimeIndex by remember { mutableStateOf<Int?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }
    var dosageError by remember { mutableStateOf(false) }
    // 库存填了内容但不是有效数字（如 "1.2.3"）时拦截保存，
    // 不能静默当作"不管理库存"——那会把用户的库存数据悄悄丢掉
    var stockError by remember { mutableStateOf(false) }
    var endDateError by remember { mutableStateOf(false) }
    // 用户手动挑过图标之后，改单位就不再自动换图标了。
    // 编辑已有药品时视为"已挑过"——那个图标本来就是他之前定的。
    var iconPickedByUser by remember { mutableStateOf(!isNew) }
    /** 正在填哪个日期，null = 没在填。 */
    var editingDate by remember { mutableStateOf<DateField?>(null) }

    fun buildSchedule(): Schedule = when (freqTab) {
        FreqTab.DAILY -> Schedule.Daily
        FreqTab.WEEKLY -> Schedule.WeekDays(weekDays.ifEmpty { setOf(1) })
        FreqTab.INTERVAL -> Schedule.EveryNDays((intervalDays.toIntOrNull() ?: 2).coerceIn(1, 90))
        FreqTab.CYCLE -> Schedule.CycleOnOff(
            (cycleOn.toIntOrNull() ?: 21).coerceIn(1, 365),
            (cycleOff.toIntOrNull() ?: 7).coerceIn(0, 365)
        )
    }

    /** 当前输入与 initial 是否不同（用于返回挽留）。 */
    fun isDirty(): Boolean {
        if (draft != initial) return true
        // 频率区：draft.schedule 只在 commit() 里重建，必须单独比对，
        // 否则"只改了周几/间隔天数"不触发挽留
        if (buildSchedule() != initial.schedule) return true
        // 新建页"没动过"：所有输入都是 EditDraftState 的出厂值。
        // 不能用"字段为空"判断——剂量框出厂就是 "1"
        if (isNew) {
            val untouched = draft.name.isBlank() && draft.note.isBlank() &&
                draft.times == listOf(TimeOfDay(8, 0)) &&
                buildSchedule() == Schedule.Daily &&
                dosageText == Reminders.formatDosage(initial.dosage) &&
                stockText == "" &&
                thresholdText == Reminders.formatDosage(initial.stockThreshold) &&
                !hasEndDate && startDateText == initial.startDate &&
                draft.colorIndex == initial.colorIndex && draft.iconIndex == initial.iconIndex
            if (untouched) return false
        }
        return dosageText != Reminders.formatDosage(initial.dosage) ||
            stockText != (initial.stockRemaining?.let { Reminders.formatDosage(it) } ?: "") ||
            thresholdText != Reminders.formatDosage(initial.stockThreshold) ||
            hasEndDate != (initial.endDate != null) ||
            endDateText != (initial.endDate ?: "") ||
            startDateText != initial.startDate
    }

    // 有未保存修改时拦截返回：长表单（尤其 OCR 预填后）误触返回键内容全丢
    var showDiscardConfirm by remember { mutableStateOf(false) }
    fun requestBack() {
        if (isDirty()) showDiscardConfirm = true else onBack()
    }
    BackHandler { requestBack() }

    fun commit() {
        if (draft.name.isBlank()) {
            nameError = true
            return
        }
        val dosage = parsePositiveDosage(dosageText)
        if (dosage == null) {
            dosageError = true
            return
        }
        // 库存：留空=不管（null）；填了内容就必须是有效数字，否则拦截
        val stock = if (stockText.isBlank()) null else stockText.toDoubleOrNull()
        if (stockText.isNotBlank() && stock == null) {
            stockError = true
            return
        }
        // 开了"结束日期"却没选日子：不猜、不默认，弹选择器让用户选；
        // 用户直接取消对话框时行内红字说明为什么没保存成
        if (hasEndDate && endDateText.isBlank()) {
            endDateError = true
            editingDate = DateField.END
            return
        }
        onSave(
            draft.copy(
                dosage = dosage,
                schedule = buildSchedule(),
                startDate = startDateText,
                endDate = if (hasEndDate) endDateText else null,
                stockRemaining = stock,
                stockThreshold = thresholdText.toDoubleOrNull() ?: 5.0
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "添加药品" else "编辑药品") },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (onDelete != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    // 保存动作只在表单底部通栏按钮，顶栏不放重复入口
                }
            )
        }
    ) { padding ->
        // 分步导航条用：记录每个分组标题的 y 坐标，点击后平滑滚过去。
        // onGloballyPositioned 在布局完成时回调，坐标天然准确。
        val listState = rememberScrollState()
        val scope = rememberCoroutineScope()
        val sectionOffsets = remember { mutableStateMapOf<Int, Int>() }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ---- 分步导航条：长表单的路标，点击直达 ----
            val labels = listOf("基本信息", "服药时间", "频率", "疗程", "提醒库存", "外观")
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                labels.forEachIndexed { i, label ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            // Column 是全量布局，所有分组坐标在首帧后都已在
                            // sectionOffsets 里；安全起见仍判空，拿不到就不动
                            sectionOffsets[i]?.let { y ->
                                scope.launch {
                                    listState.animateScrollTo((y - 8).coerceAtLeast(0))
                                }
                            }
                        },
                        label = { Text(label) }
                    )
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(listState)
                    .padding(horizontal = 16.dp)
            ) {
            // ---- 预览徽标 ----
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val (badgeBg, badgeFg) = medColorAt(draft.colorIndex).colors()
                MedBadge(
                    iconIndex = draft.iconIndex,
                    container = badgeBg,
                    content = badgeFg,
                    size = 80.dp
                )
            }
            Spacer(Modifier.height(20.dp))

            // ---- 拍说明书识别入口（仅新建时提供）----
            if (isNew && onScanLeaflet != null) {
                OutlinedButton(
                    onClick = onScanLeaflet,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.PhotoCamera, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("拍药品说明书自动填写")
                }
                Spacer(Modifier.height(20.dp))
            }

            // ---- OCR 预填提示 ----
            if (prefillNote != null) {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(prefillNote, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            // ---- 基本信息 ----
            SettingCard("基本信息", 0, onPlaced = { sectionOffsets[0] = it }) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { updateMed(draft.copy(name = it)); nameError = false },
                    label = { Text("药品名称") },
                    placeholder = { Text("例如：阿司匹林") },
                    isError = nameError,
                    supportingText = if (nameError) {
                        { Text("请填写药品名称", color = MaterialTheme.colorScheme.error) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = dosageText,
                    onValueChange = {
                        updateDosageText(sanitizeDosageInput(it))
                        dosageError = false
                    },
                    label = { Text("单次剂量") },
                    supportingText = if (dosageError) {
                        { Text("请输入大于 0 的剂量，例如 0.5", color = MaterialTheme.colorScheme.error) }
                    } else {
                        // 支持小数是本 App 的特性（半片 0.5），常驻提示
                        { Text("支持小数：0.5 = 半片，0.25 = 四分之一片") }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = dosageError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft.unit,
                    onValueChange = { raw ->
                        val u = raw.take(6)
                        // 图标按剂型分类，而单位就是剂型的直接线索：填"粒"多半是胶囊。
                        // 只在用户还没手动挑过图标时跟着变，挑过就尊重他的选择。
                        updateMed(if (iconPickedByUser) {
                            draft.copy(unit = u)
                        } else {
                            draft.copy(unit = u, iconIndex = suggestIconForUnit(u))
                        })
                    },
                    label = { Text("单位") },
                    placeholder = { Text("片") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { updateMed(draft.copy(note = it)) },
                    label = { Text("备注（可选）") },
                    placeholder = { Text("例如：随餐服用，避免与钙片同服") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---- 服药时间 ----
            SettingCard("服药时间", 1, onPlaced = { sectionOffsets[1] = it }) {
                Text(
                    "可以加多个时间点，比如早晚各一次",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    draft.times.sorted().forEachIndexed { index, t ->
                        InputChip(
                            selected = false,
                            onClick = {
                                editingTimeIndex = draft.times.indexOf(t)
                                showTimePicker = true
                            },
                            label = { Text(t.format()) },
                            leadingIcon = {
                                Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp))
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "删除该时间",
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable {
                                            if (draft.times.size > 1) {
                                                updateMed(draft.copy(times = draft.times - t))
                                            }
                                        }
                                )
                            }
                        )
                    }
                    AssistChip(
                        onClick = { editingTimeIndex = null; showTimePicker = true },
                        label = { Text("加时间") },
                        leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) }
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text("与进餐的关系", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MealRelation.entries.forEach { rel ->
                        FilterChip(
                            selected = draft.mealRelation == rel,
                            onClick = { updateMed(draft.copy(mealRelation = rel)) },
                            label = { Text(rel.label) },
                            leadingIcon = if (draft.mealRelation == rel) {
                                { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }
                            } else null
                        )
                    }
                }
            }

            // ---- 用药频率 ----
            SettingCard("用药频率", 2, onPlaced = { sectionOffsets[2] = it }) {
                // FilterChip 组（与进餐关系、设置页稍后提醒同语言；
                // SegmentedButton 是工具型形态，与其他选择器不统一）
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FreqTab.entries.forEach { tab ->
                        FilterChip(
                            selected = freqTab == tab,
                            onClick = { updateFreqTab(tab) },
                            label = { Text(tab.label) },
                            leadingIcon = if (freqTab == tab) {
                                { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }
                            } else null
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                when (freqTab) {
                    FreqTab.DAILY -> Text(
                        "每天都要吃，共 ${draft.times.size} 次",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FreqTab.WEEKLY -> {
                        Text(
                            "选择每周哪几天服用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (1..7).forEach { d ->
                                val selected = d in weekDays
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable {
                                            updateWeekDays(if (selected) weekDays - d else weekDays + d)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        ScheduleEngine.weekdayName(d),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    FreqTab.INTERVAL -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("每隔", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.width(12.dp))
                            OutlinedTextField(
                                value = intervalDays,
                                onValueChange = { updateIntervalDays(it.filter { c -> c.isDigit() }.take(3)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.width(90.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("天吃一次", style = MaterialTheme.typography.bodyLarge)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "填 2 就是隔天吃。从下面的「开始日期」算起。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    FreqTab.CYCLE -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("连吃", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = cycleOn,
                                onValueChange = { updateCycleOn(it.filter { c -> c.isDigit() }.take(3)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.width(80.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("天，停", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = cycleOff,
                                onValueChange = { updateCycleOff(it.filter { c -> c.isDigit() }.take(3)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.width(80.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("天", style = MaterialTheme.typography.bodyLarge)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "适合避孕药、激素类等周期性用药，循环往复。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- 疗程 ----
            SettingCard("疗程", 3, onPlaced = { sectionOffsets[3] = it }) {
                // 原来是两个要手打 yyyy-MM-dd 的文本框，格式打错了不好发现。
                // 现在点开填年月日，和暂停用药那边共用同一个对话框。
                DateRow(
                    label = "开始日期",
                    dateText = startDateText,
                    hint = "间隔/周期用药从这天开始算",
                    onClick = { editingDate = DateField.START }
                )
                Spacer(Modifier.height(4.dp))
                ListItem(
                    headlineContent = { Text("设定结束日期") },
                    supportingContent = {
                        Text(
                            when {
                                !hasEndDate -> "长期服用，不设结束"
                                endDateText.isBlank() -> "请选择结束日期"
                                else -> "${endDateText.toPrettyDate()} 后自动不再提醒"
                            }
                        )
                    },
                    trailingContent = {
                        Switch(checked = hasEndDate, onCheckedChange = { updateHasEndDate(it) })
                    },
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
                if (hasEndDate) {
                    DateRow(
                        label = "结束日期",
                        dateText = endDateText,
                        // 保存被拦的原因要看得见：取消日期对话框后行内红字
                        hint = if (endDateText.isBlank())
                            (if (endDateError) "请先选择结束日期，否则无法保存" else "还没选日期")
                        else null,
                        hintIsError = endDateError,
                        onClick = { editingDate = DateField.END; endDateError = false }
                    )
                }
            }

            // ---- 提醒与库存 ----
            SettingCard("提醒与库存", 4, onPlaced = { sectionOffsets[4] = it }) {
                ListItem(
                    headlineContent = { Text("到点通知提醒") },
                    supportingContent = { Text("关掉后只在清单里显示，不推送通知") },
                    trailingContent = {
                        Switch(
                            checked = draft.remindersEnabled,
                            onCheckedChange = { updateMed(draft.copy(remindersEnabled = it)) }
                        )
                    },
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = stockText,
                    onValueChange = {
                        updateStockText(sanitizeDosageInput(it))
                        stockError = false
                    },
                    label = { Text("现有库存") },
                    placeholder = { Text("留空=不管") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = stockError,
                    supportingText = if (stockError) {
                        { Text("填了内容必须是有效数字，或清空表示不管", color = MaterialTheme.colorScheme.error) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = thresholdText,
                    onValueChange = { updateThresholdText(sanitizeDosageInput(it)) },
                    label = { Text("续药提醒线") },
                    supportingText = { Text("库存低于该值提醒续药，默认 5") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---- 外观 ----
            SettingCard("图标与颜色", 5, onPlaced = { sectionOffsets[5] = it }) {
                Text("颜色", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MedColors.forEachIndexed { index, c ->
                        val selected = draft.colorIndex == index
                        val (cBg, cFg) = c.colors()
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(cBg)
                                .then(
                                    if (selected) Modifier.border(
                                        3.dp,
                                        MaterialTheme.colorScheme.primary,
                                        CircleShape
                                    ) else Modifier
                                )
                                .clickable { updateMed(draft.copy(colorIndex = index)) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = cFg,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("图标", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MedIcons.forEachIndexed { index, (label, icon) ->
                        val selected = draft.iconIndex == index
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable {
                                    updateMed(draft.copy(iconIndex = index))
                                    iconPickedByUser = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                icon,
                                contentDescription = label,
                                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                // 与 MedBadge 同一比例（48 * 0.74），选择器里的图标
                                // 才和卡片上看到的大小一致
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            FilledTonalButton(
                onClick = { commit() },
                shape = ButtonDefaults.shape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Check, null)
                Spacer(Modifier.width(8.dp))
                Text(if (isNew) "添加药品" else "保存修改")
            }
            Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showTimePicker) {
        val existing = editingTimeIndex?.let { draft.times.getOrNull(it) }
        TimePickerDialog(
            initialHour = existing?.hour ?: 8,
            initialMinute = existing?.minute ?: 0,
            onDismiss = { showTimePicker = false; editingTimeIndex = null },
            onConfirm = { h, m ->
                val newTime = TimeOfDay(h, m)
                updateMed(if (existing != null) {
                    draft.copy(times = (draft.times - existing + newTime).distinct().sorted())
                } else {
                    draft.copy(times = (draft.times + newTime).distinct().sorted())
                })
                showTimePicker = false
                editingTimeIndex = null
            }
        )
    }

    editingDate?.let { field ->
        val current = when (field) {
            DateField.START -> startDateText
            DateField.END -> endDateText
        }
        DatePickerDialog(
            initialDate = runCatching { LocalDate.parse(current) }
                .getOrDefault(LocalDate.now()),
            title = if (field == DateField.START) "填写开始日期" else "填写结束日期",
            supportingText = if (field == DateField.START)
                "间隔用药、周期用药都从这天开始算。"
            else "这天之后就不再提醒了。",
            // 结束不能早于开始，否则这药一天都不用吃，等于静默失效
            minDate = if (field == DateField.END)
                runCatching { LocalDate.parse(startDateText) }.getOrNull()
            else null,
            onDismiss = { editingDate = null },
            onConfirm = { picked ->
                when (field) {
                    DateField.START -> updateStartDateText(picked.toString())
                    DateField.END -> updateEndDateText(picked.toString())
                }
                editingDate = null
            }
        )
    }

    // 有未保存修改时的返回挽留
    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("放弃修改？") },
            text = { Text("你填写的内容还没有保存，离开后会丢失。") },
            confirmButton = {
                TextButton(onClick = { showDiscardConfirm = false; onBack() }) {
                    Text("放弃", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("继续编辑") }
            }
        )
    }

    if (showDeleteConfirm && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除「${draft.name}」？") },
            text = { Text("这会同时删掉它的所有服药记录，无法恢复。如果只是暂时停药，建议用药箱页菜单里的「停用」。") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 疗程里的两个日期，用来标识正在填哪一个。 */
private enum class DateField { START, END }

/** 存储格式(2026-09-22)转成人话(2026 年 9 月 22 日)，非法输入原样返回。 */
private fun String.toPrettyDate(): String = runCatching {
    LocalDate.parse(this).format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日"))
}.getOrDefault(this)

/**
 * 单位下拉（M3E 标准 select 的等价实现）。单位本质是枚举（片/粒/mL/滴/单位），
 * 自由输入允许打错字；下拉从源头杜绝。
 *
 * 实现说明：官方 ExposedDropdownMenuBox 在 material3 1.5.0-rc01 下
 * BoxScope 成员函数跨文件解析失败（编译期 Unresolved），改用等价组合——
 * readOnly TextField + toggleable（无障碍语义正确的整框点击）+ 标准下拉。
 * 点选仍触发图标联动（"粒"→胶囊图标，见 suggestIconForUnit）。
 */

/**
 * 一行可点的日期。点了弹填写对话框。
 *
 * 显示成「2026 年 9 月 6 日」而不是存储用的 2026-09-06——后者是给机器看的。
 */
@Composable
private fun DateRow(
    label: String,
    dateText: String,
    hint: String?,
    onClick: () -> Unit,
    /** hint 是否按错误样式（红字）显示，如"保存被拦"的提示。 */
    hintIsError: Boolean = false
) {
    val pretty = remember(dateText) {
        runCatching {
            LocalDate.parse(dateText)
                .format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日"))
        }.getOrDefault(dateText)
    }
    // 不用 ListItem：它的 trailingContent 是单独测量的插槽，不是按整行高度算，
    // 给图标包一层 fillMaxHeight 也撑不满——开始日期带 hint 更高、结束日期没有，
    // 图标就跟着偏上/居中不一致。改成自己拼 Row，用 Alignment.CenterVertically
    // 是相对 Row 实际高度算的，两行的图标才能真正对齐到同一条基准。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(pretty, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hintIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(Icons.Filled.EditCalendar, contentDescription = "修改$label")
    }
}

/**
 * 表单分组：左侧 primary 色条 + 步骤序号（x / N）+ 卡片。
 * [onPlaced] 把本组标题的 y 坐标报给调用方，分步导航条据此滚动。
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SettingCard(
    title: String,
    index: Int,
    onPlaced: (Int) -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 4.dp, bottom = 8.dp)
            .onGloballyPositioned { coords ->
                onPlaced(coords.positionInParent().y.toInt())
            }
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 16.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(8.dp))
        Text(
            "${index + 1} / 6",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), content = content)
    }
    Spacer(Modifier.height(20.dp))
}

