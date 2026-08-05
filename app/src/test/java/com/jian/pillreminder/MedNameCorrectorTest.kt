package com.jian.pillreminder

import com.jian.pillreminder.domain.MedNameCorrector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MedNameCorrectorTest {

    // ---- 能纠对 ----

    @Test
    fun `氨气纠正为氨氯`() {
        val r = MedNameCorrector.correct("苯磺酸氨气地平片")
        assertEquals("苯磺酸氨氯地平片", r.text)
        assertTrue(r.corrected)
        assertEquals("氨气", r.fixes.first().from)
        assertEquals("氨氯", r.fixes.first().to)
    }

    @Test
    fun `二甲双肌纠正为二甲双胍`() {
        assertEquals("二甲双胍片", MedNameCorrector.correct("二甲双肌片").text)
    }

    @Test
    fun `他汁纠正为他汀`() {
        assertEquals("阿托伐他汀钙片", MedNameCorrector.correct("阿托伐他汁钙片").text)
    }

    @Test
    fun `头抱纠正为头孢`() {
        assertEquals("头孢克洛胶囊", MedNameCorrector.correct("头抱克洛胶囊").text)
    }

    @Test
    fun `剂型错字能纠正`() {
        assertEquals("阿司匹林肠溶片", MedNameCorrector.correct("阿司匹林肠溶斤").text)
        assertEquals("布洛芬缓释胶囊", MedNameCorrector.correct("布洛芬缓释胶襄").text)
    }

    @Test
    fun `阿斯匹林纠正为阿司匹林`() {
        assertEquals("阿司匹林", MedNameCorrector.correct("阿斯匹林").text)
    }

    @Test
    fun `氧雷他定纠正为氯雷他定`() {
        assertEquals("氯雷他定片", MedNameCorrector.correct("氧雷他定片").text)
    }

    @Test
    fun `酯脂混淆能纠正`() {
        assertEquals("硝酸甘酯", MedNameCorrector.correct("硝酸甘脂").text)
    }

    @Test
    fun `仅差一字时按药名库校正`() {
        // "缓释斤" 不在词组表里的组合，靠药名库整体比对
        val r = MedNameCorrector.correct("硝苯地平缓释斤")
        assertEquals("硝苯地平缓释片", r.text)
        assertTrue(r.corrected)
    }

    // ---- 不该乱改（比能纠对更重要）----

    @Test
    fun `正确的药名不做任何修改`() {
        val names = listOf(
            "苯磺酸氨氯地平片",
            "二甲双胍片",
            "阿托伐他汀钙片",
            "氯雷他定片",
            "维生素 D",
            "布洛芬缓释胶囊"
        )
        for (n in names) {
            val r = MedNameCorrector.correct(n)
            assertEquals("「$n」不应被修改", n, r.text)
            assertFalse("「$n」不该产生纠正记录", r.corrected)
        }
    }

    @Test
    fun `药名库里没有的药名保持原样`() {
        // 自制/生僻名称，差异大于一个字，不该被硬套成库里的药
        val weird = "某某牌复合草本调理丸"
        assertEquals(weird, MedNameCorrector.correct(weird).text)
    }

    @Test
    fun `差两个字以上不做整体校正`() {
        // 与"二甲双胍片"差 2 字，不应被改
        val r = MedNameCorrector.correct("三甲双肌斤")
        // "二甲双肌"的词组规则不匹配"三甲双肌"，且距离>1，所以只可能被词组规则改动
        assertFalse("差两字以上不该整体套用药名库", r.text == "二甲双胍片")
    }

    @Test
    fun `太短的名称不做整体校正`() {
        val r = MedNameCorrector.correct("钙片")
        assertEquals("钙片", r.text)
    }

    @Test
    fun `空字符串安全返回`() {
        val r = MedNameCorrector.correct("")
        assertEquals("", r.text)
        assertFalse(r.corrected)
    }

    @Test
    fun `氯化钠等正确化学名不被误伤`() {
        // 「氯化钠」本身正确，不应因为含"氯"被规则改动
        assertEquals("氯化钠注射液", MedNameCorrector.correct("氯化钠注射液").text)
        assertEquals("氯化钾缓释片", MedNameCorrector.correct("氯化钾缓释片").text)
    }

    @Test
    fun `含数字与英文的药名不被破坏`() {
        assertEquals("碳酸钙D3片", MedNameCorrector.correct("碳酸钙D3片").text)
        assertEquals("维生素B12片", MedNameCorrector.correct("维生素B12片").text)
    }

    @Test
    fun `库里存在的药名不会被改成另一个仅差一字的真实药名`() {
        // 「硝苯地平缓释片」和「硝苯地平控释片」都真实存在、剂型不同，
        // 仅差一个字。曾经的实现会把前者改成后者——这是用药风险，不是小瑕疵。
        assertEquals("硝苯地平缓释片", MedNameCorrector.correct("硝苯地平缓释片").text)
        assertEquals("硝苯地平控释片", MedNameCorrector.correct("硝苯地平控释片").text)
        assertFalse(MedNameCorrector.correct("硝苯地平缓释片").corrected)
        assertFalse(MedNameCorrector.correct("硝苯地平控释片").corrected)
    }

    @Test
    fun `库里每个药名都是纠错的不动点`() {
        // 防回归：以后往药名库加词时，如果新词和已有词只差一字，
        // 这个测试会立刻失败，提醒不要引入互相纠错的一对。
        for (name in MedNameCorrector.knownNamesForTest()) {
            val r = MedNameCorrector.correct(name)
            assertEquals("库里的「$name」被改动了", name, r.text)
            assertFalse("库里的「$name」产生了纠正记录", r.corrected)
        }
    }

    @Test
    fun `差一字但有多个候选时不猜`() {
        // 与「硝苯地平缓释片」「硝苯地平控释片」同时差一字 → 无法判断，应保持原样
        val ambiguous = "硝苯地平X释片"
        assertEquals(ambiguous, MedNameCorrector.correct(ambiguous).text)
    }

    @Test
    fun `每次纠正都留下可核对的记录`() {
        val r = MedNameCorrector.correct("苯黄酸氨气地平片")
        assertTrue("应记录多处纠正", r.fixes.size >= 2)
        assertTrue(r.fixes.any { it.from == "苯黄酸" && it.to == "苯磺酸" })
        assertTrue(r.fixes.any { it.from == "氨气" && it.to == "氨氯" })
        assertEquals("苯磺酸氨氯地平片", r.text)
    }
}
