import Foundation
import SwiftUI
import UIKit

/// 一个产物引用。`path` 是共享层给好的相对地址（/view?...），
/// 宿主拼上 baseUrl 就能取，不用自己拼查询参数。
struct ArtifactRef: Identifiable {
    var id: String { "\(type)/\(subfolder)/\(filename)" }
    let filename: String
    let displayName: String
    let subfolder: String
    let type: String
    let mime: String
    let isImage: Bool
    let isVideo: Bool
    let path: String
}

struct TaskRow: Identifiable {
    let id: String
    let promptId: String
    let name: String
    let workflowPath: String
    let state: String
    let percent: Double
    let node: String
    let createdAtMs: Int64
    let updatedAtMs: Int64
    let artifacts: [ArtifactRef]
    let error: String?

    /// 跑着和刚跑完都画进度条（安卓那张任务页上，完成的任务也是满格）；
    /// 排队/提交中不画——那时条恒为 0，画出来像卡住了。
    var showsProgress: Bool { state == "Running" || state == "Completed" }

    /// 排队中也能取消（ComfyUI 的 /queue 支持删 pending）。
    var canCancel: Bool { state == "Running" || state == "Queued" || state == "Submitting" }

    var isActive: Bool { state == "Running" || state == "Queued" || state == "Submitting" }

    /// 安卓那边是「已用 0:51」/「耗时 0:51」，这里保持一致。
    func elapsedText(nowMs: Int64) -> String {
        let end = isActive ? nowMs : updatedAtMs
        let seconds = max(0, (end - createdAtMs) / 1000)
        let prefix = isActive ? "已用 " : "耗时 "
        if seconds >= 3600 {
            return String(format: "%@%d:%02d:%02d", prefix, seconds / 3600, (seconds % 3600) / 60, seconds % 60)
        }
        return String(format: "%@%d:%02d", prefix, seconds / 60, seconds % 60)
    }
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
    var baseUrl: String = ""
    var generation: Int = 0
    var queueRunning: Int = 0
    var queuePending: Int = 0
    var wsTextFrames: Int = 0
    var wsBinaryFrames: Int = 0
    var network: String = ""
    var validated: Bool = false
    var tasks: [TaskRow] = []
    var catalog: [CatalogRow] = []
    var catalogState: String = "idle"
    var workflowName: String = ""
    var workflowPath: String = ""
    var workflowError: String = ""
    /// 已加载的节点定义数量。变了说明 /object_info 有新数据，视图要重算。
    var nodeInfoCount: Int = 0

    /// 采样预览的元数据。图片字节单独按需取，原因见 SessionStore.previewImage。
    var previewPromptId: String = ""
    var previewNodeId: String = ""
    var previewReceivedAtMs: Int64 = 0
    var previewBytes: Int = 0
    var animationRate: Int = 0
    var animationCount: Int = 0

    /// Crystools 监控采样（经 WebSocket 推来）；服务器没装扩展时全为 nil。
    var cpuPercent: Double?
    var ramPercent: Double?
    var gpuPercent: Double?
    var vramPercent: Double?
    var temperature: Double?
    var gpuName: String = ""

    var isConnected: Bool { phase == "Connected" }
    var isBusy: Bool { phase == "Connecting" || phase == "Reconnecting" || phase == "Recovering" }
}

@MainActor
final class SessionStore: ObservableObject {
    @Published private(set) var snapshot = SessionSnapshot()
    @Published private(set) var notices: [String] = []
    @Published private(set) var running = false
    @Published var address: String = SavedAddress.initial

    /// 工作流视图：参数模型、节点、问题清单。状态帧只有元数据，这份是按需拉的。
    @Published private(set) var workflowView: WorkflowView?
    /// 需要在界面上切换到的 tab（目前只有自动提交后会用）。
    @Published private(set) var pendingScreen: String?

    /// 最新一帧采样预览。状态帧只带元数据，图片走 IosHost.preview() 按需取，
    /// 只在 receivedAtMs 变化时拉一次——不然每 250ms 一帧会白白编码几十 KB。
    @Published private(set) var previewImage: UIImage?
    /// 取预览失败时的原因。界面直接显示它，省得为了「为什么没有画面」再跑一轮 CI。
    @Published private(set) var previewError: String = ""

    private var didAutoSubmit = false

    private var overrides: [String: [String: Any]] = [:]
    /// 当前启用的图分组；nil 表示还没动过，用工作流文件里的默认选择。
    private var enabledGraphIds: Set<String>?
    private var openedPath: String?
    /// 已经发过 workflow.open 的路径。必须记录：SwiftUI 的子视图 onAppear 早于父视图，
    /// 详情页可能在会话还没建好时就点开，那条命令会被丢掉，要等连上后重发。
    private var openSentFor: String?
    private var loadedPath: String = ""
    private var loadedNodeInfoCount: Int = -1
    private var catalogRequested = false

