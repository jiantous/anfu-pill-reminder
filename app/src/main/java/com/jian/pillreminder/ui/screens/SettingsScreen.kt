package com.jian.pillreminder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.jian.pillreminder.data.SNOOZE_OPTIONS

// 档位表在 data.SNOOZE_OPTIONS，和默认值放在一起，避免两处不同步。

/** CSV 导出的时间范围选项。null = 全部历史。 */
private val CsvRanges: List<Pair<Int?, String>> =
    listOf(7 to "近 7 天", 30 to "近 30 天", 90 to "近 90 天", null to "全部")

/**
 * 设置页。
 *
 * 刻意做成无状态的：所有值和回调都从外面传进来，Intent 与 ViewModel 的活
 * 全在 MainActivity 里干——和 BackupScreen 一个路子。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    snoozeMinutes: Int,
    ongoingNotification: Boolean,
    logCount: Int,
    busy: Boolean,
    message: String?,
    onSnoozeMinutesChange: (Int) -> Unit,
    onOngoingNotificationChange: (Boolean) -> Unit,
    onExportCsv: (Int?) -> Unit,
    onShareCsv: (Int?) -> Unit,
    onOpenReminderSetup: () -> Unit,
    onClearMessage: () -> Unit,
    onBack: () -> Unit
) {
    var csvRangeIndex by remember { mutableStateOf(1) }
    val selectedRange = CsvRanges[csvRangeIndex].first

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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

            // ---- 提醒 ----
            SettingsSection("提醒") {
                SwitchRow(
                    title = "提醒保留到确认",
                    checked = ongoingNotification,
                    onCheckedChange = onOngoingNotificationChange
                )
                Spacer(Modifier.height(20.dp))
                Text("稍后提醒", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SNOOZE_OPTIONS.forEach { m ->
                        FilterChip(
                            selected = snoozeMinutes == m,
                            onClick = { onSnoozeMinutesChange(m) },
                            label = { Text("$m 分钟") }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onOpenReminderSetup,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text("检查提醒功能是否正常") }
            }

            // ---- 数据 ----
            SettingsSection("数据") {
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CsvRanges.forEachIndexed { i, (_, label) ->
                        FilterChip(
                            selected = csvRangeIndex == i,
                            onClick = { csvRangeIndex = i },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { onExportCsv(selectedRange) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("另存为")
                    }
                    OutlinedButton(
                        onClick = { onShareCsv(selectedRange) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("分享")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "目前有 $logCount 条打卡记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 「备份」和「关于」的入口在三点菜单里，这里不再放一份：
            // 同一个页面两个入口，用户会以为是两个不同的东西。

            Spacer(Modifier.height(32.dp))
        }
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = onClearMessage,
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = onClearMessage) { Text("好") } }
        )
    }
}

/** 标题 + 卡片 + 间距，沿用备份页的节奏。 */
@Composable
private fun SettingsSection(
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

/** 左侧标题+说明，右侧开关。 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
