// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "CodeLensMenuBar",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .executable(name: "CodeLensMenuBar", targets: ["CodeLensMenuBar"]),
        .executable(name: "CodeLensTests", targets: ["CodeLensTests"])
    ],
    targets: [
        .executableTarget(
            name: "CodeLensMenuBar",
            dependencies: [],
            path: "Sources/CodeLensMenuBar",
            swiftSettings: [
                .enableUpcomingFeature("StrictConcurrency")
            ]
        ),
        .executableTarget(
            name: "CodeLensTests",
            dependencies: [],
            path: "Tests/CodeLensMenuBarTests",
            swiftSettings: [
                .enableUpcomingFeature("StrictConcurrency")
            ]
        )
    ]
)
