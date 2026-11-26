package app.nbult.lite.data.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资源包结构闸门：包比 App 新就整包跳过。
 *
 * 为什么值得单独盯：判错的代价不对称 —— 放过一个读不懂的包，坏的是**整份数据**
 * （校历、入口、资讯一起错）；错拦一个能读的包，用户只是晚几天拿到内容。
 * 所以这里只测"该拦的拦住了"和"不该拦的别乱拦"。
 */
class PackCompatibilityTest {

    @Test
    fun `老包没有 schema 字段时按第一版处理`() {
        // parse() 会把它读成 SCHEMA_UNVERSIONED；这里直接验证判定不会误拦
        val decision = packSchemaDecision(PackManifest.SCHEMA_UNVERSIONED)
        assertEquals(PackSchemaDecision.Compatible, decision)
    }

    @Test
    fun `结构版本相同即兼容`() {
        assertEquals(
            PackSchemaDecision.Compatible,
            packSchemaDecision(1, supported = 1)
        )
    }

    @Test
    fun `包结构比 App 新则整包跳过并带上两侧版本号`() {
        val decision = packSchemaDecision(2, supported = 1)
        assertTrue(decision is PackSchemaDecision.TooNew)
        decision as PackSchemaDecision.TooNew
        assertEquals(2, decision.packSchema)
        assertEquals(1, decision.supported)
    }

    @Test
    fun `App 升到更高结构后旧包仍然兼容`() {
        // 支持到 3 的 App 读结构 1、2 的包都必须放行：结构是向后读，不是精确匹配
        assertEquals(PackSchemaDecision.Compatible, packSchemaDecision(1, supported = 3))
        assertEquals(PackSchemaDecision.Compatible, packSchemaDecision(2, supported = 3))
        assertEquals(PackSchemaDecision.Compatible, packSchemaDecision(3, supported = 3))
    }

    @Test
    fun `超出支持范围一版也要拦`() {
        // 差一版是最容易被"顺手放行"的情况，专门钉一下
        assertTrue(packSchemaDecision(4, supported = 3) is PackSchemaDecision.TooNew)
    }

    @Test
    fun `回退目标取比当前小的最大者`() {
        // 设备上可能因清理失败留着多个目录，不能简单取"第二大的"
        assertEquals(202611251600L, pickPreviousVersion(202611260900L, listOf(202611251600L, 202611260900L)))
        assertEquals(
            202611251600L,
            pickPreviousVersion(202611260900L, listOf(202611242000L, 202611251600L, 202611260900L))
        )
    }

    @Test
    fun `回退过又升回去时不会把当前版当成上一版`() {
        // 回退到旧版后目录里仍留着那个"更新的坏版本"，它不能成为回退目标
        val available = listOf(202611242000L, 202611260900L)
        assertEquals(202611242000L, pickPreviousVersion(202611251600L, available))
    }

    @Test
    fun `还在用内置资源时没有可回退的版本`() {
        assertEquals(null, pickPreviousVersion(0L, listOf(202611251600L)))
        assertEquals(null, pickPreviousVersion(202611251600L, listOf(202611251600L)))
    }
}
