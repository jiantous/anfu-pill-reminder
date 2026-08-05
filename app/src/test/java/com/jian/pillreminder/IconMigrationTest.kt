package com.jian.pillreminder

import com.jian.pillreminder.data.BackupFile
import com.jian.pillreminder.data.BackupManager
import com.jian.pillreminder.data.Medication
import com.jian.pillreminder.data.mapLegacyIconIndex
import com.jian.pillreminder.ui.components.suggestIconForUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 图标表换代的下标映射。
 *
 * 这段逻辑会一次性改写用户已保存的数据，错了不可逆（用户看到的图标会莫名变形状），
 * 所以每一条对应关系都锁死。
 */
class IconMigrationTest {

    /** 新图标表的大小，映射结果必须落在这个范围内。 */
    private val newTableSize = 10

    private fun med(iconIndex: Int) = Medication(
        id = "m$iconIndex",
        name = "药$iconIndex",
        iconIndex = iconIndex,
        startDate = "2026-01-01"
    )

    @Test
    fun `剂型类图标按语义一一对应`() {
        assertEquals("药片 → 圆片", 0, mapLegacyIconIndex(0))
        assertEquals("胶囊 → 胶囊", 1, mapLegacyIconIndex(1))
        assertEquals("滴剂 → 滴剂", 5, mapLegacyIconIndex(2))
        assertEquals("针剂 → 注射", 6, mapLegacyIconIndex(3))
        assertEquals("外用 → 软膏", 8, mapLegacyIconIndex(7))
    }

    @Test
    fun `适应症类图标落到该类最常见的剂型`() {
        assertEquals("心脏 → 圆片", 0, mapLegacyIconIndex(4))
        assertEquals("血液 → 口服液", 4, mapLegacyIconIndex(5))
        assertEquals("保健 → 胶囊", 1, mapLegacyIconIndex(6))
        assertEquals("其他 → 圆片", 0, mapLegacyIconIndex(8))
    }

    @Test
    fun `所有旧下标都映射到新表的合法范围内`() {
        // 旧表 9 个图标
        for (old in 0 until 9) {
            val new = mapLegacyIconIndex(old)
            assertEquals(
                "旧下标 $old 映射后越界（得到 $new）",
                true,
                new in 0 until newTableSize
            )
        }
    }

    @Test
    fun `越界的旧下标原样返回而不是猜一个`() {
        // 不该出现，但真出现时保持原值比乱改安全（medIconAt 会取模兜底）
        assertEquals(99, mapLegacyIconIndex(99))
        assertEquals(-1, mapLegacyIconIndex(-1))
    }

    // ---- 备份升级 ----

    private fun backup(version: Int, icons: List<Int>) = BackupFile(
        version = version,
        exportedAt = "2026-08-05T10:00:00",
        medications = icons.map { med(it) }
    )

    @Test
    fun `v1 备份导入时图标被映射到新表`() {
        val old = backup(1, listOf(4, 5, 6, 7))   // 心脏、血液、保健、外用
        val up = BackupManager.upgrade(old)

        assertEquals(BackupFile.CURRENT_VERSION, up.version)
        assertEquals(listOf(0, 4, 1, 8), up.medications.map { it.iconIndex })
    }

    @Test
    fun `已是当前版本的备份不再改动`() {
        val current = backup(BackupFile.CURRENT_VERSION, listOf(4, 5, 6, 7))
        val up = BackupManager.upgrade(current)
        assertEquals("图标不该被二次映射", listOf(4, 5, 6, 7), up.medications.map { it.iconIndex })
    }

    @Test
    fun `重复升级是幂等的`() {
        // 迁移结果要等下一次写盘才落地，中间可能被反复读到，必须幂等
        val once = BackupManager.upgrade(backup(1, listOf(4, 5, 6, 7)))
        val twice = BackupManager.upgrade(once)
        assertEquals(once.medications.map { it.iconIndex }, twice.medications.map { it.iconIndex })
    }

    @Test
    fun `升级不动药品的其它字段`() {
        val src = BackupFile(
            version = 1,
            exportedAt = "2026-08-05T10:00:00",
            medications = listOf(
                Medication(
                    id = "abc",
                    name = "苯磺酸氨氯地平片",
                    dosage = 2.5,
                    unit = "片",
                    note = "早餐后",
                    iconIndex = 6,          // 保健 → 胶囊
                    colorIndex = 3,
                    startDate = "2026-03-01",
                    stockRemaining = 12.0
                )
            ),
            snoozeMinutes = 15
        )
        val m = BackupManager.upgrade(src).medications.single()

        assertEquals(1, m.iconIndex)        // 只有这个字段该变
        assertEquals("abc", m.id)
        assertEquals("苯磺酸氨氯地平片", m.name)
        assertEquals(2.5, m.dosage, 0.001)
        assertEquals("片", m.unit)
        assertEquals("早餐后", m.note)
        assertEquals(3, m.colorIndex)
        assertEquals("2026-03-01", m.startDate)
        assertEquals(12.0, m.stockRemaining!!, 0.001)
        assertEquals(15, BackupManager.upgrade(src).snoozeMinutes)
    }

    @Test
    fun `映射确实改变了适应症类图标`() {
        // 防止有人把映射表改成恒等，让迁移变成空操作
        assertNotEquals("血液类不该还指向下标 5", 5, mapLegacyIconIndex(5))
    }

    // ---- 按单位推荐图标 ----

    @Test
    fun `常见单位推荐到对应剂型`() {
        assertEquals("片 → 圆片", 0, suggestIconForUnit("片"))
        assertEquals("粒 → 胶囊", 1, suggestIconForUnit("粒"))
        assertEquals("颗 → 胶囊", 1, suggestIconForUnit("颗"))
        assertEquals("袋 → 颗粒", 3, suggestIconForUnit("袋"))
        assertEquals("mL → 口服液", 4, suggestIconForUnit("mL"))
        assertEquals("滴 → 滴剂", 5, suggestIconForUnit("滴"))
        assertEquals("IU → 注射", 6, suggestIconForUnit("IU"))
        assertEquals("喷 → 喷雾", 7, suggestIconForUnit("喷"))
        assertEquals("贴 → 贴剂", 9, suggestIconForUnit("贴"))
    }

    @Test
    fun `单位大小写与空格不影响推荐`() {
        assertEquals(4, suggestIconForUnit("ML"))
        assertEquals(4, suggestIconForUnit(" ml "))
        assertEquals(6, suggestIconForUnit("iu"))
    }

    @Test
    fun `没见过的单位回退到圆片而不是抛异常`() {
        assertEquals(0, suggestIconForUnit(""))
        assertEquals(0, suggestIconForUnit("勺"))
        assertEquals(0, suggestIconForUnit("???"))
    }

    @Test
    fun `推荐结果都在新表范围内`() {
        val units = listOf("片", "粒", "颗", "丸", "袋", "包", "克", "g", "ml", "毫升",
            "支", "滴", "iu", "单位", "喷", "贴", "枚", "未知")
        for (u in units) {
            val i = suggestIconForUnit(u)
            assertEquals("单位「$u」推荐越界（$i）", true, i in 0 until newTableSize)
        }
    }
}
