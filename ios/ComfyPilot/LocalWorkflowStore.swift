import Foundation

/// 本地导入的工作流：原文存沙盒，索引只记展示信息。
///
/// 对齐 Android `LocalWorkflowStore`：路径形如 `local://<uuid>`，和服务器路径
/// 分开（草稿 / 备注按主机 + 路径隔离）；不写回 ComfyUI 主机，也不上传。
final class LocalWorkflowStore {
    static let shared = LocalWorkflowStore()

    struct Item: Identifiable, Equatable {
        let path: String
        let name: String
        let importedAtMs: Int64
        var id: String { path }
    }

    /// 与 Android 导入校验一致的上限。
    static let maxBytes = 2 * 1024 * 1024

    private var dir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("ComfyPilot", isDirectory: true)
            .appendingPathComponent("local_workflows", isDirectory: true)
    }

    private var indexURL: URL { dir.appendingPathComponent("index.json") }

    private init() {}

    func list() -> [Item] {
        guard let data = try? Data(contentsOf: indexURL),
              let array = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else {
            return []
        }
        return array.compactMap { object -> Item? in
            guard let path = object["path"] as? String, !path.isEmpty,
                  let name = object["name"] as? String, !name.isEmpty,
                  fileExists(path: path) else { return nil }
            let importedAt = (object["importedAtMs"] as? NSNumber)?.int64Value ?? 0
            return Item(path: path, name: name, importedAtMs: importedAt)
        }
        .sorted { $0.importedAtMs > $1.importedAtMs }
    }

    /// 原文过大或不是 JSON 的校验交给调用方；这里只负责落盘。
    @discardableResult
    func save(name: String, raw: String) -> Item? {
        do {
            try FileManager.default.createDirectory(
                at: dir,
                withIntermediateDirectories: true,
                attributes: nil,
            )
        } catch {
            NSLog("[ComfyPilot] 本地工作流目录创建失败: %@", error.localizedDescription)
            return nil
        }
        let id = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let item = Item(
            path: "local://\(id)",
            name: name.isEmpty ? "本地工作流" : name,
            importedAtMs: Int64(Date().timeIntervalSince1970 * 1000.0),
        )
        let file = dir.appendingPathComponent("\(id).json")
        do {
            try raw.data(using: .utf8)?.write(to: file, options: .atomic)
        } catch {
            NSLog("[ComfyPilot] 本地工作流写入失败: %@", error.localizedDescription)
            return nil
        }
        var items = list().filter { $0.path != item.path }
        items.insert(item, at: 0)
        writeIndex(items)
        return item
    }

    func read(path: String) -> String? {
        let file = fileURL(for: path)
        guard let data = try? Data(contentsOf: file) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func name(for path: String) -> String? {
        list().first { $0.path == path }?.name
    }

    /// 删除本地导入；只删沙盒文件，不影响主机。
    func delete(path: String) {
        try? FileManager.default.removeItem(at: fileURL(for: path))
        writeIndex(list().filter { $0.path != path })
    }

    // MARK: - 备份（三端共用 schema，见 BackupManager）

    /// 备份恢复条目：原文随条目一起带，避免二次读盘。
    struct RestoreItem {
        let path: String
        let name: String
        let importedAtMs: Int64
        let raw: String
    }

    /// 备份恢复：整体替换本机导入的工作流。
    ///
    /// 两阶段提交：先把所有新文件写进临时目录（`.staging-*`），全部写成功
    /// 才删旧文件并移入；任何一步失败都返回 false，旧数据原样保留——
    /// 备份恢复不能出现「导入失败还把原有工作流清空了」。
    @discardableResult
    func replaceAll(_ items: [RestoreItem]) -> Bool {
        let valid = items.filter { $0.path.hasPrefix("local://") }
        let staging = dir.appendingPathComponent(".staging-\(UUID().uuidString)", isDirectory: true)
        var index: [Item] = []

        do {
            try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
            for entry in valid {
                guard let data = entry.raw.data(using: .utf8) else {
                    throw CocoaError(.fileWriteInapplicableStringEncoding)
                }
                try data.write(
                    to: staging.appendingPathComponent(fileName(for: entry.path), isDirectory: false),
                    options: .atomic,
                )
                index.append(Item(path: entry.path, name: entry.name, importedAtMs: entry.importedAtMs))
            }
        } catch {
            try? FileManager.default.removeItem(at: staging)
            NSLog("[ComfyPilot] 备份工作流暂存失败: %@", error.localizedDescription)
            return false
        }

        // 提交阶段：删旧文件 → 移入新文件 → 写索引。
        do {
            if FileManager.default.fileExists(atPath: dir.path) {
                let files = try FileManager.default.contentsOfDirectory(
                    at: dir,
                    includingPropertiesForKeys: nil,
                )
                for file in files where file.pathExtension == "json" {
                    try FileManager.default.removeItem(at: file)
                }
            }
            if FileManager.default.fileExists(atPath: staging.path) {
                let staged = try FileManager.default.contentsOfDirectory(
                    at: staging,
                    includingPropertiesForKeys: nil,
                )
                for file in staged where file.pathExtension == "json" {
                    let target = dir.appendingPathComponent(file.lastPathComponent)
                    try FileManager.default.moveItem(at: file, to: target)
                }
                try? FileManager.default.removeItem(at: staging)
            }
            return writeIndex(index)
        } catch {
            NSLog("[ComfyPilot] 提交本地工作流失败: %@", error.localizedDescription)
            return false
        }
    }

    private func fileExists(path: String) -> Bool {
        FileManager.default.fileExists(atPath: fileURL(for: path).path)
    }

    /// 沙盒里的文件名：`local://<uuid>` → `<uuid>.json`（只取最后一段，防目录穿越）。
    private func fileName(for path: String) -> String {
        let id = path.split(separator: "/").last.map(String.init) ?? path
        return "\(id).json"
    }

    private func fileURL(for path: String) -> URL {
        dir.appendingPathComponent(fileName(for: path))
    }

    @discardableResult
    private func writeIndex(_ items: [Item]) -> Bool {
        let array: [[String: Any]] = items.map {
            ["path": $0.path, "name": $0.name, "importedAtMs": $0.importedAtMs]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: array) else { return false }
        do {
            try data.write(to: indexURL, options: .atomic)
            return true
        } catch {
            NSLog("[ComfyPilot] 写本地工作流索引失败: %@", error.localizedDescription)
            return false
        }
    }
}
