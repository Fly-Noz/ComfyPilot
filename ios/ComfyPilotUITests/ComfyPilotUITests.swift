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
    /// -uiResetTasks 先清空本机快照，避免前面用例残留的任务造成假通过。
    func testTaskSurvivesRelaunch() throws {
        let app = launch(["-uiResetTasks", "-uiOpenWorkflow", "demo_upload.json", "-uiAutoSubmit"])

        let done = app.staticTexts["已完成"].firstMatch
        XCTAssertTrue(
            done.waitForExistence(timeout: 45),
            "首次提交没有完成。界面树：\n\(app.debugDescription)"
        )

        // 终结态由 TaskPersistence 同步原子写盘，只留很小余量即可。
        Thread.sleep(forTimeInterval: 0.3)

        // 杀掉进程重开：不带 reset（否则会把刚存的清掉），直接进任务页看恢复。
        app.terminate()
        app.launchArguments = [
            "-uiBaseUrl", "http://127.0.0.1:8188",
            "-uiScreen", "tasks",
        ]
        app.launch()

        // 断言恢复的是「本 mock 的产物任务」，而不是随便一条旧任务：
        // 产物文件名形如 ComfyPilot_mock_xxxxxxxx.jpg。
        let restoredArtifact = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "ComfyPilot_mock_")
        ).firstMatch
        XCTAssertTrue(
            restoredArtifact.waitForExistence(timeout: 40),
            "重开后任务历史没有恢复。界面树：\n\(app.debugDescription)"
        )
        XCTAssertTrue(
            app.staticTexts["已完成"].firstMatch.exists,
            "恢复的任务不是完成态。界面树：\n\(app.debugDescription)"
        )
    }

    /// 运行中任务重启：shared 保留 Running 继续对账（Submitting 才是「结果未知」），
    /// 任务不能丢、也不能被重启误判成完成或未知。
    func testRunningTaskSurvivesRelaunch() throws {
        // slow 场景：每 10s 一帧，保证 terminate 时任务还在跑。
        setMockScenario("slow")
        defer { setMockScenario("normal") }

        let app = launch(["-uiResetTasks", "-uiOpenWorkflow", "demo_upload.json", "-uiAutoSubmit"])

        let running = app.staticTexts["执行中"].firstMatch
        XCTAssertTrue(
            running.waitForExistence(timeout: 30),
            "提交后任务没有进入执行中。界面树：\n\(app.debugDescription)"
        )
        // 状态跳变由 TaskPersistence 即时写盘，留一点余量再杀进程。
        Thread.sleep(forTimeInterval: 0.5)

        app.terminate()
        app.launchArguments = ["-uiBaseUrl", "http://127.0.0.1:8188", "-uiScreen", "tasks"]
        app.launch()

        // 恢复后可能是「执行中」（继续对账）或「已完成」（对账期间跑完了），
        // 但绝不能是「结果未知」——那是 Submitting 才有的状态。
        let restored = app.staticTexts.matching(
            NSPredicate(format: "label IN %@", ["执行中", "已完成"])
        ).firstMatch
        XCTAssertTrue(
            restored.waitForExistence(timeout: 40),
            "重开后运行中任务没有恢复。界面树：\n\(app.debugDescription)"
        )
        XCTAssertFalse(
            app.staticTexts["结果未知"].exists,
            "运行中任务被误判为结果未知。界面树：\n\(app.debugDescription)"
        )
    }


    /// 产物切换：一次产出两个文件时查看器才有「上一个/下一个」。
    /// 断言首项禁用、翻页后页码与禁用态对调、再翻回原位。
    func testArtifactSwitcher() throws {
        let app = launch([
            "-uiResetTasks",
            "-uiOpenWorkflow", "demo_multi.json",
            "-uiAutoSubmit",
            "-uiOpenArtifact",
        ])

        // 切换条只在 artifacts.count > 1 时出现：它存在就说明两个产物都被解析到了。
        let next = app.buttons["下一个产物"].firstMatch
        XCTAssertTrue(
            next.waitForExistence(timeout: 50),
            "产物查看器没有「下一个产物」按钮（可能只产出一个产物）。界面树：\n\(app.debugDescription)"
        )
        let prev = app.buttons["上一个产物"].firstMatch
        XCTAssertTrue(prev.exists, "切换条缺少「上一个产物」。界面树：\n\(app.debugDescription)")

        // 第一条：页码 1 / 2，往前翻禁用、往后翻可点
        XCTAssertTrue(
            app.staticTexts["1 / 2"].waitForExistence(timeout: 8),
            "首条产物的页码不是「1 / 2」。界面树：\n\(app.debugDescription)"
        )
        XCTAssertFalse(prev.isEnabled, "首条产物的「上一个」应当禁用")
        XCTAssertTrue(next.isEnabled, "首条产物的「下一个」应当可点")

        // 查看器是 fullScreenCover，弹出动画期间元素的坐标还在动，点早了会点空。
        Thread.sleep(forTimeInterval: 0.5)
        next.tap()
        XCTAssertTrue(
            app.staticTexts["2 / 2"].waitForExistence(timeout: 8),
            "点「下一个」后页码没有变成「2 / 2」。界面树：\n\(app.debugDescription)"
        )
        XCTAssertFalse(next.isEnabled, "末条产物的「下一个」应当禁用")
        XCTAssertTrue(prev.isEnabled, "末条产物的「上一个」应当可点")

        prev.tap()
        XCTAssertTrue(
            app.staticTexts["1 / 2"].waitForExistence(timeout: 8),
            "点「上一个」后页码没有回到「1 / 2」。界面树：\n\(app.debugDescription)"
        )
    }


    /// 设置页「数据」分区：导出 / 导入两个备份入口存在且可点
    /// （与安卓、鸿蒙同位置；入口被误删时这条会红）。
    func testSettingsBackupEntries() throws {
        let app = launch(["-uiScreen", "settings"])

        let exportButton = app.buttons["btnBackupExport"].firstMatch
        XCTAssertTrue(
            exportButton.waitForExistence(timeout: 20),
            "设置页没有「导出本地备份」入口。界面树：\n\(app.debugDescription)"
        )
        scrollToHittable(exportButton, in: app)
        XCTAssertTrue(
            exportButton.isHittable,
            "「导出本地备份」滚到可见后仍不可点。界面树：\n\(app.debugDescription)"
        )

        let importButton = app.buttons["btnBackupImport"].firstMatch
        XCTAssertTrue(
            importButton.exists,
            "设置页没有「导入本地备份」入口。界面树：\n\(app.debugDescription)"
        )
    }

    /// 蒙版编辑器：未上传原图时给出引导，不允许拿主机上的文件名硬编。
    func testMaskEditorRequiresUploadedSource() throws {
        let app = launch(["-uiOpenWorkflow", "demo_upload.json"])

        let edit = app.buttons["edit-mask-1.image"]
        XCTAssertTrue(
            edit.waitForExistence(timeout: 30),
            "LoadImage 节点没有「编辑蒙版」入口。界面树：\n\(app.debugDescription)"
        )
        scrollToHittable(edit, in: app)
        edit.tap()

        let alert = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH %@", "当前图片是直接使用主机上的文件名")
        ).firstMatch
        XCTAssertTrue(
            alert.waitForExistence(timeout: 10),
            "未上传原图时没有弹出引导。界面树：\n\(app.debugDescription)"
        )
    }

    /// 蒙版保存链路：画一笔后上传的必须是带 alpha 的 PNG（颜色类型 6），
    /// 且文件名带 _mask 并覆盖回 LoadImage.image。
    func testMaskEditorUploadsRGBAPNG() throws {
        resetMock()
        let app = launch(["-uiOpenWorkflow", "demo_upload.json", "-uiUploadImage"])

        let edit = app.buttons["edit-mask-1.image"]
        XCTAssertTrue(
            edit.waitForExistence(timeout: 30),
            "LoadImage 节点没有「编辑蒙版」入口。界面树：\n\(app.debugDescription)"
        )
        XCTAssertNotNil(
            waitForUpload(named: "preview.png"),
            "内置夹具图没有上传成功。界面树：\n\(app.debugDescription)"
        )
        scrollToHittable(edit, in: app)
        edit.tap()

        let editor = app.otherElements["mask-editor"]
        XCTAssertTrue(
            editor.waitForExistence(timeout: 15),
            "点「编辑蒙版」后编辑器没有出现。界面树：\n\(app.debugDescription)"
        )
        let canvas = app.otherElements["mask-canvas"]
        XCTAssertTrue(canvas.exists, "蒙版画布不存在")

        canvas.swipeRight()
        Thread.sleep(forTimeInterval: 0.3)
        app.buttons["mask-save"].tap()

        let mask = waitForUpload(containing: "_mask")
        XCTAssertNotNil(mask, "蒙版没有上传到 mock")
        XCTAssertEqual(mask?["pngColorType"] as? Int ?? -1, 6, "蒙版 PNG 丢了 alpha 通道（不是 RGBA）")
        let fields = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH %@", "蒙版已上传：")
        )
        XCTAssertTrue(fields.count > 0, "节点卡片里没有显示蒙版上传结果")
    }

    // MARK: - 辅助

    /// 清空 mock 的上传记录；UI 测试之间不互相污染。
    private func resetMock() {
        guard let url = URL(string: "http://127.0.0.1:8188/mock/reset") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        let done = expectation(description: "mock reset")
        URLSession.shared.dataTask(with: request) { _, _, _ in done.fulfill() }.resume()
        wait(for: [done], timeout: 10)
    }

    private func mockState() -> [String: Any]? {
        guard let url = URL(string: "http://127.0.0.1:8188/mock/state"),
              let data = try? Data(contentsOf: url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return json
    }

    private func waitForUpload(named name: String, timeout: TimeInterval = 30) -> [String: Any]? {
        waitForUpload(timeout: timeout) { $0 == name }
    }

    private func waitForUpload(containing fragment: String, timeout: TimeInterval = 20) -> [String: Any]? {
        waitForUpload(timeout: timeout) { $0.contains(fragment) }
    }

    private func waitForUpload(
        timeout: TimeInterval,
        matches: (String) -> Bool
    ) -> [String: Any]? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let last = mockState()?["lastUpload"] as? [String: Any],
               let name = last["name"] as? String,
               matches(name) {
                return last
            }
            Thread.sleep(forTimeInterval: 0.4)
        }
        return nil
    }

    /// 切 mock 场景（XCUITest 进程与宿主共享网络栈，直接打 127.0.0.1:8188）。
    private func setMockScenario(_ name: String) {
        guard let url = URL(string: "http://127.0.0.1:8188/mock/scenario") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = "{\"scenario\":\"\(name)\"}".data(using: .utf8)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let done = expectation(description: "mock scenario \(name)")
        URLSession.shared.dataTask(with: request) { _, _, _ in done.fulfill() }.resume()
        wait(for: [done], timeout: 10)
    }

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
