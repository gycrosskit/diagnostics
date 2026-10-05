package io.github.gycrosskit.diagnostics.dingtalk

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals

class HmacDigestTest {
    @Test fun sharedDigestCompositionMatchesPlatformHmac() {
        for (length in listOf(1, 20, 64, 65, 131)) {
            val key = ByteArray(length) { (it * 17).toByte() }
            val data = "1700000000000\nfixture 中".encodeToByteArray()
            assertContentEquals(hmacSha256(key, data), hmacSha256UsingDigest(key, data) {
                MessageDigest.getInstance("SHA-256").digest(it)
            })
        }
    }
}
