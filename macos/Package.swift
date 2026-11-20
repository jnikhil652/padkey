// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "PadKey",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(name: "PadKey", path: "Sources/PadKey")
    ]
)
