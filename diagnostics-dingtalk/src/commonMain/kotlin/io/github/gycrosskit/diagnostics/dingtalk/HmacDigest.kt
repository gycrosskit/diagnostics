package io.github.gycrosskit.diagnostics.dingtalk

/** 固定 SHA-256 的 64-byte block；digest 必须返回标准 32-byte SHA-256，不持有密钥。 */
internal fun hmacSha256UsingDigest(key: ByteArray, data: ByteArray, digest: (ByteArray) -> ByteArray): ByteArray {
    val normalized = if (key.size > 64) digest(key) else key
    val inner = ByteArray(64) { ((normalized.getOrNull(it)?.toInt() ?: 0) xor 0x36).toByte() }
    val outer = ByteArray(64) { ((normalized.getOrNull(it)?.toInt() ?: 0) xor 0x5c).toByte() }
    return digest(outer + digest(inner + data))
}
