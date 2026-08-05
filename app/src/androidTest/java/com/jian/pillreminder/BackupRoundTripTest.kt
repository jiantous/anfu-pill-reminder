package com.jian.pillreminder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jian.pillreminder.data.AppData
import com.jian.pillreminder.data.BackupFile
import com.jian.pillreminder.data.BackupManager
import com.jian.pillreminder.data.DoseLog
import com.jian.pillreminder.data.DoseStatus
import com.jian.pillreminder.data.ImportMode
import com.jian.pillreminder.data.MealRelation
import com.jian.pillreminder.data.Medication
import com.jian.pillreminder.data.Schedule
import com.jian.pillreminder.data.TimeOfDay
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 在真机上跑的备份往返测试：导出到文件 → 读回 → 应用，
 * 验证换手机场景下数据不丢、不变形。用临时文件，不动用户真实数据。
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sampleData() = AppData(
        medications = listOf(
            Medication(
                id = "med-1",
                name = "苯磺酸氨氯地平片",
                dosage = 1.0,
                unit = "片",
                note = "饭后服用，避免与葡萄柚同服",
                colorIndex = 3,
                iconIndex = 2,
                schedule = Schedule.CycleOnOff(onDays = 21, offDays = 7),
                times = listOf(TimeOfDay(8, 30), TimeOfDay(20, 30)),
                mealRelation = MealRelation.AFTER_MEAL,
                startDate = "2026-07-01",
                endDate = "2026-12-31",
                remindersEnabled = true,
                stockRemaining = 14.5,
                stockThreshold = 6.0,
                archived = false
            ),
            Medication(
                id = "med-2",
                name = "维生素 D",
                schedule = Schedule.WeekDays(setOf(1, 3, 5)),
                times = listOf(TimeOfDay(9, 0)),
                startDate = "2026-08-01",
                archived = true
            )
        ),
        logs = listOf(
            DoseLog("med-1", "2026-08-03", TimeOfDay(8, 30), DoseStatus.TAKEN, 1_700_000_000_000L),
            DoseLog("med-1", "2026-08-03", TimeOfDay(20, 30), DoseStatus.SKIPPED, 1_700_000_100_000L),
            DoseLog("med-2", "2026-08-03", TimeOfDay(9, 0), DoseStatus.TAKEN, 1_700_000_200_000L)
        ),
        snoozeMinutes = 15
    )

    @Test
    fun 导出到文件再读回_全部字段无损() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val original = sampleData()

        // 1. 导出
        val content = BackupManager.buildBackup(original, appVersion = "1.0")
        val file = File(ctx.cacheDir, "roundtrip_test.json")
        file.writeText(content)
        assertTrue("备份文件应已写出", file.exists() && file.length() > 0)

        // 2. 读回
        val backup = json.decodeFromString<BackupFile>(file.readText())

        // 3. 应用到空数据（模拟新手机）
        val restored = BackupManager.apply(AppData(), backup, ImportMode.REPLACE)

        // 4. 逐字段核对：药品
        assertEquals(2, restored.medications.size)
        val m1 = restored.medications.first { it.id == "med-1" }
        val o1 = original.medications.first { it.id == "med-1" }
        assertEquals(o1.name, m1.name)
        assertEquals(o1.dosage, m1.dosage, 0.0001)
        assertEquals(o1.unit, m1.unit)
        assertEquals(o1.note, m1.note)
        assertEquals(o1.colorIndex, m1.colorIndex)
        assertEquals(o1.iconIndex, m1.iconIndex)
        assertEquals(o1.schedule, m1.schedule)
        assertEquals(o1.times, m1.times)
        assertEquals(o1.mealRelation, m1.mealRelation)
        assertEquals(o1.startDate, m1.startDate)
        assertEquals(o1.endDate, m1.endDate)
        assertEquals(o1.stockRemaining!!, m1.stockRemaining!!, 0.0001)
        assertEquals(o1.stockThreshold, m1.stockThreshold, 0.0001)

        // 复杂周期（吃21停7）必须原样带回，否则提醒会全错
        assertTrue("周期用药应保持 CycleOnOff", m1.schedule is Schedule.CycleOnOff)
        assertEquals(21, (m1.schedule as Schedule.CycleOnOff).onDays)
        assertEquals(7, (m1.schedule as Schedule.CycleOnOff).offDays)

        // 已停用状态与按周设置也要带回
        val m2 = restored.medications.first { it.id == "med-2" }
        assertTrue("已停用状态应保留", m2.archived)
        assertEquals(setOf(1, 3, 5), (m2.schedule as Schedule.WeekDays).daysOfWeek)

        // 5. 服药记录
        assertEquals(3, restored.logs.size)
        val taken = restored.logs.first { it.medicationId == "med-1" && it.time == TimeOfDay(8, 30) }
        assertEquals(DoseStatus.TAKEN, taken.status)
        assertEquals(1_700_000_000_000L, taken.actedAtMillis)

        assertEquals(15, restored.snoozeMinutes)

        file.delete()
    }

    @Test
    fun 合并两台手机的数据_双方记录都在() {
        val phoneA = sampleData()
        // B 手机自己录了一种药和一条记录
        val phoneB = AppData(
            medications = listOf(
                Medication(id = "med-b", name = "新手机加的药", startDate = "2026-08-04")
            ),
            logs = listOf(
                DoseLog("med-b", "2026-08-04", TimeOfDay(7, 0), DoseStatus.TAKEN, 1_800_000_000_000L)
            )
        )

        val backupFromA = json.decodeFromString<BackupFile>(
            BackupManager.buildBackup(phoneA, "1.0")
        )
        val merged = BackupManager.apply(phoneB, backupFromA, ImportMode.MERGE)

        assertEquals("两边药品都应保留", 3, merged.medications.size)
        assertTrue(merged.medications.any { it.name == "新手机加的药" })
        assertTrue(merged.medications.any { it.name == "苯磺酸氨氯地平片" })
        assertEquals("两边记录都应保留", 4, merged.logs.size)
    }

    @Test
    fun 备份文件是可读的纯文本() {
        val content = BackupManager.buildBackup(sampleData(), "1.0")
        // 用户应该能直接打开看懂，出问题时也便于人工排查
        assertTrue("应含药名", content.contains("苯磺酸氨氯地平片"))
        assertTrue("应是缩进过的 JSON", content.contains("\n    "))
        assertTrue("应含格式版本", content.contains("\"version\": 1"))
    }
}
