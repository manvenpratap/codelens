# CodeLens macOS Menu Bar Companion

Built following the **write-swift** architectural guidelines for Swift 6.3+:
- **Value Types by Default**: `JVMMetrics` modeled with pure immutable `Sendable` / `Codable` structs.
- **Approachable Concurrency**: `async/await` with `URLSession` data tasks without unsafe threading primitives or bare unmanaged pointers.
- **SwiftUI Concurrency**: Seamless integration with `MenuBarExtra` keeping UI state mutations synchronous and offloading network queries to scoped structured tasks.
- **Modern Swift Testing**: Test suite built on `@Suite`, `@Test`, and `#expect` assertions without legacy XCTest overhead.
- **Zero-Latency Telemetry**: Instant heap usage indicators, thread deadlock alert badges, and quick Garbage Collection triggering.
