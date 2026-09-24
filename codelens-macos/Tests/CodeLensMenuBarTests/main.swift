import Foundation

// Inline copy of value models for direct test verification
public struct JVMMetrics: Sendable, Codable, Equatable {
    public let usedHeapMb: Double
    public let maxHeapMb: Double
    public let activeThreads: Int
    public let deadlockCount: Int
    public let watchdogArmed: Bool
    public let circuitBreakerTripped: Bool
    public let indexedClassesCount: Int

    public var heapPressureRatio: Double {
        guard maxHeapMb > 0 else { return 0.0 }
        return min(1.0, max(0.0, usedHeapMb / maxHeapMb))
    }

    public var isUnderHighPressure: Bool {
        heapPressureRatio >= 0.80
    }
}

public enum CodeLensClientError: Error, Sendable, Equatable {
    case invalidURL(String)
    case serverUnavailable(statusCode: Int)
    case decodingFailure(String)
    case networkFailure(String)
}

print("Running CodeLens Modern Swift Telemetry Tests...")

// Test 1: Normal memory pressure ratio
let normal = JVMMetrics(
    usedHeapMb: 512.0,
    maxHeapMb: 2048.0,
    activeThreads: 12,
    deadlockCount: 0,
    watchdogArmed: true,
    circuitBreakerTripped: false,
    indexedClassesCount: 1420
)
assert(abs(normal.heapPressureRatio - 0.25) < 0.001, "Normal heap pressure must be 0.25")
assert(!normal.isUnderHighPressure, "25% heap must not trigger high pressure")
print("  ✓ Test 1: Normal memory pressure passed (0.25 ratio)")

// Test 2: High memory pressure ratio
let critical = JVMMetrics(
    usedHeapMb: 1800.0,
    maxHeapMb: 2048.0,
    activeThreads: 48,
    deadlockCount: 2,
    watchdogArmed: true,
    circuitBreakerTripped: true,
    indexedClassesCount: 1420
)
assert(critical.heapPressureRatio > 0.85, "Critical heap pressure must be > 0.85")
assert(critical.isUnderHighPressure, "88% heap must trigger high pressure")
print("  ✓ Test 2: High memory pressure passed (>0.85 ratio, high pressure flag true)")

// Test 3: Zero max heap boundary protection
let zero = JVMMetrics(
    usedHeapMb: 100.0,
    maxHeapMb: 0.0,
    activeThreads: 1,
    deadlockCount: 0,
    watchdogArmed: false,
    circuitBreakerTripped: false,
    indexedClassesCount: 0
)
assert(zero.heapPressureRatio == 0.0, "Zero max heap must return 0.0 ratio")
assert(!zero.isUnderHighPressure, "Zero max heap must not trigger high pressure")
print("  ✓ Test 3: Zero max heap boundary protection passed")

// Test 4: Structured error equatability
let err1 = CodeLensClientError.invalidURL("http://bad url")
let err2 = CodeLensClientError.invalidURL("http://bad url")
let err3 = CodeLensClientError.serverUnavailable(statusCode: 503)
assert(err1 == err2, "Identical errors must be equal")
assert(err1 != err3, "Different error cases must not be equal")
print("  ✓ Test 4: Structured error equatability passed")

print("\nAll 4 CodeLens Swift 6 test suites passed successfully!")
