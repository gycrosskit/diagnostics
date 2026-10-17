import Foundation
import GYDiagnosticsSdk
import UMCommon
import UMAPM
@testable import GYDiagnosticsBugly
import BuglyPro
import TDOS_Diagnose
import GYDiagnosticsUmeng

@main
struct NativeSdkTestMain {
    static func main() throws {
        let mode = CommandLine.arguments[1]
        var handoffs = 0
        let suite = "test-" + UUID().uuidString
        let store = DiagnosticVendorLogStore(name: suite)
        defer { store.clearAll() }
        precondition(store.setString("value", forKey: "string") && store.string(forKey: "string") == "value")
        precondition(store.setData(Data([1, 2]), forKey: "data") && store.data(forKey: "data") == Data([1, 2]))
        precondition(store.setNumber(23, forKey: "number") && store.number(forKey: "number") == 23)
        precondition(store.setBool(true, forKey: "bool") && store.bool(forKey: "bool"))
        let object = ["key": "value"] as NSDictionary
        precondition(store.setObject(object, forKey: "object"))
        precondition((store.object(forKey: "object", with: NSDictionary.self) as? NSDictionary) == object)
        precondition(DiagnosticVendorLogStore(name: suite).string(forKey: "string") == "value")
        let isolated = DiagnosticVendorLogStore(name: suite + "-isolated")
        precondition(!isolated.containsKey("string"))
        store.removeValues(forKeys: ["string", "data"])
        precondition(!store.containsKey("string") && !store.containsKey("data"))
        precondition(store.allKeys()?.count == 3)

        if mode == "bugly-log-failure" {
            let manager = FileManager.default
            let directory = manager.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            let logs = directory.appendingPathComponent("logs")
            try! manager.createDirectory(at: logs, withIntermediateDirectories: true)
            defer { try? manager.removeItem(at: directory) }
            let safeFile = logs.appendingPathComponent("business.xlog")
            let rawFile = directory.appendingPathComponent("raw-diagnostics.txt")
            try! Data("filtered log".utf8).write(to: safeFile)
            try! Data("private raw data".utf8).write(to: rawFile)
            let symlink = logs.appendingPathComponent("link.xlog")
            try! manager.createSymbolicLink(at: symlink, withDestinationURL: rawFile)
            let alias = directory.appendingPathComponent("logs-alias")
            try! manager.createSymbolicLink(at: alias, withDestinationURL: logs)
            let packer = DiagnosticLogFilePacker(logDirectory: logs.path)
            defer { if let path = packer.packFilesTempDir() { try? manager.removeItem(atPath: path) } }
            let initialPacks = TDLogFilePackerImp.packs
            precondition(packer.packFiles([safeFile.path], withZipName: "safe.zip") != nil)
            precondition(packer.packFiles([safeFile.resolvingSymlinksInPath().path], withZipName: "canonical.zip") != nil)
            let validPacks = TDLogFilePackerImp.packs
            precondition(validPacks == initialPacks + 2)
            for files in [[], [rawFile.path], [symlink.path], [logs.path],
                          [logs.path + "/../logs/business.xlog"], [safeFile.path, rawFile.path],
                          [directory.path + "/logs-other/business.xlog"], [alias.appendingPathComponent("business.xlog").path], ["logs/business.xlog"]] {
                precondition(packer.packFiles(files, withZipName: "rejected.zip") == nil)
            }
            for name in ["", "..", "../escape.zip", "/outside.zip", "sub\\escape.zip", "bad\0name"] {
                precondition(packer.packFiles([safeFile.path], withZipName: name) == nil)
            }
            precondition(TDLogFilePackerImp.packs == validPacks)
            TDLogFilePackerImp.outputOverride = rawFile.path
            precondition(packer.packFiles([safeFile.path], withZipName: "outside-output.zip") == nil)
            TDLogFilePackerImp.outputOverride = nil

        }
        if mode.hasPrefix("bugly") {
            let sdk = GYBuglyDiagnosticSdk()
            sdk.writeLog(level: "ERROR", tag: "test", message: "before init")
            precondition(TDOSLogger.levels.isEmpty)
            let logs = mode == "bugly-log-failure" || mode == "bugly-log-only" || mode == "bugly-both"
            let crashes = mode != "bugly-log-only" && mode != "bugly-disabled"
            let fails = mode == "bugly-performance" || mode == "bugly-disabled" || mode == "bugly-invalid" || mode == "bugly-log-failure"
            TDLogSDK.startResult = mode != "bugly-log-failure"
            let config = GYDiagnosticSdkConfiguration(appId: mode == "bugly-invalid" ? "" : "test-id", appKey: "test-key", channel: "test-channel",
                                                     crashes: crashes, performance: mode == "bugly-performance", remoteLogs: logs)
            do {
                try sdk.initialize(configuration: config, deviceId: "installation-test", versionName: "1.2.3", buildNumber: "45") { handoffs += 1 }
                precondition(!fails)
                precondition(handoffs == (crashes ? 1 : 0) && Bugly.starts == (crashes ? 1 : 0))
                if crashes {
                    precondition(Bugly.modules == [BUGLY_MODULE_CRASH])
                    precondition(Bugly.config?.clientTag == "installation-test" && Bugly.config?.appVersion == "1.2.3")
                } else { precondition(Bugly.config == nil) }
                precondition(TDLogSDK.starts == (logs ? 1 : 0))
                try sdk.initialize(configuration: config, deviceId: "changed", versionName: "changed", buildNumber: "46") { handoffs += 1 }
                precondition(handoffs == (crashes ? 1 : 0) && Bugly.starts == (crashes ? 1 : 0))
                precondition(TDLogSDK.starts == (logs ? 1 : 0))
                for level in ["ERROR", "WARN", "DEBUG", "INFO"] { sdk.writeLog(level: level, tag: "test", message: "safe") }
                precondition(TDOSLogger.levels.count == (logs ? 4 : 0))
            } catch {
                precondition(fails)
                precondition(handoffs == 0 && Bugly.starts == 0)
                precondition(TDLogSDK.starts == (mode == "bugly-log-failure" ? 1 : 0))
                sdk.writeLog(level: "ERROR", tag: "test", message: "after failure")
                precondition(TDOSLogger.levels.isEmpty)
            }
            return
        }
        let sdk = GYUmengDiagnosticSdk()
        sdk.writeLog(level: "ERROR", tag: "test", message: "before init")
        precondition(UAPMLog.levels.isEmpty && UMConfigure.starts == 0)
        let remote = mode == "umeng-logs" || mode == "umeng-log-only"
        let crashes = mode != "umeng-log-only" && mode != "umeng-disabled"
        let config = GYDiagnosticSdkConfiguration(appKey: mode == "umeng-invalid" ? "" : "test-key", channel: "test-channel",
                                                 crashes: crashes, performance: mode == "umeng-performance", remoteLogs: remote)
        do {
            try sdk.initialize(configuration: config, deviceId: "installation-test", versionName: "1.2.3", buildNumber: "45") { handoffs += 1 }
            precondition(mode != "umeng-performance" && mode != "umeng-invalid" && mode != "umeng-disabled")
        } catch {
            precondition(mode == "umeng-performance" || mode == "umeng-invalid" || mode == "umeng-disabled")
            precondition(handoffs == 0 && UMConfigure.starts == 0 && UMCrashConfigure.config == nil)
            return
        }
        precondition(handoffs == (crashes ? 1 : 0) && UMConfigure.starts == 1)
        let flags = UMCrashConfigure.config!
        precondition(flags.crashAndBlockMonitorEnable == crashes && flags.logCollectEnable == remote)
        precondition(!flags.launchMonitorEnable && !flags.memMonitorEnable && !flags.oomMonitorEnable && !flags.networkEnable)
        precondition(!flags.javaScriptBridgeEnable && !flags.pageMonitorEnable)
        precondition(flags.logCollectUserId == "installation-test")
        precondition(UMCrashConfigure.version == "1.2.3" && UMCrashConfigure.build == "45")
        precondition(!UMConfigure.analyticsEnabled && !UMConfigure.logEnabled)
        for level in ["ERROR", "WARN", "DEBUG", "INFO"] { sdk.writeLog(level: level, tag: "test", message: "safe") }
        precondition(UAPMLog.levels == (remote ? ["error", "warn", "debug", "info"] : []))
        try sdk.initialize(configuration: config, deviceId: "changed", versionName: "changed", buildNumber: "46") { handoffs += 1 }
        precondition(UMConfigure.starts == 1 && handoffs == (crashes ? 1 : 0))
    }
}
