package com.jian.pillreminder.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.jian.pillreminder.data.DoseStatus
import com.jian.pillreminder.data.Medication
import com.jian.pillreminder.domain.DoseItem
import com.jian.pillreminder.domain.ScheduleEngine
import com.jian.pillreminder.notify.Reminders
import com.jian.pillreminder.ui.MedViewModel
import com.jian.pillreminder.ui.components.CheckCircle
import com.jian.pillreminder.ui.components.EmptyState
import com.jian.pillreminder.ui.components.GroupLabel
import com.jian.pillreminder.ui.components.MedBadge
import com.jian.pillreminder.ui.components.TimePickerDialog
import com.jian.pillreminder.ui.theme.medColorAt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    vm: MedViewModel,
    onAddMedication: () -> Unit,
    onOpenMedication: (String) -> Unit,
    permissionBanner: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val doses by vm.dosesForSelectedDate.collectAsState()
    val date by vm.selectedDate.collectAsState()
    val now by vm.now.collectAsState()
    val lowStock by vm.lowStockMedications.collectAsState()

    // 正在挪时间的那一次服药，null = 没在挪
    var rescheduling by remember { mutableStateOf<DoseItem?>(null) }

    LaunchedEffect(Unit) { vm.refreshNow() }


    val taken = doses.count { it.status == DoseStatus.TAKEN }
    val total = doses.size
    val pending = doses.filter { it.status == DoseStatus.PENDING && !it.isOverdue(now) }
    val overdue = doses.filter { it.status == DoseStatus.PENDING && it.isOverdue(now) }
    val done = doses.filter { it.status != DoseStatus.PENDING }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 备份横幅已删（用户要求），只剩提醒体检横幅
        permissionBanner?.let { banner ->
            item { banner() }
        }

        item {
            DateHeader(
                date = date,
                onPrev = { vm.selectDate(date.minusDays(1)) },
                onNext = { vm.selectDate(date.plusDays(1)) },
                onToday = { vm.selectDate(LocalDate.now()) }
            )
        }

        if (total > 0) {
            item { ProgressSummary(taken = taken, total = total) }
        }

        if (lowStock.isNotEmpty()) {
            item { LowStockBanner(lowStock) }
        }

        if (total == 0) {
            val noMedsAtAll = vm.activeMedications.value.isEmpty()
            item {
                EmptyState(
                    icon = Icons.Filled.Today,
                    title = if (noMedsAtAll) "还没有添加药品" else "这一天不用吃药",
                    subtitle = if (noMedsAtAll) "" else "按你设定的用药周期，今天没有需要服用的药"
                )
            }
            if (noMedsAtAll) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        TextButton(onClick = { vm.addSampleData() }) {
                            Text("先放两条示例看看效果")
                        }
                    }
                }
            }
        }

        if (overdue.isNotEmpty()) {
            item { GroupLabel("已错过", overdue.size, MaterialTheme.colorScheme.error) }
            items(overdue, key = { it.key }) { item ->
                DoseCard(
                    item, now,
                    modifier = Modifier.animateItem(
                        fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        fadeOutSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ),
                    onToggle = { vm.toggleTaken(item) },
                    onSkip = { vm.markSkipped(item) },
                    onOpen = { onOpenMedication(item.medication.id) },
                    onReschedule = { rescheduling = item },
                    onClearReschedule = { vm.clearDoseReschedule(item) }
                )
            }
        }

        if (pending.isNotEmpty()) {
            item { GroupLabel("待服用", pending.size, MaterialTheme.colorScheme.primary) }
            items(pending, key = { it.key }) { item ->
                DoseCard(
                    item, now,
                    modifier = Modifier.animateItem(
                        fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        fadeOutSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ),
                    onToggle = { vm.toggleTaken(item) },
                    onSkip = { vm.markSkipped(item) },
                    onOpen = { onOpenMedication(item.medication.id) },
                    onReschedule = { rescheduling = item },
                    onClearReschedule = { vm.clearDoseReschedule(item) }
                )
            }
        }

        if (done.isNotEmpty()) {
            item { GroupLabel("已完成", done.size, MaterialTheme.colorScheme.onSurfaceVariant) }
            items(done, key = { it.key }) { item ->
                DoseCard(
                    item, now,
                    modifier = Modifier.animateItem(
                        fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        fadeOutSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ),
                    onToggle = { vm.toggleTaken(item) },
                    onSkip = { vm.markSkipped(item) },
                    onOpen = { onOpenMedication(item.medication.id) },
                    onReschedule = { rescheduling = item },
                    onClearReschedule = { vm.clearDoseReschedule(item) }
                )
            }
        }

        item { Spacer(Modifier.height(72.dp)) }
    }

    // 临时改这次的时间。挪过的先给撤销选项，免得用户找不到怎么恢复。
    rescheduling?.let { item ->
        val base = item.effectiveTime
        TimePickerDialog(
            initialHour = base.hour,
            initialMinute = base.minute,
            title = "改这一次的时间",
            supportingText = if (item.movedTo != null)
                "原定 ${item.time.format()}，现在是 ${item.movedTo.format()}。"
            else "只改今天这一次。",
            onDismiss = { rescheduling = null },
            onConfirm = { h, m ->
                vm.rescheduleDose(item, com.jian.pillreminder.data.TimeOfDay(h, m))
                rescheduling = null
            }
        )
    }
}

@Composable
private fun DateHeader(
    date: LocalDate,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    val today = LocalDate.now()
    val label = when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        today.plusDays(1) -> "明天"
        else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.headlineSmall)
            Text(
                date.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (date != today) {
            TextButton(onClick = onToday) { Text("回到今天") }
        }
        IconButton(onClick = onPrev) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "前一天")
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "后一天")
        }
    }
}

