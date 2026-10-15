package com.jian.pillreminder

import com.jian.pillreminder.notify.Reminders
import com.jian.pillreminder.ui.screens.parsePositiveDosage
import com.jian.pillreminder.ui.screens.sanitizeDosageInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 加药表单剂量输入的规范化与校验（1.1.15 修复"半片被存成 1 片"）。
 */
class EditMedicationValidationTest {

    // ---- 输入规范化：至多一个小数点，前导点补 0 ----

    @Test
    fun `正常数字原样保留`() {
        assertEquals("0.5", sanitizeDosageInput("0.5"))
        assertEquals("1", sanitizeDosageInput("1"))
        assertEquals("2.25", sanitizeDosageInput("2.25"))
        assertEquals("10", sanitizeDosageInput("10"))
    }

    @Test
    fun `前导小数点自动补零`() {
        assertEquals("0.5", sanitizeDosageInput(".5"))
        assertEquals("0.25", sanitizeDosageInput(".25"))
    }

    @Test
    fun `第二个小数点被丢弃但后续数字保留`() {
        assertEquals("1.23", sanitizeDosageInput("1.2.3"))
        assertEquals("0.5", sanitizeDosageInput("0..5"))
        assertEquals("1.5", sanitizeDosageInput("1.5."))
    }

    @Test
    fun `非数字字符被过滤`() {
        assertEquals("0.5", sanitizeDosageInput("a0.5片"))
        assertEquals("05", sanitizeDosageInput("0，5")) // 全角/小数逗号不识别为小数点
        assertEquals("", sanitizeDosageInput("片"))
    }

    @Test
    fun `空串与纯点`() {
        assertEquals("", sanitizeDosageInput(""))
        assertEquals("0.", sanitizeDosageInput("."))
    }

    // ---- 正数解析：只接受有限正数 ----

    @Test
    fun `正小数按原值解析`() {
        assertEquals(0.5, parsePositiveDosage("0.5")!!, 0.0)
        assertEquals(0.25, parsePositiveDosage("0.25")!!, 0.0)
        assertEquals(1.0, parsePositiveDosage("1")!!, 0.0)
        assertEquals(1.0, parsePositiveDosage("1.0")!!, 0.0)
        assertEquals(2.5, parsePositiveDosage("2.5")!!, 0.0)
    }

    @Test
    fun `零与空值被拒绝`() {
        assertNull(parsePositiveDosage(""))
        assertNull(parsePositiveDosage("."))
        assertNull(parsePositiveDosage("0"))
        assertNull(parsePositiveDosage("0.0"))
        assertNull(parsePositiveDosage("0.000"))
    }

    @Test
    fun `负数与非数字被拒绝`() {
        assertNull(parsePositiveDosage("-1"))
        assertNull(parsePositiveDosage("abc"))
        assertNull(parsePositiveDosage("Infinity"))
        assertNull(parsePositiveDosage("NaN"))
    }

    // ---- formatDosage：极小值不再走科学计数法 ----

    @Test
    fun `整数不带小数点`() {
        assertEquals("1", Reminders.formatDosage(1.0))
        assertEquals("10", Reminders.formatDosage(10.0))
    }

    @Test
    fun `小数原样显示`() {
        assertEquals("0.5", Reminders.formatDosage(0.5))
        assertEquals("0.25", Reminders.formatDosage(0.25))
        assertEquals("2.5", Reminders.formatDosage(2.5))
    }

    @Test
    fun `极小值展开为普通小数而非科学计数法`() {
        assertEquals("0.0001", Reminders.formatDosage(0.0001))
        assertEquals("0.001", Reminders.formatDosage(0.001))
        // 1.0E-4 经输入过滤曾会损坏成 "1.04"，现在初值本身就是安全的 "0.0001"
        assertEquals(
            "0.0001",
            sanitizeDosageInput(Reminders.formatDosage(0.0001))
        )
    }
}
