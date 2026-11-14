import SwiftUI

@main
struct ComfyPilotApp: App {
    @StateObject private var store = SessionStore()
    @StateObject private var updates = UpdateStore()

    init() {
        // CI 探针：把本地备份原样写到沙盒 Documents（-uiBackupDump 文件名），
        // 流水线据此校验导出格式——备份是数据功能，格式错了会丢用户数据。
        if let name = LaunchOptions.backupDumpPath {
            let url = name.hasPrefix("/")
                ? URL(fileURLWithPath: name)
                : FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                    .appendingPathComponent(name)
            do {
                let text = try BackupManager.makeBackupText()
                try text.write(to: url, atomically: true, encoding: .utf8)
                NSLog("[ComfyPilot] 备份已写出: %@", url.path)
            } catch {
                NSLog("[ComfyPilot] 备份写出失败: %@", error.localizedDescription)
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
}
