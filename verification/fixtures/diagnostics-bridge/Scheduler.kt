package com.tencent.kuikly.compose.coroutines.internal

object KuiklyContextScheduler {
    var renderer = false
    val closed = mutableSetOf<String>()
    private val tasks = java.util.ArrayDeque<Pair<String, (Boolean) -> Unit>>()
    val pending get() = tasks.size
    fun runOnKuiklyThread(pagerId: String, block: (Boolean) -> Unit) { tasks.addLast(pagerId to block) }
    fun runNext() {
        val (page, action) = tasks.removeFirst()
        renderer = true
        try { action(page in closed) } finally { renderer = false }
    }
    fun drain() { while (tasks.isNotEmpty()) runNext() }
    fun onRenderer(block: () -> Unit) { renderer = true; try { block() } finally { renderer = false } }
}
