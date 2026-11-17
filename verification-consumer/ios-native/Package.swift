// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "DiagnosticsNativeConsumer",
    platforms: [.iOS(.v14)],
    products: [.library(name: "DiagnosticsNativeConsumer", targets: ["DiagnosticsNativeConsumer"])],
    dependencies: [.package(url: "https://github.com/gycrosskit/diagnostics.git", exact: "1.0.6")],
    targets: [.target(
        name: "DiagnosticsNativeConsumer",
        dependencies: [.product(name: "GYDiagnosticsNative", package: "diagnostics")],
        path: "Sources"
    )]
)
