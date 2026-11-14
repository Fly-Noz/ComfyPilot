import SwiftUI

@main
struct ComfyPilotApp: App {
    @StateObject private var store = SessionStore()
    @StateObject private var updates = UpdateStore()

    init() {
        // CI 探针：把本地备份原样写到沙盒 Documents（-uiBackupDump 文件名），
        // 流水线据此校验导出格式——备份是数据功能，格式错了会丢用户数据。
        if let name = LaunchOptions.backupDumpPath {
            do {
                Self.writeProbe(text: try BackupManager.makeBackupText(), name: name)
            } catch {
                NSLog("[ComfyPilot] 备份写出失败: %@", error.localizedDescription)
            }
        }
        // CI 探针：往返自检（导出 → 恢复 → 再导出），验证导入真的把数据还原了。
        if let name = LaunchOptions.backupRoundTripPath {
            let report = BackupManager.roundTripReport()
            if let data = try? JSONSerialization.data(
                withJSONObject: report,
                options: [.prettyPrinted, .sortedKeys],
            ), let text = String(data: data, encoding: .utf8) {
                Self.writeProbe(text: text, name: name)
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(updates)
        }
    }

    /// 探针文件写到沙盒 Documents（或绝对路径），供 CI 读取。
    private static func writeProbe(text: String, name: String) {
        let url = name.hasPrefix("/")
            ? URL(fileURLWithPath: name)
            : FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .appendingPathComponent(name)
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            NSLog("[ComfyPilot] 探针已写出: %@", url.path)
        } catch {
            NSLog("[ComfyPilot] 探针写出失败: %@", error.localizedDescription)
        }
    }
}
