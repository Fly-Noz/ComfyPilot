import Foundation
import SwiftUI

struct TaskRow: Identifiable {
    let id: String
    let promptId: String
    let name: String
    let state: String
    let percent: Double
    let node: String
    let outputs: Int
    let error: String?

    var isActive: Bool { state == "Running" || state == "Queued" || state == "Submitting" }
}

struct CatalogRow: Identifiable {
    var id: String { path }
    let path: String
    let name: String
}

/// 状态帧里这一版真正用得上的字段。其余字段原样躺在 JSON 里，等对应界面做出来再接。
struct SessionSnapshot {
    var phase: String = "Idle"
    var label: String = ""
    var generation: Int = 0
    var queueRunning: Int = 0
    var queuePending: Int = 0
    var wsTextFrames: Int = 0
    var wsBinaryFrames: Int = 0
    var tasks: [TaskRow] = []
    var catalog: [CatalogRow] = []
    var catalogState: String = "idle"
    var workflowName: String = ""

    var isConnected: Bool { phase == "Connected" }
    var isBusy: Bool { phase == "Connecting" || phase == "Reconnecting" || phase == "Recovering" }
}

@MainActor
final class SessionStore: ObservableObject {
    @Published private(set) var snapshot = SessionSnapshot()
    @Published private(set) var notices: [String] = []
    @Published private(set) var running = false
    @Published var address: String = LaunchOptions.baseUrl

    private var bridge: BridgeHost?
    private var booted = false

    func boot() {
        guard !booted else { return }
        booted = true
        if LaunchOptions.fixture {
            snapshot = Fixtures.snapshot
            notices = ["样例数据 · 未连接任何服务端"]
            return
        }
        start()
    }

    func start() {
        guard !running else { return }
        let bridge = BridgeHost(configJson: makeConfigJson(), tasksJson: "{}")
        bridge.onEvent = { [weak self] event in
            // BridgeHost 保证回调都在主线程；这里断言一下，别把假设藏起来。
            MainActor.assumeIsolated {
                guard let self else { return }
                switch event {
                case .state(let payload):
                    self.apply(payload)
                case .notice(let payload):
                    self.appendNotice(payload)
                }
            }
        }
        self.bridge = bridge
        bridge.start()
        bridge.send(["t": "start"])
        running = true
        pushNotice("连接 \(address)")
    }

    func disconnect() {
        bridge?.send(["t": "stop"])
        bridge?.stop()
        bridge = nil
        running = false
        snapshot.phase = "Idle"
        snapshot.label = "已断开"
        pushNotice("已断开")
    }

    func toggleConnection() {
        if running { disconnect() } else { start() }
    }

    func reconnect() {
        bridge?.send(["t": "reconnect"])
    }

    func cancel(_ task: TaskRow) {
        guard !task.promptId.isEmpty else { return }
        bridge?.send(["t": "cancel", "promptId": task.promptId])
    }

    func refreshWorkflows() {
        bridge?.send(["t": "workflows"])
    }

    // MARK: - 桥回调

    private func apply(_ state: [String: Any]) {
        var next = SessionSnapshot()
        next.phase = (state["phase"] as? String) ?? "Idle"
        next.label = (state["label"] as? String) ?? ""
        next.generation = int(state["generation"])
        next.queueRunning = int(state["queueRunning"])
        next.queuePending = int(state["queuePending"])
        next.wsTextFrames = int(state["wsTextFrames"])
        next.wsBinaryFrames = int(state["wsBinaryFrames"])

        if let workflow = state["workflow"] as? [String: Any] {
            next.workflowName = (workflow["name"] as? String) ?? ""
        }
        if let catalog = state["catalog"] as? [String: Any] {
            next.catalogState = (catalog["state"] as? String) ?? "idle"
            if let entries = catalog["entries"] as? [[String: Any]] {
                next.catalog = entries.compactMap { entry in
                    guard let path = entry["path"] as? String else { return nil }
                    return CatalogRow(path: path, name: (entry["name"] as? String) ?? path)
                }
            }
        }
        if let tasks = state["tasks"] as? [[String: Any]] {
            next.tasks = tasks.map { task in
                TaskRow(
                    id: (task["id"] as? String) ?? UUID().uuidString,
                    promptId: (task["promptId"] as? String) ?? "",
                    name: (task["name"] as? String) ?? "",
                    state: (task["state"] as? String) ?? "Unknown",
                    percent: double(task["percent"]),
                    node: (task["progressNode"] as? String) ?? "",
                    outputs: int(task["outputs"]),
                    error: task["error"] as? String
                )
            }
        }
        snapshot = next
    }

