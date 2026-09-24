import Foundation

/// Structured concurrency client connecting to the CodeLens backend server.
public final class CodeLensClient: Sendable {
    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    /// Fetches the latest JVM and process metrics asynchronously.
    public func fetchMetrics(baseURL: URL) async throws -> JVMMetrics {
        let endpoint = baseURL.appendingPathComponent("api/process-hub/jvm")
        var request = URLRequest(url: endpoint)
        request.timeoutInterval = 5.0

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw CodeLensClientError.networkFailure(error.localizedDescription)
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw CodeLensClientError.serverUnavailable(statusCode: 0)
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            throw CodeLensClientError.serverUnavailable(statusCode: httpResponse.statusCode)
        }

        do {
            guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let system = json["system"] as? [String: Any],
                  let heap = system["heap"] as? [String: Any],
                  let threads = system["threads"] as? [String: Any] else {
                throw CodeLensClientError.decodingFailure("Malformed JSON structure")
            }

            let usedHeap = (heap["usedMb"] as? NSNumber)?.doubleValue ?? 0.0
            let maxHeap = (heap["maxMb"] as? NSNumber)?.doubleValue ?? 2048.0
            let threadCount = (threads["threadCount"] as? NSNumber)?.intValue ?? 0
            let deadlockCount = (threads["deadlockedCount"] as? NSNumber)?.intValue ?? 0
            let watchdogArmed = (json["watchdogArmed"] as? Bool) ?? true
            let circuitBreaker = (json["circuitBreakerTripped"] as? Bool) ?? false
            let classesCount = (system["indexedClasses"] as? NSNumber)?.intValue ?? 0

            return JVMMetrics(
                usedHeapMb: usedHeap,
                maxHeapMb: maxHeap,
                activeThreads: threadCount,
                deadlockCount: deadlockCount,
                watchdogArmed: watchdogArmed,
                circuitBreakerTripped: circuitBreaker,
                indexedClassesCount: classesCount
            )
        } catch {
            throw CodeLensClientError.decodingFailure(error.localizedDescription)
        }
    }

    /// Requests immediate Garbage Collection on the CodeLens server.
    public func triggerGC(baseURL: URL) async throws -> Double {
        let endpoint = baseURL.appendingPathComponent("api/process-hub/jvm/gc")
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 8.0

        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
            throw CodeLensClientError.serverUnavailable(statusCode: (response as? HTTPURLResponse)?.statusCode ?? 500)
        }

        if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let freedBytes = (json["freedBytes"] as? NSNumber)?.doubleValue {
            return freedBytes / (1024.0 * 1024.0)
        }
        return 0.0
    }
}
