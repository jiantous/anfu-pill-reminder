package com.jian.pillreminder

import com.jian.pillreminder.data.TimeOfDay
import com.jian.pillreminder.notify.Reminders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已排闹钟台账的编解码。
 *
 * 这份台账是"改了服药时间后旧时间还会响一次"那个 bug 的修复核心：
 * 取消闹钟必须用当初排进去的时刻算 requestCode。台账读错就等于没修。
 */
class ReminderLedgerTest {

    @Test
    fun `编码后再解码得到原始时刻`() {
        val times = listOf(TimeOfDay(8, 0), TimeOfDay(13, 30), TimeOfDay(20, 5))
        val decoded = Reminders.decodeTimes(Reminders.encodeTimes(times))
        assertEquals(times, decoded)
    }

    @Test
    fun `空列表编码为空串_解码回空列表`() {
        assertEquals("", Reminders.encodeTimes(emptyList()))
        assertTrue(Reminders.decodeTimes("").isEmpty())
    }

    @Test
    fun `台账缺失时解码为空_不抛异常`() {
        assertTrue(Reminders.decodeTimes(null).isEmpty())
        assertTrue(Reminders.decodeTimes("   ").isEmpty())
    }

    @Test
    fun `午夜零点能正确往返`() {
        // 0:0 容易被"空值"逻辑误吞
        val times = listOf(TimeOfDay(0, 0))
        assertEquals(times, Reminders.decodeTimes(Reminders.encodeTimes(times)))
    }

    @Test
    fun `脏数据被跳过而不是让整条台账失效`() {
        // 只丢掉坏的那一项，其余照常取消，否则一条脏数据会让所有旧闹钟都取消不掉
        val decoded = Reminders.decodeTimes("8:0,坏数据,20:30,99:99,7:")
        assertEquals(listOf(TimeOfDay(8, 0), TimeOfDay(20, 30)), decoded)
    }

    @Test
    fun `越界时刻被拒绝`() {
        assertTrue(Reminders.decodeTimes("24:00").isEmpty())
        assertTrue(Reminders.decodeTimes("12:60").isEmpty())
        assertTrue(Reminders.decodeTimes("-1:30").isEmpty())
    }
}
