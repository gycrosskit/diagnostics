package com.tencent.kuikly.core.nvi.serialization.json

class JSONObject {
    private val values = mutableMapOf<String, Any?>()
    constructor()
    constructor(encoded: String) { values.putAll(checkNotNull(objects[encoded]).values) }
    fun put(key: String, value: Any?): JSONObject { values[key] = value; return this }
    fun optString(key: String): String = values[key] as? String ?: ""
    fun optJSONObject(key: String): JSONObject? = values[key] as? JSONObject
    fun optBoolean(key: String, fallback: Boolean): Boolean = values[key] as? Boolean ?: fallback
    override fun toString(): String = "fixture:${objects.size}".also { objects[it] = this }
    companion object { private val objects = mutableMapOf<String, JSONObject>() }
}