    private var bridge: BridgeHost?
    private var booted = false
    private var lastPreviewAtMs: Int64 = 0

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
        // 地址是用户手打的，常见问题是漏协议、带尾斜杠、带空格。
        address = SavedAddress.normalize(address)
        SavedAddress.save(address)
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
        openedPath = nil
        openSentFor = nil
        loadedPath = ""
        overrides = [:]
        workflowView = nil
        catalogRequested = false
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

    /// 手动刷新：让共享层查一遍 /history 与 /queue，不重连。
    func reconcile() {
        bridge?.send(["t": "reconcile"])
    }

    /// 关闭/重新启用单个节点。改动只存在会话内存里，不写回服务器文件。
    /// 共享层收下命令后要等下一拍才重算，所以这里延后一点再拉视图。
    func setNodeBypassed(nodeId: Int, bypassed: Bool) {
        bridge?.send(["t": "bypass", "nodeId": nodeId, "bypassed": bypassed ? 1 : 0])
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
            self?.reloadWorkflow()
        }
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
        next.baseUrl = (state["baseUrl"] as? String) ?? ""
        next.generation = int(state["generation"])
        next.queueRunning = int(state["queueRunning"])
        next.queuePending = int(state["queuePending"])
        next.wsTextFrames = int(state["wsTextFrames"])
        next.wsBinaryFrames = int(state["wsBinaryFrames"])
        next.network = (state["network"] as? String) ?? ""
        next.validated = (state["validated"] as? Bool) ?? false

        if let workflow = state["workflow"] as? [String: Any] {
            next.workflowName = (workflow["name"] as? String) ?? ""
            next.workflowPath = (workflow["path"] as? String) ?? ""
            next.workflowError = (workflow["error"] as? String) ?? ""
            next.nodeInfoCount = int(workflow["nodeInfo"])
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
        if let preview = state["preview"] as? [String: Any] {
            next.previewPromptId = (preview["promptId"] as? String) ?? ""
            next.previewNodeId = (preview["nodeId"] as? String) ?? ""
            next.previewReceivedAtMs = int64(preview["receivedAtMs"])
            next.previewBytes = int(preview["bytes"])
            next.animationRate = int(preview["animationRate"])
            next.animationCount = int(preview["animationCount"])
        } else {
            // 没有预览帧时把旧画面清掉，别让上一轮结束的画面留在界面上。
            if previewImage != nil { previewImage = nil }
            previewError = ""
            lastPreviewAtMs = 0
        }
        if let monitor = state["monitor"] as? [String: Any] {
            next.cpuPercent = optionalDouble(monitor["cpu"])
            next.ramPercent = optionalDouble(monitor["ram"])
            next.gpuPercent = optionalDouble(monitor["gpu"])
            next.vramPercent = optionalDouble(monitor["vram"])
            next.temperature = optionalDouble(monitor["temperature"])
            next.gpuName = (monitor["gpuName"] as? String) ?? ""
        }
        if let tasks = state["tasks"] as? [[String: Any]] {
            next.tasks = tasks.map { task in
                TaskRow(
                    id: (task["id"] as? String) ?? UUID().uuidString,
                    promptId: (task["promptId"] as? String) ?? "",
                    name: (task["name"] as? String) ?? "",
                    workflowPath: (task["workflowPath"] as? String) ?? "",
                    state: (task["state"] as? String) ?? "Unknown",
                    percent: double(task["percent"]),
                    node: (task["progressNode"] as? String) ?? "",
                    createdAtMs: int64(task["createdAtMs"]),
                    updatedAtMs: int64(task["updatedAtMs"]),
                    artifacts: (task["outputRefs"] as? [[String: Any]] ?? []).map { raw in
                        ArtifactRef(
                            filename: (raw["filename"] as? String) ?? "",
                            displayName: (raw["displayName"] as? String) ?? "",
                            subfolder: (raw["subfolder"] as? String) ?? "",
                            type: (raw["type"] as? String) ?? "",
                            mime: (raw["mime"] as? String) ?? "",
                            isImage: (raw["image"] as? Bool) ?? false,
                            isVideo: (raw["video"] as? Bool) ?? false,
                            path: (raw["path"] as? String) ?? ""
                        )
                    },
                    error: task["error"] as? String
                )
            }
        }
        snapshot = next
        refreshPreviewIfNeeded(next)

        if next.phase == "Connected" {
            // 工作流列表只拉一次；断线重连后重新拉。
            if !catalogRequested {
                catalogRequested = true
                bridge?.send(["t": "workflows"])
            }
            // CI 没法点屏幕，用启动参数把指定工作流顶到最前面。
            if openedPath == nil,
               let target = LaunchOptions.workflowPath,
               !target.isEmpty,
               next.catalog.contains(where: { $0.path == target }) {
                openWorkflow(path: target)
            }
            // 详情页可能在连接建立前就打开了，这里补发。
            sendOpenIfReady()
        } else {
            catalogRequested = false
        }

        // 文档真正加载完成后再拉视图：早一步只会拿到「还没有打开工作流」。
        if !next.workflowPath.isEmpty, next.workflowPath != loadedPath {
            loadedPath = next.workflowPath
            loadedNodeInfoCount = next.nodeInfoCount
            reloadWorkflow()
        } else if !next.workflowPath.isEmpty,
                  openedPath != nil,
                  next.nodeInfoCount != loadedNodeInfoCount {
            // 字段是从 /object_info 抽的：定义晚到一步，第一次算出来的视图就是空的。
            loadedNodeInfoCount = next.nodeInfoCount
            reloadWorkflow()
        }

        // CI 验证用：参数页一就绪就提交一次，然后跳到任务页看结果。
        if LaunchOptions.autoSubmit,
           !didAutoSubmit,
           let view = workflowView,
           view.canSubmit {
            didAutoSubmit = true
            submitWorkflow()
            // 传了 -uiTab preview 就留在详情页看采样预览，不切到任务页。
            if LaunchOptions.detailTab == 0 {
                pendingScreen = "tasks"
            }
        }
    }

