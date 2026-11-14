import Foundation
import SwiftUI
import UIKit

/// 一个产物引用。`path` 是共享层给好的相对地址（/view?...），
/// 宿主拼上 baseUrl 就能取，不用自己拼查询参数。
struct ArtifactRef: Identifiable, Equatable {
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

struct TaskRow: Identifiable, Equatable {
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

struct CatalogRow: Identifiable, Equatable {
    var id: String { path }
    let path: String
    let name: String
    /// 文件字节数；服务端没给就为 0（不显示大小）。
    let sizeBytes: Int64
}

/// 关闭节点前的影响探测结果；只有 breaking 才需要二次确认。
struct BypassConfirmation: Identifiable {
    let nodeId: Int
    let reason: String
    var id: Int { nodeId }
}

/// 本地校验通过、但带 warnings 的提交；等用户确认后再发。
struct PendingSubmit: Identifiable {
    let id = UUID()
    let promptText: String
    let workflowName: String
    let workflowPath: String
    let warnings: [String]
}

/// 状态帧里这一版真正用得上的字段。其余字段原样躺在 JSON 里，等对应界面做出来再接。
struct SessionSnapshot: Equatable {
    var phase: String = "Idle"
    var label: String = ""
    var baseUrl: String = ""
    var clientId: String = ""
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
    var catalogError: String = ""
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
    /// 每收满一轮帧 +1；只有它变了才重新取帧。
    var animationSeq: Int64 = 0

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

    /// VHS 动画帧（视频工作流的循环预览）。空表示当前没有动画。
    @Published private(set) var animationFrames: [UIImage] = []
    @Published private(set) var animationRate: Int = 0
    /// 取预览失败时的原因。界面直接显示它，省得为了「为什么没有画面」再跑一轮 CI。
    @Published private(set) var previewError: String = ""

    /// 置顶的节点。跟着草稿存本机，重开工作流还在。
    @Published private(set) var pinnedNodeIds: Set<Int> = []
    /// 工作流置顶的版本号：只用来触发列表重排（真值在 UserDefaults）。
    @Published private(set) var pinnedRevision: Int = 0
    /// 种子模式的版本号：开关变化后让字段行重绘。
    @Published private(set) var seedRevision: Int = 0

    /// 节点备注，键是节点 id。只在本机，和草稿一起存。
    @Published private(set) var nodeNotes: [String: String] = [:]

    /// 一次性提示条（提交成功/被拒、上传完成等）。Android 用 Toast，iOS 用底部小胶囊。
    @Published private(set) var toast: String?

    /// 诊断日志（带时间戳，最多 400 条）。只在内存里，退出即丢。
    @Published private(set) var diagnostics: [String] = []

    /// 正在上传的字段 id；nil 表示没有上传在跑。
    @Published private(set) var uploadingFieldId: String?
    @Published private(set) var uploadError: String = ""
    /// 错误属于哪个字段：只在该字段下面显示，别让所有图片字段一起报错。
    @Published private(set) var uploadErrorFieldId: String?
    /// 参数显示刷新计数。字段值来自工作流原文（不含 override），只靠 valueText
    /// 变化回灌永远等不到；上传回填、恢复默认、重开工作流都靠它让 FieldRow 重取显示值。
    @Published private(set) var fieldDisplayRevision: Int = 0
    /// 关闭关键节点前的确认；nil 表示没有待确认的开关。
    @Published private(set) var bypassConfirmation: BypassConfirmation?
    /// 带 warnings 的提交；用户确认后才真正发出。
    @Published private(set) var pendingSubmit: PendingSubmit?

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
    private var didAutoUpload = false
    private var toastTask: Task<Void, Never>?
    private var logConsumed: Int32 = 0
    private static let logTime: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter
    }()
    private var lastPreviewAtMs: Int64 = 0
    private var lastAnimationSeq: Int64 = 0

