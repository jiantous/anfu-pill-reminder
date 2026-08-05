package com.jian.pillreminder

import com.jian.pillreminder.data.MealRelation
import com.jian.pillreminder.data.Schedule
import com.jian.pillreminder.data.TimeOfDay
import com.jian.pillreminder.domain.LeafletParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeafletParserTest {

    private fun parse(vararg lines: String) = LeafletParser.parse(lines.toList())

    @Test
    fun `标准说明书_药名剂量频次全识别`() {
        val r = parse(
            "阿司匹林肠溶片说明书",
            "药品名称：阿司匹林肠溶片",
            "规格：100mg",
            "用法用量：口服。每次1片，每日1次，饭后服用。"
        )
        assertEquals("阿司匹林肠溶片", r.name)
        assertEquals(1.0, r.dosage!!, 0.001)
        assertEquals("片", r.unit)
        assertEquals(1, r.timesPerDay)
        assertEquals(MealRelation.AFTER_MEAL, r.mealRelation)
    }

    @Test
    fun `每日三次_推荐三个服药时间`() {
        val r = parse("用法用量：一次2粒，每日三次")
        assertEquals(2.0, r.dosage!!, 0.001)
        assertEquals("粒", r.unit)
        assertEquals(3, r.timesPerDay)
        assertEquals(3, r.suggestedTimes.size)
        assertEquals(TimeOfDay(8, 0), r.suggestedTimes.first())
    }

    @Test
    fun `医嘱缩写_tid识别为每天三次`() {
        val r = parse("Amoxicillin 0.5g po tid")
        assertEquals(3, r.timesPerDay)
    }

    @Test
    fun `每8小时一次_换算为每天三次`() {
        val r = parse("每8小时1次，每次1片")
        assertEquals(3, r.timesPerDay)
        assertEquals(1.0, r.dosage!!, 0.001)
    }

    @Test
    fun `隔日服用_识别为间隔两天`() {
        val r = parse("用法：隔日服用一次，每次1片")
        assertEquals(Schedule.EveryNDays(2), r.schedule)
    }

    @Test
    fun `服21天停7天_识别为周期用药`() {
        val r = parse("每日1片，连服21天，停7天后开始下一周期")
        assertEquals(Schedule.CycleOnOff(21, 7), r.schedule)
    }

    @Test
    fun `空腹服用_优先于饭前`() {
        val r = parse("请空腹服用，每次1片，每日1次")
        assertEquals(MealRelation.EMPTY_STOMACH, r.mealRelation)
    }

    @Test
    fun `半片剂量_识别为零点五`() {
        val r = parse("每次半片，每日2次")
        assertEquals(0.5, r.dosage!!, 0.001)
        assertEquals("片", r.unit)
        assertEquals(2, r.timesPerDay)
    }

    @Test
    fun `毫升单位_归一化为大写mL`() {
        val r = parse("每次10ml，每日3次")
        assertEquals(10.0, r.dosage!!, 0.001)
        assertEquals("mL", r.unit)
    }

    @Test
    fun `剂量在频次前面的写法也能识别`() {
        val r = parse("口服，2片，每日两次")
        assertEquals(2.0, r.dosage!!, 0.001)
        assertEquals(2, r.timesPerDay)
    }

    @Test
    fun `无标签时按剂型结尾兜底识别药名`() {
        val r = parse("硝苯地平缓释片", "规格 30mg", "每日1次")
        assertEquals("硝苯地平缓释片", r.name)
    }

    @Test
    fun `药名括号内容被剔除`() {
        val r = parse("通用名称：二甲双胍片（盐酸二甲双胍）")
        assertEquals("二甲双胍片", r.name)
    }

    @Test
    fun `睡前服用_隐含每天一次`() {
        val r = parse("每次1片，睡前服用")
        assertEquals(1, r.timesPerDay)
    }

    @Test
    fun `完全无关的文字_不瞎猜任何字段`() {
        val r = parse("今天天气不错", "我去超市买了点水果")
        assertNull(r.dosage)
        assertNull(r.timesPerDay)
        assertNull(r.schedule)
        assertNull(r.mealRelation)
        assertTrue("不应识别出任何内容", !r.hasAnything)
    }

    @Test
    fun `识别成功的字段都带依据说明`() {
        val r = parse("药品名称：布洛芬缓释胶囊", "每次1粒，每日2次，饭后服用")
        assertTrue("剂量应有依据", r.evidence.containsKey("dosage"))
        assertTrue("频次应有依据", r.evidence.containsKey("timesPerDay"))
        assertTrue("餐食关系应有依据", r.evidence.containsKey("meal"))
    }

    @Test
    fun `每日次数超出合理范围时不采纳`() {
        val r = parse("每日99次")
        assertNull(r.timesPerDay)
    }
}
