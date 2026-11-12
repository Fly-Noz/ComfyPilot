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

    /// connect / workflow / tasks / settings
    static var screen: String { screenOverride ?? "connect" }

    /// 用户是否显式指定了首屏。显式指定时不自动跳转，方便截图。
    static var screenOverride: String? { value("-uiScreen") }

    /// 命令行指定的地址；没有就返回 nil，让 SessionStore 去读上次保存的值。
    static var baseUrlFromArgs: String? { value("-uiBaseUrl") }

    static let defaultBaseUrl = "http://127.0.0.1:8188"

    /// dark / light；不传则跟随系统。
    static var theme: String? { value("-uiTheme") }

    /// 用内置样例数据渲染，不连服务端。只用于调布局，不代表真实状态。
    static var fixture: Bool { flag("-uiFixture") }

    /// CI/截图参数下不弹更新：否则启动就盖住要截的页面。
    static var blocksUpdateCheck: Bool {
        fixture || args.contains { $0.hasPrefix("-ui") }
    }

    /// 连上后自动打开这个工作流（相对 workflows/ 的路径），CI 截图用。
    static var workflowPath: String? { value("-uiOpenWorkflow") }

    /// 参数页就绪后自动提交一次，然后跳到任务页。用来验证提交链路，CI 点不了屏幕。
    static var autoSubmit: Bool { flag("-uiAutoSubmit") }

    /// 详情页打开后停在哪个分段：input（默认）/ preview。
    static var detailTab: Int { value("-uiTab") == "preview" ? 1 : 0 }

    /// 启动就弹出诊断页，CI 截图用（模拟器点不了顶栏按钮）。
    static var showDiagnostics: Bool { flag("-uiDiagnostics") }

    /// 工作流加载完成后自动弹出节点图，CI 截图用。
    static var showGraph: Bool { flag("-uiGraph") }

    /// 打开工作流后自动上传内置夹具图到 LoadImage 字段，CI 验证上传链路用。
    static var autoUpload: Bool { flag("-uiUploadImage") }

    /// 任务页出现「完成且有产物」的任务时自动打开产物查看器，CI 截图用。
    static var openArtifact: Bool { flag("-uiOpenArtifact") }

    /// 启动时清空本机任务快照（XCUITest 用例间隔离用，只消费一次）。
    static var resetTasks: Bool { flag("-uiResetTasks") }
}
