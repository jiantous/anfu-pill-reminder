package com.jian.pillreminder.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas

// 药品图标集见 MedIconSet.kt（按剂型分类的手绘矢量）

/** 圆形的药品图标徽标。 */
@Composable
fun MedBadge(
    iconIndex: Int,
    container: Color,
    content: Color,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = medIconAt(iconIndex),
            contentDescription = null,
            tint = content,
            // 0.55 是当初配 Material 线稿图标定的——那些图标画满整个 24 视口。
            // 现在这套手绘图标主体只占约 18/24（四周留气口），两层缩小叠加后
            // 实机上图标缩成一小点、圆底一大圈空白。按 24/18 折算补回来。
            modifier = Modifier.size(size * 0.74f)
        )
    }
}

/** 分区小标题。 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
    )
}

/** 空状态占位。 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(88.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** 圆环进度，用于依从率。 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 12.dp,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    color: Color = MaterialTheme.colorScheme.primary,
    center: @Composable () -> Unit = {}
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(700),
        label = "ring"
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 必须 fillMaxSize：只给宽度时 Canvas 高度会塌成 0，圆环画不出来
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val diameter = kotlin.math.min(size.width, size.height) - stroke
            val topLeft = androidx.compose.ui.geometry.Offset(
                (size.width - diameter) / 2f,
                (size.height - diameter) / 2f
            )
            val arcSize = Size(diameter, diameter)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        center()
    }
}

/** 小圆形勾选按钮，用于"已服用"。 */
@Composable
fun CheckCircle(
    checked: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    checkedColor: Color = MaterialTheme.colorScheme.primary,
    uncheckedBorder: Color = MaterialTheme.colorScheme.outline
) {
    val bg by animateColorAsState(
        if (checked) checkedColor else Color.Transparent,
        label = "checkBg"
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .then(
                if (checked) Modifier
                else Modifier.border(width = 2.dp, color = uncheckedBorder, shape = CircleShape)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已服用",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(size * 0.6f)
            )
        }
    }
}

/** 一行水平柱状图，用于每周依从率。 */
@Composable
fun MiniBarChart(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        values.forEachIndexed { i, v ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(trackColor),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    val h = (88 * v.coerceIn(0f, 1f)).dp
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (h < 4.dp && v > 0f) 4.dp else h)
                            .clip(RoundedCornerShape(8.dp))
                            .background(barColor)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    labels.getOrElse(i) { "" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun VerticalSpacer(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun HorizontalSpacer(width: Dp) = Spacer(Modifier.width(width))
