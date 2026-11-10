package com.jian.pillreminder.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * M3E 波浪形线性进度条——薄封装官方 LinearWavyProgressIndicator
 * （material3 1.5.0，随 BOM 收编后此版本钉定可移除）。
 *
 * 三处调用（今日进度/统计完成度/库存余量）全是确定值，走 determinate
 * 重载。包一层是为了：①收拢 @OptIn 标注；②官方 API 的默认样式
 * （波幅/轨道粗细/停顿点）一处可调；③将来官方调整签名时只改这里。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WavyLinearProgress(
    /** 0f..1f，超出会被官方组件夹取。 */
    progress: Float,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color,
    trackColor: androidx.compose.ui.graphics.Color
) {
    LinearWavyProgressIndicator(
        progress = { progress },
        modifier = modifier.fillMaxWidth(),
        color = color,
        trackColor = trackColor
    )
}
