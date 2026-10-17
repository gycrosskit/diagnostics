import Foundation
public enum RAFTLogLevel { case info, warn, error, debug }
public protocol RAFTKVStorageProtocol: NSObjectProtocol {}
public protocol RAFTKVStorageFactoryProtocol: NSObjectProtocol {
    func createKVStorage(withName name: String) -> any RAFTKVStorageProtocol
}
public protocol TDLogSDKDataSource: NSObjectProtocol { func guidForTDLog() -> String }
public final class TDOSLoggerConfig {
    public let logPath = NSTemporaryDirectory() + "TDOSLog"
    public static func `default`() -> TDOSLoggerConfig { TDOSLoggerConfig() }
}
public final class TDOSLogger {
    public static var levels: [RAFTLogLevel] = []
    public init(config: TDOSLoggerConfig) {}
    public func setLogLevel(_ value: RAFTLogLevel) {}
    public func setMaxLogAliveTime(_ value: Int) {}
    public func setMaxTotalLogFileSize(_ value: Int64) {}
    public func setConsoleLogEnabled(_ value: Bool) {}
    public func log(_ value: RAFTLogLevel, tag: String, file: String, `func`: String, line: Int32, msg: String) { Self.levels.append(value) }
}
public protocol TDLogFilePackerProtocol: NSObjectProtocol {
    func packFiles(_ files: [String], withZipName zipName: String) -> String?
    func packFilesTempDir() -> String?
    func getPackerLogExpiredTime() -> Int
}
public final class TDLogFilePackerImp: NSObject, TDLogFilePackerProtocol {
    public static var packs = 0
    public static var outputOverride: String?
    public func packFiles(_ files: [String], withZipName zipName: String) -> String? {
        Self.packs += 1
        if let path = Self.outputOverride { return path }
        let directory = URL(fileURLWithPath: packFilesTempDir()!)
        try! FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let output = directory.appendingPathComponent(zipName)
        try! Data([1]).write(to: output)
        return output.path
    }
    public func packFilesTempDir() -> String? { NSTemporaryDirectory() + "TDOSPack-" + String(ProcessInfo.processInfo.processIdentifier) }
    public func getPackerLogExpiredTime() -> Int { 7 * 24 * 60 * 60 }
}
public final class TDIAGDepends {
    public init(logImp: TDOSLogger, kvFactoryImp: any RAFTKVStorageFactoryProtocol, andFilePackerImp: any TDLogFilePackerProtocol) {}
}
public enum TDLogServerHostType { case buglyPro }
public final class TDLogSDKConfig {
    public var serverHostType: TDLogServerHostType = .buglyPro
    public init(appId: String, appKey: String, dataSource: any TDLogSDKDataSource, depends: TDIAGDepends) {}
}
public final class TDLogSDK {
    public static var starts = 0
    public static var startResult = false
    private static let instance = TDLogSDK()
    public static func sharedInstance() -> TDLogSDK { instance }
    public func start(with config: TDLogSDKConfig) -> Bool { Self.starts += 1; return Self.startResult }
}