@Composable
private fun ProgressSummary(taken: Int, total: Int) {
    val progress = if (total == 0) 0f else taken.toFloat() / total
    val animated by animateFloatAsState(progress, label = "todayProgress")
    val allDone = total > 0 && taken == total

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // 刻意不放 emoji：它由系统字体渲染，颜色和字重都跟不上主题，
                    // 在这张深色卡片上很突兀。文字本身已经说清楚了。
                    Text(
                        if (allDone) "今天的药都吃完了，安心休息" else "今日进度",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "$taken / $total 次已完成",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
        }
    }
}

@Composable
private fun LowStockBanner(meds: List<Medication>) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Inventory2, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("有药快用完了", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    meds.joinToString("、") {
                        "${it.name} 剩 ${Reminders.formatDosage(it.stockRemaining ?: 0.0)}${it.unit}"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun DoseCard(
    item: DoseItem,
    now: java.time.LocalDateTime,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onSkip: () -> Unit,
    onOpen: () -> Unit,
    onReschedule: () -> Unit,
    onClearReschedule: () -> Unit
) {
    val med = item.medication
    val palette = medColorAt(med.colorIndex)
    val isTaken = item.status == DoseStatus.TAKEN
    val isSkipped = item.status == DoseStatus.SKIPPED
    val isOverdue = item.isOverdue(now)
    // drawBehind 的 lambda 不是 Composable 上下文，颜色得先在这里取出来捕获
    val errorColor = MaterialTheme.colorScheme.error
    val primaryColor = MaterialTheme.colorScheme.primary
    // 容器色动画：打卡瞬间从 surfaceContainer 平滑过渡到完成态的
    // surfaceContainerLowest——原先是硬切，两色只差一级灰度看不出"渐变"，
    // 现在配 400ms tween，过渡本身成为完成反馈的一部分。
    val containerColor by animateColorAsState(
        targetValue = when {
            isTaken || isSkipped -> MaterialTheme.colorScheme.surfaceContainerLowest
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(400),
        label = "doseContainer"
    )

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        )
    ) {
        // 左边条状态色：错过 = error 红；已完成 = primary 主色。
        // 错过条常显；完成条随打卡动画出现（弹簧缩放从 0 长到全高）。
        val stripeColor = when {
            isOverdue -> errorColor
            isTaken || isSkipped -> primaryColor
            else -> androidx.compose.ui.graphics.Color.Transparent
        }
        val stripeScale by animateFloatAsState(
            targetValue = if (isTaken || isSkipped || isOverdue) 1f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "stripeScale"
        )
        Column(
            Modifier
                .drawBehind {
                    if (stripeScale > 0.01f) {
                        drawRoundRect(
                            color = stripeColor,
                            topLeft = Offset(0f, size.height * (1f - stripeScale) / 2f),
                            size = Size(5.dp.toPx(), size.height * stripeScale),
                            cornerRadius = CornerRadius(2.5.dp.toPx())
                        )
                    }
                }
                .clickable(onClick = onOpen)
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MedBadge(
                    iconIndex = med.iconIndex,
                    container = palette.container(),
                    content = palette.content(),
                    size = 46.dp,
                    modifier = Modifier.alpha(if (isTaken || isSkipped) 0.55f else 1f)
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            med.name.ifBlank { "未命名药品" },
                            style = MaterialTheme.typography.titleMedium,
                            textDecoration = if (isSkipped) TextDecoration.LineThrough else null,
                            color = if (isTaken || isSkipped)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface
                        )
                        // 示例药不会真的提醒，必须标出来，否则用户会以为提醒失灵了
                        if (med.isSample) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            ) {
                                Text(
                                    "示例",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    val dose = Reminders.formatDosage(med.dosage) + med.unit
                    val meal = if (med.mealRelation.label == "无要求") "" else " · ${med.mealRelation.label}"
                    // 挪过时间的把原定时刻划掉再写新的，一眼能看出这次是临时调整过的
                    Text(
                        buildString {
                            if (item.movedTo != null) {
                                append(item.movedTo.format())
                                append("（原 ")
                                append(item.time.format())
                                append("）")
                            } else {
                                append(item.time.format())
                            }
                            append(" · ")
                            append(dose)
                            append(meal)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (item.movedTo != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.clip(CircleShape).clickable(onClick = onToggle).padding(4.dp)
                ) {
                    CheckCircle(checked = isTaken)
                }
            }

            if (med.note.isNotBlank() && !isTaken && !isSkipped) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Text(
                        med.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            AnimatedVisibility(visible = item.status == DoseStatus.PENDING) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    // 挪过的给一个撤销入口，否则用户找不到怎么恢复
                    if (item.movedTo != null) {
                        TextButton(onClick = onClearReschedule) { Text("恢复原时间") }
                    }
                    // 「改时间」用图标按钮：三个文字按钮挤一行会太窄，
                    // 而挪时间是相对少用的操作
                    IconButton(onClick = onReschedule) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = "临时改这次的时间",
                            modifier = Modifier.size(20.dp),
                            tint = if (item.movedTo != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onSkip) {
                        Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("跳过")
                    }
                    Spacer(Modifier.width(4.dp))
                    FilledTonalButton(onClick = onToggle) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("已服用")
                    }
                }
            }

            if (isTaken && item.actedAtMillis != null) {
                Spacer(Modifier.height(4.dp))
                val t = java.time.Instant.ofEpochMilli(item.actedAtMillis)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                Text(
                    "已于 %02d:%02d 服用".format(t.hour, t.minute),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 60.dp)
                )
            }
            if (isSkipped) {
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已跳过",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 60.dp)
                    )
                    TextButton(onClick = onSkip) { Text("撤销") }
                }
            }
        }
    }
}