    private func appendNotice(_ payload: [String: Any]) {
        let kind = (payload["kind"] as? String) ?? "notice"
        var text = noticeTitle(kind)
        if let message = payload["message"] as? String, !message.isEmpty {
            text += " · \(message)"
        }
        if let reason = payload["reason"] as? String, !reason.isEmpty {
            text += " · \(reason)"
        }
        if let promptId = payload["promptId"] as? String, !promptId.isEmpty {
            text += " · \(promptId.prefix(8))"
        }
        if let ids = payload["promptIds"] as? [String], !ids.isEmpty {
            text += " · \(ids.count) 个任务"
        }
        pushNotice(text)
    }

    /// 共享层给的是协议里的英文 kind，界面上不该露出来。
    private func noticeTitle(_ kind: String) -> String {
        switch kind {
        case "connected": return "已连接"
        case "disconnected": return "连接断开"
        case "task_failed": return "任务失败"
        case "task_lost": return "任务状态未知"
        case "progress_timeout": return "长时间无进度"
        case "reconcile_gave_up": return "对账失败"
        case "submit_rejected": return "提交被拒绝"
        case "submit_accepted": return "已提交"
        default: return kind
        }
    }

    private func pushNotice(_ text: String) {
        // 同一件事连续来两次（比如握手后紧接着对账再报一次 Connected）只留一条。
        if notices.first == text { return }
        notices.insert(text, at: 0)
        if notices.count > 40 { notices.removeLast(notices.count - 40) }
    }

    // MARK: - 工作流

    func openWorkflow(path: String) {
        // 切回 tab 会重新触发 onAppear；已经打开过就别把用户的改动清掉。
        if openedPath == path, workflowView != nil { return }
        openedPath = path
        openSentFor = nil
        loadedPath = ""
        loadedNodeInfoCount = -1
        overrides = [:]
        enabledGraphIds = nil
        workflowView = nil
        sendOpenIfReady()
    }

    /// 只在已连接时发：未连接时发出去的请求会被状态机丢掉，而且不会重试。
    private func sendOpenIfReady() {
        guard snapshot.phase == "Connected",
              let bridge,
              let path = openedPath,
              openSentFor != path else { return }
        bridge.send(["t": "workflow.open", "path": path])
        openSentFor = path
    }

    /// 重新向共享层要一份视图。改过参数后调用，让它按字段类型重算并回报问题。
    func reloadWorkflow() {
        guard let bridge, let path = openedPath, !path.isEmpty else { return }
        let raw = bridge.workflowView(requestJson: requestJson(op: "view"))
        if let view = WorkflowView.parse(raw) {
            workflowView = view
        }
    }

    /// 切换一个图分组。以界面当前显示的选择为基准算，不另外维护一份状态，
    /// 避免和工作流文件里的默认选择打架。
    func toggleGraph(_ id: String) {
        guard let view = workflowView else { return }
        var next = Set(view.graphs.filter { $0.enabled }.map { $0.id })
        if next.contains(id) {
            next.remove(id)
        } else {
            next.insert(id)
        }
        enabledGraphIds = next
        reloadWorkflow()
    }

    func setOverride(_ field: WorkflowField, value: Any) {
        var node = overrides[String(field.nodeId)] ?? [:]
        node[field.inputName] = value
        overrides[String(field.nodeId)] = node
        reloadWorkflow()
    }

