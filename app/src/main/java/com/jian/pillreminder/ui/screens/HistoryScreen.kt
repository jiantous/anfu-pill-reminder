package com.jian.pillreminder.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jian.pillreminder.data.DoseStatus
import com.jian.pillreminder.domain.ScheduleEngine
import com.jian.pillreminder.notify.Reminders
import com.jian.pillreminder.ui.DayStatus
import com.jian.pillreminder.ui.MedViewModel
import com.jian.pillreminder.ui.components.EmptyState
import com.jian.pillreminder.ui.components.MiniBarChart
import com.jian.pillreminder.ui.components.ProgressRing
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val RangeOptions = listOf(7 to "近 7 天", 30 to "近 30 天", 90 to "近 90 天")

@Composable
fun HistoryScreen(vm: MedViewModel) {
    val data by vm.data.collectAsState()
    var rangeIndex by remember { mutableStateOf(0) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }

    if (data.medications.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Insights,
            title = "还没有服药记录",
            subtitle = "",
            modifier = Modifier.fillMaxSize().padding(top = 80.dp)
        )
        return
    }

    val days = RangeOptions[rangeIndex].first
    val stat = remember(data, rangeIndex) { vm.adherence(days) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RangeOptions.forEachIndexed { index, (_, label) ->
                    SegmentedButton(
                        selected = rangeIndex == index,
                        onClick = { rangeIndex = index },
                        shape = SegmentedButtonDefaults.itemShape(index, RangeOptions.size),
                        label = { Text(label) }
                    )
                }
            }
        }

        item { AdherenceCard(stat) }

        item { WeeklyChart(vm) }

        item {
            MonthCalendarCard(
                vm = vm,
                month = month,
                selected = selectedDay,
                onPrevMonth = { month = month.minusMonths(1) },
                onNextMonth = { month = month.plusMonths(1) },
                onSelectDay = { selectedDay = if (selectedDay == it) null else it }
            )
        }

        selectedDay?.let { day ->
            item { DayDetailCard(vm, day) }
        }

        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun AdherenceCard(stat: ScheduleEngine.Adherence) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("按时服药率", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(120.dp)) {
                    ProgressRing(
                        progress = stat.rate ?: 0f,
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 14.dp
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stat.rate?.let { "${(it * 100).toInt()}%" } ?: "—",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "已完成",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.width(20.dp))
                // weight(1f) 让右侧统计项占满剩余宽度，卡片才不会右侧留白
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatRow(
                        Icons.Filled.CheckCircle,
                        "按时服用",
                        "${stat.taken} 次",
                        MaterialTheme.colorScheme.primary
                    )
                    StatRow(
                        Icons.Filled.ErrorOutline,
                        "错过未服",
                        "${stat.missed} 次",
                        MaterialTheme.colorScheme.error
                    )
                    StatRow(
                        Icons.Filled.SkipNext,
                        "主动跳过",
                        "${stat.skipped} 次",
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (stat.totalDue == 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "这段时间还没有需要服用的记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    tint: Color
) {
    // 标签靠左、次数靠右，三行形成对齐的两列。
    // 别改成"标签和次数紧挨着"：那样每行的次数会跟着标签宽度走，
    // 数字位数一变（9 次 → 12 次 → 100 次）右边就参差不齐。
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = tint
        )
    }
}

