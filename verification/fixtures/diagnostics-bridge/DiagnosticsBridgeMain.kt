import io.github.gycrosskit.diagnostics.kuikly.DiagnosticSdkModule
import io.github.gycrosskit.diagnostics.kuikly.OhosDiagnosticSdkBridge
import com.tencent.kuikly.compose.coroutines.internal.KuiklyContextScheduler as Renderer
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.module.Transport
import kotlinx.coroutines.*

fun main() = runBlocking {
    val root = DiagnosticSdkModule()
    Renderer.onRenderer {
        BridgeManager.currentPageId = "root"
        OhosDiagnosticSdkBridge.attach(root)
        check(!root.injected)
        check(root.configuration().appKey == "key") // 首页 willInit 尚未 injectVar，仍必须读取真实配置。
        root.injected = true
    }
    val configuration = root.configurationOnRenderer()
    val beforeDispatch = async { OhosDiagnosticSdkBridge.initialize(configuration, "installation", "1.0", "2") }
    yield(); beforeDispatch.cancelAndJoin(); Renderer.drain()
    check(Transport.calls.isEmpty())
    val initialized = async { OhosDiagnosticSdkBridge.initialize(configuration, "installation", "1.0", "2") }
    yield()
    check(Transport.calls.isEmpty())
    Renderer.drain()
    check(!initialized.isCompleted)
    val payload = checkNotNull(Transport.inputs.last().optJSONObject("configuration"))
    check(payload.optString("provider") == configuration.provider)
    check(payload.optString("appId") == configuration.appId && payload.optString("appKey") == configuration.appKey)
    check(payload.optString("channel") == configuration.channel)
    check(payload.optString("brandId") == configuration.brandId && payload.optString("environment") == configuration.environment)
    check(payload.optBoolean("crashes", false) == configuration.crashes)
    check(payload.optBoolean("remoteLogs", false) == configuration.remoteLogs)
    check(payload.optBoolean("performance", true) == configuration.performance)
    Renderer.onRenderer { Transport.ready(Transport.calls.last().second) }
    initialized.await()

    repeat(1000) { OhosDiagnosticSdkBridge.writeLog("I", "tag", "message") }
    check(Renderer.pending == 64) // Main 消费快于 Renderer 时也不能无限积累任务。
    Renderer.drain()
    check(Transport.logs.size == 64)

    val audience = DiagnosticSdkModule()
    Renderer.onRenderer {
        BridgeManager.currentPageId = "audience"
        OhosDiagnosticSdkBridge.attach(audience)
        audience.injected = true
    }
    val transferred = async { OhosDiagnosticSdkBridge.initialize(configuration, "installation", "1.0", "2") }
    yield(); Renderer.drain()
    val oldAudienceCallback = Transport.calls.last().second
    OhosDiagnosticSdkBridge.writeLog("I", "tag", "switch")
    Renderer.onRenderer { OhosDiagnosticSdkBridge.detach(audience) }
    Renderer.closed.add("audience")
    yield()
    check(!transferred.isCompleted) // 销毁 transport 不能把进程 SDK 初始化永久取消。
    Renderer.drain()
    check(oldAudienceCallback?.active == false)
    check(Transport.calls.last().first == "root")
    Renderer.onRenderer { Transport.ready(Transport.calls.last().second) }
    transferred.await()
    check(Transport.logs.last() == "root")

    val cancelled = async { OhosDiagnosticSdkBridge.initialize(configuration, "installation", "1.0", "2") }
    yield(); Renderer.drain()
    val pending = Transport.calls.last().second
    cancelled.cancelAndJoin()
    check(pending?.active == true) // Main 取消不能直接删除 Renderer 回执。
    Renderer.drain()
    check(pending?.active == false)

    repeat(1000) { OhosDiagnosticSdkBridge.writeLog("I", "tag", "cancelled-queue") }
    check(Renderer.pending == 64)
    Renderer.drain()
    val destroyed = async { OhosDiagnosticSdkBridge.initialize(configuration, "installation", "1.0", "2") }
    yield(); Renderer.drain()
    val last = Transport.calls.last().second
    Renderer.onRenderer { OhosDiagnosticSdkBridge.detach(root) }
    Renderer.closed.add("root")
    Renderer.drain()
    yield()
    check(!destroyed.isCompleted && last?.active == false)
    OhosDiagnosticSdkBridge.writeLog("I", "tag", "no-page")
    check(Renderer.pending == 0)
    val rebuilt = DiagnosticSdkModule()
    Renderer.onRenderer {
        BridgeManager.currentPageId = "rebuilt"
        OhosDiagnosticSdkBridge.attach(rebuilt)
        rebuilt.injected = true
    }
    yield(); Renderer.drain()
    check(!destroyed.isCompleted && Transport.calls.last().first == "rebuilt")
    Renderer.onRenderer { Transport.ready(Transport.calls.last().second) }
    destroyed.await()
    println("OHOS Kotlin bridge: early config / Renderer / bounded logs / page switch / cancellation / destroy passed")
}

private fun DiagnosticSdkModule.configurationOnRenderer(): io.github.gycrosskit.diagnostics.sdk.DiagnosticSdkConfiguration {
    var result: io.github.gycrosskit.diagnostics.sdk.DiagnosticSdkConfiguration? = null
    Renderer.onRenderer { result = configuration() }
    return checkNotNull(result)
}