    func submitWorkflow() {
        guard let bridge, let view = workflowView, view.canSubmit else { return }
        let raw = bridge.workflowView(
            requestJson: requestJson(op: "prompt", runToken: Self.runToken())
        )
        guard let data = raw.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              (object["ok"] as? Bool) == true,
              let prompt = object["prompt"],
              let promptData = try? JSONSerialization.data(withJSONObject: prompt),
              let promptText = String(data: promptData, encoding: .utf8) else {
            pushNotice("提交被拒绝：prompt 没通过本地校验")
            return
        }
        bridge.send([
            "t": "submit",
            "workflow": promptText,
            "workflowName": view.name,
            "workflowPath": view.path,
        ])
        pushNotice("已提交 \(view.name)")
    }

    private func requestJson(op: String, runToken: String? = nil) -> String {
        var payload: [String: Any] = ["op": op]
        if !overrides.isEmpty { payload["overrides"] = overrides }
        if let enabledGraphIds { payload["graphs"] = enabledGraphIds.sorted() }
        if let runToken { payload["run"] = runToken }
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8) else {
            return "{\"op\":\"view\"}"
        }
        return text
    }

    /// 保存类节点按这个后缀命名产物，避免同一工作流多次提交互相覆盖。
    private static func runToken() -> String {
        let raw = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        return String(raw.prefix(6))
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

    /// 预览帧变了才取图；同一帧重复取只会浪费 base64 编码。
    ///
    /// 失败原因写进 `previewError` 而不是只打日志：模拟器截不出控制台，
    /// 界面上一行字是唯一能带回 CI 的证据。
    private func refreshPreviewIfNeeded(_ next: SessionSnapshot) {
        guard let bridge, next.previewReceivedAtMs > 0 else { return }
        guard next.previewReceivedAtMs != lastPreviewAtMs else { return }
        lastPreviewAtMs = next.previewReceivedAtMs
        let raw = bridge.previewBase64()
        guard !raw.isEmpty else {
            previewError = "共享层没有帧"
            return
        }
        guard let bytes = Data(base64Encoded: raw) else {
            previewError = "base64 解码失败（\(raw.count) 字符）"
            return
        }
        guard let image = UIImage(data: bytes) else {
            previewError = "图片解码失败（\(bytes.count) 字节）"
            return
        }
        previewError = ""
        previewImage = image
    }

    private func optionalDouble(_ value: Any?) -> Double? {
        if let number = value as? NSNumber { return number.doubleValue }
        if let text = value as? String { return Double(text) }
        return nil
    }

    private func int64(_ value: Any?) -> Int64 {
        if let number = value as? NSNumber { return number.int64Value }
        if let text = value as? String { return Int64(text) ?? 0 }
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
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var snapshot = SessionSnapshot()
        snapshot.phase = "Connected"
        snapshot.label = "已连接"
        snapshot.baseUrl = "http://192.168.1.42:8188"
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
                workflowPath: "sdxl_base.json",
                state: "Running",
                percent: 0.62,
                node: "KSampler",
                createdAtMs: now - 51_000,
                updatedAtMs: now - 400,
                artifacts: [],
                error: nil
            ),
            TaskRow(
                id: "t2",
                promptId: "2b7e9f01",
                name: "Flux 人像",
                workflowPath: "flux_portrait.json",
                state: "Queued",
                percent: 0,
                node: "",
                createdAtMs: now - 8_000,
                updatedAtMs: now - 8_000,
                artifacts: [],
                error: nil
            ),
            TaskRow(
                id: "t3",
                promptId: "5c1a8b44",
                name: "Wan 视频",
                workflowPath: "wan_video.json",
                state: "Completed",
                percent: 1,
                node: "",
                createdAtMs: now - 620_000,
                updatedAtMs: now - 540_000,
                artifacts: [],
                error: nil
            ),
        ]
        return snapshot
    }()
}

/// 连接地址的持久化与规范化。
///
/// 每次连成功就把地址存下来，下次启动直接带出来——iOS 上没有「最近连接」列表，
/// 让用户每次重敲一遍 IP 是很差的体验（安卓那边是存在设置里的）。
enum SavedAddress {
    private static let key = "lastBaseUrl"

    static var initial: String {
        if let raw = LaunchOptions.baseUrlFromArgs { return raw }
        if let saved = UserDefaults.standard.string(forKey: key), !saved.isEmpty { return saved }
        return LaunchOptions.defaultBaseUrl
    }

    /// 去空格、补协议、去尾斜杠；保留路径（ComfyUI 可能挂在反代子路径下）。
    static func normalize(_ raw: String) -> String {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return LaunchOptions.defaultBaseUrl }
        if !text.contains("://") { text = "http://" + text }
        while text.hasSuffix("/") { text.removeLast() }
        return text
    }

    static func save(_ value: String) {
        UserDefaults.standard.set(value, forKey: key)
    }
}
