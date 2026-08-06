package com.jian.pillreminder.ui.components

import androidx.compose.foundation.layout.Arrangement
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
import java.time.LocalDate

/**
 * 填日期的对话框：年 / 月 / 日 三个数字框，单位直接印在框里。
 *
 * **刻意不用日历控件**。Material3 的 DatePicker 在中文环境下七列表头会全部
 * 显示成同一个字（它取「星期一」这种 SHORT 格式再截首字，于是全是「星」），
 * 而表头不可定制；自己画一个月视图也验证过，但对"住院到 8 月 20 日"这种
 * 已经知道确切日期的场景，翻月点格子反而比直接打三个数字慢。
 *
 * 校验在确定时做，不在输入中途拦：边打边报错（比如月份打到「1」就说超范围）
 * 很烦人。
 */
@Composable
fun DatePickerDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
    title: String = "填写日期",
    supportingText: String? = null,
    /** 可选的最早日期（含）。 */
    minDate: LocalDate? = null,
    /** 可选的最晚日期（含）。 */
    maxDate: LocalDate? = null
) {
    var year by remember { mutableStateOf(initialDate.year.toString()) }
    var month by remember { mutableStateOf(initialDate.monthValue.toString()) }
    var day by remember { mutableStateOf(initialDate.dayOfMonth.toString()) }

    // 合法就是日期本身，不合法是 null——错误提示和确定按钮都看它
    val parsed = remember(year, month, day) {
        runCatching {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
        }.getOrNull()
    }
    val error: String? = when {
        year.isEmpty() || month.isEmpty() || day.isEmpty() -> null // 还没填完，先不报错
        parsed == null -> "这一天不存在，请检查"
        minDate != null && parsed.isBefore(minDate) ->
            "不能早于 ${minDate.monthValue} 月 ${minDate.dayOfMonth} 日"
        maxDate != null && parsed.isAfter(maxDate) ->
            "不能晚于 ${maxDate.monthValue} 月 ${maxDate.dayOfMonth} 日"
        else -> null
    }
    val valid = parsed != null && error == null

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
                        value = year,
                        onValueChange = { year = it },
                        unit = "年",
                        maxLen = 4,
                        isError = error != null,
                        modifier = Modifier.weight(1.35f)
                    )
                    Spacer(Modifier.width(8.dp))
                    NumberBox(
                        value = month,
                        onValueChange = { month = it },
                        unit = "月",
                        maxLen = 2,
                        isError = error != null,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    NumberBox(
                        value = day,
                        onValueChange = { day = it },
                        unit = "日",
                        maxLen = 2,
                        isError = error != null,
                        autoAdvance = false,
                        modifier = Modifier.weight(1f)
                    )
                }
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
                onClick = { parsed?.let(onConfirm) },
                enabled = valid
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
