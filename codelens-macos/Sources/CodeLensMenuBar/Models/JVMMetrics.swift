import Foundation

/// Value type representing real-time JVM telemetry reported by CodeLens.
public struct JVMMetrics: Sendable, Codable, Equatable {
    public let usedHeapMb: Double
    public let maxHeapMb: Double
    public let activeThreads: Int
    public let deadlockCount: Int
    public let watchdogArmed: Bool
    public let circuitBreakerTripped: Bool
    public let indexedClassesCount: Int

    public init(
        usedHeapMb: Double,
        maxHeapMb: Double,
        activeThreads: Int,
        deadlockCount: Int,
        watchdogArmed: Bool,
        circuitBreakerTripped: Bool,
        indexedClassesCount: Int
    ) {
        self.usedHeapMb = usedHeapMb
        self.maxHeapMb = maxHeapMb
        self.activeThreads = activeThreads
        self.deadlockCount = deadlockCount
        self.watchdogArmed = watchdogArmed
        self.circuitBreakerTripped = circuitBreakerTripped
        self.indexedClassesCount = indexedClassesCount
    }

    /// Calculated memory pressure ratio (0.0 to 1.0).
    public var heapPressureRatio: Double {
        guard maxHeapMb > 0 else { return 0.0 }
        return min(1.0, max(0.0, usedHeapMb / maxHeapMb))
    }

    /// True if memory pressure exceeds the 80% watermark.
    public var isUnderHighPressure: Bool {
        heapPressureRatio >= 0.80
    }
}

/// Structured error enum with contextual information according to Section 2 of write-swift.
public enum CodeLensClientError: Error, Sendable, Equatable {
    case invalidURL(String)
    case serverUnavailable(statusCode: Int)
    case decodingFailure(String)
    case networkFailure(String)
}
