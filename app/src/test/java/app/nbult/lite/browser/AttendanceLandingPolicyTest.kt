package app.nbult.lite.browser
import org.junit.Assert.*
import org.junit.Test
class AttendanceLandingPolicyTest {
 @Test fun loginAndCallbacksAreNeverInterrupted() {
  assertTrue(AttendanceLandingPolicy.isAuthentication("https://uis.nbu.edu.cn/authserver/login?service=x"))
  assertFalse(AttendanceLandingPolicy.shouldReturn("https://uis.nbu.edu.cn/authserver/reAuthCheck/reAuthLoginView.do", true))
  assertFalse(AttendanceLandingPolicy.shouldReturn("https://attendance.nbu.edu.cn/api/getIndex?ticket=x", true))
 }
 @Test fun returnFromSuccessfulLandingPages() {
  assertTrue(AttendanceLandingPolicy.shouldReturn("https://i.nbu.edu.cn/index.html", true))
  assertFalse(AttendanceLandingPolicy.shouldReturn("https://i.nbu.edu.cn/index.html", false))
  assertTrue(AttendanceLandingPolicy.shouldReturn("https://attendance.nbu.edu.cn/h5/student/", false))
 }
 @Test fun neverLoopOnTargetOrMatchOtherHosts() {
  assertFalse(AttendanceLandingPolicy.shouldReturn(AttendanceLandingPolicy.SCHEDULE, true))
  assertFalse(AttendanceLandingPolicy.shouldReturn("https://i.nbu.edu.cn.evil.example/index.html", true))
  assertFalse(AttendanceLandingPolicy.shouldReturn("http://i.nbu.edu.cn/index.html", true))
 }
}
