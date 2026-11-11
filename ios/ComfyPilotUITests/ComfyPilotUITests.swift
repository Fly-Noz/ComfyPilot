import XCTest

/// CI 的模拟器交互回归：CI 点不了屏幕，只能靠这里的用例把点击链路跑一遍。
/// 依赖 CI 环境：mock ComfyUI 已在 127.0.0.1:8188 起好，App 用 -uiBaseUrl 自动连接。
final class ComfyPilotUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch(_ extraArgs: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uiBaseUrl", "http://127.0.0.1:8188"] + extraArgs
        app.launch()
        return app
    }

    /// 连接链路：连上 mock → 点「选择工作流」→ 列表出现 demo 工作流 → 打开后看到「提交任务」。
    func testConnectAndOpenWorkflow() throws {
        let app = launch()

        let choose = app.buttons["选择工作流"]
        XCTAssertTrue(
            choose.waitForExistence(timeout: 30),
            "连接后没出现「选择工作流」按钮。界面树：\n\(app.debugDescription)"
        )
        choose.tap()

        openWorkflow(named: "demo_ui.json", in: app)

        XCTAssertTrue(
            app.buttons["提交任务"].waitForExistence(timeout: 30),
            "打开工作流后没有「提交任务」按钮。界面树：\n\(app.debugDescription)"
        )
    }

    /// 提交链路：直接打开 demo_upload → 点提交 → 任务页出现在执行或已完成状态。
    func testSubmitWorkflow() throws {
        let app = launch(["-uiOpenWorkflow", "demo_upload.json"])

        let submit = app.buttons["提交任务"]
        XCTAssertTrue(
            submit.waitForExistence(timeout: 40),
            "工作流页没有「提交任务」按钮。界面树：\n\(app.debugDescription)"
        )
        submit.tap()

        // 任务页的完成态：mock 每 0.5s 一次进度、8 次完成；给足余量
        let done = app.staticTexts["已完成"].firstMatch
        let failed = app.staticTexts["失败"].firstMatch
        let submitDone = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "已提交")).firstMatch
        let stateAppeared = done.waitForExistence(timeout: 30)
            || failed.exists
            || submitDone.waitForExistence(timeout: 10)
        XCTAssertTrue(
            stateAppeared,
            "提交后没有看到任务状态（已完成/失败/已提交）。界面树：\n\(app.debugDescription)"
        )
        XCTAssertFalse(failed.exists, "任务提交失败（mock 应成功）")
    }

    /// 设置 → 关于页 → 三行信息存在 → 返回设置。
    func testAboutPage() throws {
        let app = launch()

        let settings = app.buttons["设置"].firstMatch
        XCTAssertTrue(
            settings.waitForExistence(timeout: 30),
            "连接页没有「设置」入口。界面树：\n\(app.debugDescription)"
        )
        settings.tap()

        let about = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@", "关于 ComfyPilot")
        ).firstMatch
        XCTAssertTrue(
            about.waitForExistence(timeout: 15),
            "设置页没有「关于 ComfyPilot」入口。界面树：\n\(app.debugDescription)"
        )
        about.tap()

        XCTAssertTrue(
            app.staticTexts["GPL-3.0"].waitForExistence(timeout: 15),
            "关于页缺少「开源许可证 GPL-3.0」行。界面树：\n\(app.debugDescription)"
        )

        let license = app.staticTexts["GPL-3.0"].firstMatch
        license.tap()
        XCTAssertTrue(
            app.staticTexts["开源许可证"].waitForExistence(timeout: 10),
            "许可证浮层没有弹出。界面树：\n\(app.debugDescription)"
        )
        let close = app.buttons["关闭"].firstMatch
        XCTAssertTrue(close.waitForExistence(timeout: 8), "许可证浮层没有关闭按钮")
        close.tap()

        let back = app.buttons["返回"].firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 10), "关于页没有返回按钮")
        back.tap()
        XCTAssertTrue(
            app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "关于 ComfyPilot"))
                .firstMatch.waitForExistence(timeout: 10),
            "返回后没有回到设置页"
        )
    }

    // MARK: - 辅助

    private func openWorkflow(named name: String, in app: XCUIApplication) {
        let buttonRow = app.buttons[name].firstMatch
        if buttonRow.waitForExistence(timeout: 20) {
            buttonRow.tap()
            return
        }
        let textRow = app.staticTexts[name].firstMatch
        XCTAssertTrue(
            textRow.waitForExistence(timeout: 10),
            "工作流列表里没有 \(name)。界面树：\n\(app.debugDescription)"
        )
        textRow.tap()
    }
}
