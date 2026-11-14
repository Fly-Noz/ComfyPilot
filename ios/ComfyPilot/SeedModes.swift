import Foundation

/// 种子字段的本机行为：每次随机（默认）/ 固定，并记住每个节点上次的种子。
///
/// 对齐 Android `SeedModeStore`：不改服务器上的工作流文件，模式和
/// 「上次种子」都只存本机；键按「主机 + 路径 + 节点」隔离。
enum SeedModes {
    private static let everyKey = "seedEveryRandom"
    private static let lastKey = "seedLast"

    private static func key(host: String, path: String, nodeId: Int) -> String {
        "\(host)|\(path)|\(nodeId)"
    }

    /// 默认开启「每次随机」；只有用户明确关过才为 false。
    static func isEveryRandom(host: String, path: String, nodeId: Int) -> Bool {
        let map = UserDefaults.standard.dictionary(forKey: everyKey) as? [String: Int] ?? [:]
        return map[key(host: host, path: path, nodeId: nodeId)] != 0
    }

    static func setEveryRandom(host: String, path: String, nodeId: Int, value: Bool) {
        var map = UserDefaults.standard.dictionary(forKey: everyKey) as? [String: Int] ?? [:]
        map[key(host: host, path: path, nodeId: nodeId)] = value ? 1 : 0
        UserDefaults.standard.set(map, forKey: everyKey)
    }

    static func lastSeed(host: String, path: String, nodeId: Int) -> Int64? {
        let map = UserDefaults.standard.dictionary(forKey: lastKey) as? [String: NSNumber] ?? [:]
        return map[key(host: host, path: path, nodeId: nodeId)]?.int64Value
    }

    static func rememberSeed(host: String, path: String, nodeId: Int, seed: Int64) {
        var map = UserDefaults.standard.dictionary(forKey: lastKey) as? [String: NSNumber] ?? [:]
        map[key(host: host, path: path, nodeId: nodeId)] = NSNumber(value: seed)
        UserDefaults.standard.set(map, forKey: lastKey)
    }

    // MARK: - 备份（三端共用 schema，见 BackupManager）

    /// 两个映射的 JSON 文本（与安卓 / 鸿蒙的 `every` / `last` 同结构）。
    static func exportJSON() -> (every: String, last: String) {
        let every = UserDefaults.standard.dictionary(forKey: everyKey) ?? [:]
        let last = UserDefaults.standard.dictionary(forKey: lastKey) ?? [:]
        return (jsonText(every), jsonText(last))
    }

    /// 备份恢复：整体替换两个映射（空/坏 JSON 按空处理）。
    static func restore(everyJSON: String, lastJSON: String) {
        UserDefaults.standard.set(parseMap(everyJSON), forKey: everyKey)
        UserDefaults.standard.set(parseMap(lastJSON), forKey: lastKey)
    }

    private static func jsonText(_ map: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(map),
              let data = try? JSONSerialization.data(withJSONObject: map, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return text
    }

    private static func parseMap(_ raw: String) -> [String: Any] {
        guard let data = raw.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return [:]
        }
        return object
    }

    /// 种子字段判定：可调的 INT 控件，名字含 seed（对齐 Android `isSeedField`）。
    static func isSeedField(_ field: WorkflowField) -> Bool {
        field.widget && !field.linked && field.type == "INT" &&
            field.inputName.lowercased().contains("seed")
    }

    /// 提交前替换：每次随机的节点换新随机数，固定模式记住当前值。
    /// 只处理真正会提交的节点（关闭 / 不可达的改了也没意义）。
    static func prepareForSubmit(
        host: String,
        path: String,
        overrides: [String: [String: Any]],
        nodes: [WorkflowNode],
    ) -> [String: [String: Any]] {
        var next = overrides
        for node in nodes {
            guard node.reachable, node.enabled else { continue }
            for field in node.fields {
                guard isSeedField(field) else { continue }
                let nodeKey = String(node.id)
                if isEveryRandom(host: host, path: path, nodeId: node.id) {
                    let seed = Int64.random(in: 1...2_147_483_647)
                    next[nodeKey, default: [:]][field.inputName] = NSNumber(value: seed)
                    rememberSeed(host: host, path: path, nodeId: node.id, seed: seed)
                } else if let current = numericValue(next[nodeKey]?[field.inputName]) {
                    rememberSeed(host: host, path: path, nodeId: node.id, seed: current)
                }
            }
        }
        return next
    }

    /// overrides 里的值可能是 Int / Int64 / NSNumber / String（输入框提交的是文本），
    /// 统一按整数解析。
    private static func numericValue(_ value: Any?) -> Int64? {
        switch value {
        case let v as Int64: return v
        case let v as Int: return Int64(v)
        case let v as NSNumber: return v.int64Value
        case let v as String: return Int64(v.trimmingCharacters(in: .whitespaces))
        default: return nil
        }
    }
}
