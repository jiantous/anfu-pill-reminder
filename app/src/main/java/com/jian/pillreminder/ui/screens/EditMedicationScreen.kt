package com.jian.pillreminder.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jian.pillreminder.data.MealRelation
import com.jian.pillreminder.data.Medication
import com.jian.pillreminder.data.Schedule
import com.jian.pillreminder.data.TimeOfDay
import com.jian.pillreminder.domain.ScheduleEngine
import com.jian.pillreminder.ui.components.MedBadge
import com.jian.pillreminder.ui.components.DatePickerDialog
import com.jian.pillreminder.ui.components.TimePickerDialog
import com.jian.pillreminder.ui.components.MedIcons
import com.jian.pillreminder.ui.components.suggestIconForUnit
import com.jian.pillreminder.ui.theme.MedColors
import com.jian.pillreminder.ui.theme.medColorAt
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private enum class FreqTab(val label: String) {
    DAILY("每天"), WEEKLY("按周"), INTERVAL("间隔"), CYCLE("周期")
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
    prefillNote: String? = null
) {
    var draft by remember { mutableStateOf(initial) }
    var showTimePicker by remember { mutableStateOf(false) }
    var editingTimeIndex by remember { mutableStateOf<Int?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }
    // 用户手动挑过图标之后，改单位就不再自动换图标了。
    // 编辑已有药品时视为"已挑过"——那个图标本来就是他之前定的。
    var iconPickedByUser by remember { mutableStateOf(!isNew) }

    var freqTab by remember {
        mutableStateOf(
            when (initial.schedule) {
                is Schedule.Daily -> FreqTab.DAILY
                is Schedule.WeekDays -> FreqTab.WEEKLY
                is Schedule.EveryNDays -> FreqTab.INTERVAL
                is Schedule.CycleOnOff -> FreqTab.CYCLE
            }
        )
    }
    // 各频率模式的独立输入状态，切 tab 时不互相清空
    var weekDays by remember {
        mutableStateOf((initial.schedule as? Schedule.WeekDays)?.daysOfWeek ?: setOf(1, 2, 3, 4, 5))
    }
    var intervalDays by remember {
        mutableStateOf(((initial.schedule as? Schedule.EveryNDays)?.intervalDays ?: 2).toString())
    }
    var cycleOn by remember {
        mutableStateOf(((initial.schedule as? Schedule.CycleOnOff)?.onDays ?: 21).toString())
    }
    var cycleOff by remember {
        mutableStateOf(((initial.schedule as? Schedule.CycleOnOff)?.offDays ?: 7).toString())
    }
    var dosageText by remember {
        mutableStateOf(com.jian.pillreminder.notify.Reminders.formatDosage(initial.dosage))
    }
    var stockText by remember {
        mutableStateOf(initial.stockRemaining?.let { com.jian.pillreminder.notify.Reminders.formatDosage(it) } ?: "")
    }
    var thresholdText by remember {
        mutableStateOf(com.jian.pillreminder.notify.Reminders.formatDosage(initial.stockThreshold))
    }
    var hasEndDate by remember { mutableStateOf(initial.endDate != null) }
    var endDateText by remember {
        mutableStateOf(initial.endDate ?: LocalDate.now().plusMonths(1).toString())
    }
    var startDateText by remember { mutableStateOf(initial.startDate) }
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

    fun commit() {
        if (draft.name.isBlank()) {
            nameError = true
            return
        }
        onSave(
            draft.copy(
                dosage = dosageText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 1.0,
                schedule = buildSchedule(),
                startDate = startDateText,
                endDate = if (hasEndDate) endDateText else null,
                stockRemaining = stockText.toDoubleOrNull(),
                stockThreshold = thresholdText.toDoubleOrNull() ?: 5.0
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "添加药品" else "编辑药品") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                    TextButton(onClick = { commit() }) { Text("保存") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ---- 预览徽标 ----
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val palette = medColorAt(draft.colorIndex)
                MedBadge(
                    iconIndex = draft.iconIndex,
                    container = palette.container(),
                    content = palette.content(),
                    size = 80.dp
                )
            }
            Spacer(Modifier.height(20.dp))

            // ---- 拍说明书识别入口（仅新建时提供）----
            if (isNew && onScanLeaflet != null) {
                OutlinedButton(
                    onClick = onScanLeaflet,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
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
                        Spacer(Modifier.width(10.dp))
                        Text(prefillNote, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            // ---- 基本信息 ----
            SettingCard("基本信息") {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it); nameError = false },
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
                Row {
                    OutlinedTextField(
                        value = dosageText,
                        onValueChange = { dosageText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("单次剂量") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = draft.unit,
                        onValueChange = { raw ->
                            val u = raw.take(6)
                            // 图标按剂型分类，而单位就是剂型的直接线索：填"粒"多半是胶囊。
                            // 只在用户还没手动挑过图标时跟着变，挑过就尊重他的选择。
                            draft = if (iconPickedByUser) {
                                draft.copy(unit = u)
                            } else {
                                draft.copy(unit = u, iconIndex = suggestIconForUnit(u))
                            }
                        },
                        label = { Text("单位") },
                        placeholder = { Text("片") },
                        singleLine = true,
                        modifier = Modifier.weight(0.8f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { draft = draft.copy(note = it) },
                    label = { Text("备注（可选）") },
                    placeholder = { Text("例如：随餐服用，避免与钙片同服") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---- 服药时间 ----
            SettingCard("服药时间") {
                Text(
                    "可以加多个时间点，比如早晚各一次",
                    style = MaterialTheme.typography.bodySmall,
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
                                                draft = draft.copy(times = draft.times - t)
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
                            onClick = { draft = draft.copy(mealRelation = rel) },
                            label = { Text(rel.label) },
                            leadingIcon = if (draft.mealRelation == rel) {
                                { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }
                            } else null
                        )
                    }
                }
            }

            // ---- 用药频率 ----
            SettingCard("用药频率") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    FreqTab.entries.forEachIndexed { index, tab ->
                        SegmentedButton(
                            selected = freqTab == tab,
                            onClick = { freqTab = tab },
                            shape = SegmentedButtonDefaults.itemShape(index, FreqTab.entries.size),
                            label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

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
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                            weekDays = if (selected) weekDays - d else weekDays + d
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
                            Spacer(Modifier.width(10.dp))
                            OutlinedTextField(
                                value = intervalDays,
                                onValueChange = { intervalDays = it.filter { c -> c.isDigit() }.take(3) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.width(90.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("天吃一次", style = MaterialTheme.typography.bodyLarge)
                        }
                        Spacer(Modifier.height(6.dp))
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
                                onValueChange = { cycleOn = it.filter { c -> c.isDigit() }.take(3) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.width(80.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("天，停", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = cycleOff,
                                onValueChange = { cycleOff = it.filter { c -> c.isDigit() }.take(3) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.width(80.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("天", style = MaterialTheme.typography.bodyLarge)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "适合避孕药、激素类等周期性用药，循环往复。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- 疗程 ----
            SettingCard("疗程") {
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
                        Text(if (hasEndDate) "到期后自动不再提醒" else "长期服用，不设结束")
                    },
                    trailingContent = {
                        Switch(checked = hasEndDate, onCheckedChange = { hasEndDate = it })
                    },
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
                if (hasEndDate) {
                    DateRow(
                        label = "结束日期",
                        dateText = endDateText,
                        hint = null,
                        onClick = { editingDate = DateField.END }
                    )
                }
            }

            // ---- 提醒与库存 ----
            SettingCard("提醒与库存") {
                ListItem(
                    headlineContent = { Text("到点通知提醒") },
                    supportingContent = { Text("关掉后只在清单里显示，不推送通知") },
                    trailingContent = {
                        Switch(
                            checked = draft.remindersEnabled,
                            onCheckedChange = { draft = draft.copy(remindersEnabled = it) }
                        )
                    },
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        value = stockText,
                        onValueChange = { stockText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("现有库存") },
                        placeholder = { Text("留空=不管") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = thresholdText,
                        onValueChange = { thresholdText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("续药提醒线") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ---- 外观 ----
            SettingCard("图标与颜色") {
                Text("颜色", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MedColors.forEachIndexed { index, c ->
                        val selected = draft.colorIndex == index
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(c.container())
                                .then(
                                    if (selected) Modifier.border(
                                        3.dp,
                                        MaterialTheme.colorScheme.primary,
                                        CircleShape
                                    ) else Modifier
                                )
                                .clickable { draft = draft.copy(colorIndex = index) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = c.content(),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("图标", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                                    draft = draft.copy(iconIndex = index)
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
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Filled.Check, null)
                Spacer(Modifier.width(8.dp))
                Text(if (isNew) "添加药品" else "保存修改")
            }
            Spacer(Modifier.height(32.dp))
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
                draft = if (existing != null) {
                    draft.copy(times = (draft.times - existing + newTime).distinct().sorted())
                } else {
                    draft.copy(times = (draft.times + newTime).distinct().sorted())
                }
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
                    DateField.START -> startDateText = picked.toString()
                    DateField.END -> endDateText = picked.toString()
                }
                editingDate = null
            }
        )
    }

    if (showDeleteConfirm && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除「${draft.name}」？") },
            text = { Text("会同时删掉所有服药记录，无法恢复。") },
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
    onClick: () -> Unit
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(Icons.Filled.EditCalendar, contentDescription = "修改$label")
    }
}

@Composable
private fun SettingCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
    Spacer(Modifier.height(20.dp))
}

