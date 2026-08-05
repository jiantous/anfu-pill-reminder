package com.jian.pillreminder.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 药品图标集：按**剂型**分类，手绘矢量。
 *
 * 为什么不用 Material 现成图标：那套里「药片/胶囊/滴剂」是剂型、
 * 「心脏/血液/保健」是适应症，两种分类逻辑混在一起，用户没有一致的判断依据，
 * 而且通用图标看不出"是哪种药"。
 *
 * 为什么按剂型而不按适应症：加药时手里拿着实物，"这是胶囊还是片"一眼可判；
 * "这算心脏类还是血液类"要想。剂型还与剂量单位天然对应，可据此自动推荐
 * （见 [suggestIconForUnit]）。按适应症分类带医疗判断成分，不该由 App 替用户下。
 *
 * ## 视觉语言：与启动图标（斜置双色胶囊）保持一套
 *
 * 启动图标是实心双色、圆润、无描边、无渐变。所以这里也一样：
 *   - **实心填充**，不用描边（早先画成线稿，和启动图标不是一套）
 *   - **双色**：主体 [MAIN] 不透明，次要块面 [SUB] 半透明，靠同一个 tint 出层次
 *   - 分隔缝用**留白间隙**表达，呼应启动图标胶囊上那道浅色中缝
 *   - 24x24 视口，主体控制在 2.4..21.6，四周留气口
 */
private const val VIEWPORT = 24f

/** 主体块面：完全不透明。 */
private const val MAIN = 1.0f

/** 次要块面：半透明，与启动图标里鼠尾草绿相对白色的明度关系近似。 */
private const val SUB = 0.42f

/** 分隔缝宽度。两块之间留出它，就成了启动图标上那道中缝。 */
private const val SEAM = 0.9f

private fun medIcon(
    name: String,
    block: ImageVector.Builder.() -> ImageVector.Builder
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = VIEWPORT,
    viewportHeight = VIEWPORT
).block().build()

/** 实心块面。[alpha] 用 [MAIN] 或 [SUB]。 */
private fun ImageVector.Builder.solid(
    alpha: Float = MAIN,
    evenOdd: Boolean = false,
    pathBuilder: PathBuilder.() -> Unit
) = path(
    fill = SolidColor(Color.Black),
    fillAlpha = alpha,
    pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
    pathBuilder = pathBuilder
)

// ---- 画形状的小工具 ----

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx, cy - r)
    arcToRelative(r, r, 0f, true, true, 0f, 2 * r)
    arcToRelative(r, r, 0f, true, true, 0f, -2 * r)
    close()
}

/** 四角全圆的矩形。 */
private fun PathBuilder.roundRect(x0: Float, y0: Float, x1: Float, y1: Float, r: Float) {
    moveTo(x0 + r, y0)
    lineTo(x1 - r, y0)
    arcToRelative(r, r, 0f, false, true, r, r)
    lineTo(x1, y1 - r)
    arcToRelative(r, r, 0f, false, true, -r, r)
    lineTo(x0 + r, y1)
    arcToRelative(r, r, 0f, false, true, -r, -r)
    lineTo(x0, y0 + r)
    arcToRelative(r, r, 0f, false, true, r, -r)
    close()
}

/** 只有上面两角圆的矩形。 */
private fun PathBuilder.roundTop(x0: Float, y0: Float, x1: Float, y1: Float, r: Float) {
    moveTo(x0, y1)
    lineTo(x0, y0 + r)
    arcToRelative(r, r, 0f, false, true, r, -r)
    lineTo(x1 - r, y0)
    arcToRelative(r, r, 0f, false, true, r, r)
    lineTo(x1, y1)
    close()
}

/** 只有下面两角圆的矩形。 */
private fun PathBuilder.roundBottom(x0: Float, y0: Float, x1: Float, y1: Float, r: Float) {
    moveTo(x0, y0)
    lineTo(x1, y0)
    lineTo(x1, y1 - r)
    arcToRelative(r, r, 0f, false, true, -r, r)
    lineTo(x0 + r, y1)
    arcToRelative(r, r, 0f, false, true, -r, -r)
    close()
}

/**
 * 横向胶囊的左半（圆头在左，直边在 [xm]）。
 * [cx] 是左侧圆头的圆心 x，胶囊左端到 cx - r。
 */
private fun PathBuilder.capsuleLeft(cx: Float, cy: Float, r: Float, xm: Float) {
    moveTo(xm, cy - r)
    lineTo(cx, cy - r)
    arcToRelative(r, r, 0f, true, false, 0f, 2 * r)
    lineTo(xm, cy + r)
    close()
}

/** 横向胶囊的右半（圆头在右，直边在 [xm]）。 */
private fun PathBuilder.capsuleRight(cx: Float, cy: Float, r: Float, xm: Float) {
    moveTo(xm, cy - r)
    lineTo(cx, cy - r)
    arcToRelative(r, r, 0f, true, true, 0f, 2 * r)
    lineTo(xm, cy + r)
    close()
}