    func boot() {
        guard !booted else { return }
        booted = true
        // 退到后台前把任务快照落盘：persist 命令有 1 秒节流窗口，
        // 系统挂起可能正好落在这个窗口里。
        NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main,
        ) { _ in
            TaskPersistence.shared.flush()
        }
        if LaunchOptions.fixture {
            snapshot = Fixtures.snapshot
            notices = ["样例数据 · 未连接任何服务端"]
            return
        }
        // 只有「之前连过」或「命令行指定了地址」才自动连；首装就停在连接页。
        if SavedAddress.hasSaved || LaunchOptions.baseUrlFromArgs != nil {
            start()
        }
    }

    func start() {
        guard !running else { return }
        if address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            pushNotice("先填 ComfyUI 地址，例如 192.168.1.20:8188")
            return
        }
        // 地址是用户手打的，常见问题是漏协议、带尾斜杠、带空格。
        address = SavedAddress.normalize(address)
        SavedAddress.save(address)
        // 启动恢复：本机快照原样回传给 shared（schema 见 TaskPersistence），
        // 杀掉进程重开后完成记录与未完成任务都还在。
        let bridge = BridgeHost(
            configJson: makeConfigJson(),
            tasksJson: TaskPersistence.shared.loadTasksJson(),
        )
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
        enabledGraphIds = nil
        pinnedNodeIds = []
        nodeNotes = [:]
        workflowView = nil
        fieldDisplayRevision &+= 1
        uploadingFieldId = nil
        uploadError = ""
        uploadErrorFieldId = nil
        pendingSubmit = nil
        bypassConfirmation = nil
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

    /// 关闭/重新启用单个节点。关闭前先做一次构建试算；breaking 时先确认。
    /// 改动只存在会话内存里，不写回服务器文件。
    func requestNodeToggle(nodeId: Int, bypassed: Bool) {
        if !bypassed {
            sendBypass(nodeId: nodeId, bypassed: false)
            return
        }
        guard let bridge else { return }
        let raw = bridge.workflowView(
            requestJson: requestJson(op: "disable_impact", nodeId: nodeId)
        )
        guard let data = raw.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              (object["ok"] as? Bool) == true,
              (object["breaking"] as? Bool) == true else {
            // 探测失败不阻塞用户：退回原来的直接开关。
            sendBypass(nodeId: nodeId, bypassed: true)
            return
        }
        let reason = (object["reason"] as? String) ?? ""
        bypassConfirmation = BypassConfirmation(
            nodeId: nodeId,
            reason: reason.isEmpty ? "关闭后提交可能失败。" : reason
        )
    }

    func confirmBypass() {
        guard let pending = bypassConfirmation else { return }
        bypassConfirmation = nil
        sendBypass(nodeId: pending.nodeId, bypassed: true)
    }

    func cancelBypass() {
        bypassConfirmation = nil
    }

