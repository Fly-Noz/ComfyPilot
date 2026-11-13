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
}
