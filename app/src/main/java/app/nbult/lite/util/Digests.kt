package app.nbult.lite.util

import java.io.File
import java.security.MessageDigest

/**
 * 哈希只在这里实现一次。
 *
 * 之前 `ResourcePackStore`、`WeekExpansionCache`、`AppUpdateChecker` 各写了一份
 * SHA-256：改算法或修字节处理时，很容易漏掉其中一处。收敛到这里之后，
 * 三处调用点只保留语义，不再保留实现。
 */
internal object Digests {

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** 流式读取：安装包有二十多兆，整块读进内存没有必要。 */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(digest: ByteArray): String = buildString(digest.size * 2) {
        for (b in digest) append("%02x".format(b))
    }
}
