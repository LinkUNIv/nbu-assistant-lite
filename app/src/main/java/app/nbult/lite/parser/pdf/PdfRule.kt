package app.nbult.lite.parser.pdf

/**
 * 一条表格线（竖线或横线），坐标与 [TextFragment] 同一套：
 * **y 越大越靠上**（已从 PDFBox 的自上而下换算过来）。
 */
internal data class PdfRule(
    val page: Int,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
) {
    val isVertical: Boolean get() = kotlin.math.abs(x1 - x2) < kotlin.math.abs(y1 - y2)
    val thickness: Float
        get() = if (isVertical) kotlin.math.abs(x1 - x2) else kotlin.math.abs(y1 - y2)
    val length: Float
        get() = if (isVertical) kotlin.math.abs(y1 - y2) else kotlin.math.abs(x1 - x2)
}
