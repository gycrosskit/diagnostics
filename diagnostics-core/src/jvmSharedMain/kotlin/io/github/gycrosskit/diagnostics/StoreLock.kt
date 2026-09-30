package io.github.gycrosskit.diagnostics

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal actual class StoreLock actual constructor() {
    private val lock = ReentrantLock()
    actual fun <T> locked(block: () -> T): T = lock.withLock(block)
}
