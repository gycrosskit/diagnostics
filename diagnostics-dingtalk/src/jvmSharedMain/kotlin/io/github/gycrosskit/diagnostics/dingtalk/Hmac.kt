package io.github.gycrosskit.diagnostics.dingtalk
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
internal actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
}
