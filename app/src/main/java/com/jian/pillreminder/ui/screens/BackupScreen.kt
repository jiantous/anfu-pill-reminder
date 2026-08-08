package com.jian.pillreminder.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jian.pillreminder.data.BackupSummary
import com.jian.pillreminder.data.ImportMode

/**
 * 备份与恢复页。
 *
 * 设计取向：不做云账号，把备份文件交给用户自己掌控（存云盘同步目录 / 发给自己）。
 * 好处是不依赖任何服务器、健康数据不经第三方，代价是需要用户主动导出一次。
 *
 * 只有两个按钮：备份、恢复。
 * 曾经把"选文件夹"和"备份到这个文件夹"拆成两步——先配置一次文件夹，之后才能备份。
 * 那等于要求用户先理解"这个 App 记住了一个文件夹"才会用，而且换位置还得再找一个
 * 小按钮。现在点一次「备份到本地」就走完：弹系统选择器 → 选位置 → 立即写入。
 * 想换地方，下次点同一个按钮时在选择器里换个目录就行，系统本身会记住上次去过哪。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    lastBackupDate: String?,
    daysSinceBackup: Long?,
    medicationCount: Int,
    logCount: Int,
    busy: Boolean,
    message: String?,
    onBackup: () -> Unit,
    onImport: () -> Unit,
    onClearMessage: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                // 和三点菜单里的入口同名，别一个叫"备份"另一个叫"备份与换机"
                title = { Text("备份") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
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
            Spacer(Modifier.height(8.dp))

            // ---- 当前状态 ----
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("当前数据", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    InfoRow("药品", "$medicationCount 种")
                    InfoRow("服药记录", "$logCount 条")
                    Spacer(Modifier.height(16.dp))
                    InfoRow(
                        "上次备份",
                        when {
                            lastBackupDate == null -> "还没备份过"
                            daysSinceBackup == 0L -> "今天"
                            daysSinceBackup != null -> "$lastBackupDate（$daysSinceBackup 天前）"
                            else -> lastBackupDate
                        },
                        highlight = lastBackupDate == null || (daysSinceBackup ?: 0) > 30
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // 合并到一个卡片，不分"导出"和"导入"两个标题。
            // 备份和恢复是一件事的正反两面。
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(
                        "备份 JSON 格式文件存档，导入可恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = onBackup,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Icon(Icons.Filled.CloudUpload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("备份到本地")
                    }

                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = onImport,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("选择备份文件并恢复")
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // 操作结果提示
    message?.let { msg ->
        AlertDialog(
            onDismissRequest = onClearMessage,
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = onClearMessage) { Text("好") } }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = if (highlight) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 导入前的确认弹窗：让用户看清备份内容再决定覆盖还是合并。 */
@Composable
fun ImportConfirmDialog(
    summary: BackupSummary,
    currentMedCount: Int,
    currentLogCount: Int,
    onConfirm: (ImportMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("要怎么恢复？") },
        text = {
            Column {
                Text("备份文件", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "导出于 ${summary.exportedAt}\n" +
                        "${summary.medicationCount} 种药 · ${summary.logCount} 条记录",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (summary.medicationNames.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        summary.medicationNames.take(6).joinToString("、") +
                            if (summary.medicationNames.size > 6) " 等" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text("这台手机现在", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "$currentMedCount 种药 · $currentLogCount 条记录",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    if (currentMedCount == 0 && currentLogCount == 0)
                        "这台手机还没有数据，建议直接「完全覆盖」。"
                    else
                        "「完全覆盖」会丢弃这台手机上现有的数据；「合并」两边都保留，同一次服药以时间较新的为准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(ImportMode.REPLACE) }) { Text("完全覆盖") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { onConfirm(ImportMode.MERGE) }) { Text("合并") }
            }
        }
    )
}

/** 首页横幅：很久没备份时提醒。 */
@Composable
fun BackupReminderBanner(
    days: Long?,
    onOpenBackup: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CloudUpload, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (days == null) "还没备份过数据" else "上次备份是 $days 天前",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("不再提示") }
                Spacer(Modifier.width(4.dp))
                FilledTonalButton(onClick = onOpenBackup) { Text("去备份") }
            }
        }
    }
}
