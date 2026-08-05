package com.jian.pillreminder.ui.screens

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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.jian.pillreminder.data.Medication
import com.jian.pillreminder.domain.ScheduleEngine
import com.jian.pillreminder.notify.Reminders
import com.jian.pillreminder.ui.MedViewModel
import com.jian.pillreminder.ui.components.EmptyState
import com.jian.pillreminder.ui.components.MedBadge
import com.jian.pillreminder.ui.theme.medColorAt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationsScreen(
    vm: MedViewModel,
    onOpenMedication: (String) -> Unit
) {
    val meds by vm.activeMedications.collectAsState()
    val hasSample by vm.hasSampleData.collectAsState()
    var pendingDelete by remember { mutableStateOf<Medication?>(null) }
    var stockEditing by remember { mutableStateOf<Medication?>(null) }
    var pendingClearSamples by remember { mutableStateOf(false) }

    val active = meds.filterNot { it.archived }
    val archived = meds.filter { it.archived }

    if (meds.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Medication,
            title = "药箱是空的",
            subtitle = "点右下角的 + 添加药品，设置好剂量、时间和用药周期",
            modifier = Modifier.fillMaxSize().padding(top = 80.dp)
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (hasSample) {
            item { SampleNotice(onClear = { pendingClearSamples = true }) }
        }

        if (active.isNotEmpty()) {
            item {
                Text(
                    "在用药品 · ${active.size}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            items(active, key = { it.id }) { med ->
                MedicationCard(
                    med = med,
                    onClick = { onOpenMedication(med.id) },
                    onArchive = { vm.toggleArchived(med) },
                    onDelete = { pendingDelete = med },
                    onEditStock = { stockEditing = med }
                )
            }
        }

        if (archived.isNotEmpty()) {
            item {
                Text(
                    "已停用 · ${archived.size}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }
            items(archived, key = { it.id }) { med ->
                MedicationCard(
                    med = med,
                    onClick = { onOpenMedication(med.id) },
                    onArchive = { vm.toggleArchived(med) },
                    onDelete = { pendingDelete = med },
                    onEditStock = { stockEditing = med }
                )
            }
        }

        item { Spacer(Modifier.height(72.dp)) }
    }

    pendingDelete?.let { med ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除「${med.name}」？") },
            text = { Text("这会同时删掉它的所有服药记录，无法恢复。如果只是暂时停药，建议用「停用」。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteMedication(med)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    if (pendingClearSamples) {
        AlertDialog(
            onDismissRequest = { pendingClearSamples = false },
            title = { Text("清除示例药品？") },
            text = { Text("会删掉「维生素 D」和「降压药」这两条示例，以及它们的打卡记录。你自己添加的药不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearSampleData()
                    pendingClearSamples = false
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearSamples = false }) { Text("取消") }
            }
        )
    }

    stockEditing?.let { med ->
        StockDialog(
            med = med,
            onDismiss = { stockEditing = null },
            onSave = { stock, threshold ->
                vm.setStock(med, stock, threshold)
                stockEditing = null
            }
        )
    }
}

/** 提示列表里混着示例数据，并给一键清除入口。 */
@Composable
private fun SampleNotice(onClear: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("列表里有示例药品", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    "示例只用于演示界面，不会真的提醒你吃药。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onClear) { Text("清除") }
        }
    }
}

/** 药名后面的「示例」小标签。 */
@Composable
private fun SampleTag() {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MedicationCard(
    med: Medication,
    onClick: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onEditStock: () -> Unit
) {
    val palette = medColorAt(med.colorIndex)
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        shape = MaterialTheme.shapes.large,
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.alpha(if (med.archived) 0.6f else 1f)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MedBadge(
                    iconIndex = med.iconIndex,
                    container = palette.container(),
                    content = palette.content(),
                    size = 46.dp
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            med.name.ifBlank { "未命名药品" },
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (med.isSample) {
                            Spacer(Modifier.width(6.dp))
                            SampleTag()
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${Reminders.formatDosage(med.dosage)}${med.unit} · ${ScheduleEngine.describeSchedule(med)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("管理库存") },
                            leadingIcon = { Icon(Icons.Filled.Inventory2, null) },
                            onClick = { menuOpen = false; onEditStock() }
                        )
                        DropdownMenuItem(
                            text = { Text(if (med.archived) "恢复用药" else "停用（保留记录）") },
                            leadingIcon = {
                                Icon(
                                    if (med.archived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                                    null
                                )
                            },
                            onClick = { menuOpen = false; onArchive() }
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                            },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 8.dp)
            ) {
                med.times.take(4).forEach { t ->
                    AssistChip(
                        onClick = onClick,
                        label = { Text(t.format(), style = MaterialTheme.typography.labelMedium) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = palette.container().copy(alpha = 0.5f),
                            labelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = null
                    )
                }
                if (med.times.size > 4) {
                    Text(
                        "+${med.times.size - 4}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (!med.remindersEnabled) {
                    Icon(
                        Icons.Filled.NotificationsOff,
                        contentDescription = "已关闭提醒",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp).size(18.dp)
                    )
                }
            }

            med.stockRemaining?.let { remaining ->
                Spacer(Modifier.height(12.dp))
                val low = remaining <= med.stockThreshold
                // 以"阈值的 4 倍"作为满格参考，纯展示用
                val full = (med.stockThreshold * 4).coerceAtLeast(1.0)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Inventory2,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (low) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "剩 ${Reminders.formatDosage(remaining)}${med.unit}" +
                            if (low) " · 该续药了" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (low) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (remaining / full).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp)
                        .height(6.dp)
                        .clip(CircleShape),
                    color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
            }
        }
    }
}

@Composable
private fun StockDialog(
    med: Medication,
    onDismiss: () -> Unit,
    onSave: (Double?, Double?) -> Unit
) {
    var stockText by remember {
        mutableStateOf(med.stockRemaining?.let { Reminders.formatDosage(it) } ?: "")
    }
    var thresholdText by remember { mutableStateOf(Reminders.formatDosage(med.stockThreshold)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${med.name} · 库存") },
        text = {
            Column {
                Text(
                    "每次标记「已服用」会自动扣掉 ${Reminders.formatDosage(med.dosage)}${med.unit}。留空表示不管库存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = stockText,
                    onValueChange = { stockText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("现有数量") },
                    suffix = { Text(med.unit) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = thresholdText,
                    onValueChange = { thresholdText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("低于多少时提醒续药") },
                    suffix = { Text(med.unit) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(stockText.toDoubleOrNull(), thresholdText.toDoubleOrNull())
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
