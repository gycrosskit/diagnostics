package io.github.gycrosskit.diagnostics.consumer.r8;

import android.app.Application;
import io.github.gycrosskit.diagnostics.sdk.AndroidDiagnosticSdkFactoryKt;

/** 只让正式 SPI 工厂在 R8 中可达；不调用 initialize，也不启动供应商 SDK。 */
public final class R8Application extends Application {
    @Override public void onCreate() {
        super.onCreate();
        if (!"none".equals(BuildConfig.DIAGNOSTICS_PROVIDER)) {
            AndroidDiagnosticSdkFactoryKt.installedAndroidDiagnosticSdkBridge(this);
        }
    }
}
