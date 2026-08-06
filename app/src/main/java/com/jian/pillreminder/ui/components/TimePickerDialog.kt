package com.jian.pillreminder.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 填时刻的对话框：时 / 分两个数字框，单位印在框里。
 *
 * 有两处要用（编辑药品设服药时间、今日清单里把某一次临时挪走），共用一套，
 * 免得出现第二条选时间的代码路径。
 *
 * **刻意不用表盘控件**：设「08:30」这种确切时刻，转表盘要点两次还容易点偏，
 * 直接打四个数字更快。
 */
@Composable
fun TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
    title: String = "填写服药时间",
    /** 额外说明，比如"只改今天这一次"。 */
    supportingText: String? = null
) {
    // 补零显示，和界面上其它地方的 08:30 一致
    var hour by remember { mutableStateOf("%02d".format(initialHour)) }
    var minute by remember { mutableStateOf("%02d".format(initialMinute)) }

    val h = hour.toIntOrNull()
    val m = minute.toIntOrNull()
    val error: String? = when {
        hour.isEmpty() || minute.isEmpty() -> null // 还没填完，先不报错
        h == null || h > 23 -> "小时填 0 到 23"
        m == null || m > 59 -> "分钟填 0 到 59"
        else -> null
    }
    val valid = h != null && m != null && error == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (supportingText != null) {
                    Text(
                        supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberBox(
                        value = hour,
                        onValueChange = { hour = it },
                        unit = "时",
                        maxLen = 2,
                        isError = error != null,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    NumberBox(
                        value = minute,
                        onValueChange = { minute = it },
                        unit = "分",
                        maxLen = 2,
                        isError = error != null,
                        autoAdvance = false,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    // 24 小时制不是所有人都习惯，把"下午 8 点"这种说法也写出来
                    if (valid) "也就是 ${describeClock(h!!, m!!)}" else "24 小时制，例如 20 时 30 分",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onConfirm(h!!, m!!) },
                enabled = valid
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 把 24 小时制说成日常说法，用来确认没填错半天。 */
private fun describeClock(hour: Int, minute: Int): String {
    val period = when (hour) {
        0, in 1..4 -> "凌晨"
        in 5..8 -> "早上"
        in 9..11 -> "上午"
        12 -> "中午"
        in 13..17 -> "下午"
        else -> "晚上"
    }
    val h12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "$period $h12 点 %02d 分".format(minute)
}
