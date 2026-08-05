package com.jian.pillreminder.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jian.pillreminder.data.BackupSummary
import com.jian.pillreminder.data.ImportMode

/**
 * 备份与恢复页。
 *
 * 设计取向：不做云账号，把备份文件交给用户自己掌控（存云盘同步目录 / 发给自己）。
 * 好处是不依赖任何服务器、健康数据不经第三方，代价是需要用户主动导出一次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    lastBackupDate: String?,
    daysSinceBackup: Long?,
    folderName: String?,
    medicationCount: Int,
    logCount: Int,
    busy: Boolean,
    message: String?,
    onPickFolder: () -> Unit,
    onExportToFolder: () -> Unit,
    onExportToFile: () -> Unit,
    onShare: () -> Unit,
    onImport: () -> Unit,
    onClearMessage: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("备份与换机") },
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
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
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
                    InfoRow("备份文件夹", folderName ?: "未设置")
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---- 导出 ----
            SectionTitle("导出备份")
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (folderName == null) {
                        Text(
                            "先选一个文件夹放备份。建议选微云 / 百度网盘 / OneDrive 等云盘 App 的自动同步目录，" +
                                "这样每次导出后云盘会自己上传，换手机时直接从云盘取。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = onPickFolder,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Icon(Icons.Filled.Folder, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("选择备份文件夹")
                        }
                    } else {
                        Text(
                            "导出后会在「$folderName」里生成一个备份文件，同名旧文件会被替换。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = onExportToFolder,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Icon(Icons.Filled.CloudUpload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("备份到这个文件夹")
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onPickFolder, enabled = !busy) {
                            Text("换一个文件夹")
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "其它导出方式",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onExportToFile,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("另存为…") }
                        OutlinedButton(
                            onClick = onShare,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Share, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("分享")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "「分享」可以直接发到微信收藏或发给自己，也是一种备份。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---- 导入 ----
            SectionTitle("从备份恢复")
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "换了新手机：装好安服后点下面按钮，选中云盘里的备份文件即可恢复。" +
                            "恢复前会先告诉你备份里有什么，由你决定覆盖还是合并。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
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

            Spacer(Modifier.height(20.dp))

            // ---- 说明 ----
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(14.dp)) {
                    Icon(Icons.Filled.Info, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("关于数据安全", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "备份文件是普通文本，不加密，能直接打开看。里面只有你的药品和服药记录，" +
                                "不包含任何账号信息。它不会自动上传到任何服务器——存到哪、给谁看，完全由你决定。",
                            style = MaterialTheme.typography.bodySmall
                        )
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
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
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
            Text(
                "导出一份备份，换手机或手机丢了都能恢复。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("不再提示") }
                Spacer(Modifier.width(4.dp))
                FilledTonalButton(onClick = onOpenBackup) { Text("去备份") }
            }
        }
    }
}
