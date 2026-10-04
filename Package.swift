// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "GYDiagnosticsNative",
    platforms: [.iOS(.v14)],
    products: [.library(name: "GYDiagnosticsNative", targets: ["GYDiagnosticsNative"])],
    targets: [.target(name: "GYDiagnosticsNative", path: "ios-support")]
)
