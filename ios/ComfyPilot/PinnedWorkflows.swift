import Foundation

/// 工作流置顶（对齐 Android `PinnedWorkflowStore`）。
/// 按「主机」隔离：不同服务器上的同名路径互不影响；只存本机。
enum PinnedWorkflows {
    private static let prefix = "pinnedWorkflows."

    static func list(host: String) -> [String] {
        UserDefaults.standard.stringArray(forKey: prefix + host) ?? []
    }

    static func isPinned(host: String, path: String) -> Bool {
        list(host: host).contains(path)
    }

    static func toggle(host: String, path: String) {
        var items = list(host: host)
        if let index = items.firstIndex(of: path) {
            items.remove(at: index)
        } else {
            items.insert(path, at: 0)
        }
        UserDefaults.standard.set(items, forKey: prefix + host)
    }

    /// 列表刷新后清理已不存在的路径（工作流被删/换主机）。
    static func prune(host: String, existing: Set<String>) {
        let items = list(host: host)
        let kept = items.filter { existing.contains($0) }
        if kept.count != items.count {
            UserDefaults.standard.set(kept, forKey: prefix + host)
        }
    }

    // MARK: - 备份（三端共用 schema，见 BackupManager）

    /// 所有主机的置顶并集：安卓 / 鸿蒙是扁平集合，导出时对齐。
    static func allPaths() -> [String] {
        let keys = UserDefaults.standard.dictionaryRepresentation().keys.filter { $0.hasPrefix(prefix) }
        var set = Set<String>()
        for key in keys {
            set.formUnion(UserDefaults.standard.stringArray(forKey: key) ?? [])
        }
        return set.sorted()
    }

    /// 备份恢复：清掉所有主机的置顶，再把备份里的路径挂到指定主机。
    static func replaceAll(host: String, paths: [String]) {
        for key in UserDefaults.standard.dictionaryRepresentation().keys where key.hasPrefix(prefix) {
            UserDefaults.standard.removeObject(forKey: key)
        }
        if !paths.isEmpty {
            UserDefaults.standard.set(paths, forKey: prefix + host)
        }
    }
}
