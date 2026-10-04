package io.github.gycrosskit.diagnostics.dingtalk
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
internal actual fun defaultDingTalkHttpClient() = HttpClient(CIO)
