import SwiftUI

/// 导航模型照抄 ComfyPilotApp.kt：没有常驻底栏，全靠页面栈 + 顶栏返回。
/// Connect(0) → Catalog(1) → Detail(2)；Tasks / Settings 是同级覆盖页。
enum AppRoute: Equatable {
    case connect
    case catalog
    case detail(String)
    case tasks
    case settings

    var depth: Int {
        switch self {
        case .connect: return 0
        case .catalog, .settings: return 1
        case .detail, .tasks: return 2
        }
    }

    static func initial() -> AppRoute {
        if let path = LaunchOptions.workflowPath, !path.isEmpty {
            return .detail(path)
        }
        switch LaunchOptions.screen {
        case "workflow": return .catalog
        case "tasks": return .tasks
        case "settings": return .settings
        default: return .connect
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var store: SessionStore
    @Environment(\.colorScheme) private var scheme
    @State private var route: AppRoute = AppRoute.initial()
    @State private var settingsReturn: AppRoute = .connect
    /// 设置里改外观后要立刻整体换色，所以主题读在这里而不是每个页面各读一份。
    @AppStorage("themeMode") private var themeMode = "system"

    var body: some View {
        let palette = Spectrum.palette(for: forcedScheme ?? scheme)
        content(palette)
            .preferredColorScheme(forcedScheme)
            // 提示条浮在所有页面之上：提交、上传、恢复参数都用它给一次即时反馈。
            .overlay(alignment: .bottom) {
                if let toast = store.toast {
                    Text(toast)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(palette.text)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 9)
                        .background(Capsule().fill(palette.dialog))
                        .overlay(Capsule().stroke(palette.border, lineWidth: 1))
                        .padding(.horizontal, 24)
                        .padding(.bottom, 84)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.easeOut(duration: 0.18), value: store.toast)
            .onAppear { store.boot() }
            .onChange(of: store.pendingScreen) { _, target in
                if target == "tasks" { route = .tasks }
            }
            .onChange(of: store.snapshot.isConnected) { _, connected in
                // 连上后自动进工作流列表。显式指定首屏时不抢方向盘（截图用）。
                if connected, route == .connect, LaunchOptions.screenOverride == nil {
                    store.refreshWorkflows()
                    route = .catalog
                }
            }
    }

    @ViewBuilder
    private func content(_ palette: SpectrumPalette) -> some View {
        switch route {
        case .connect:
            ConnectScreen(
                palette: palette,
                onSettings: {
                    settingsReturn = .connect
                    route = .settings
                },
                onCatalog: {
                    store.refreshWorkflows()
                    route = .catalog
                }
            )
        case .catalog:
            WorkflowListScreen(
                palette: palette,
                onBack: { route = .connect },
                onOpen: { path in route = .detail(path) },
                onTasks: { route = .tasks }
            )
        case .detail(let path):
            WorkflowDetailScreen(
                path: path,
                palette: palette,
                onBack: { route = .catalog },
                onTasks: { route = .tasks }
            )
        case .tasks:
            TasksScreen(palette: palette, onBack: { route = .catalog })
        case .settings:
            SettingsScreen(palette: palette, onBack: { route = settingsReturn })
        }
    }

    /// 命令行 `-uiTheme` 只用于 CI 截图；平时跟随设置里的外观。
    private var forcedScheme: ColorScheme? {
        if LaunchOptions.theme == "dark" { return .dark }
        if LaunchOptions.theme == "light" { return .light }
        switch themeMode {
        case "dark": return .dark
        case "light": return .light
        default: return nil
        }
    }
}

/// 每个页面自包含背景层，和 Compose 的 SpectrumScaffold 一样。
struct ScreenScaffold<Content: View>: View {
    let palette: SpectrumPalette
    let content: Content

    init(palette: SpectrumPalette, @ViewBuilder content: () -> Content) {
        self.palette = palette
        self.content = content()
    }

    var body: some View {
        ZStack {
            SpectrumBackdrop(palette: palette)
            content
        }
    }
}

extension View {
    /// GlassCard 的修饰符版本，方便直接套在 VStack 上。
    func glassCard(_ palette: SpectrumPalette, padding: CGFloat = 14, strong: Bool = false) -> some View {
        self
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(strong ? palette.cardStrong : palette.card)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(palette.border, lineWidth: 1)
            )
    }
}

// MARK: - 连接（对应 ConnectScreen.kt）

struct ConnectScreen: View {
    let palette: SpectrumPalette
    let onSettings: () -> Void
    let onCatalog: () -> Void
    @EnvironmentObject private var store: SessionStore
    @State private var diagnostics = LaunchOptions.showDiagnostics

