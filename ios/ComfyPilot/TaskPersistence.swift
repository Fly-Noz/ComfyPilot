import Foundation

/// 任务快照持久化（P0-1）。
///
/// shared 的 `SessionHost` 在任务变化时通过 bridge 命令下发 `persist` /
/// `persist.remove`，payload 是 `toPersistJson()` 的单个任务 JSON ——
/// **schema 由 Kotlin 单方面拥有，这里只做原样存取**（不解析字段、不改写）。
///
/// 存储：`Application Support/ComfyPilot/tasks.json`，原子写（`.atomic` =
/// 临时文件 + rename）。任务快照是数据不是偏好，不用 UserDefaults 的异步落盘。
/// 旧版本（69c0e87 首版）存在 UserDefaults 里的快照会一次性迁移过来。
///
/// 保护与 Android `JsonFileTaskStore` 对齐：
/// - `removed` 集合：`persist.remove` 之后迟到的旧 `persist` 不能复活任务；
/// - stale 丢弃：`updatedAtMs` 更旧的快照不覆盖更新的状态；
/// - 裁剪按 `updatedAtMs`：创建很久但最近还在跑的任务不会被新建任务挤掉。
final class TaskPersistence {
    static let shared = TaskPersistence()

    /// 与 shared `DEFAULT_RESTORE_LIMIT` 对齐。
    private let limit = 50
    private let flushInterval: TimeInterval = 0.8
    private let legacyDefaultsKey = "comfypilot.taskSnapshots.v1"

    private var tasks: [String: [String: Any]] = [:]
    /// 已删除的 id：异步命令下，旧的 persist 可能晚于 remove 到达。
    /// 只在本进程生命周期内有效——任务 id 是 shared 的 `draft-N`，进程内单调
    /// 递增不复用（重启后 `restoreTasks` 会按恢复任务重算 nextDraftId，此时本
    /// 集合已随进程销毁），所以永不清空是安全的，量级也只是本会话的删除数。
    /// （外部约束：ComfyUI 的 promptId 正常也不复用；即使极端复用，removed
    /// 命中的旧 id 只会让该任务本进程内不落盘，不影响其他数据。）
    private var removed: Set<String> = []
    private var lastFlush: TimeInterval = 0
    private var loaded = false
    /// 每个任务上次落盘时的 state。Submitting→Queued→Running→Completed 这类
    /// 状态跳变必须立即写盘——只按 0.8s 节流的话，"运行中"重启时可能还在
    /// 文件里留着 Submitting，恢复后会被误标成「结果未知」。
    /// 进度更新时 state 不变，仍然走节流，不会 4Hz 写盘。
    private var flushedState: [String: String] = [:]
    /// `-uiResetTasks` 只消费一次：重连时再次 start() 不能把刚存的又清掉。
    private var didReset = false

    private init() {}

    private var fileURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base
            .appendingPathComponent("ComfyPilot", isDirectory: true)
            .appendingPathComponent("tasks.json")
    }

    /// 启动时交给 BridgeHost 的 tasksJson。没有历史时给 "{}"
    /// （`restoredTasksFromJson` 对没有 tasks 键的对象返回空列表）。
    func loadTasksJson() -> String {
        if LaunchOptions.resetTasks && !didReset {
            didReset = true
            try? FileManager.default.removeItem(at: fileURL)
            UserDefaults.standard.removeObject(forKey: legacyDefaultsKey)
            return "{}"
        }
        if let text = try? String(contentsOf: fileURL, encoding: .utf8), !text.isEmpty {
            return text
        }
        // 首版存 UserDefaults：先写成功新文件，再删旧 key——
        // 反过来的话，文件写失败时两份数据都没了。
        if let legacy = UserDefaults.standard.string(forKey: legacyDefaultsKey) {
            if writeSnapshot(text: legacy) {
                UserDefaults.standard.removeObject(forKey: legacyDefaultsKey)
            } else {
                // 本次仍用旧数据启动；旧 key 保留，下次启动会重试迁移。
                NSLog("[ComfyPilot] 任务快照迁移写盘失败，保留 UserDefaults 旧数据待重试")
            }
            return legacy
        }
        return "{}"
    }

    func handlePersist(_ task: [String: Any]) {
        guard let id = task["id"] as? String else { return }
        ensureLoaded()
        guard !removed.contains(id) else { return }
        if let old = tasks[id], updatedMs(old) > updatedMs(task) {
            // 迟到的旧快照：不覆盖更新的状态。
            return
        }
        let state = task["state"] as? String ?? ""
        let stateChanged = flushedState[id] != state
        tasks[id] = task
        // 状态跳变（含终结态）立即落盘；纯进度更新走节流。
        flushIfNeeded(force: stateChanged)
    }

    func handleRemove(id: String) {
        ensureLoaded()
        removed.insert(id)
        tasks.removeValue(forKey: id)
        // 删除必须立刻生效，否则重启后任务会"复活"。
        flushIfNeeded(force: true)
    }

    /// 强制落盘：桥停止、退到后台、退出时调用。
    /// 返回是否写成功——写失败会进 NSLog（Console 可见），且不推进节流时间，
    /// 让下一次 persist 立刻重试，而不是把失败静默吞掉。
    @discardableResult
    func flush() -> Bool {
        ensureLoaded()
        let sorted = tasks.values.sorted { updatedMs($0) > updatedMs($1) }
        let payload: [String: Any] = ["tasks": Array(sorted.prefix(limit))]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8) else {
            NSLog("[ComfyPilot] 任务快照序列化失败，本次未落盘")
            return false
        }
        guard writeSnapshot(text: text) else { return false }
        lastFlush = Date().timeIntervalSince1970
        flushedState = tasks.mapValues { $0["state"] as? String ?? "" }
        return true
    }

    /// 原子写快照：临时文件 + rename，避免写一半被杀留下坏文件。
    private func writeSnapshot(text: String) -> Bool {
        guard let data = text.data(using: .utf8) else { return false }
        do {
            try FileManager.default.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true,
                attributes: nil,
            )
            try data.write(to: fileURL, options: .atomic)
            return true
        } catch {
            NSLog("[ComfyPilot] 任务快照写盘失败: %@", error.localizedDescription)
            return false
        }
    }

    private func ensureLoaded() {
        guard !loaded else { return }
        loaded = true
        guard let data = try? Data(contentsOf: fileURL),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let list = object["tasks"] as? [[String: Any]] else { return }
        for task in list {
            if let id = task["id"] as? String {
                tasks[id] = task
                flushedState[id] = task["state"] as? String ?? ""
            }
        }
    }

    private func updatedMs(_ task: [String: Any]) -> Int64 {
        (task["updatedAtMs"] as? NSNumber)?.int64Value ?? 0
    }

    private func flushIfNeeded(force: Bool) {
        let now = Date().timeIntervalSince1970
        guard force || now - lastFlush >= flushInterval else { return }
        flush()
    }
}
