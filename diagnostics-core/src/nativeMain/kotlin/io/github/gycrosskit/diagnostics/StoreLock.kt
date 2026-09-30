package io.github.gycrosskit.diagnostics

import kotlinx.cinterop.*
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner
import platform.posix.*

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal actual class StoreLock actual constructor(recursive: Boolean) {
    private val mutex = nativeHeap.alloc<pthread_mutex_t>().also {
        memScoped {
            val attributes = alloc<pthread_mutexattr_t>()
            check(pthread_mutexattr_init(attributes.ptr) == 0)
            try {
                if (recursive) check(pthread_mutexattr_settype(attributes.ptr, PTHREAD_MUTEX_RECURSIVE) == 0)
                check(pthread_mutex_init(it.ptr, attributes.ptr) == 0)
            } finally {
                pthread_mutexattr_destroy(attributes.ptr)
            }
        }
    }
    // 由对象生命周期回收锁，避免 close 与等待中的回调发生 use-after-free。
    private val cleaner = createCleaner(mutex.ptr) { pointer ->
        pthread_mutex_destroy(pointer)
        nativeHeap.free(pointer)
    }
    actual fun <T> locked(block: () -> T): T {
        check(pthread_mutex_lock(mutex.ptr) == 0)
        return try { block() } finally { check(pthread_mutex_unlock(mutex.ptr) == 0) }
    }
}

internal actual fun platformMove(source: kotlinx.io.files.Path, destination: kotlinx.io.files.Path) =
    kotlinx.io.files.SystemFileSystem.atomicMove(source, destination)
