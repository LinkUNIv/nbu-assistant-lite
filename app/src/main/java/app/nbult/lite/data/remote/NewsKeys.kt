package app.nbult.lite.data.remote

/**
 * 资讯条目主键的生成规则。
 *
 * 踩过的坑：文章号（`info/1129/18266.htm` 里的 18266）只在**本栏目**内唯一，
 * 两所学校的栏目 idOffset 都曾配成 0，于是同号文章会互相 REPLACE ——
 * 表现是「宁大的列表里冒出湘潭的文章」。把栏目 id 乘进来做命名空间，跨校就不会再撞。
 */
internal object NewsKeys {

    /** 每个栏目预留的号段。真实文章号是 5–8 位，留 9 位足够。 */
    private const val COLUMN_STRIDE = 1_000_000_000L

    fun articleKey(columnId: Int, raw: Long): Long = columnId.toLong() * COLUMN_STRIDE + raw

    /** 文章号必须落在号段内，否则会串到相邻栏目上去；越界的直接跳过。 */
    fun fits(raw: Long): Boolean = raw >= 0L && raw < COLUMN_STRIDE
}
