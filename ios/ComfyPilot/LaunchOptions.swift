import Foundation

/// 启动参数。CI 没法点屏幕，只能靠参数把 App 推到指定页面截图。
enum LaunchOptions {
    private static let args = ProcessInfo.processInfo.arguments

    static func value(_ key: String) -> String? {
        guard let index = args.firstIndex(of: key), index + 1 < args.count else { return nil }
        let raw = args[index + 1]
        return raw.hasPrefix("-") ? nil : raw
    }

    static func flag(_ key: String) -> Bool {
        args.contains(key)
    }

    /// connect / tasks / settings
    static var screen: String { value("-uiScreen") ?? "connect" }

    static var baseUrl: String { value("-uiBaseUrl") ?? "http://127.0.0.1:8188" }

    /// dark / light；不传则跟随系统。
    static var theme: String? { value("-uiTheme") }

    /// 用内置样例数据渲染，不连服务端。只用于调布局，不代表真实状态。
    static var fixture: Bool { flag("-uiFixture") }
}
