package app.nbult.lite.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WashedProjectionTest {
    @Test fun comparePrivateProjectionPhoto() {
        val context=InstrumentationRegistry.getInstrumentation().context
        org.junit.Assume.assumeTrue("Private OCR fixture not supplied", context.assets.list("")?.contains("washed.jpg") == true)
        val source=BitmapFactory.decodeStream(context.assets.open("washed.jpg"))
        val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            var recognized: String?=null
            for((name, image) in listOf("old" to BaselineImagePreprocessor.prepare(source,0),
                "adaptive" to SignImagePreprocessor.prepare(source,0),
                "colour" to SignImagePreprocessor.prepare(source,2))) {
                java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"ocr-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                val start=android.os.SystemClock.elapsedRealtime()
                val result=Tasks.await(recognizer.process(InputImage.fromBitmap(image,0)),30,TimeUnit.SECONDS)
                val lines=result.textBlocks.flatMap { it.lines }.mapNotNull { line -> line.boundingBox?.let { OcrLine(line.text,it.left,it.top,it.right,it.bottom) } }
                val candidate=SignCodeRecognition.candidate(lines)
                println("OCR_TEST $name size=${image.width}x${image.height} elapsed=${android.os.SystemClock.elapsedRealtime()-start} candidate=$candidate text=${result.text.take(150)}")
                if(name!="old" && candidate!=null) recognized=candidate
                image.recycle()
            }
            assertNotNull("Enhanced photo should produce a four-digit candidate",recognized)
        } finally { source.recycle();recognizer.close() }
    }
}