    var body: some View {
        ScreenScaffold(palette: palette) {
            VStack(spacing: 0) {
                topBar

                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        Spacer().frame(height: 6)

                        Text("连接 ComfyUI")
                            .font(.system(size: 27, weight: .bold))
                            .tracking(-0.6)
                            .foregroundStyle(palette.text)

                        Text("连上后选择服务器上已有的工作流，调参数、提交任务、看采样。")
                            .font(.system(size: 12.5))
                            .lineSpacing(5)
                            .foregroundStyle(palette.textMuted)

                        addressCard
                        statusCard
                        footnotes
                    }
                    .padding(.horizontal, 18)
                    .padding(.bottom, 24)
                }
            }
        }
        .sheet(isPresented: $diagnostics) {
            DiagnosticsSheet(palette: palette)
        }
    }

    private var topBar: some View {
        ZStack(alignment: .leading) {
            SpectrumTopBar(palette: palette, title: "") {
                TopBarAction(text: "设置", palette: palette, action: onSettings)
                TopBarAction(text: "诊断", palette: palette) { diagnostics = true }
            }
            wordmark.padding(.leading, 12)
        }
    }

    private var wordmark: some View {
        HStack(spacing: 0) {
            Text("Comfy")
                .font(.system(size: 16, weight: .bold))
                .tracking(-0.3)
                .foregroundStyle(palette.text)
            Text("Pilot")
                .font(.system(size: 16, weight: .bold))
                .tracking(-0.3)
                .foregroundStyle(palette.gradientBrush)
        }
    }

    private var addressCard: some View {
        GlassCard(palette: palette, padding: 14) {
            VStack(alignment: .leading, spacing: 0) {
                Text("COMFYUI 地址")
                    .font(.system(size: 9.5, design: .monospaced))
                    .tracking(1.1)
                    .foregroundStyle(palette.textDim)

                Spacer().frame(height: 7)

                SpectrumTextField(
                    placeholder: "http://主机IP:8188",
                    text: $store.address,
                    palette: palette,
                    keyboard: .URL
                )

                addressHistory

                Spacer().frame(height: 12)

                HStack(spacing: 9) {
                    GradientButton(
                        title: store.snapshot.isConnected ? "重新连接" : "连接",
                        palette: palette
                    ) {
                        store.address = store.address.trimmingCharacters(in: .whitespacesAndNewlines)
                        store.toggleConnection()
                    }
                    GhostButton(title: "断开", palette: palette) {
                        store.disconnect()
                    }
                    .frame(width: 84)
                }

                if store.snapshot.isConnected {
                    Spacer().frame(height: 9)
                    GhostButton(title: "选择工作流", palette: palette) {
                        store.refreshWorkflows()
                        onCatalog()
                    }
                }
            }
        }
    }

    /// 地址历史：只在本机保存，点一下换过去。
    @ViewBuilder
    private var addressHistory: some View {
        let items = SavedAddress.history.filter { $0 != store.address }
        if !items.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(items, id: \.self) { item in
                        Button {
                            store.address = item
                        } label: {
                            Text(item.replacingOccurrences(of: "http://", with: "")
                                .replacingOccurrences(of: "https://", with: ""))
                                .font(.system(size: 10, design: .monospaced))
                                .lineLimit(1)
                                .padding(.horizontal, 9)
                                .padding(.vertical, 5)
                                .background(Capsule().fill(palette.cardStrong))
                                .overlay(Capsule().stroke(palette.border, lineWidth: 1))
                                .foregroundStyle(palette.textMuted)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.top, 8)
            }
        }
    }

    private var statusCard: some View {
        GlassCard(palette: palette, padding: 14) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 7) {
                    Circle()
                        .fill(store.snapshot.isConnected ? palette.ok : palette.textDim)
                        .frame(width: 6, height: 6)
                    Text(store.snapshot.label.isEmpty ? "未连接" : store.snapshot.label)
                        .font(.system(size: 13.5, weight: .semibold))
                        .foregroundStyle(palette.text)
                    Spacer(minLength: 4)
                    Text("GEN \(store.snapshot.generation)")
                        .font(.system(size: 9.5, design: .monospaced))
                        .tracking(0.6)
                        .foregroundStyle(palette.textDim)
                }

                Spacer().frame(height: 10)

                monoRow("网络", networkText)
                monoRow("clientId", store.snapshot.clientId.isEmpty ? "—" : String(store.snapshot.clientId.prefix(8)))
                monoRow("队列", "运行 \(store.snapshot.queueRunning) / 等待 \(store.snapshot.queuePending)")
            }
        }
    }

    private var footnotes: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let notice = store.notices.first {
                HStack(spacing: 6) {
                    Text("最近事件")
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(palette.textDim)
                    Text(notice)
                        .font(.system(size: 11))
                        .foregroundStyle(palette.textMuted)
                        .lineLimit(1)
                }
            }
            if store.snapshot.isConnected {
                Text("已连接，正在进入工作流列表；也可以点上面的「选择工作流」。")
                    .font(.system(size: 11))
                    .foregroundStyle(palette.textDim)
            }
            Text("后台可能暂停监控，回来自动同步。")
                .font(.system(size: 11))
                .foregroundStyle(palette.textDim)
                .padding(.top, 2)
        }
    }

    private var networkText: String {
        let name: String
        switch store.snapshot.network {
        case "wifi": name = "WIFI"
        case "cellular": name = "CELLULAR"
        case "ethernet": name = "ETHERNET"
        case "none": name = "NONE"
        default: name = "OTHER"
        }
        return store.snapshot.validated ? "\(name) · 已验证" : name
    }

    private func monoRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top, spacing: 0) {
            Text(label)
                .font(.system(size: 9.5, design: .monospaced))
                .tracking(0.5)
                .foregroundStyle(palette.textDim)
                .frame(width: 64, alignment: .leading)
            Text(value)
                .font(.system(size: 11.5, design: .monospaced))
                .foregroundStyle(palette.textMuted)
                .lineLimit(1)
                .truncationMode(.middle)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.top, 7)
    }
}

