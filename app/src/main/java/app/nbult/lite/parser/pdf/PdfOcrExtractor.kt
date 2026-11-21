package app.nbult.lite.parser.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.nbult.lite.util.AppLog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * 把 PDF **渲染成图再 OCR**，产出与 [PdfTextExtractor] 同构的文字片段。
 *
 * 为什么需要：有些教务系统导出的课表 PDF 没有文字层（扫描件 / 图片页），
 * 或者文字是逐 token 绘制的、行列归位做不稳（中南民大）。这时从像素入手更可靠。
 *
 * 关键约定：**坐标换算回 PDF 的点（pt）空间**。
 * 渲染按 [SCALE] 放大，OCR 得到的是像素坐标，统一除以 [SCALE] 之后，
 * 现有的列锚、行距、单元格正则都能照用 —— 不必为 OCR 再写一套参数。
 */
internal class PdfOcrExtractor(private val context: Context) {

    companion object {
        /** 渲染倍率：约 200dpi。太小看不清周次与教室号，太大既慢又费内存。 */
        private const val SCALE = 200f / 72f

        /** 页面最长边上限（像素）：超过就降采样，避免 OOM。 */
        private const val MAX_SIDE_PX = 2600
    }

    suspend fun extract(bytes: ByteArray): List<TextFragment> {
        val out = mutableListOf<TextFragment>()
        val temp = File.createTempFile("timetable-ocr", ".pdf", context.cacheDir)
        try {
            temp.writeBytes(bytes)
            // 用 try/finally 而不是 `use { }`：`use` 是 inline 函数，
            // 在它的 lambda 里 `continue` 到外层 for 属于实验特性，编译直接失败
            // （CI: The feature "break continue in inline lambdas" is experimental）。
            val pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            try {
                for (index in 0 until renderer.pageCount) {
                    val page = renderer.openPage(index)
                    val bitmap = renderPage(page)
                    page.close()
                    val text = suspendCancellableCoroutine { cont ->
                        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                            .addOnSuccessListener { cont.resume(it) }
                            .addOnFailureListener { cont.resume(null) }
                    }
                    bitmap.recycle()
                    val blocks = text?.textBlocks ?: emptyList()
                    for (block in blocks) {
                        for (line in block.lines) {
                            val box = line.boundingBox ?: continue
                            val trimmed = line.text.trim()
                            if (trimmed.isEmpty()) continue
                            out.add(
                                TextFragment(
                                    page = index,
                                    x = box.left / SCALE,
                                    // 图片坐标自上而下，统一成"y 越大越靠上"
                                    y = -box.top / SCALE,
                                    text = trimmed,
                                    width = box.width() / SCALE
                                )
                            )
                        }
                    }
                }
            } finally {
                recognizer.close()
                renderer.close()
                pfd.close()
            }
        } finally {
            temp.delete()
        }
        AppLog.log("Parse", "PDF 识图：${out.size} 行文字")
        return out
    }

    private fun renderPage(page: PdfRenderer.Page): Bitmap {
        val w = (page.width * SCALE).toInt().coerceAtLeast(1)
        val h = (page.height * SCALE).toInt().coerceAtLeast(1)
        val longest = max(w, h)
        val factor = if (longest > MAX_SIDE_PX) MAX_SIDE_PX.toFloat() / longest else 1f
        val bitmap = Bitmap.createBitmap(
            (w * factor).toInt().coerceAtLeast(1),
            (h * factor).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        // 底色填白：PDF 里没有背景时渲染结果可能透明，OCR 会当成黑底
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }
}
