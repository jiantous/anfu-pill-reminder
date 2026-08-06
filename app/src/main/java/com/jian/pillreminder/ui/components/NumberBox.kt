package com.jian.pillreminder.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign

/**
 * 只填数字的小输入框，单位写在框里（「20」+「日」）。
 *
 * 给日期和时间对话框共用。两处都是"填几个数字"，不该各写一遍：
 * 过滤非数字、限长、填满自动跳下一格这几件事很容易只在一处改对。
 *
 * @param unit 显示在数字右边的单位，比如「年」「月」「日」「时」「分」
 * @param maxLen 最多几位；填满后自动跳到下一格
 */
@Composable
internal fun NumberBox(
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    maxLen: Int,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    /** 最后一格不用跳，跳了会把焦点甩到按钮上 */
    autoAdvance: Boolean = true
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(maxLen)
            onValueChange(digits)
            if (autoAdvance && digits.length == maxLen) {
                focus.moveFocus(FocusDirection.Next)
            }
        },
        suffix = { Text(unit) },
        singleLine = true,
        isError = isError,
        textStyle = TextStyle(textAlign = TextAlign.End),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}
