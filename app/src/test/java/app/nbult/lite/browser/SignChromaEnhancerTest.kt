package app.nbult.lite.browser

import org.junit.Assert.*
import org.junit.Test

class SignChromaEnhancerTest {
    @Test fun separatesFaintInkFromWashedBackground() {
        val w=100; val h=50
        val pixels=IntArray(w*h) { 0xffbebeb2.toInt() }
        for(y in 15..34) for(x in 30..69) pixels[y*w+x]=0xffaab2b8.toInt()
        SignChromaEnhancer.enhance(pixels,w,h)
        assertTrue((pixels[20*w+40] and 255)<40)
        assertTrue((pixels[25*w+15] and 255)>230)
    }
    @Test fun detectsLowSaturationYellowOnWashedBlue() {
        val w=100; val h=70
        val pixels=IntArray(w*h) { 0xff96a7c7.toInt() }
        for(y in 15..25) for(x in 25..74) pixels[y*w+x]=0xffbebeb2.toInt()
        assertEquals(1,SignColorRegions.detect(pixels,w,h).size)
    }
}
