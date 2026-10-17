package consumer

import io.github.gycrosskit.diagnostics.sdk.AndroidDiagnosticSdkFactory
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals

class SdkSpiConsumerTest {
    @Test fun selectedRemoteAdapterIsRegisteredExactlyOnce() {
        val provider = System.getProperty("diagnostics.provider", "none")
        val factories = ServiceLoader.load(AndroidDiagnosticSdkFactory::class.java).toList()
        assertEquals(if (provider == "none") 0 else 1, factories.size)
    }
}
