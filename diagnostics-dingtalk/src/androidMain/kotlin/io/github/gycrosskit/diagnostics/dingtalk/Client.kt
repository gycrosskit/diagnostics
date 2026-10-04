package io.github.gycrosskit.diagnostics.dingtalk
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
internal actual fun defaultDingTalkHttpClient() = HttpClient(Android)
