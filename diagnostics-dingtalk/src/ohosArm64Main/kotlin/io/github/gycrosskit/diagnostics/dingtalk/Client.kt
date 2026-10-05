package io.github.gycrosskit.diagnostics.dingtalk

import io.github.gycrosskit.diagnostics.diagnosticFingerprint
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl

internal actual fun defaultDingTalkHttpClient(): HttpClient = HttpClient(Curl)

// API 12 基线不能依赖较新系统的 Native Crypto API；复用同仓库已验证的 SHA-256，按 RFC 2104 组合。
internal actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    hmacSha256UsingDigest(key, data) { diagnosticFingerprint(it).hexToByteArray() }
