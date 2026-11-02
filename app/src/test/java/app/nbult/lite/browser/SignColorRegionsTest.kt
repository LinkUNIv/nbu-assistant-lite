package app.nbult.lite.browser

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.FileInputStream

class SignColorRegionsTest {
    @Test fun selectsYellowCapsuleOnBlueButNotTinyIcons() {
        val w=100; val h=70; val p=IntArray(w*h) { 0xff007ed0.toInt() }
        for(y in 10..19) for(x in 25..69) p[y*w+x]=0xffffd050.toInt()
        for(y in 40..44) for(x in 10..14) p[y*w+x]=0xffffd050.toInt()
        val found=SignColorRegions.detect(p,w,h)
        assertEquals(1,found.size)
        assertEquals(25,found.single().left)
    }
    @Test fun rejectsYellowObjectsWithoutBlueContext() {
        val p=IntArray(100*70) { 0xffaaaaaa.toInt() }
        for(y in 10..19) for(x in 25..69) p[y*100+x]=0xffffd050.toInt()
        assertTrue(SignColorRegions.detect(p,100,70).isEmpty())
    }
    @Test fun privateExamplePhotoFindsCodeArea() {
        val path=System.getenv("NBU_OCR_SAMPLE_GRID") ?: return
        DataInputStream(FileInputStream(path)).use { input ->
            val w=input.readInt(); val h=input.readInt()
            val found=SignColorRegions.detect(IntArray(w*h) { input.readInt() },w,h)
            println("Example colour regions: $found")
            assertTrue(found.none { it.top == 0 || it.left == 0 })
            assertTrue("Expected yellow code capsule in upper-right screen area", found.any {
                it.left in (w*0.58).toInt()..(w*0.68).toInt() && it.top in (h*0.28).toInt()..(h*0.38).toInt()
            })
        }
    }
}
