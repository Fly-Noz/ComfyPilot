import Foundation

/// 一个工作流的本机草稿：参数改动、置顶、图分组选择。
///
/// 按「主机 + 路径」隔离，和安卓的 `WorkflowDraftStore` 一个粒度：
/// 同一个工作流在两台机器上互不影响，也不会把 A 主机的改动带到 B 主机。
/// 只存本机，永远不写回服务器上的工作流文件。
struct WorkflowDraft {
    var overrides: [String: [String: Any]] = [:]
    var pinned: Set<Int> = []
    /// nil = 从没动过图分组，用工作流文件里的默认选择。
    var graphs: Set<String>?
    /// 节点备注，键是节点 id。和安卓一样跟着草稿存本机。
    var notes: [String: String] = [:]
    var updatedAtMs: Int64 = 0
}

enum WorkflowDrafts {
    private static let prefix = "workflowDraft."
    /// 最多留 40 份：再旧的草稿基本不会再打开，留着只占空间。
    private static let limit = 40

    static func key(host: String, path: String) -> String {
        prefix + host + "|" + path
    }

    static func load(host: String, path: String) -> WorkflowDraft? {
        guard let data = UserDefaults.standard.data(forKey: key(host: host, path: path)),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        var draft = WorkflowDraft()
        if let overrides = root["overrides"] as? [String: [String: Any]] {
            draft.overrides = overrides
        }
        if let pinned = root["pinned"] as? [NSNumber] {
            draft.pinned = Set(pinned.map { $0.intValue })
        }
        if let graphs = root["graphs"] as? [String] {
            draft.graphs = Set(graphs)
        }
        if let notes = root["notes"] as? [String: String] {
            draft.notes = notes
        }
        draft.updatedAtMs = (root["updatedAtMs"] as? NSNumber)?.int64Value ?? 0
        return draft
    }

    static func save(_ draft: WorkflowDraft, host: String, path: String) {
        write(draft, host: host, path: path)
        prune()
    }

    /// 实际写入（不裁剪）：恢复备份要完整，不能被 40 份上限裁掉。
    private static func write(_ draft: WorkflowDraft, host: String, path: String) {
        var root: [String: Any] = [
            "updatedAtMs": Int64(Date().timeIntervalSince1970 * 1000),
        ]
        if !draft.overrides.isEmpty { root["overrides"] = draft.overrides }
        if !draft.pinned.isEmpty { root["pinned"] = draft.pinned.sorted() }
        if let graphs = draft.graphs { root["graphs"] = graphs.sorted() }
        if !draft.notes.isEmpty { root["notes"] = draft.notes }

        guard let data = try? JSONSerialization.data(withJSONObject: root) else { return }
        UserDefaults.standard.set(data, forKey: key(host: host, path: path))
    }

    // MARK: - 备份（三端共用 schema，见 BackupManager）

    /// 所有本机草稿的「主机 + 路径 + 内容」，导出备份用。
    static func allScoped() -> [(host: String, path: String, draft: WorkflowDraft)] {
        let keys = UserDefaults.standard.dictionaryRepresentation().keys.filter { $0.hasPrefix(prefix) }
        var result: [(host: String, path: String, draft: WorkflowDraft)] = []
        for key in keys {
            let rest = String(key.dropFirst(prefix.count))
            guard let cut = rest.firstIndex(of: "|") else { continue }
            let host = String(rest[rest.startIndex..<cut])
            let path = String(rest[rest.index(after: cut)...])
            guard !path.isEmpty, let draft = load(host: host, path: path) else { continue }
            result.append((host: host, path: path, draft: draft))
        }
        return result
    }

    /// 备份恢复：整体替换本机草稿。
    static func replaceAll(_ records: [(host: String, path: String, draft: WorkflowDraft)]) {
        for key in UserDefaults.standard.dictionaryRepresentation().keys where key.hasPrefix(prefix) {
            UserDefaults.standard.removeObject(forKey: key)
        }
        for record in records {
            write(record.draft, host: record.host, path: record.path)
        }
    }

    static func clear(host: String, path: String) {
        UserDefaults.standard.removeObject(forKey: key(host: host, path: path))
    }

    private static func prune() {
        let keys = UserDefaults.standard.dictionaryRepresentation().keys.filter { $0.hasPrefix(prefix) }
        guard keys.count > limit else { return }
        let sorted = keys.compactMap { key -> (String, Int64)? in
            guard let data = UserDefaults.standard.data(forKey: key),
                  let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return (key, 0)
            }
            return (key, (root["updatedAtMs"] as? NSNumber)?.int64Value ?? 0)
        }.sorted { $0.1 > $1.1 }
        for (key, _) in sorted.dropFirst(limit) {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }
}
