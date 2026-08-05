package com.jian.pillreminder

import com.jian.pillreminder.data.AppData
import com.jian.pillreminder.data.BackupFile
import com.jian.pillreminder.data.BackupManager
import com.jian.pillreminder.data.DoseLog
import com.jian.pillreminder.data.DoseStatus
import com.jian.pillreminder.data.ImportMode
import com.jian.pillreminder.data.Medication
import com.jian.pillreminder.data.TimeOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BackupManagerTest {

    private fun med(id: String, name: String) = Medication(
        id = id,
        name = name,
        startDate = "2026-08-01"
    )

    private fun log(medId: String, date: String, hour: Int, status: DoseStatus, at: Long) =
        DoseLog(medId, date, TimeOfDay(hour, 0), status, at)

    // ---- 导出 / 读回 ----

    @Test
    fun `导出的备份能完整读回`() {
        val data = AppData(
            medications = listOf(med("a", "维生素 D"), med("b", "降压药")),
            logs = listOf(log("a", "2026-08-04", 8, DoseStatus.TAKEN, 1000L)),
            snoozeMinutes = 15
        )
        val text = BackupManager.buildBackup(data, appVersion = "1.0")
        assertTrue("应含药名", text.contains("维生素 D"))
        assertTrue("应含版本号", text.contains("\"version\""))
        assertTrue("应含导出时间", text.contains("exportedAt"))
    }

    @Test
    fun `备份文件名带日期时间`() {
        val name = BackupManager.suggestFileName(
            java.time.LocalDateTime.of(2026, 8, 4, 21, 46)
        )
        assertEquals("安服备份_2026-08-04_2146.json", name)
    }

    // ---- REPLACE ----

    @Test
    fun `完全覆盖会丢弃当前数据只留备份`() {
        val current = AppData(
            medications = listOf(med("x", "新手机上录的药")),
            logs = listOf(log("x", "2026-08-04", 9, DoseStatus.TAKEN, 5000L))
        )
        val backup = BackupFile(
            exportedAt = "2026-08-03T10:00:00",
            medications = listOf(med("a", "旧手机的药")),
            logs = listOf(log("a", "2026-08-01", 8, DoseStatus.TAKEN, 1000L))
        )
        val result = BackupManager.apply(current, backup, ImportMode.REPLACE)
        assertEquals(1, result.medications.size)
        assertEquals("旧手机的药", result.medications.first().name)
        assertEquals(1, result.logs.size)
    }

    // ---- MERGE ----

    @Test
    fun `合并会保留双方的药品`() {
        val current = AppData(medications = listOf(med("x", "新药")))
        val backup = BackupFile(
            exportedAt = "2026-08-03T10:00:00",
            medications = listOf(med("a", "旧药"))
        )
        val result = BackupManager.apply(current, backup, ImportMode.MERGE)
        assertEquals(2, result.medications.size)
        assertTrue(result.medications.any { it.name == "新药" })
        assertTrue(result.medications.any { it.name == "旧药" })
    }

    @Test
    fun `合并时同 id 的药品以备份为准`() {
        val current = AppData(medications = listOf(med("a", "改过名字的")))
        val backup = BackupFile(
            exportedAt = "2026-08-03T10:00:00",
            medications = listOf(med("a", "备份里的名字"))
        )
        val result = BackupManager.apply(current, backup, ImportMode.MERGE)
        assertEquals(1, result.medications.size)
        assertEquals("备份里的名字", result.medications.first().name)
    }

    @Test
    fun `合并时同一次服药以操作时间更晚的为准`() {
        val current = AppData(
            medications = listOf(med("a", "药")),
            logs = listOf(log("a", "2026-08-04", 8, DoseStatus.SKIPPED, 9000L))
        )
        val backup = BackupFile(
            exportedAt = "2026-08-03T10:00:00",
            medications = listOf(med("a", "药")),
            logs = listOf(log("a", "2026-08-04", 8, DoseStatus.TAKEN, 1000L))
        )
        val result = BackupManager.apply(current, backup, ImportMode.MERGE)
        assertEquals(1, result.logs.size)
        // 当前那条 actedAtMillis 更大，应该胜出
        assertEquals(DoseStatus.SKIPPED, result.logs.first().status)
    }

    @Test
    fun `合并时不同日期的记录都保留`() {
        val current = AppData(
            medications = listOf(med("a", "药")),
            logs = listOf(log("a", "2026-08-04", 8, DoseStatus.TAKEN, 5000L))
        )
        val backup = BackupFile(
            exportedAt = "2026-08-03T10:00:00",
            medications = listOf(med("a", "药")),
            logs = listOf(
                log("a", "2026-08-01", 8, DoseStatus.TAKEN, 1000L),
                log("a", "2026-08-02", 8, DoseStatus.TAKEN, 2000L)
            )
        )
        val result = BackupManager.apply(current, backup, ImportMode.MERGE)
        assertEquals(3, result.logs.size)
    }

    @Test
    fun `合并后丢弃没有对应药品的孤儿记录`() {
        val current = AppData(medications = emptyList(), logs = emptyList())
        val backup = BackupFile(
            exportedAt = "2026-08-03T10:00:00",
            medications = listOf(med("a", "药")),
            // 这条的 medicationId 在药品列表里不存在
            logs = listOf(
                log("a", "2026-08-01", 8, DoseStatus.TAKEN, 1000L),
                log("已删除的药", "2026-08-01", 8, DoseStatus.TAKEN, 1000L)
            )
        )
        val result = BackupManager.apply(current, backup, ImportMode.MERGE)
        assertEquals(1, result.logs.size)
        assertEquals("a", result.logs.first().medicationId)
    }

    @Test
    fun `合并空备份不会破坏当前数据`() {
        val current = AppData(
            medications = listOf(med("x", "药")),
            logs = listOf(log("x", "2026-08-04", 8, DoseStatus.TAKEN, 1000L))
        )
        val backup = BackupFile(exportedAt = "2026-08-03T10:00:00")
        val result = BackupManager.apply(current, backup, ImportMode.MERGE)
        assertEquals(1, result.medications.size)
        assertEquals(1, result.logs.size)
    }

    // ---- 摘要与提醒 ----

    @Test
    fun `摘要能列出药品数量与名称`() {
        val backup = BackupFile(
            exportedAt = "2026-08-04T21:46:00",
            medications = listOf(med("a", "维生素 D"), med("b", "降压药")),
            logs = listOf(log("a", "2026-08-04", 8, DoseStatus.TAKEN, 1L))
        )
        val s = BackupManager.summarize(backup)
        assertEquals(2, s.medicationCount)
        assertEquals(1, s.logCount)
        assertTrue(s.medicationNames.contains("维生素 D"))
        assertTrue("导出时间应格式化为中文", s.exportedAt.contains("2026 年 8 月 4 日"))
    }

    @Test
    fun `从未备份时天数为 null`() {
        assertEquals(null, BackupManager.daysSince(null))
    }

    @Test
    fun `能算出距上次备份的天数`() {
        val days = BackupManager.daysSince("2026-07-05", LocalDate.of(2026, 8, 4))
        assertNotNull(days)
        assertEquals(30L, days)
    }

    @Test
    fun `未来日期的备份记录不产生负天数`() {
        val days = BackupManager.daysSince("2026-09-01", LocalDate.of(2026, 8, 4))
        assertEquals(0L, days)
    }
}
