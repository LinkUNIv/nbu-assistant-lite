package app.nbult.lite.browser

internal data class BaselineColorRegion(val left: Int, val top: Int, val right: Int, val bottom: Int, val score: Float)

/** Runs on a small colour grid; no OCR or Android objects required. */
internal object BaselineColorRegions {
    private fun yellow(c: Int): Boolean {
        val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
        return r > 50 && g > 45 && r >= g * 0.90 && g > b * 1.10 && r > b * 1.12
    }
    private fun blue(c: Int): Boolean {
        val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
        return b > 65 && b > r * 1.12 && g > r * 1.08
    }
    fun detect(pixels: IntArray, width: Int, height: Int): List<BaselineColorRegion> {
        require(width > 0 && height > 0 && pixels.size == width * height)
        if (pixels.count { blue(it) } < pixels.size * 0.08) return emptyList()
        val mask = BooleanArray(pixels.size) { yellow(pixels[it]) }
        val queue = IntArray(pixels.size)
        val found = mutableListOf<BaselineColorRegion>()
        for (start in mask.indices) {
            if (!mask[start]) continue
            var head = 0; var tail = 1; queue[0] = start; mask[start] = false
            var left = start % width; var right = left; var top = start / width; var bottom = top
            while (head < tail) {
                val pos = queue[head++]; val x = pos % width; val y = pos / width
                left = minOf(left,x); right = maxOf(right,x); top = minOf(top,y); bottom = maxOf(bottom,y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x+dx; val ny = y+dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val n = ny*width+nx
                    if (mask[n]) { mask[n] = false; queue[tail++] = n }
                }
            }
            // A capsule cut by the camera border is unsafe; edge reflections also
            // tend to form large pale-yellow components.
            if (left == 0 || top == 0 || right == width-1 || bottom == height-1) continue
            val w = right-left+1; val h = bottom-top+1
            if (w < 8 || h < 3 || w.toFloat()/h !in 1.8f..12f || tail < 25 || tail < w*h*0.25) continue
            val margin = maxOf(2,h/2)
            var surround = 0; var blueCount = 0
            for (y in maxOf(0,top-margin)..minOf(height-1,bottom+margin))
                for (x in maxOf(0,left-margin)..minOf(width-1,right+margin)) {
                    if (x in left..right && y in top..bottom) continue
                    surround++; if (blue(pixels[y*width+x])) blueCount++
                }
            val ratio = blueCount.toFloat()/surround.coerceAtLeast(1)
            if (ratio > 0.30f) found.add(BaselineColorRegion(left,top,right+1,bottom+1,tail*ratio))
        }
        return found.sortedByDescending { it.score }.take(3)
    }
}