    private func appendNotice(_ payload: [String: Any]) {
        let kind = (payload["kind"] as? String) ?? "notice"
        var text = kind
        if let message = payload["message"] as? String { text += " · \(message)" }
        if let reason = payload["reason"] as? String { text += " · \(reason)" }
        if let promptId = payload["promptId"] as? String { text += " · \(promptId.prefix(8))" }
        pushNotice(text)
    }

    private func pushNotice(_ text: String) {
        notices.insert(text, at: 0)
        if notices.count > 40 { notices.removeLast(notices.count - 40) }
    }

    // MARK: - 工具

    /// clientId 按地址存：ComfyUI 用它当 sid，换地址就得换一个，
    /// 否则旧任务的事件会推到错的连接上。
    private func makeConfigJson() -> String {
        let key = "clientId.\(address)"
        var clientId = UserDefaults.standard.string(forKey: key)
        if clientId == nil {
            let raw = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
            clientId = "ios" + String(raw.prefix(16))
            UserDefaults.standard.set(clientId, forKey: key)
        }
        let payload: [String: Any] = [
            "baseUrl": address,
            "clientId": clientId ?? "",
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return text
    }

    private func int(_ value: Any?) -> Int {
        if let number = value as? NSNumber { return number.intValue }
        if let text = value as? String { return Int(text) ?? 0 }
        return 0
    }

    private func double(_ value: Any?) -> Double {
        if let number = value as? NSNumber { return number.doubleValue }
        if let text = value as? String { return Double(text) ?? 0 }
        return 0
    }
}

/// 只用来看排版，不参与任何逻辑。真正的数据永远来自共享层。
enum Fixtures {
    static let snapshot: SessionSnapshot = {
        var snapshot = SessionSnapshot()
        snapshot.phase = "Connected"
        snapshot.label = "已连接 · 192.168.1.42:8188"
        snapshot.generation = 3
        snapshot.queueRunning = 1
        snapshot.queuePending = 1
        snapshot.wsTextFrames = 128
        snapshot.wsBinaryFrames = 42
        snapshot.workflowName = "SDXL 基础 · 文生图"
        snapshot.catalogState = "ready"
        snapshot.catalog = [
            CatalogRow(path: "sdxl_base.json", name: "SDXL 基础"),
            CatalogRow(path: "flux_portrait.json", name: "Flux 人像"),
            CatalogRow(path: "wan_video.json", name: "Wan 视频"),
        ]
        snapshot.tasks = [
            TaskRow(
                id: "t1",
                promptId: "8f3c1d2a",
                name: "SDXL 基础 · 文生图",
                state: "Running",
                percent: 0.62,
                node: "KSampler",
                outputs: 0,
                error: nil
            ),
            TaskRow(
                id: "t2",
                promptId: "2b7e9f01",
                name: "Flux 人像",
                state: "Queued",
                percent: 0,
                node: "",
                outputs: 0,
                error: nil
            ),
            TaskRow(
                id: "t3",
                promptId: "5c1a8b44",
                name: "Wan 视频",
                state: "Completed",
                percent: 1,
                node: "",
                outputs: 4,
                error: nil
            ),
        ]
        return snapshot
    }()
}