// ---- 0. 圆片：实心圆 + 横向中缝 ----
//
// 中缝取横向：真实药片的压痕就是横的，而且胶囊(1)与椭圆片(2)都是左右分色，
// 圆片再用纵缝，三个挨在一起时区分度太低。
private val IcTablet: ImageVector = medIcon("MedTablet") {
    // 上半：从左端点沿上侧弧走到右端点（sweep=true 向上凸）
    solid(SUB) {
        moveTo(3.6f, 12f - SEAM / 2)
        arcTo(8.4f, 8.4f, 0f, true, true, 20.4f, 12f - SEAM / 2)
        close()
    }
    // 下半
    solid(MAIN) {
        moveTo(3.6f, 12f + SEAM / 2)
        arcTo(8.4f, 8.4f, 0f, true, false, 20.4f, 12f + SEAM / 2)
        close()
    }
}

// ---- 1. 胶囊：斜置双色，直接对应启动图标 ----
private val IcCapsule: ImageVector = medIcon("MedCapsule") {
    group(rotate = -32f, pivotX = 12f, pivotY = 12f) {
        solid(MAIN) { capsuleLeft(cx = 7.6f, cy = 12f, r = 4.4f, xm = 12f - SEAM / 2) }
        solid(SUB) { capsuleRight(cx = 16.4f, cy = 12f, r = 4.4f, xm = 12f + SEAM / 2) }
    }
}

// ---- 2. 椭圆片（糖衣片）：扁长、水平，靠形状与胶囊区分 ----
private val IcOblong: ImageVector = medIcon("MedOblong") {
    solid(MAIN) { capsuleLeft(cx = 6.4f, cy = 12f, r = 3.6f, xm = 12f - SEAM / 2) }
    solid(SUB) { capsuleRight(cx = 17.6f, cy = 12f, r = 3.6f, xm = 12f + SEAM / 2) }
}

// ---- 3. 颗粒/粉剂：药袋，上下封口 + 袋内颗粒（挖空）----
private val IcGranule: ImageVector = medIcon("MedGranule") {
    // 上封口
    solid(SUB) { roundTop(6.2f, 3.2f, 17.8f, 6.3f, 1.4f) }
    // 袋身，用 EvenOdd 把颗粒挖成空洞
    solid(MAIN, evenOdd = true) {
        roundBottom(6.2f, 6.3f + SEAM, 17.8f, 20.8f, 1.4f)
        circle(10.2f, 11.4f, 1.15f)
        circle(14.1f, 13.4f, 1.15f)
        circle(11.1f, 16.4f, 1.15f)
    }
}

// ---- 4. 口服液：宽口直筒瓶，液面把瓶身分成双色 ----
//
// 三个"容器类"图标靠剪影区分：这里是**宽直筒 + 宽瓶盖**，
// 软膏(8)是上宽下窄的梯形，喷雾(7)是带侧喷嘴的 L 形。
private val IcSyrup: ImageVector = medIcon("MedSyrup") {
    // 瓶盖：比瓶身略窄一点，但明显比软膏的盖宽
    solid(MAIN) { roundRect(8.8f, 2.4f, 15.2f, 5.2f, 0.8f) }
    // 瓶身上半（空的部分）
    solid(SUB) { roundTop(7.0f, 5.2f + SEAM, 17.0f, 13.2f, 1.6f) }
    // 液体
    solid(MAIN) { roundBottom(7.0f, 13.2f + SEAM, 17.0f, 21.4f, 1.8f) }
}

// ---- 5. 滴剂：滴管（副色）+ 液滴（主色）----
private val IcDrops: ImageVector = medIcon("MedDrops") {
    // 管帽
    solid(MAIN) { roundRect(9.6f, 2.4f, 14.4f, 4.6f, 0.8f) }
    // 管身，下端收圆
    solid(SUB) { roundBottom(9.6f, 4.6f + SEAM, 14.4f, 13.4f, 2.4f) }
    // 液滴：上尖下圆
    solid(MAIN) {
        moveTo(12f, 15.4f)
        curveTo(14f, 18f, 14.9f, 19.1f, 14.9f, 20.2f)
        arcToRelative(2.9f, 2.9f, 0f, true, true, -5.8f, 0f)
        curveTo(9.1f, 19.1f, 10f, 18f, 12f, 15.4f)
        close()
    }
}

// ---- 6. 注射：斜置针管，管身副色、推杆与针头主色 ----
private val IcInjection: ImageVector = medIcon("MedInjection") {
    group(rotate = -45f, pivotX = 12f, pivotY = 12f) {
        // 针头
        solid(MAIN) { roundRect(2.6f, 11.3f, 7.2f, 12.7f, 0.7f) }
        // 管身
        solid(SUB) { roundRect(7.2f, 9.4f, 16.6f, 14.6f, 1.2f) }
        // 推杆 + 尾翼。尾翼刻意窄（宽 1.6、高 5.0）：
        // 早先做到 7.2 高，剪影像把锤子，而且小尺寸下反而糊掉。
        solid(MAIN) {
            roundRect(16.6f, 11.0f, 20.4f, 13.0f, 0.7f)
            roundRect(20.4f, 9.5f, 22.0f, 14.5f, 0.8f)
        }
    }
}

