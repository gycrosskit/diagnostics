import GYDiagnosticsSdk

/// 一个应用只接入一个供应商 Pod；配置与实际链接组件不一致时拒绝启动。
public enum GYDiagnosticSdkProviderFactory {
    public static func create(provider: String) throws -> any GYDiagnosticSdkAdapter {
        guard provider == "bugly" else { throw GYDiagnosticSdkError.unsupported("iOS diagnostics provider: " + provider) }
        return GYBuglyDiagnosticSdk()
    }
}
