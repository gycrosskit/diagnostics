package com.tencent.kuikly.core.module

import com.tencent.kuikly.compose.coroutines.internal.KuiklyContextScheduler
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

class CallbackRef(val callback: (JSONObject?) -> Unit) { var active = true }
class ReturnValue(val callbackRef: CallbackRef?)
open class Module {
    var pagerId = ""
    var injected = false
    open fun moduleName(): String = ""
    fun syncToNativeMethod(method: String, data: JSONObject?, callbackFn: ((JSONObject?) -> Unit)?): String {
        check(KuiklyContextScheduler.renderer && pagerId.isNotBlank())
        return JSONObject().put("provider", "bugly").put("appId", "app").put("appKey", "key")
            .put("brandId", "chonghe").put("environment", "qa").toString()
    }
    fun toNative(keepAlive: Boolean, method: String, data: String, callbackFn: ((JSONObject?) -> Unit)?, sync: Boolean): ReturnValue {
        check(KuiklyContextScheduler.renderer && injected)
        val ref = callbackFn?.let(::CallbackRef)
        Transport.calls.add(pagerId to ref)
        return ReturnValue(ref)
    }
    fun removeCallback(ref: CallbackRef) { check(KuiklyContextScheduler.renderer); ref.active = false }
    fun asyncToNativeMethod(method: String, data: JSONObject?, callbackFn: ((JSONObject?) -> Unit)?) {
        check(KuiklyContextScheduler.renderer && injected)
        Transport.logs.add(pagerId)
    }
}
object Transport {
    val calls = mutableListOf<Pair<String, CallbackRef?>>()
    val logs = mutableListOf<String>()
    fun ready(ref: CallbackRef?) { if (ref?.active == true) ref.callback(JSONObject().put("status", "ready")) }
}
