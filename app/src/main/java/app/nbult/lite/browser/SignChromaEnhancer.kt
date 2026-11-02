package app.nbult.lite.browser

/** Local chroma normalization preserves faint blue strokes on washed-out yellow. */
internal object SignChromaEnhancer {
    fun enhance(pixels: IntArray, width: Int, height: Int) {
        require(pixels.size == width*height)
        val histogram = IntArray(511)
        // Use the interior to avoid sampling the blue surround added as OCR padding.
        for(y in height/5 until height*4/5) for(x in width/12 until width*11/12) {
            val p = pixels[y*width+x]
            histogram[((p shr 16 and 255)-(p and 255)+255).coerceIn(0,510)]++
        }
        val total = histogram.sum().coerceAtLeast(1)
        fun percentile(q: Float): Int {
            var sum = 0
            for(i in histogram.indices) { sum += histogram[i]; if(sum >= total*q) return i-255 }
            return 0
        }
        val low = percentile(0.12f)
        val high = maxOf(low+8,percentile(0.80f))
        for(i in pixels.indices) {
            val p=pixels[i]; val difference=(p shr 16 and 255)-(p and 255)
            val value=((difference-low)*255/(high-low)).coerceIn(0,255)
            pixels[i]=(0xff000000L or (value.toLong() shl 16) or (value.toLong() shl 8) or value.toLong()).toInt()
        }
    }
}