    private func sendBypass(nodeId: Int, bypassed: Bool) {
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
        if let bridge { drainDiagnostics(bridge) }
        var next = SessionSnapshot()
        next.phase = (state["phase"] as? String) ?? "Idle"
        next.label = (state["label"] as? String) ?? ""
        next.baseUrl = (state["baseUrl"] as? String) ?? ""
        next.clientId = (state["clientId"] as? String) ?? ""
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
            next.catalogError = (catalog["error"] as? String) ?? ""
            if let entries = catalog["entries"] as? [[String: Any]] {
                next.catalog = entries.compactMap { entry in
                    guard let path = entry["path"] as? String else { return nil }
                    return CatalogRow(
                        path: path,
                        name: (entry["name"] as? String) ?? path,
                        sizeBytes: int64(entry["size"])
                    )
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
            next.animationSeq = int64(preview["animationSeq"])
        } else {
            // 没有预览帧时把旧画面清掉，别让上一轮结束的画面留在界面上。
            if previewImage != nil { previewImage = nil }
            if !animationFrames.isEmpty { animationFrames = [] }
            previewError = ""
            lastPreviewAtMs = 0
            lastAnimationSeq = 0
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
        // 250ms 一帧不等于 250ms 一改：只有内容真的变了才发布，避免整页 4Hz 重绘。
        if next != snapshot {
            notifyFinishedTasks(from: snapshot, to: next)
            snapshot = next
        }
        refreshPreviewIfNeeded(next)
        refreshAnimationIfNeeded(next)

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

        // CI 验证用：工作流一就绪就把内置夹具图传到第一个 LoadImage 字段。
        if LaunchOptions.autoUpload,
           !didAutoUpload,
           let field = workflowView?.nodes.flatMap({ $0.fields }).first(where: { $0.isImageUpload }) {
            didAutoUpload = true
            // 夹具可能被打平到 bundle 根，也可能保留 Fixtures 子目录——两种都试。
            let url = Bundle.main.url(forResource: "preview", withExtension: "png")
                ?? Bundle.main.url(forResource: "preview", withExtension: "png", subdirectory: "Fixtures")
            if let url, let data = try? Data(contentsOf: url) {
                uploadImage(data: data, filename: "preview.png", field: field)
            } else {
                // 找不到就明说：CI 截图里能直接看到，不用猜为什么没上传。
                uploadError = "内置夹具图没打进包里"
                uploadErrorFieldId = field.id
                showToast("夹具图缺失")
            }
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

    /// 对比前后两帧的任务状态：进入终结态（完成/失败/取消）时发本地通知。
    /// 恢复出来的历史任务不会误报——它们在首帧里就存在且状态没变化。
    private func notifyFinishedTasks(from old: SessionSnapshot, to next: SessionSnapshot) {
        guard TaskNotifier.enabled else { return }
        var oldStates: [String: String] = [:]
        for item in old.tasks { oldStates[item.id] = item.state }
        for task in next.tasks {
            guard let previous = oldStates[task.id], previous != task.state else { continue }
            switch task.state {
            case "Completed", "Failed", "Cancelled":
                TaskNotifier.notifyTaskFinished(task)
            default:
                break
            }
        }
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
        pinnedNodeIds = []
        nodeNotes = [:]
        workflowView = nil
        // 打开就把本机草稿带出来：参数、置顶、图分组。安卓也是这个行为。
        if let draft = WorkflowDrafts.load(host: draftHost, path: path) {
            overrides = draft.overrides
            pinnedNodeIds = draft.pinned
            enabledGraphIds = draft.graphs
            nodeNotes = draft.notes
        }
        fieldDisplayRevision &+= 1
        uploadingFieldId = nil
        uploadError = ""
        uploadErrorFieldId = nil
        pendingSubmit = nil
        bypassConfirmation = nil
        sendOpenIfReady()
    }

    /// 只在已连接时发：未连接时发出去的请求会被状态机丢掉，而且不会重试。
    private func sendOpenIfReady() {
        guard snapshot.phase == "Connected",
              let bridge,
              let path = openedPath,
              openSentFor != path else { return }
        if path.hasPrefix("local://") {
            // 本地导入：原文来自沙盒，走 open_local（shared 不碰文件系统）。
            guard let raw = LocalWorkflowStore.shared.read(path: path) else {
                showToast("本地工作流读取失败")
                return
            }
            bridge.send([
                "t": "workflow.open_local",
                "path": path,
                "name": LocalWorkflowStore.shared.name(for: path) ?? "本地工作流",
                "raw": raw,
            ])
        } else {
            bridge.send(["t": "workflow.open", "path": path])
        }
        openSentFor = path
    }

    /// 导入本地工作流：2 MB 上限 + JSON 对象校验（对齐 Android 的导入约束）。
    /// 通过后存沙盒，路径 local://<uuid>。
    @discardableResult
    func importLocalWorkflow(name: String, raw: String) -> Bool {
        if raw.utf8.count > LocalWorkflowStore.maxBytes {
            showToast("文件太大（上限 2 MB）")
            return false
        }
        guard let data = raw.data(using: .utf8),
              (try? JSONSerialization.jsonObject(with: data)) is [String: Any] else {
            showToast("不是有效的 ComfyUI 工作流 JSON")
            return false
        }
        guard let item = LocalWorkflowStore.shared.save(name: name, raw: raw) else {
            showToast("保存失败")
            return false
        }
        showToast("已导入 \(item.name)")
        return true
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
        saveDraft()
        reloadWorkflow()
    }

    /// 写/清节点备注。空字符串等于删除。
    func setNote(nodeId: Int, text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            nodeNotes.removeValue(forKey: String(nodeId))
        } else {
            nodeNotes[String(nodeId)] = trimmed
        }
        saveDraft()
    }

    /// 置顶/取消置顶。置顶只影响排序，不改变工作流本身。
    func togglePin(_ nodeId: Int) {
        if pinnedNodeIds.contains(nodeId) {
            pinnedNodeIds.remove(nodeId)
        } else {
            pinnedNodeIds.insert(nodeId)
        }
        saveDraft()
    }

    /// 当前工作流改过多少个参数。设置页用它决定「重置」是否可点。
    var overrideCount: Int {
        overrides.values.reduce(0) { $0 + $1.count }
    }

    /// 弹一条 2.6 秒的提示。连着来两条时后一条顶掉前一条。
    func showToast(_ text: String) {
        toast = text
        toastTask?.cancel()
        toastTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 2_600_000_000)
            guard !Task.isCancelled else { return }
            self?.toast = nil
        }
    }

    /// 把一张相册图片传到服务器，成功后用返回的文件名覆盖 LoadImage 的 image 字段。
    ///
    /// 上传不进状态机：失败只影响这一个字段，不打断连接或任务。
    func uploadImage(data: Data, filename: String, field: WorkflowField) {
        guard let bridge else { return }
        let startPath = openedPath
        let maxSide: CGFloat = Prefs.weakNetwork ? 1536 : 2048
        guard let jpeg = ImageUploadPrep.jpeg(from: data, maxSide: maxSide) else {
            uploadError = "这张图读不出来，换一张试试"
            uploadErrorFieldId = field.id
            return
        }
        uploadingFieldId = field.id
        uploadError = ""
        uploadErrorFieldId = nil

        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let raw = bridge.uploadRequest(
            base64: jpeg.base64EncodedString(),
            filename: filename,
            baseUrl: snapshot.baseUrl.isEmpty ? address : snapshot.baseUrl,
            nowMs: nowMs
        )
        let lines = raw.split(separator: "\n", maxSplits: 4, omittingEmptySubsequences: false).map(String.init)
        guard lines.count == 5, lines[0] == "OK" else {
            uploadingFieldId = nil
            uploadError = lines.count > 1 ? lines[1] : "上传准备失败"
            uploadErrorFieldId = field.id
            return
        }
        let serverName = lines[1]
        guard let url = URL(string: lines[3]), let body = Data(base64Encoded: lines[4]) else {
            uploadingFieldId = nil
            uploadError = "上传请求不完整"
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue(lines[2], forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 60
        request.httpBody = body

        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            let status = Int32((response as? HTTPURLResponse)?.statusCode ?? 0)
            let text = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            let failure = error?.localizedDescription
            Task { @MainActor [weak self] in
                guard let self else { return }
                // 上传期间切了工作流或重连：这次结果已经不属于当前页面，直接丢弃。
                guard self.openedPath == startPath, self.bridge === bridge else { return }
                if self.uploadingFieldId == field.id {
                    self.uploadingFieldId = nil
                }
                if let failure {
                    self.uploadError = failure
                    self.uploadErrorFieldId = field.id
                    return
                }
                let result = bridge.uploadResult(status: status, body: text)
                let parts = result.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
                guard parts.count == 2, parts[0] == "OK" else {
                    self.uploadError = parts.count > 1 ? parts[1] : "上传失败"
                    self.uploadErrorFieldId = field.id
                    return
                }
                self.uploadError = ""
                self.uploadErrorFieldId = nil
                self.setOverride(field, value: parts[1])
                self.pushNotice("已上传 \(serverName)")
                self.showToast("已上传 \(serverName)")
            }
        }.resume()
    }

    /// 清掉本次打开后的所有参数修改（不写回服务器上的工作流文件）。
    func resetOverrides() {
        guard !overrides.isEmpty else { return }
        overrides = [:]
        saveDraft()
        reloadWorkflow()
        fieldDisplayRevision &+= 1
        pushNotice("已恢复默认参数")
        showToast("已恢复默认参数")
    }

    func setOverride(_ field: WorkflowField, value: Any) {
        var node = overrides[String(field.nodeId)] ?? [:]
        node[field.inputName] = value
        overrides[String(field.nodeId)] = node
        saveDraft()
        reloadWorkflow()
        fieldDisplayRevision &+= 1
    }

    /// 字段当前应显示的值：优先本次会话的 override，其次工作流原文。
    /// 上传回填、恢复默认、重启后读草稿都走这一条，避免只在上传成功那一刻刷新。
    func fieldDisplayText(for field: WorkflowField) -> String {
        guard let raw = overrides[String(field.nodeId)]?[field.inputName] else {
            return field.valueText
        }
        if let bool = raw as? Bool {
            return bool ? "true" : "false"
        }
        if let number = raw as? NSNumber {
            let value = number.doubleValue
            if value == value.rounded() {
                return String(Int64(value))
            }
            return String(value)
        }
        return String(describing: raw)
    }

    func submitWorkflow() {
        guard let bridge, let view = workflowView, view.canSubmit else { return }
        // 种子快捷：每次随机的节点换成新随机数（对齐 Android prepareForSubmit）。
        let seededOverrides = SeedModes.prepareForSubmit(
            host: hostKey,
            path: seedScopePath,
            overrides: overrides,
            nodes: view.nodes,
        )
        let raw = bridge.workflowView(
            requestJson: requestJson(
                op: "prompt",
                runToken: Self.runToken(),
                overridesOverride: seededOverrides,
            )
        )
        guard let data = raw.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              (object["ok"] as? Bool) == true,
              let prompt = object["prompt"],
              let promptData = try? JSONSerialization.data(withJSONObject: prompt),
              let promptText = String(data: promptData, encoding: .utf8) else {
            pushNotice("提交被拒绝：prompt 没通过本地校验")
            showToast("提交被拒绝：本地校验没过")
            return
        }
        let warnings = (object["warnings"] as? [String]) ?? []
        if !warnings.isEmpty {
            // CI 自动化（-uiAutoSubmit）：不弹确认框直接提交——否则「请确认」
            // 弹窗会把任务页/采样预览/产物三张截图全部挡成空白页。
            if LaunchOptions.autoSubmit {
                sendPrompt(promptText, workflowName: view.name, workflowPath: view.path)
                return
            }
            // 有告警时先让用户看一眼；确认后由 confirmSubmit 真正发出。
            pendingSubmit = PendingSubmit(
                promptText: promptText,
                workflowName: view.name,
                workflowPath: view.path,
                warnings: warnings
            )
            return
        }
        sendPrompt(promptText, workflowName: view.name, workflowPath: view.path)
    }

    func confirmSubmit() {
        guard let pending = pendingSubmit else { return }
        pendingSubmit = nil
        sendPrompt(
            pending.promptText,
            workflowName: pending.workflowName,
            workflowPath: pending.workflowPath
        )
    }

    func cancelSubmit() {
        pendingSubmit = nil
    }

    private func sendPrompt(_ promptText: String, workflowName: String, workflowPath: String) {
        bridge?.send([
            "t": "submit",
            "workflow": promptText,
            "workflowName": workflowName,
            "workflowPath": workflowPath,
        ])
        pushNotice("已提交 \(workflowName)")
        showToast("已提交 \(workflowName.isEmpty ? "任务" : workflowName)")
    }

    private func requestJson(
        op: String,
        runToken: String? = nil,
        nodeId: Int? = nil,
        overridesOverride: [String: [String: Any]]? = nil,
    ) -> String {
        var payload: [String: Any] = ["op": op]
        let usedOverrides = overridesOverride ?? overrides
        if !usedOverrides.isEmpty { payload["overrides"] = usedOverrides }
        if let enabledGraphIds { payload["graphs"] = enabledGraphIds.sorted() }
        if let nodeId { payload["nodeId"] = nodeId }
        // 和安卓一样：开关打开时，本地问题只告警，prompt 照样构建。
        if Prefs.forceSubmit { payload["forceSubmit"] = true }
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

    // MARK: - 草稿

    /// 草稿按「主机 + 路径」隔离；没连上时退回输入框里的地址。
    /// 当前主机的稳定标识（草稿、置顶都按它隔离）。
    var hostKey: String { draftHost }

    /// 会话是否还在活动（连接中 / 已连接 / 重连 / 恢复）。
    /// 导入备份要求完全空闲：状态机在跑时覆盖任务快照会互相打架。
    var isSessionActive: Bool {
        bridge != nil || snapshot.phase != "Idle"
    }

    /// 最后一次收到采样预览的时间（网络测速用它判断长连接是否静默）。
    var lastPreviewFrameAtMs: Int64? {
        lastPreviewAtMs > 0 ? lastPreviewAtMs : nil
    }

    /// 置顶工作流路径；改变后 pinnedRevision 自增，列表据此重排。
    var pinnedWorkflowPaths: [String] { PinnedWorkflows.list(host: draftHost) }

    func togglePinnedWorkflow(path: String) {
        PinnedWorkflows.toggle(host: draftHost, path: path)
        pinnedRevision &+= 1
    }

    // MARK: - 种子快捷（对齐 Android SeedModeStore）

    private var seedScopePath: String { openedPath ?? "" }

    func seedEveryRandom(nodeId: Int) -> Bool {
        SeedModes.isEveryRandom(host: hostKey, path: seedScopePath, nodeId: nodeId)
    }

    func setSeedEveryRandom(nodeId: Int, _ value: Bool) {
        SeedModes.setEveryRandom(host: hostKey, path: seedScopePath, nodeId: nodeId, value: value)
        seedRevision &+= 1
    }

    func seedLast(nodeId: Int) -> Int64? {
        SeedModes.lastSeed(host: hostKey, path: seedScopePath, nodeId: nodeId)
    }

    private var draftHost: String {
        let raw = snapshot.baseUrl.isEmpty ? address : snapshot.baseUrl
        return raw.isEmpty ? "unconnected" : raw
    }

    private func saveDraft() {
        guard let path = openedPath, !path.isEmpty else { return }
        var draft = WorkflowDraft()
        draft.overrides = overrides
        draft.pinned = pinnedNodeIds
        draft.graphs = enabledGraphIds
        draft.notes = nodeNotes
        WorkflowDrafts.save(draft, host: draftHost, path: path)
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
        // 弱网下不追每一帧：既省带宽也少一次 base64 编码。
        if Prefs.weakNetwork, next.previewReceivedAtMs - lastPreviewAtMs < 2_000 {
            return
        }
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

    /// 动画帧变了才取：几十帧、每帧几十 KB，追着取会明显吃内存和带宽。
    private func refreshAnimationIfNeeded(_ next: SessionSnapshot) {
        // 弱网不拉多帧动画：一帧采样预览就够看，动画帧要几十倍带宽。
        guard !Prefs.weakNetwork else { return }
        guard let bridge, next.animationCount > 0, next.animationSeq > 0 else { return }
        guard next.animationSeq != lastAnimationSeq else { return }
        lastAnimationSeq = next.animationSeq
        let raw = bridge.previewAnimationBase64()
        let lines = raw.split(separator: "\n", omittingEmptySubsequences: true).map(String.init)
        guard let header = lines.first, header.hasPrefix("OK|") else { return }
        let fields = header.split(separator: "|")
        guard fields.count == 4, let rate = Int(fields[2]) else { return }

        var frames: [UIImage] = []
        frames.reserveCapacity(lines.count - 1)
        for line in lines.dropFirst() {
            guard let data = Data(base64Encoded: line), let image = UIImage(data: data) else { continue }
            frames.append(image)
        }
        guard !frames.isEmpty else { return }
        animationRate = max(rate, 1)
        animationFrames = frames
    }

    /// 拉增量日志并打时间戳。Kotlin 那边不给时间（拿本地时区要碰 posix），
    /// 宿主每 250ms 取一次，误差完全够用。
    private func drainDiagnostics(_ bridge: BridgeHost) {
        let total = bridge.diagnosticsTotal()
        guard total != logConsumed else { return }
        let text = bridge.diagnosticsSince(consumed: logConsumed)
        logConsumed = total
        guard !text.isEmpty else { return }
        let stamp = Self.logTime.string(from: Date())
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
            .map { "\(stamp) \($0)" }
        diagnostics.append(contentsOf: lines)
        if diagnostics.count > 400 {
            diagnostics.removeFirst(diagnostics.count - 400)
        }
    }

    func clearDiagnostics() {
        bridge?.diagnosticsClear()
        diagnostics = []
        logConsumed = 0
    }

    /// 复制按钮用：整段日志一次性拿走。
    var diagnosticsText: String { diagnostics.joined(separator: "\n") }

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
            CatalogRow(path: "sdxl_base.json", name: "SDXL 基础", sizeBytes: 18_432),
            CatalogRow(path: "flux_portrait.json", name: "Flux 人像", sizeBytes: 7_680),
            CatalogRow(path: "wan_video.json", name: "Wan 视频", sizeBytes: 2_411_724),
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
    private static let historyKey = "baseUrlHistory"
    /// 历史只留最近几条：太多会把连接页撑长，实际来回用的就那两三台机器。
    private static let historyLimit = 6

    /// 没存过就返回空串：首屏应该让用户自己填，而不是替他连 127.0.0.1。
    static var initial: String {
        if let raw = LaunchOptions.baseUrlFromArgs { return raw }
        return UserDefaults.standard.string(forKey: key) ?? ""
    }

    static var hasSaved: Bool {
        (UserDefaults.standard.string(forKey: key)?.isEmpty == false)
    }

    /// 去空格、补协议、去尾斜杠；保留路径（ComfyUI 可能挂在反代子路径下）。
    static func normalize(_ raw: String) -> String {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return LaunchOptions.defaultBaseUrl }
        if !text.contains("://") { text = "http://" + text }
        while text.hasSuffix("/") { text.removeLast() }
        return text
    }

    static var history: [String] {
        UserDefaults.standard.stringArray(forKey: historyKey) ?? []
    }

    /// 连接时记一条：当前地址提到最前，重复地址不重复存。
    static func save(_ value: String) {
        guard !value.isEmpty else { return }
        UserDefaults.standard.set(value, forKey: key)
        var items = history.filter { $0 != value }
        items.insert(value, at: 0)
        if items.count > historyLimit { items.removeLast(items.count - historyLimit) }
        UserDefaults.standard.set(items, forKey: historyKey)
    }

    /// 备份恢复：整体替换最近地址与历史（空串表示没有记录）。
    static func replace(last: String, history: [String]) {
        let trimmedLast = last.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedLast.isEmpty {
            UserDefaults.standard.removeObject(forKey: key)
        } else {
            UserDefaults.standard.set(trimmedLast, forKey: key)
        }
        var seen = Set<String>()
        var items: [String] = []
        for raw in history {
            let url = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !url.isEmpty, seen.insert(url).inserted else { continue }
            items.append(url)
        }
        if items.count > historyLimit { items.removeLast(items.count - historyLimit) }
        UserDefaults.standard.set(items, forKey: historyKey)
    }
}
