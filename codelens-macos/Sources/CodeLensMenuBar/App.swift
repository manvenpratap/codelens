import AppKit
import Foundation

@MainActor
final class CodeLensStatusItemController: NSObject, NSMenuDelegate {
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let client = CodeLensClient()
    private let serverPort: Int = 7878
    private var currentMetrics: JVMMetrics?

    var baseURL: URL {
        URL(string: "http://localhost:\(serverPort)")!
    }

    func setup() {
        if let button = statusItem.button {
            button.title = "CodeLens"
            button.image = NSImage(systemSymbolName: "cpu", accessibilityDescription: "CodeLens")
        }
        rebuildMenu()
        Task {
            await refreshTelemetry()
        }
    }

    func rebuildMenu() {
        let menu = NSMenu()
        menu.delegate = self

        let headerItem = NSMenuItem(title: "CodeLens Architecture Sentinel", action: nil, keyEquivalent: "")
        headerItem.isEnabled = false
        menu.addItem(headerItem)
        menu.addItem(NSMenuItem.separator())

        if let m = currentMetrics {
            let heapText = String(format: "Heap: %.1f MB / %.0f MB (%.0f%%)", m.usedHeapMb, m.maxHeapMb, m.heapPressureRatio * 100)
            let heapItem = NSMenuItem(title: heapText, action: nil, keyEquivalent: "")
            heapItem.isEnabled = false
            menu.addItem(heapItem)

            let threadsText = "Threads: \(m.activeThreads) active · \(m.deadlockCount) deadlocks"
            let threadItem = NSMenuItem(title: threadsText, action: nil, keyEquivalent: "")
            threadItem.isEnabled = false
            menu.addItem(threadItem)

            let watchdogText = "Watchdog: " + (m.watchdogArmed ? "Armed & Monitoring" : "Standby")
            let watchdogItem = NSMenuItem(title: watchdogText, action: nil, keyEquivalent: "")
            watchdogItem.isEnabled = false
            menu.addItem(watchdogItem)
        } else {
            let statusItem = NSMenuItem(title: "Status: Connecting to :\(serverPort)…", action: nil, keyEquivalent: "")
            statusItem.isEnabled = false
            menu.addItem(statusItem)
        }

        menu.addItem(NSMenuItem.separator())

        let gcItem = NSMenuItem(title: "Trigger Garbage Collection (GC)", action: #selector(triggerGC), keyEquivalent: "g")
        gcItem.target = self
        menu.addItem(gcItem)

        let openWebItem = NSMenuItem(title: "Open CodeLens Web Dashboard", action: #selector(openWeb), keyEquivalent: "o")
        openWebItem.target = self
        menu.addItem(openWebItem)

        let refreshItem = NSMenuItem(title: "Refresh Telemetry", action: #selector(triggerRefresh), keyEquivalent: "r")
        refreshItem.target = self
        menu.addItem(refreshItem)

        menu.addItem(NSMenuItem.separator())

        let quitItem = NSMenuItem(title: "Quit", action: #selector(quit), keyEquivalent: "q")
        quitItem.target = self
        menu.addItem(quitItem)

        statusItem.menu = menu
    }

    @objc func triggerGC() {
        Task {
            _ = try? await client.triggerGC(baseURL: baseURL)
            await refreshTelemetry()
        }
    }

    @objc func openWeb() {
        NSWorkspace.shared.open(baseURL)
    }

    @objc func triggerRefresh() {
        Task {
            await refreshTelemetry()
        }
    }

    @objc func quit() {
        NSApplication.shared.terminate(nil)
    }

    func refreshTelemetry() async {
        do {
            currentMetrics = try await client.fetchMetrics(baseURL: baseURL)
            if let button = statusItem.button, let m = currentMetrics {
                button.title = String(format: "CodeLens: %.0f%%", m.heapPressureRatio * 100)
            }
        } catch {
            currentMetrics = nil
            if let button = statusItem.button {
                button.title = "CodeLens (offline)"
            }
        }
        rebuildMenu()
    }
}

@main
struct CodeLensMenuBarMain {
    @MainActor
    static func main() {
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        let controller = CodeLensStatusItemController()
        controller.setup()
        app.run()
    }
}