@Composable
private fun WeeklyChart(vm: MedViewModel) {
    val data by vm.data.collectAsState()
    val today = LocalDate.now()

    val (values, labels) = remember(data) {
        val v = mutableListOf<Float>()
        val l = mutableListOf<String>()
        for (i in 6 downTo 0) {
            val d = today.minusDays(i.toLong())
            val items = ScheduleEngine.dosesForDate(data.medications, data.logs, d)
            val rate = if (items.isEmpty()) 0f
            else items.count { it.status == DoseStatus.TAKEN }.toFloat() / items.size
            v += rate
            l += ScheduleEngine.weekdayName(d.dayOfWeek.value)
        }
        v to l
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("最近 7 天完成度", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            MiniBarChart(values = values, labels = labels)
            if (values.all { it == 0f }) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "这 7 天还没有打卡记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MonthCalendarCard(
    vm: MedViewModel,
    month: YearMonth,
    selected: LocalDate?,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDay: (LocalDate) -> Unit
) {
    val data by vm.data.collectAsState()
    val today = LocalDate.now()

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    month.format(DateTimeFormatter.ofPattern("yyyy 年 M 月")),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                )
                IconButton(onClick = onPrevMonth) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "上个月")
                }
                IconButton(onClick = onNextMonth) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "下个月")
                }
            }
            Spacer(Modifier.height(8.dp))

            Row {
                (1..7).forEach { d ->
                    Text(
                        ScheduleEngine.weekdayName(d),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            // 首日之前补空格，让日期落在正确的星期列（周一为第一列）
            val firstDay = month.atDay(1)
            val leading = firstDay.dayOfWeek.value - 1
            val totalCells = leading + month.lengthOfMonth()
            val rows = (totalCells + 6) / 7

            for (row in 0 until rows) {
                Row {
                    for (col in 0 until 7) {
                        val cellIndex = row * 7 + col
                        val dayNum = cellIndex - leading + 1
                        Box(
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            if (dayNum in 1..month.lengthOfMonth()) {
                                val date = month.atDay(dayNum)
                                val status = remember(data, date) { vm.dayStatus(date) }
                                DayCell(
                                    day = dayNum,
                                    status = status,
                                    isToday = date == today,
                                    isSelected = date == selected,
                                    isFuture = date.isAfter(today),
                                    onClick = { onSelectDay(date) }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendDot("全部服用", MaterialTheme.colorScheme.primary)
                LegendDot("部分服用", MaterialTheme.colorScheme.tertiary)
                LegendDot("有漏服", MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    status: DayStatus,
    isToday: Boolean,
    isSelected: Boolean,
    isFuture: Boolean,
    onClick: () -> Unit
) {
    val bg = when {
        isFuture && status != DayStatus.NONE -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        status == DayStatus.ALL_TAKEN -> MaterialTheme.colorScheme.primary
        status == DayStatus.PARTIAL -> MaterialTheme.colorScheme.tertiary
        status == DayStatus.MISSED -> MaterialTheme.colorScheme.error
        status == DayStatus.UPCOMING -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    val fg = when {
        isFuture && status != DayStatus.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
        status == DayStatus.ALL_TAKEN -> MaterialTheme.colorScheme.onPrimary
        status == DayStatus.PARTIAL -> MaterialTheme.colorScheme.onTertiary
        status == DayStatus.MISSED -> MaterialTheme.colorScheme.onError
        status == DayStatus.UPCOMING -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(bg)
            .then(
                when {
                    isSelected -> Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    isToday -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    else -> Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            day.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = fg
        )
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DayDetailCard(vm: MedViewModel, day: LocalDate) {
    val data by vm.data.collectAsState()
    val items = remember(data, day) {
        ScheduleEngine.dosesForDate(data.medications, data.logs, day)
    }
    val now = LocalDateTime.now()

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                day.format(DateTimeFormatter.ofPattern("M 月 d 日")) + " 明细",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(12.dp))
            if (items.isEmpty()) {
                Text(
                    "这天没有需要服用的药",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                items.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val (icon, tint, label) = when {
                            item.status == DoseStatus.TAKEN ->
                                Triple(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary, "已服用")
                            item.status == DoseStatus.SKIPPED ->
                                Triple(Icons.Filled.SkipNext, MaterialTheme.colorScheme.onSurfaceVariant, "已跳过")
                            item.isOverdue(now) ->
                                Triple(Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.error, "漏服")
                            else ->
                                Triple(Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.onSurfaceVariant, "待服用")
                        }
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.medication.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${item.time.format()} · ${Reminders.formatDosage(item.medication.dosage)}${item.medication.unit}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
                    }
                }
            }
        }
    }
}
