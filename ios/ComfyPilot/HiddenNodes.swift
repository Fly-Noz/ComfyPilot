import Foundation

/// 节点隐藏：纯本机 UI 偏好，按「主机 + 路径」隔离。
///
/// 隐藏只影响列表显示，不进入工作流或任务协议——被隐藏的节点照常提交、
/// 照常参与构建，只是卡片收在列表底部的「已隐藏」区域，可随时恢复。
enum HiddenNodes {
    private static let prefix = "hiddenNodes."
    private static let noticeKey = "hiddenNodesNoticeAcknowledged"

    static func list(host: String, path: String) -> Set<Int> {
        let raw = UserDefaults.standard.array(forKey: prefix + host + "|" + path) as? [NSNumber] ?? []
        return Set(raw.map { $0.intValue })
    }

    static func save(host: String, path: String, ids: Set<Int>) {
        let key = prefix + host + "|" + path
        if ids.isEmpty {
            UserDefaults.standard.removeObject(forKey: key)
        } else {
            UserDefaults.standard.set(ids.sorted(), forKey: key)
        }
    }

    /// 隐藏是破坏可见性的操作：第一次使用前提醒一次，确认后不再打扰。
    static var noticeAcknowledged: Bool {
        get { UserDefaults.standard.bool(forKey: noticeKey) }
        set { UserDefaults.standard.set(newValue, forKey: noticeKey) }
    }
}
