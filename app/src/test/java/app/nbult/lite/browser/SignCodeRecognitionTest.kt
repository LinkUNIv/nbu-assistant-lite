package app.nbult.lite.browser

import org.junit.Assert.*
import org.junit.Test

class SignCodeRecognitionTest {
    @Test fun rejectsLettersAndWrongDigitCounts() {
        for (value in listOf("A1234", "1234B", "123", "12345", "12A4")) {
            assertNull(value, SignCodeRecognition.candidate(listOf(OcrLine("签到码：$value",0,0,300,30))))
        }
    }
    @Test fun prefersLabelOverYearAndStudentNumber() {
        assertEquals("6855", SignCodeRecognition.candidate(listOf(
            OcrLine("2026/11/02 10:00",0,0,400,20),
            OcrLine("签到码：6855",20,50,260,80),
            OcrLine("236001749",20,130,200,150)
        )))
    }
    @Test fun readsCodeToRightOfLabel() {
        assertEquals("0123", SignCodeRecognition.candidate(listOf(
            OcrLine("签到码",10,40,90,70), OcrLine("0123",100,42,180,72)
        )))
    }
    @Test fun rejectsAmbiguousLabeledCodes() {
        assertNull(SignCodeRecognition.candidate(listOf(
            OcrLine("签到码 1234",0,0,200,25), OcrLine("签到码 5678",0,70,200,95)
        )))
    }
    @Test fun rejectsDateAndLongIdentifiers() {
        assertNull(SignCodeRecognition.candidate(listOf(
            OcrLine("2026",0,0,80,30), OcrLine("236001749",0,50,180,70)
        )))
    }
    @Test fun normalizesFullWidthAndSpacedDigits() {
        assertEquals("0123", SignCodeRecognition.candidate(listOf(OcrLine("签到码 ０ １ ２ ３",0,0,300,30))))
    }
}