// ---- 7. 喷雾：瓶身 + 一体式扳机喷头（主色）+ 雾点（副色）----
//
// 关键是轮廓要一眼区别于口服液(4)和软膏(8)——它们在小尺寸下都是"一个瓶子"。
// 办法：喷头做成向右伸出的一整块（连着瓶口，不是浮在上方的碎块），
// 让整体剪影呈"L 形"，加上右侧渐小的雾点，缩到 25px 也认得出。
private val IcSpray: ImageVector = medIcon("MedSpray") {
    // 瓶身（偏左，给喷头留位置）
    solid(MAIN) { roundRect(4.2f, 10.2f, 11.6f, 21.6f, 1.8f) }
    // 瓶口 + 向右伸出的喷嘴，连成一块 L 形
    solid(MAIN) {
        moveTo(6.4f, 4.6f)
        lineTo(10.0f, 4.6f)
        lineTo(10.0f, 6.0f)
        lineTo(14.2f, 6.0f)
        lineTo(14.2f, 8.4f)
        lineTo(10.0f, 8.4f)
        lineTo(10.0f, 10.2f)
        lineTo(6.4f, 10.2f)
        close()
    }
    // 雾点：与喷嘴同高，向右渐远渐小
    solid(SUB) {
        circle(16.8f, 7.2f, 1.45f)
        circle(19.7f, 7.2f, 1.05f)
        circle(21.9f, 7.2f, 0.7f)
    }
}

// ---- 8. 软膏：上宽下窄的软管 + 压平封口 ----
//
// 口服液(4)是"直筒瓶 + 小盖"，软膏必须换个剪影才不会撞车：
// 管身做成上宽下窄的梯形（挤过的软管就是这形状），底部一道压平封口。
// 这两个特征在小尺寸下仍然读得出来。
private val IcOintment: ImageVector = medIcon("MedOintment") {
    solid(MAIN) {
        // 盖
        roundRect(10.2f, 2.4f, 13.8f, 5.0f, 0.7f)
        // 颈
        roundRect(10.9f, 5.0f, 13.1f, 6.8f, 0.4f)
    }
    // 管身：上宽下窄的梯形，上缘两角圆（r=1.4）
    solid(SUB) {
        moveTo(7.8f, 7.7f)
        lineTo(16.2f, 7.7f)
        arcToRelative(1.4f, 1.4f, 0f, false, true, 1.4f, 1.4f)
        lineTo(15.4f, 18.0f)
        lineTo(8.6f, 18.0f)
        lineTo(6.4f, 9.1f)
        arcToRelative(1.4f, 1.4f, 0f, false, true, 1.4f, -1.4f)
        close()
    }
    // 底部压平封口
    solid(MAIN) { roundRect(8.0f, 18.0f + SEAM, 16.0f, 21.4f, 0.7f) }
}

// ---- 9. 贴剂：外圈副色，中间药垫主色 ----
private val IcPatch: ImageVector = medIcon("MedPatch") {
    solid(SUB, evenOdd = true) {
        roundRect(3.8f, 3.8f, 20.2f, 20.2f, 2.6f)
        roundRect(8.2f - SEAM, 8.2f - SEAM, 15.8f + SEAM, 15.8f + SEAM, 1.6f)
    }
    solid(MAIN) { roundRect(8.2f, 8.2f, 15.8f, 15.8f, 1.2f) }
}

/**
 * 可选的药品图标，索引存进 `Medication.iconIndex`。
 *
 * **顺序一经发布不可调整**：已保存的药品存的是下标，改顺序会让存量数据图标错位。
 * 要加新图标就往末尾追加。
 */
val MedIcons: List<Pair<String, ImageVector>> = listOf(
    "圆片" to IcTablet,        // 0
    "胶囊" to IcCapsule,       // 1
    "椭圆片" to IcOblong,      // 2
    "颗粒" to IcGranule,       // 3
    "口服液" to IcSyrup,       // 4
    "滴剂" to IcDrops,         // 5
    "注射" to IcInjection,     // 6
    "喷雾" to IcSpray,         // 7
    "软膏" to IcOintment,      // 8
    "贴剂" to IcPatch          // 9
)

fun medIconAt(index: Int): ImageVector = MedIcons[index.mod(MedIcons.size)].second

// 旧图标表到本表的下标映射见 data/IconMigration.kt
// （放在 data 层：迁移是数据层的事，ui 不该被 data 反向依赖）

/**
 * 按剂量单位推荐图标下标，用户没手动选过时用它当默认。
 * 拿不准就回退到圆片（0），不硬猜。
 */
fun suggestIconForUnit(unit: String): Int = when (unit.trim().lowercase()) {
    "片" -> 0
    "粒", "颗", "丸" -> 1
    "袋", "包", "克", "g" -> 3
    "ml", "毫升", "支" -> 4
    "滴" -> 5
    "iu", "单位" -> 6
    "喷" -> 7
    "贴", "枚" -> 9
    else -> 0
}
