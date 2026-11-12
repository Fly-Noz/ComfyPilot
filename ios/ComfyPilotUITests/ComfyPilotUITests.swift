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

        // 提交后 App 留在工作流页（不自动跳任务页）：点顶栏「任务」进去看状态。
        // mock 每 0.5s 一次进度、8 次完成；首次加载还要拉 history，给足余量。
        let tasksButton = app.buttons["任务"].firstMatch
        XCTAssertTrue(
            tasksButton.waitForExistence(timeout: 10),
            "工作流页没有「任务」入口。界面树：\n\(app.debugDescription)"
        )
        tasksButton.tap()

        let done = app.staticTexts["已完成"].firstMatch
        XCTAssertTrue(
            done.waitForExistence(timeout: 40),
            "任务页没有出现「已完成」。界面树：\n\(app.debugDescription)"
        )
        let failed = app.staticTexts["失败"].firstMatch
        XCTAssertFalse(failed.exists, "任务提交失败（mock 应成功）")
    }

    /// 设置页有「关于」入口（只验证存在，不点击——入口是 fullScreenCover，
    /// 在 XCUITest 里 click 弹出不稳定，人工在 Appetize/真机点一次即可）。
    func testSettingsAboutEntryExists() throws {
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
    }

    /// 关于页内部交互：许可证行存在、点开浮层、关闭、返回。
    /// 入口用 -uiScreen about 直达（与 CI 截图同一条已稳定的路径）。
    func testAboutPage() throws {
        let app = launch(["-uiScreen", "about"])

        // 页面标志：简介文案（关于页独有）
        let intro = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "ComfyUI 远程客户端")
        ).firstMatch
        XCTAssertTrue(
            intro.waitForExistence(timeout: 20),
            "没有进入关于页。界面树：\n\(app.debugDescription)"
        )

        let license = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@", "GPL-3.0")
        ).firstMatch
        XCTAssertTrue(
            license.waitForExistence(timeout: 10),
            "关于页缺少「开源许可证 GPL-3.0」行。界面树：\n\(app.debugDescription)"
        )
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
        // toolbar 里的按钮 tap 会先尝试 AXScrollToVisible 并失败（kAXErrorCannotComplete）：
        // 直接点中心坐标绕过这套 AX 动作。
        back.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    }

    /// 底栏节点图入口：打开工作流 → 点底栏图标 → 节点图浮层出现。
    func testNodeGraphFromBottomBar() throws {
        let app = launch(["-uiOpenWorkflow", "demo_upload.json"])

        let graphButton = app.buttons["btnNodeGraph"].firstMatch
        XCTAssertTrue(
            graphButton.waitForExistence(timeout: 40),
            "底栏没有节点图入口。界面树：\n\(app.debugDescription)"
        )
        graphButton.tap()

        XCTAssertTrue(
            app.staticTexts["节点图"].waitForExistence(timeout: 15),
            "点底栏入口后节点图没有打开。界面树：\n\(app.debugDescription)"
        )
    }

    /// 任务历史持久化：提交完成 → 杀掉进程重开 → 任务仍在（本地快照恢复）。
    func testTaskSurvivesRelaunch() throws {
        let app = launch(["-uiOpenWorkflow", "demo_upload.json", "-uiAutoSubmit"])

        let done = app.staticTexts["已完成"].firstMatch
        XCTAssertTrue(
            done.waitForExistence(timeout: 45),
            "首次提交没有完成。界面树：\n\(app.debugDescription)"
        )

        // 杀掉进程重开：不带 autoSubmit / workflow，直接进任务页看恢复结果。
        app.terminate()
        app.launchArguments = [
            "-uiBaseUrl", "http://127.0.0.1:8188",
            "-uiScreen", "tasks",
        ]
        app.launch()

        let restored = app.staticTexts["已完成"].firstMatch
        XCTAssertTrue(
            restored.waitForExistence(timeout: 40),
            "重开后任务历史没有恢复。界面树：\n\(app.debugDescription)"
        )
    }

    // MARK: - 辅助

    /// 元素存在但在滚动视图的可视区域外时，tap 会点空：先上滑直到可点击。
    private func scrollToHittable(_ element: XCUIElement, in app: XCUIApplication) {
        var attempts = 0
        while !element.isHittable && attempts < 6 {
            app.swipeUp()
            attempts += 1
            // 等滚动惯性停下：滑动刚结束时的 tap 会被当成「停止滚动」而吞掉。
            Thread.sleep(forTimeInterval: 0.6)
        }
    }

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
