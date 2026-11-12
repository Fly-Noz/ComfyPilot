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
    private var removed: Set<String> = []
    private var lastFlush: TimeInterval = 0
    private var loaded = false
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
        // 首版存 UserDefaults：迁移一次后删掉旧 key。
        if let legacy = UserDefaults.standard.string(forKey: legacyDefaultsKey) {
            UserDefaults.standard.removeObject(forKey: legacyDefaultsKey)
            if let data = legacy.data(using: .utf8) {
                try? FileManager.default.createDirectory(
                    at: fileURL.deletingLastPathComponent(),
                    withIntermediateDirectories: true,
                    attributes: nil,
                )
                try? data.write(to: fileURL, options: .atomic)
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
        tasks[id] = task
        // 终结态立即落盘：完成/失败结果不能因为节流窗口丢掉。
        let state = task["state"] as? String ?? ""
        let terminal = state == "Completed" || state == "Failed" || state == "Cancelled"
        flushIfNeeded(force: terminal)
    }

    func handleRemove(id: String) {
        ensureLoaded()
        removed.insert(id)
        tasks.removeValue(forKey: id)
        // 删除必须立刻生效，否则重启后任务会"复活"。
        flushIfNeeded(force: true)
    }

    /// 强制落盘：桥停止、退到后台、退出时调用。
    func flush() {
        ensureLoaded()
        let sorted = tasks.values.sorted { updatedMs($0) > updatedMs($1) }
        let payload: [String: Any] = ["tasks": Array(sorted.prefix(limit))]
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return }
        try? FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(),
            withIntermediateDirectories: true,
            attributes: nil,
        )
        // .atomic：先写临时文件再 rename，避免写一半被杀留下坏快照。
        try? data.write(to: fileURL, options: .atomic)
        lastFlush = Date().timeIntervalSince1970
    }

    private func ensureLoaded() {
        guard !loaded else { return }
        loaded = true
        guard let data = try? Data(contentsOf: fileURL),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let list = object["tasks"] as? [[String: Any]] else { return }
        for task in list {
            if let id = task["id"] as? String { tasks[id] = task }
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
