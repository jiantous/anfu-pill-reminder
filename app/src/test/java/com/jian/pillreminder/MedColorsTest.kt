package com.jian.pillreminder

import androidx.compose.ui.graphics.Color
import com.jian.pillreminder.ui.theme.MedColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * 药品色板的显示保障测试。
 *
 * 1. 对比度：每个颜色的文字色对容器色 ≥4.5:1（WCAG 正文标准），
 *    深浅两套都要过——这是"信息传达清晰优先"的硬门槛，加新色漏检会直接红。
 * 2. 前 8 色顺序锁定：老数据的 colorIndex 靠列表顺序映射到原颜色，
 *    前 8 个条目（1.1.16 之前发布的色板）一旦换位/删除，存量药品会变色。
 */
class MedColorsTest {

    // ---- WCAG 对比度 ----

    private fun srgbToLin(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    /** 从 Compose Color 直接取 R/G/B 分量（0-255），避开 value 位布局的版本差异。 */
    private fun Color.rgb(): Triple<Int, Int, Int> =
        Triple(
            (red * 255f).toInt().coerceIn(0, 255),
            (green * 255f).toInt().coerceIn(0, 255),
            (blue * 255f).toInt().coerceIn(0, 255)
        )

    private fun contrast(fg: Color, bg: Color): Double {
        val (fr, fgc, fb) = fg.rgb()
        val (br, bgc, bb) = bg.rgb()
        val la = 0.2126 * srgbToLin(fr) + 0.7152 * srgbToLin(fgc) + 0.0722 * srgbToLin(fb)
        val lb = 0.2126 * srgbToLin(br) + 0.7152 * srgbToLin(bgc) + 0.0722 * srgbToLin(bb)
        val hi = maxOf(la, lb); val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `全部颜色浅色模式对比度达标`() {
        MedColors.forEach { c ->
            val r = contrast(c.onLight, c.light)
            assertTrue(
                "${c.name} 浅色文字对比度 ${"%.2f".format(r)}:1 低于 4.5:1",
                r >= 4.5
            )
        }
    }

    @Test
    fun `全部颜色深色模式对比度达标`() {
        MedColors.forEach { c ->
            val r = contrast(c.onDark, c.dark)
            assertTrue(
                "${c.name} 深色文字对比度 ${"%.2f".format(r)}:1 低于 4.5:1",
                r >= 4.5
            )
        }
    }

    // ---- 前 8 色顺序锁定（老 colorIndex 兼容）----

    @Test
    fun `色板总数为16`() {
        assertEquals(16, MedColors.size)
    }

    @Test
    fun `前8色顺序与1_1_16之前完全一致`() {
        // 只断言名字顺序：名字对了，颜色值自然就是发布过的那套
        val expected = listOf(
            "鼠尾草", "蓝", "紫", "粉", "橙", "黄", "绿", "灰蓝"
        )
        assertEquals(expected, MedColors.take(8).map { it.name })
    }
}