// MARK: - 任务（对应 TaskScreen.kt / TaskList.kt）

struct TasksScreen: View {
    let palette: SpectrumPalette
    let onBack: () -> Void
    @EnvironmentObject private var store: SessionStore
    /// 一个 sheet 两个用途：SwiftUI 在同一条视图上叠两个 .sheet 只有最后一个稳定生效。
    @State private var sheet: TaskSheet?

    enum TaskSheet: Identifiable {
        case artifacts(TaskRow)
        case diagnostics

        var id: String {
            switch self {
            case .artifacts(let task): return "artifacts-\(task.id)"
            case .diagnostics: return "diagnostics"
            }
        }
    }

    var body: some View {
        ScreenScaffold(palette: palette) {
            VStack(spacing: 0) {
                SpectrumTopBar(palette: palette, title: "任务", onBack: onBack) {
                    TopBarAction(text: "诊断", palette: palette) { sheet = .diagnostics }
                    TopBarAction(text: "断开", palette: palette) { store.disconnect() }
                }

                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 7) {
                        Circle()
                            .fill(store.snapshot.isConnected ? palette.ok : palette.textDim)
                            .frame(width: 6, height: 6)
                        Text(store.snapshot.label.isEmpty ? "未连接" : store.snapshot.label)
                            .font(.system(size: 11.5))
                            .foregroundStyle(palette.textMuted)
                        Text("· \(transportName) · 第 \(store.snapshot.generation) 代")
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(palette.textDim)
                    }
                    Text("队列：运行 \(store.snapshot.queueRunning) / 等待 \(store.snapshot.queuePending)")
                        .font(.system(size: 10.5, design: .monospaced))
                        .foregroundStyle(palette.textDim)
                    if let notice = store.notices.first {
                        Text("最近事件：\(notice)")
                            .font(.system(size: 10.5))
                            .foregroundStyle(palette.textDim)
                            .lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 18)
                .padding(.vertical, 4)

                Spacer().frame(height: 6)

                if store.snapshot.tasks.isEmpty {
                    emptyState
                    Spacer()
                } else {
                    ScrollView {
                        // 下拉对账：不重连，只让共享层重查 /history 与 /queue。
                        // 安卓是自动对账，这里补一个 iOS 用户习惯的手势，多一个出口不冲突。
                        LazyVStack(spacing: 9) {
                            ForEach(store.snapshot.tasks) { task in
                                TaskCard(
                                    task: task,
                                    palette: palette,
                                    onView: task.artifacts.isEmpty ? nil : { sheet = .artifacts(task) },
                                    onCancel: { store.cancel(task) }
                                )
                            }
                        }
                        .padding(.horizontal, 18)
                        .padding(.bottom, 24)
                    }
                    .refreshable { store.reconcile() }
                }
            }
        }
        .sheet(item: $sheet) { item in
            switch item {
            case .artifacts(let task):
                ArtifactViewer(task: task, baseUrl: store.snapshot.baseUrl, palette: palette)
            case .diagnostics:
                DiagnosticsSheet(palette: palette)
            }
        }
    }

    private var transportName: String {
        switch store.snapshot.network {
        case "wifi": return "WIFI"
        case "cellular": return "CELLULAR"
        case "ethernet": return "ETHERNET"
        case "none": return "NONE"
        default: return "OTHER"
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "tray")
                .font(.system(size: 28, weight: .light))
                .foregroundStyle(palette.textDim)
            Text("还没有任务")
                .font(.system(size: 13.5))
                .foregroundStyle(palette.textMuted)
            Text("连接后在网页端提交，或在工作流页直接提交")
                .font(.system(size: 12))
                .foregroundStyle(palette.textDim)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .glassCard(palette)
        .padding(.horizontal, 18)
    }
}

