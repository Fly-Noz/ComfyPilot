import Foundation

/// 任务快照持久化。
///
/// shared 的 `SessionHost` 在任务变化时通过 bridge 命令下发 `persist` /
/// `persist.remove`，payload 是 `toPersistJson()` 的单个任务 JSON ——
/// **schema 由 Kotlin 单方面拥有，这里只做原样存取**（不解析字段、不改写），
/// 和 Android `JsonFileTaskStore` 用同一份格式。
///
/// 存 UserDefaults：`{"tasks":[...]}` 字符串，启动时原样交给
/// `IosHost(configJson:tasksJson:)`，由 shared 的 `restoredTasksFromJson` 解析。
final class TaskPersistence {
    static let shared = TaskPersistence()

    private let defaultsKey = "comfypilot.taskSnapshots.v1"
    /// 与 shared `DEFAULT_RESTORE_LIMIT` 对齐。
    private let limit = 50
    private let flushInterval: TimeInterval = 0.8

    private var tasks: [String: [String: Any]] = [:]
    private var lastFlush: TimeInterval = 0
    private var loaded = false

    private init() {}

    /// 启动时交给 BridgeHost 的 tasksJson。没有历史时给 "{}"
    /// （`restoredTasksFromJson` 对没有 tasks 键的对象返回空列表）。
    func loadTasksJson() -> String {
        UserDefaults.standard.string(forKey: defaultsKey) ?? "{}"
    }

    func handlePersist(_ task: [String: Any]) {
        guard let id = task["id"] as? String else { return }
        ensureLoaded()
        tasks[id] = task
        // 终结态立即落盘：完成/失败结果不能因为节流窗口丢掉。
        let state = task["state"] as? String ?? ""
        let terminal = state == "Completed" || state == "Failed" || state == "Cancelled"
        flushIfNeeded(force: terminal)
    }

    func handleRemove(id: String) {
        ensureLoaded()
        tasks.removeValue(forKey: id)
        flushIfNeeded(force: true)
    }

    /// 强制落盘：桥停止、App 退到后台或退出时调用。
    func flush() {
        ensureLoaded()
        let sorted = tasks.values.sorted {
            (($0["createdAtMs"] as? NSNumber)?.int64Value ?? 0)
                > (($1["createdAtMs"] as? NSNumber)?.int64Value ?? 0)
        }
        let payload: [String: Any] = ["tasks": Array(sorted.prefix(limit))]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8) else { return }
        UserDefaults.standard.set(text, forKey: defaultsKey)
        lastFlush = Date().timeIntervalSince1970
    }

    private func ensureLoaded() {
        guard !loaded else { return }
        loaded = true
        guard let text = UserDefaults.standard.string(forKey: defaultsKey),
              let data = text.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let list = object["tasks"] as? [[String: Any]] else { return }
        for task in list {
            if let id = task["id"] as? String { tasks[id] = task }
        }
    }

    private func flushIfNeeded(force: Bool) {
        let now = Date().timeIntervalSince1970
        guard force || now - lastFlush >= flushInterval else { return }
        flush()
    }
}
