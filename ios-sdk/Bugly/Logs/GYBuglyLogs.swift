import Foundation
import TDOS_Diagnose

/// TDOS 的指定路径命令也经过此出口，只允许读取厂商日志缓存，不能越界到业务存储。
final class DiagnosticLogFilePacker: NSObject, TDLogFilePackerProtocol {
    private let logDirectory: URL
    private let packer = TDLogFilePackerImp()

    init(logDirectory: String) {
        self.logDirectory = URL(fileURLWithPath: logDirectory).standardizedFileURL
        super.init()
    }

    func packFiles(_ files: [String], withZipName zipName: String) -> String? {
        guard !files.isEmpty, !zipName.isEmpty, zipName != ".", zipName != "..",
              !zipName.contains("/"), !zipName.contains("\\"),
              !zipName.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
              files.allSatisfy({ permitsFile($0, in: logDirectory) }),
              let temporaryDirectory = packer.packFilesTempDir(),
              let archive = packer.packFiles(files, withZipName: zipName),
              permitsFile(archive, in: URL(fileURLWithPath: temporaryDirectory).standardizedFileURL) else { return nil }
        return archive
    }

    func packFilesTempDir() -> String? { packer.packFilesTempDir() }
    func getPackerLogExpiredTime() -> Int { packer.getPackerLogExpiredTime() }

    private func permitsFile(_ path: String, in directory: URL) -> Bool {
        guard (path as NSString).isAbsolutePath,
              !(path as NSString).pathComponents.contains("..") else { return false }
        let file = URL(fileURLWithPath: path)
        let parent = file.deletingLastPathComponent().standardizedFileURL
        let canonicalDirectory = directory.resolvingSymlinksInPath()
        // /var 与 /private/var 等系统目录别名可以等价；不接受任意额外的父目录 symlink。
        guard (parent == directory || parent == canonicalDirectory),
              parent.resolvingSymlinksInPath() == canonicalDirectory,
              let values = try? file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey]),
              values.isRegularFile == true, values.isSymbolicLink != true else { return false }
        return true
    }
}

final class VendorLogDataSource: NSObject, TDLogSDKDataSource {
    private let deviceId: String
    private let version: String
    init(deviceId: String, versionName: String) { self.deviceId = deviceId; version = versionName }
    func guidForTDLog() -> String { deviceId }
    func appVersion() -> String? { version }
}

/// 独立 suite 仅保存 TDOS 任务元数据，避免供应商重新初始化业务使用的 MMKV。
final class DiagnosticVendorLogStore: NSObject {
    private let suite: String
    private let defaults: UserDefaults
    init(name: String) {
        suite = "GYCrossKit.Diagnostics.TDOS." + name
        defaults = UserDefaults(suiteName: suite)!
        super.init()
    }
    func containsKey(_ key: String) -> Bool { defaults.object(forKey: key) != nil }
    func allKeys() -> [Any]? { Array((defaults.persistentDomain(forName: suite) ?? [:]).keys) }
    func setData(_ value: Data?, forKey key: String) -> Bool { defaults.set(value, forKey: key); return true }
    func data(forKey key: String) -> Data? { defaults.data(forKey: key) }
    func removeValue(forKey key: String) { defaults.removeObject(forKey: key) }
    func removeValues(forKeys keys: [String]) { keys.forEach { defaults.removeObject(forKey: $0) } }
    func setString(_ value: String?, forKey key: String) -> Bool { defaults.set(value, forKey: key); return true }
    func string(forKey key: String) -> String? { defaults.string(forKey: key) }
    func setNumber(_ value: NSNumber?, forKey key: String) -> Bool { defaults.set(value, forKey: key); return true }
    func number(forKey key: String) -> NSNumber? { defaults.object(forKey: key) as? NSNumber }
    func setBool(_ value: Bool, forKey key: String) -> Bool { defaults.set(value, forKey: key); return true }
    func bool(forKey key: String) -> Bool { defaults.bool(forKey: key) }
    func setObject(_ object: (NSCoding & NSObjectProtocol)?, forKey key: String) -> Bool {
        guard let object else { defaults.removeObject(forKey: key); return true }
        guard let data = try? NSKeyedArchiver.archivedData(withRootObject: object, requiringSecureCoding: false) else { return false }
        defaults.set(data, forKey: key)
        return true
    }
    func object(forKey key: String, with cls: AnyClass) -> Any? {
        guard let data = defaults.data(forKey: key), let decoder = try? NSKeyedUnarchiver(forReadingFrom: data) else { return nil }
        defer { decoder.finishDecoding() }
        decoder.requiresSecureCoding = false
        decoder.decodingFailurePolicy = .setErrorAndReturn
        return decoder.decodeObject(of: [cls, NSString.self, NSNumber.self, NSData.self, NSDate.self,
                                         NSArray.self, NSDictionary.self, NSSet.self], forKey: NSKeyedArchiveRootObjectKey)
    }
    func clearAll() { defaults.removePersistentDomain(forName: suite) }
}

extension DiagnosticVendorLogStore: RAFTKVStorageProtocol {}
final class VendorLogStorageFactory: NSObject, RAFTKVStorageFactoryProtocol {
    func createKVStorage(withName name: String) -> any RAFTKVStorageProtocol { DiagnosticVendorLogStore(name: name) }
}
