package io.github.gycrosskit.diagnostics.dingtalk
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.*
import platform.CoreCrypto.*
internal actual fun defaultDingTalkHttpClient() = HttpClient(Darwin)
@OptIn(ExperimentalForeignApi::class)
internal actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    require(key.isNotEmpty() && data.isNotEmpty())
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    key.usePinned { k -> data.usePinned { d -> digest.usePinned { out ->
        CCHmac(kCCHmacAlgSHA256, k.addressOf(0), key.size.toULong(), d.addressOf(0), data.size.toULong(), out.addressOf(0))
    } } }
    return digest
}
