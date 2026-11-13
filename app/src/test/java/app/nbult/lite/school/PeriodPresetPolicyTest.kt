package app.nbult.lite.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切学校 / 换季时该不该写作息。
 *
 * 盯的是那个「切回宁大后仍是湘潭时间」的回归：湘潭写过作息，切回宁大时
 * 必须按上次所选还原，而不是因为「宁大不自动覆盖」就什么都不做。
 */
class PeriodPresetPolicyTest {

    private val default = "nbu"

    /** 湘潭某套作息的标识，形状与 SchoolContext.Preset.key 一致。 */
    private fun xtu(
        key: String = "winter/冬季作息/08:00",
        presetId: String = "winter"
    ) = PeriodPresetPolicy.Target(presetId, PeriodPresetPolicy.key("xtu", key))

    private fun decide(
        schoolId: String,
        appliedKey: String?,
        ownPreset: PeriodPresetPolicy.Target? = xtu(),
        remembered: String? = null,
        fallback: String = "main"
    ) = PeriodPresetPolicy.decide(
        schoolId = schoolId,
        appliedKey = appliedKey,
        defaultSchoolId = default,
        ownPreset = ownPreset,
        rememberedDefaultPresetId = remembered,
        defaultFallbackPresetId = fallback
    )

    @Test
    fun `从未写过时宁大不动`() {
        // 新建与老用户升级都走这条：内置默认作息本就等于主校区，写一次反而冲掉手调
        val d = decide("nbu", null)
        assertTrue("应保留：$d", d is PeriodPresetPolicy.Decision.Keep)
    }

    @Test
    fun `从未写过时湘潭应用自己的作息`() {
        val d = decide("xtu", null)
        assertEquals(PeriodPresetPolicy.Decision.Apply("winter", "xtu/winter/冬季作息/08:00"), d)
    }

    @Test
    fun `同校同季不重复写入`() {
        val d = decide("xtu", "xtu/winter/冬季作息/08:00")
        assertTrue("应保留：$d", d is PeriodPresetPolicy.Decision.Keep)
    }

    @Test
    fun `同校换季要改写成新的一套`() {
        val d = decide(
            "xtu",
            "xtu/winter/冬季作息/08:00",
            ownPreset = xtu("summer/夏季作息/08:00", presetId = "summer")
        )
        assertEquals(
            PeriodPresetPolicy.Decision.Apply("summer", "xtu/summer/夏季作息/08:00"),
            d
        )
    }

    @Test
    fun `切回宁大时按上次所选还原`() {
        val d = decide("nbu", "xtu/winter/冬季作息/08:00", remembered = "meishan")
        assertEquals(PeriodPresetPolicy.Decision.Apply("meishan", "nbu/meishan"), d)
    }

    @Test
    fun `切回宁大但没选过时用主校区兜底`() {
        val d = decide("nbu", "xtu/winter/冬季作息/08:00", remembered = null)
        assertEquals(PeriodPresetPolicy.Decision.Apply("main", "nbu/main"), d)
    }

    @Test
    fun `宁大已记录过的模板不再覆盖`() {
        val d = decide("nbu", "nbu/meishan", remembered = "meishan")
        assertTrue("应保留：$d", d is PeriodPresetPolicy.Decision.Keep)
    }

    @Test
    fun `该校没配作息时保留当前时间`() {
        val d = decide("xtu", null, ownPreset = null)
        assertTrue("应保留：$d", d is PeriodPresetPolicy.Decision.Keep)
    }

    @Test
    fun `写入标识的前缀就是写入者`() {
        assertEquals("xtu", PeriodPresetPolicy.schoolOf("xtu/winter/冬季作息/08:00"))
        assertEquals("nbu", PeriodPresetPolicy.schoolOf("nbu/main"))
        assertEquals(null, PeriodPresetPolicy.schoolOf(null))
        assertEquals(null, PeriodPresetPolicy.schoolOf(""))
    }
}