/// TaskCard：圆角 16、内边距 12/11、间距 7、左侧 3pt 色条。
/// 状态配色照抄 TaskList.kt：执行中是 ok（青绿），完成才是中性灰——之前我搞反了。
struct TaskCard: View {
    let task: TaskRow
    let palette: SpectrumPalette
    let onView: (() -> Void)?
    let onCancel: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Rectangle()
                .fill(railColor)
                .frame(width: 3)

            VStack(alignment: .leading, spacing: 7) {
                HStack(spacing: 8) {
                    Text(title)
                        .font(.system(size: 11.5, weight: .medium, design: .monospaced))
                        .foregroundStyle(palette.text)
                        .lineLimit(1)
                        .truncationMode(.tail)

                    Spacer(minLength: 4)

                    if !elapsedText.isEmpty {
                        Text(elapsedText)
                            .font(.system(size: 9.5, design: .monospaced))
                            .foregroundStyle(palette.textDim)
                    }

                    SpectrumPill(text: stateLabel, palette: palette, tone: tone)
                }

                if task.showsProgress {
                    HStack(spacing: 8) {
                        // 共享层的 percent 是 0–100，不是 0–1（TaskList.kt 里也是 /100f）。
                        SpectrumProgress(fraction: task.percent / 100, palette: palette, height: 5)
                        Text("\(Int(task.percent))%")
                            .font(.system(size: 9.5, design: .monospaced))
                            .foregroundStyle(palette.textDim)
                    }
                }

                if !task.artifacts.isEmpty {
                    Text("输出：\(task.artifacts.map { $0.filename }.joined(separator: "、"))")
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(palette.textDim)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }

                if let error = task.error, !error.isEmpty {
                    Text(error)
                        .font(.system(size: 10.5))
                        .lineSpacing(4)
                        .foregroundStyle(palette.err)
                        .lineLimit(3)
                }

                if onView != nil || task.canCancel {
                    HStack(spacing: 8) {
                        if let onView {
                            GhostButton(title: "查看产物", palette: palette, height: 34, action: onView)
                        }
                        Spacer(minLength: 0)
                        if task.canCancel {
                            GhostButton(title: "取消", palette: palette, height: 34, action: onCancel)
                        }
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 11)
        }
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(palette.card)
        )
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(palette.border, lineWidth: 1)
        )
    }

    private var title: String {
        if !task.name.isEmpty { return task.name }
        if !task.promptId.isEmpty { return task.promptId }
        return task.id
    }

    private var elapsedText: String {
        guard task.createdAtMs > 0 else { return "" }
        return task.elapsedText(nowMs: Int64(Date().timeIntervalSince1970 * 1000))
    }

    private var stateLabel: String {
        switch task.state {
        case "Submitting": return "提交中"
        case "Queued": return "排队中"
        case "Running": return "执行中"
        case "Completed": return "已完成"
        case "Failed": return "失败"
        case "Cancelled": return "已取消"
        default: return "状态未知"
        }
    }

    private var tone: PillTone {
        switch task.state {
        case "Submitting", "Queued": return .warn
        case "Running": return .ok
        case "Failed": return .err
        default: return .neutral
        }
    }

    private var railColor: Color {
        switch tone {
        case .ok: return palette.ok
        case .warn: return palette.warn
        case .err: return palette.err
        case .neutral: return palette.textDim
        }
    }
}

