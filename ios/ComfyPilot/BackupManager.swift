import Foundation
import SwiftUI
import UniformTypeIdentifiers

/// 本地备份：导出 / 导入 ComfyPilot 的本机记录。
///
/// 与 Android `backup/BackupManager.kt`、鸿蒙 `backup/BackupManager.ets`
/// **共用同一份 JSON 结构**（format / version / 字段名都一致），
/// 所以一份备份可以在三端之间互相导入。
///
/// 只含本机记录；不含 AI API Key、clientId、诊断日志。
enum BackupManager {
    static let formatName = "comfypilot-backup"
    static let formatVersion = 1
    static let maxBytes = 30 * 1024 * 1024

    /// 导入后的摘要：主题要立刻生效，数量用于提示。
    struct Summary {
        let theme: String
        let localCount: Int
        let taskCount: Int
    }

    enum Failure: LocalizedError {
        case message(String)

        var errorDescription: String? {
            if case .message(let text) = self { return text }
            return nil
        }
    }

    // MARK: - 导出

    /// 构建备份文本（pretty + 键排序，和安卓 `toString(2)` 对齐）。
    static func makeBackupText() throws -> String {
        let drafts = WorkflowDrafts.allScoped()
        var workflowDrafts: [String: String] = [:]
        var nodeNotes: [String: String] = [:]
        var pinnedNodes: [String: String] = [:]

        for record in drafts {
            let scoped = "\(record.host)|\(record.path)"
            if !record.draft.overrides.isEmpty,
               let text = jsonText(record.draft.overrides) {
                workflowDrafts[scoped] = text
            }
            if !record.draft.notes.isEmpty {
                // 与安卓 encodeNodeNotes 同格式：每行 `节点id\t备注`，按 id 升序。
                let lines: [String] = record.draft.notes.compactMap { pair -> String? in
                    guard let id = Int(pair.key) else { return nil }
                    let clean = pair.value
                        .replacingOccurrences(of: "\t", with: " ")
                        .replacingOccurrences(of: "\n", with: " ")
                        .replacingOccurrences(of: "\r", with: " ")
                    return "\(id)\t\(clean)"
                }.sorted { lhs, rhs in
                    let left = Int(lhs.split(separator: "\t").first ?? "") ?? 0
                    let right = Int(rhs.split(separator: "\t").first ?? "") ?? 0
                    return left < right
                }
                if !lines.isEmpty {
                    nodeNotes["\(record.host)|workflow:\(record.path)"] = lines.joined(separator: "\n")
                }
            }
            if !record.draft.pinned.isEmpty {
                let list = record.draft.pinned.sorted().map(String.init).joined(separator: ",")
                pinnedNodes["\(record.host)|workflow:\(record.path)"] = list
            }
        }

        let localItems: [[String: Any]] = LocalWorkflowStore.shared.list().compactMap { item in
            guard let raw = LocalWorkflowStore.shared.read(path: item.path) else { return nil }
            return [
                "path": item.path,
                "name": item.name,
                "importedAtMs": item.importedAtMs,
                "raw": raw,
            ]
        }

        let seed = SeedModes.exportJSON()
        let settings: [String: Any] = [
            "theme": Prefs.themeMode,
            "forceSubmit": Prefs.forceSubmit,
            "weakNetwork": Prefs.weakNetwork,
            "updateMode": updateMode,
            "dismissedVersion": UserDefaults.standard.integer(forKey: updateDismissedKey),
        ]

        let root: [String: Any] = [
            "format": formatName,
            "version": formatVersion,
            "exportedAtMs": Int64(Date().timeIntervalSince1970 * 1000),
            "platform": "ios",
            "addresses": [
                "last": SavedAddress.initial,
                "history": SavedAddress.history.map { ["url": $0, "usedAtMs": 0] },
            ],
            "workflowDrafts": workflowDrafts,
            "nodeNotes": nodeNotes,
            "pinnedNodes": pinnedNodes,
            "pinnedWorkflows": PinnedWorkflows.allPaths(),
            "seedEvery": seed.every,
            "seedLast": seed.last,
            "tasks": TaskPersistence.shared.exportTasks(),
            "localWorkflows": localItems,
            "settings": settings,
        ]

        guard let data = try? JSONSerialization.data(
            withJSONObject: root,
            options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes],
        ) else {
            throw Failure.message("备份序列化失败")
        }
        guard let text = String(data: data, encoding: .utf8) else {
            throw Failure.message("备份编码失败")
        }
        guard text.utf8.count <= maxBytes else {
            throw Failure.message("备份超过 30 MB，请先删除部分本地工作流或任务历史")
        }
        return text
    }

    /// 导出文件名：`ComfyPilot-backup-20261114-104500.json`（与两端同格式）。
    static func defaultFileName(_ date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return "ComfyPilot-backup-\(formatter.string(from: date)).json"
    }

    // MARK: - 导入

    /// 解析备份并**整体覆盖**本机记录。
    /// 调用前必须已断开连接（状态机在跑时导入任务历史会互相打架）。
    static func restore(text: String) throws -> Summary {
        guard text.utf8.count <= maxBytes else {
            throw Failure.message("备份文件过大，最多 30 MB")
        }
        guard let data = text.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw Failure.message("备份内容不是有效 JSON")
        }
        guard (root["format"] as? String) == formatName else {
            throw Failure.message("不是 ComfyPilot 本地备份")
        }
        guard let version = (root["version"] as? NSNumber)?.intValue, version == formatVersion else {
            throw Failure.message("备份版本不支持")
        }

        // 先把所有数据解析/校验完，再开始覆盖——避免一半新一半旧。
        let records = parseDrafts(root)
        let pinnedPaths = (root["pinnedWorkflows"] as? [String]) ?? []
        let tasks = parseTasks(root["tasks"])
        let localItems = parseLocalWorkflows(root["localWorkflows"])
        let addresses = parseAddresses(root["addresses"])
        let seedEvery = (root["seedEvery"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "{}"
        let seedLast = (root["seedLast"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "{}"
        let settings = root["settings"] as? [String: Any] ?? [:]

        // 覆盖写入（顺序与两端一致）。
        WorkflowDrafts.replaceAll(records)
        SeedModes.restore(everyJSON: seedEvery, lastJSON: seedLast)
        // 置顶在 iOS 按主机隔离；导入的扁平路径挂到备份里的最近地址上。
        let host = addresses.last.isEmpty ? "unconnected" : addresses.last
        PinnedWorkflows.replaceAll(host: host, paths: pinnedPaths)
        SavedAddress.replace(last: addresses.last, history: addresses.history)
        LocalWorkflowStore.shared.replaceAll(localItems)
        TaskPersistence.shared.replaceAll(tasks)

        let theme = normalizedTheme(settings["theme"] as? String)
        Prefs.themeMode = theme
        Prefs.forceSubmit = (settings["forceSubmit"] as? NSNumber)?.boolValue ?? false
        Prefs.weakNetwork = (settings["weakNetwork"] as? NSNumber)?.boolValue ?? false
        let mode = (settings["updateMode"] as? String) == "once" ? "once" : "always"
        UserDefaults.standard.set(mode, forKey: updateModeKey)
        let dismissed = (settings["dismissedVersion"] as? NSNumber)?.intValue ?? 0
        if dismissed > 0 {
            UserDefaults.standard.set(dismissed, forKey: updateDismissedKey)
        } else {
            UserDefaults.standard.removeObject(forKey: updateDismissedKey)
        }

        return Summary(theme: theme, localCount: localItems.count, taskCount: tasks.count)
    }

    // MARK: - 解析

    /// 把三个 map 合并成 iOS 的草稿记录。
    /// key 兼容三种形态：`host|workflow:path`（备注/置顶）、`host|path`（草稿）、legacy `path`。
    private static func parseDrafts(_ root: [String: Any]) -> [(host: String, path: String, draft: WorkflowDraft)] {
        let drafts = root["workflowDrafts"] as? [String: Any] ?? [:]
        let notes = root["nodeNotes"] as? [String: Any] ?? [:]
        let pinned = root["pinnedNodes"] as? [String: Any] ?? [:]

        var merged: [String: WorkflowDraft] = [:]
        var paths: [String: (host: String, path: String)] = [:]

        func scope(_ key: String) -> (host: String, path: String, id: String) {
            if let range = key.range(of: "|workflow:") {
                let host = String(key[key.startIndex..<range.lowerBound])
                let path = String(key[range.upperBound...])
                return (host, path, "\(host)|\(path)")
            }
            if let cut = key.firstIndex(of: "|") {
                let host = String(key[key.startIndex..<cut])
                let path = String(key[key.index(after: cut)...])
                return (host, path, "\(host)|\(path)")
            }
            return ("", key, key)
        }

        for (key, value) in drafts {
            guard let text = value as? String, !text.isEmpty else { continue }
            let scoped = scope(key)
            guard !scoped.path.isEmpty else { continue }
            guard let data = text.data(using: .utf8),
                  let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: [String: Any]]
            else { continue }
            var draft = merged[scoped.id] ?? WorkflowDraft()
            draft.overrides = object
            merged[scoped.id] = draft
            paths[scoped.id] = (scoped.host, scoped.path)
        }

        for (key, value) in notes {
            guard let text = value as? String, !text.isEmpty else { continue }
            let scoped = scope(key)
            guard !scoped.path.isEmpty else { continue }
            var draft = merged[scoped.id] ?? WorkflowDraft()
            draft.notes = decodeNotes(text)
            merged[scoped.id] = draft
            paths[scoped.id] = (scoped.host, scoped.path)
        }

        for (key, value) in pinned {
            guard let text = value as? String, !text.isEmpty else { continue }
            let scoped = scope(key)
            guard !scoped.path.isEmpty else { continue }
            var draft = merged[scoped.id] ?? WorkflowDraft()
            draft.pinned = Set(
                text.split(separator: ",").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) },
            )
            merged[scoped.id] = draft
            paths[scoped.id] = (scoped.host, scoped.path)
        }

        return merged.compactMap { pair -> (host: String, path: String, draft: WorkflowDraft)? in
            guard let scope = paths[pair.key] else { return nil }
            return (scope.host, scope.path, pair.value)
        }
    }

    /// 与安卓 `decodeNodeNotes` 同格式：每行 `节点id\t备注`。
    private static func decodeNotes(_ raw: String) -> [String: String] {
        var result: [String: String] = [:]
        for line in raw.split(separator: "\n", omittingEmptySubsequences: true) {
            guard let cut = line.firstIndex(of: "\t") else { continue }
            let id = line[line.startIndex..<cut].trimmingCharacters(in: .whitespaces)
            guard let nodeId = Int(id) else { continue }
            let label = String(line[line.index(after: cut)...]).trimmingCharacters(in: .whitespaces)
            if !label.isEmpty { result[String(nodeId)] = label }
        }
        return result
    }

    /// 任务既接受对象数组（iOS/鸿蒙导出），也接受 JSON 字符串数组（安卓导出）。
    private static func parseTasks(_ value: Any?) -> [[String: Any]] {
        guard let list = value as? [Any] else { return [] }
        var result: [[String: Any]] = []
        for entry in list {
            if let object = entry as? [String: Any] {
                if let id = object["id"] as? String, !id.isEmpty { result.append(object) }
                continue
            }
            if let text = entry as? String,
               let data = text.data(using: .utf8),
               let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
               let id = object["id"] as? String, !id.isEmpty {
                result.append(object)
            }
        }
        return result
    }

    private static func parseLocalWorkflows(_ value: Any?) -> [LocalWorkflowStore.RestoreItem] {
        guard let list = value as? [[String: Any]] else { return [] }
        return list.compactMap { object in
            guard let path = object["path"] as? String, path.hasPrefix("local://"),
                  let name = object["name"] as? String, !name.isEmpty,
                  let raw = object["raw"] as? String, !raw.isEmpty
            else { return nil }
            return LocalWorkflowStore.RestoreItem(
                path: path,
                name: name,
                importedAtMs: (object["importedAtMs"] as? NSNumber)?.int64Value ?? 0,
                raw: raw,
            )
        }
    }

    private static func parseAddresses(_ value: Any?) -> (last: String, history: [String]) {
        guard let object = value as? [String: Any] else { return ("", []) }
        let last = (object["last"] as? String) ?? ""
        let history = (object["history"] as? [[String: Any]] ?? []).compactMap { entry -> String? in
            let url = (entry["url"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return url.isEmpty ? nil : url
        }
        return (last, history)
    }

    // MARK: - 工具

    private static let updateModeKey = "updatePromptMode"
    private static let updateDismissedKey = "updateDismissedVersion"

    private static var updateMode: String {
        let raw = UserDefaults.standard.string(forKey: updateModeKey) ?? "always"
        return raw == "once" ? "once" : "always"
    }

    private static func normalizedTheme(_ raw: String?) -> String {
        switch raw {
        case "dark": return "dark"
        case "light": return "light"
        default: return "system"
        }
    }

    private static func jsonText(_ object: Any) -> String? {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return nil
        }
        return text
    }
}

/// 「存储到文件」用的 JSON 文档（fileExporter 需要 FileDocument）。
struct BackupFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }

    let text: String

    init(text: String) {
        self.text = text
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents,
              let text = String(data: data, encoding: .utf8) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        self.text = text
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}
