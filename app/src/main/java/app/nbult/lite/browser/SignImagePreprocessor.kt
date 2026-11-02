package app.nbult.lite.browser

import android.graphics.*

internal object SignImagePreprocessor {
    fun prepare(source: Bitmap, frame: Int): Bitmap {
        val gridWidth = minOf(340,source.width)
        val gridHeight = (source.height.toFloat()*gridWidth/source.width).toInt().coerceAtLeast(1)
        val grid = Bitmap.createScaledBitmap(source,gridWidth,gridHeight,false)
        val pixels = IntArray(gridWidth*gridHeight)
        grid.getPixels(pixels,0,gridWidth,0,0,gridWidth,gridHeight)
        val regions = SignColorRegions.detect(pixels,gridWidth,gridHeight)
        if (grid !== source) grid.recycle()
        // Periodic full-frame fallback tolerates reflections and colour shifts.
        if (regions.isEmpty() || frame % 8 == 7) {
            val scale = maxOf(1f,1280f/source.width).coerceAtMost(2f)
            val output = Bitmap.createBitmap((source.width*scale).toInt(),(source.height*scale).toInt(),Bitmap.Config.ARGB_8888)
            Canvas(output).drawBitmap(source,null,Rect(0,0,output.width,output.height),Paint(Paint.FILTER_BITMAP_FLAG))
            return output
        }
        val rects = regions.map { region ->
            val sx = source.width.toFloat()/gridWidth; val sy = source.height.toFloat()/gridHeight
            val pad = maxOf(2, ((region.bottom-region.top)*sy*0.15f).toInt())
            Rect((region.left*sx).toInt().minus(pad).coerceAtLeast(0), (region.top*sy).toInt().minus(pad).coerceAtLeast(0),
                ((region.right*sx).toInt()+pad).coerceAtMost(source.width), ((region.bottom*sy).toInt()+pad).coerceAtMost(source.height))
        }
        val heights = rects.map { (it.height()*960f/it.width()).toInt().coerceIn(50,450) }
        val output = Bitmap.createBitmap(992,heights.sum()+32*rects.size+16,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output); canvas.drawColor(Color.WHITE)
        var y = 16
        rects.forEachIndexed { index, rect ->
            val crop = Bitmap.createBitmap(rect.width(),rect.height(),Bitmap.Config.ARGB_8888)
            Canvas(crop).drawBitmap(source,rect,Rect(0,0,crop.width,crop.height),null)
            if (frame % 3 != 2) {
                val content = IntArray(crop.width*crop.height)
                crop.getPixels(content,0,crop.width,0,0,crop.width,crop.height)
                SignChromaEnhancer.enhance(content,crop.width,crop.height)
                crop.setPixels(content,0,crop.width,0,0,crop.width,crop.height)
            }
            canvas.drawBitmap(crop,null,Rect(16,y,976,y+heights[index]),Paint(Paint.FILTER_BITMAP_FLAG))
            if (crop !== source) crop.recycle()
            y += heights[index]+32
        }
        return output
    }
}