// MARK: - 设置

struct SettingsScreen: View {
    let palette: SpectrumPalette
    let onBack: () -> Void
    @EnvironmentObject private var store: SessionStore

    /// 和安卓一样落在 SharedPreferences 对位的地方：UserDefaults。
    @AppStorage("forceSubmit") private var forceSubmit = false
    @AppStorage("weakNetwork") private var weakNetwork = false
    @AppStorage("themeMode") private var themeMode = "system"
    @State private var diagnostics = false

    var body: some View {
        ScreenScaffold(palette: palette) {
            VStack(spacing: 0) {
                SpectrumTopBar(palette: palette, title: "设置", onBack: onBack)

                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        section("连接")
                        GlassCard(palette: palette) {
                            VStack(spacing: 0) {
                                row("工作流", store.snapshot.workflowName.isEmpty ? "未加载" : store.snapshot.workflowName)
                                row("服务端", store.snapshot.baseUrl.isEmpty ? "—" : store.snapshot.baseUrl)
                                row("连接", store.snapshot.isConnected ? "已连接" : "未连接")
                            }
                        }

                        section("外观")
                        GlassCard(palette: palette, padding: 10) {
                            VStack(alignment: .leading, spacing: 8) {
                                SpectrumTabs(
                                    labels: ["跟随系统", "深色", "浅色"],
                                    selected: themeIndex,
                                    palette: palette
                                ) { index in
                                    themeMode = ["system", "dark", "light"][index]
                                }
                                if LaunchOptions.theme != nil {
                                    Text("本次启动由命令行指定了外观；上面的选择下次启动生效。")
                                        .font(.system(size: 10))
                                        .lineSpacing(3)
                                        .foregroundStyle(palette.textDim)
                                }
                            }
                        }

                        section("提交")
                        GlassCard(palette: palette, padding: 12) {
                            VStack(spacing: 10) {
                                toggle(
                                    title: "忽略本地校验，强制提交",
                                    detail: "本地有报错也照样把任务发给服务器，由 ComfyUI 判定",
                                    isOn: $forceSubmit
                                )

                                Divider().overlay(palette.border)

                                Button {
                                    store.resetOverrides()
                                } label: {
                                    HStack(spacing: 8) {
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text("恢复当前工作流的默认参数")
                                                .font(.system(size: 13))
                                                .foregroundStyle(store.overrideCount > 0 ? palette.text : palette.textDim)
                                            Text(store.overrideCount > 0 ? "已改 \(store.overrideCount) 处，点一下清掉" : "当前没有改动")
                                                .font(.system(size: 10.5))
                                                .foregroundStyle(palette.textDim)
                                        }
                                        Spacer(minLength: 0)
                                        GhostButton(
                                            title: "恢复",
                                            palette: palette,
                                            height: 30,
                                            selected: false,
                                            enabled: store.overrideCount > 0
                                        ) {
                                            store.resetOverrides()
                                        }
                                        .disabled(store.overrideCount == 0)
                                    }
                                }
                                .buttonStyle(.plain)
                                .disabled(store.overrideCount == 0)
                            }
                        }

                        section("网络")
                        GlassCard(palette: palette, padding: 12) {
                            VStack(spacing: 10) {
                                toggle(
                                    title: "弱网模式",
                                    detail: "少抢带宽：主机状态 15 秒一刷，采样预览最多 2 秒一张",
                                    isOn: $weakNetwork
                                )

                                Divider().overlay(palette.border)

                                // 入口先摆出来，功能后补：安卓那边是独立页面（延迟/带宽 + 建议）。
                                HStack(spacing: 10) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("网络测速")
                                            .font(.system(size: 13))
                                            .foregroundStyle(palette.textDim)
                                        Text("测到主机的延迟与带宽；还在开发中")
                                            .font(.system(size: 10.5))
                                            .foregroundStyle(palette.textDim)
                                    }
                                    Spacer(minLength: 0)
                                    Text("暂未开放")
                                        .font(.system(size: 10))
                                        .foregroundStyle(palette.textDim)
                                        .padding(.horizontal, 9)
                                        .padding(.vertical, 4)
                                        .background(Capsule().fill(palette.cardStrong))
                                }
                            }
                        }

