package app.nbult.lite.data.pack

/**
 * 资源包结构与 App 的兼容判定。
 *
 * 为什么单独抽出来：这是包应用路径上**唯一的结构闸门** —— 判定为不兼容就整包跳过，
 * 绝不做"能读多少读多少"。而 `PackManifest.parse` 要碰 `org.json`，在 JVM 单测里
 * 是 Android 桩（会抛异常），所以判定只吃 Int，做成纯数据入口，能直接单测。
 *
 * 与 `minAppVersion` 的分工：
 * - `minAppVersion`：这个包用了哪些新功能，靠人判断后写死在清单里；
 * - `schema`：包的结构本身变没变，结构一变就 +1，App 只认到自己支持的那一版。
 *
 * 两者都拦，但语义不同，日志与提示必须分开写。
 */
internal sealed interface PackSchemaDecision {

    /** 包的结构比 App 新：整包跳过。 */
    data class TooNew(val packSchema: Int, val supported: Int) : PackSchemaDecision

    /** 结构在支持范围内，继续走版本与 `minAppVersion` 判定。 */
    data object Compatible : PackSchemaDecision
}

/**
 * @param packSchema 包在清单里声明的结构版本（老包缺省为
 *   [PackManifest.SCHEMA_UNVERSIONED]）
 * @param supported 本 App 支持到的结构版本，默认取 [PackManifest.SUPPORTED_SCHEMA]
 */
internal fun packSchemaDecision(
    packSchema: Int,
    supported: Int = PackManifest.SUPPORTED_SCHEMA
): PackSchemaDecision =
    if (packSchema > supported) PackSchemaDecision.TooNew(packSchema, supported)
    else PackSchemaDecision.Compatible

/**
 * 从已落地的版本里挑出「可回退的那一版」。
 *
 * 规则只有一条：**比当前版小的最大者**。不能简单取"目录里第二大的" —— 目录可能因为
 * 上次清理失败而多留几个，此时第二大的不一定小于当前版（回退过又升回去就会这样）。
 * 纯数据入口：不碰 File / Context，便于单测。
 *
 * @param current 当前生效的版本（0 表示还在用内置资源，没有可回退的）
 * @param available 设备上已有的版本目录
 */
internal fun pickPreviousVersion(current: Long, available: List<Long>): Long? =
    available.filter { it in 1 until current }.maxOrNull()
