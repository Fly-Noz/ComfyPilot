import Foundation
import SwiftUI
import UIKit

/// 更新提醒的模式。默认保持旧行为：每次启动都提示。
enum UpdatePromptMode: String, CaseIterable {
    case always
    case once

    var label: String {
        switch self {
        case .always: return "每次启动都提示"
        case .once: return "每个版本只提示一次"
        }
    }

    var detail: String {
        switch self {
        case .always: return "有新版就提示"
        case .once: return "点过稍后后，同一版本不再提示；出现更新版本或手动检查仍会提示"
        }
    }
}

enum UpdatePrefs {
    private static let modeKey = "updatePromptMode"
    private static let dismissedKey = "updateDismissedVersion"

    static var mode: UpdatePromptMode {
        get {
            let raw = UserDefaults.standard.string(forKey: modeKey) ?? ""
            return UpdatePromptMode(rawValue: raw) ?? .always
        }
        set {
            UserDefaults.standard.set(newValue.rawValue, forKey: modeKey)
        }
    }

    static var dismissedVersion: Int {
        UserDefaults.standard.integer(forKey: dismissedKey)
    }

    static func markDismissed(_ versionCode: Int) {
        guard versionCode > 0 else { return }
        UserDefaults.standard.set(versionCode, forKey: dismissedKey)
    }
}

struct UpdateInfo: Identifiable {
    let versionCode: Int
    let versionName: String
    let notes: String
    let github: String?
    let gitee: String?

    var id: Int { versionCode }
}

/// iOS 的更新检查。自签 IPA 不能自动安装，这里只负责提示和打开下载页。
@MainActor
final class UpdateStore: ObservableObject {
    @Published var pending: UpdateInfo?
    @Published private(set) var checking = false
    @Published private(set) var manualNote = ""

    private var didSilentCheck = false

    private enum Outcome {
        case available(UpdateInfo)
        case upToDate(String)
        case failed
    }

    func checkSilently() async {
        guard !didSilentCheck else { return }
        didSilentCheck = true
        guard !LaunchOptions.blocksUpdateCheck else { return }
        await check(manual: false)
    }

    func check(manual: Bool) async {
        guard !checking else { return }
        checking = true
        if manual { manualNote = "检查中…" }
        let outcome = await fetch()
        checking = false

        switch outcome {
        case .available(let info):
            if !manual &&
                UpdatePrefs.mode == .once &&
                info.versionCode <= UpdatePrefs.dismissedVersion {
                return
            }
            pending = info
            if manual { manualNote = "发现新版本 \(info.versionName)" }

        case .upToDate(let name):
            if manual { manualNote = "已是最新版本 \(name)" }

        case .failed:
            if manual { manualNote = "检查失败，请检查网络" }
        }
    }

    /// 关闭弹窗时调用；once 模式记下这个版本。
    func dismiss() {
        if let pending, UpdatePrefs.mode == .once {
            UpdatePrefs.markDismissed(pending.versionCode)
        }
        pending = nil
    }

    func open(_ raw: String?) {
        guard let raw, !raw.isEmpty, let url = URL(string: raw) else { return }
        UIApplication.shared.open(url)
    }

    private func fetch() async -> Outcome {
        let sources = [
            "https://gitee.com/Fly-Noz/ComfyPilot/raw/main/update.json",
            "https://raw.githubusercontent.com/Fly-Noz/ComfyPilot/main/update.json",
        ]
        for raw in sources {
            guard let url = URL(string: raw) else { continue }
            var request = URLRequest(
                url: url,
                cachePolicy: .reloadIgnoringLocalCacheData,
                timeoutInterval: 6
            )
            request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
            guard let result = try? await URLSession.shared.data(for: request) else { continue }
            let (data, response) = result
            guard let http = response as? HTTPURLResponse,
                  (200..<300).contains(http.statusCode) else { continue }
            guard let outcome = parse(data) else { continue }
            return outcome
        }
        return .failed
    }

    private func parse(_ data: Data) -> Outcome? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let code = intValue(root["versionCode"]),
              code > 0,
              let name = (root["versionName"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !name.isEmpty else {
            return nil
        }
        let current = currentVersionCode
        guard code > current else {
            return .upToDate(name)
        }
        let notes = (root["notes"] as? String) ?? ""
        let ios = root["ios"] as? [String: Any]
        let github = (ios?["github"] as? String)
            ?? "https://github.com/Fly-Noz/ComfyPilot/releases/latest"
        let gitee = (ios?["gitee"] as? String)
            ?? "https://gitee.com/Fly-Noz/ComfyPilot/releases"
        return .available(UpdateInfo(
            versionCode: code,
            versionName: name,
            notes: notes,
            github: github,
            gitee: gitee
        ))
    }

    private var currentVersionCode: Int {
        let raw = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "0"
        return Int(raw) ?? 0
    }

    private func intValue(_ value: Any?) -> Int? {
        if let number = value as? NSNumber { return number.intValue }
        if let text = value as? String { return Int(text) }
        return nil
    }
}