                        section("功能")
                        GlassCard(palette: palette, padding: 12) {
                            HStack(spacing: 10) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("AI 助手")
                                        .font(.system(size: 13))
                                        .foregroundStyle(palette.textDim)
                                    Text("用对话改参数、解释报错；还在开发中")
                                        .font(.system(size: 10.5))
                                        .foregroundStyle(palette.textDim)
                                }
                                Spacer(minLength: 0)
                                Text("暂未开放")
                                    .font(.system(size: 10))
                                    .foregroundStyle(palette.textDim)
                                    .padding(.horizontal, 9)
                                    .padding(.vertical, 4)
                                    .background(Capsule().fill(palette.cardStrong))
                            }
                        }

                        section("诊断")
                        GlassCard(palette: palette, padding: 12) {
                            Button {
                                diagnostics = true
                            } label: {
                                HStack(spacing: 8) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("诊断日志")
                                            .font(.system(size: 13))
                                            .foregroundStyle(palette.text)
                                        Text("连接、任务与错误事件")
                                            .font(.system(size: 10.5))
                                            .foregroundStyle(palette.textDim)
                                    }
                                    Spacer(minLength: 0)
                                    Image(systemName: "chevron.right")
                                        .font(.system(size: 11, weight: .semibold))
                                        .foregroundStyle(palette.textDim)
                                }
                            }
                            .buttonStyle(.plain)
                        }

                        section("关于")
                        GlassCard(palette: palette) {
                            VStack(spacing: 0) {
                                row("版本", bundleVersion)
                                row("共享核心", "Kotlin/Native · ComfyCore")
                                row("主题", themeName)
                                row("后台保活", "不支持（iOS 限制）")
                            }
                        }

                        Text("后台会被系统挂起，回到前台自动对账；这是 iOS 的限制，不是省掉了功能。")
                            .font(.system(size: 11))
                            .lineSpacing(4)
                            .foregroundStyle(palette.textDim)
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 8)
                    .padding(.bottom, 24)
                }
            }
        }
        .sheet(isPresented: $diagnostics) {
            DiagnosticsSheet(palette: palette)
        }
    }

    private var themeIndex: Int {
        switch themeMode {
        case "dark": return 1
        case "light": return 2
        default: return 0
        }
    }

    private func section(_ title: String) -> some View {
        Text(title)
            .font(.system(size: 9.5, design: .monospaced))
            .tracking(1.1)
            .foregroundStyle(palette.textDim)
            .padding(.top, 2)
    }

    private func toggle(title: String, detail: String, isOn: Binding<Bool>) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 13))
                    .foregroundStyle(palette.text)
                Text(detail)
                    .font(.system(size: 10.5))
                    .lineSpacing(4)
                    .foregroundStyle(palette.textDim)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            Toggle("", isOn: isOn)
                .labelsHidden()
                .tint(palette.accent)
        }
    }

    private var themeName: String {
        if let forced = LaunchOptions.theme {
            return forced == "dark" ? "深色（命令行）" : "浅色（命令行）"
        }
        switch themeMode {
        case "dark": return "深色"
        case "light": return "浅色"
        default: return "跟随系统"
        }
    }

    private var bundleVersion: String {
        let info = Bundle.main.infoDictionary
        let short = (info?["CFBundleShortVersionString"] as? String) ?? "?"
        let build = (info?["CFBundleVersion"] as? String) ?? "?"
        return "\(short) (\(build))"
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack(spacing: 8) {
            Text(title)
                .font(.system(size: 12.5))
                .foregroundStyle(palette.textMuted)
            Spacer(minLength: 4)
            Text(value)
                .font(.system(size: 12.5, design: .monospaced))
                .foregroundStyle(palette.text)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .padding(.vertical, 7)
    }
}
