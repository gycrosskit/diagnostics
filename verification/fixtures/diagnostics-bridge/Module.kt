package com.tencent.kuikly.core.module

import com.tencent.kuikly.compose.coroutines.internal.KuiklyContextScheduler
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

typealias CallbackRef = String
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
        val ref = callbackFn?.let { Transport.register(it) }
        Transport.calls.add(pagerId to ref)
        Transport.inputs.add(JSONObject(data))
        if (Transport.throwAfterCreate) error("native bridge unavailable")
        if (Transport.synchronous) Transport.ready(ref)
        return ReturnValue(ref)
    }
    fun removeCallback(ref: CallbackRef) { check(KuiklyContextScheduler.renderer); Transport.callbacks.remove(ref) }
    fun asyncToNativeMethod(method: String, data: JSONObject?, callbackFn: ((JSONObject?) -> Unit)?) {
        check(KuiklyContextScheduler.renderer && injected)
        Transport.logs.add(pagerId)
    }
}
object Transport {
    var throwAfterCreate = false
    var synchronous = false
    var nextRef = 1
    val callbacks = mutableMapOf<String, (JSONObject?) -> Unit>()
    fun register(callback: (JSONObject?) -> Unit): String = (nextRef++).toString().also { callbacks[it] = callback }
    fun active(ref: String?) = ref in callbacks
    val calls = mutableListOf<Pair<String, CallbackRef?>>()
    val logs = mutableListOf<String>()
    val inputs = mutableListOf<JSONObject>()
    fun ready(ref: CallbackRef?) { callbacks[ref]?.invoke(JSONObject().put("status", "ready")) }
}
