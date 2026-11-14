import SwiftUI
import UniformTypeIdentifiers

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
        case "about": return .settings
        default: return .connect
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var store: SessionStore
    @EnvironmentObject private var updates: UpdateStore
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
            .task { await updates.checkSilently() }
            .sheet(item: updateSheetBinding) { info in
                UpdateSheet(
                    info: info,
                    palette: palette,
                    onLater: { updates.dismiss() },
                    onOpen: { url in updates.open(url) }
                )
            }
            .onChange(of: store.pendingScreen) { _, target in
                if target == "tasks" { route = .tasks }
            }
            // 连上后不再自动跳工作流列表：以前这样做，用户想改地址时会一直被拽走。
            // 连接成功后连接页本来就是「已连接 + 选择工作流」，点一下进去即可。
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
                onTasks: { route = .tasks },
                onSettings: {
                    // 从工作流列表进设置，返回还回列表（不是连接页）。
                    settingsReturn = .catalog
                    route = .settings
                }
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

    /// 用 item 形式展示更新页；关闭时让 store 记录“稍后”的版本。
    private var updateSheetBinding: Binding<UpdateInfo?> {
        Binding(
            get: { updates.pending },
            set: { info in
                if info == nil { updates.dismiss() }
            }
        )
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

    /// 已完成且有产物的任务数：onChange 用它感知「任务跑完了」。
    private var completedArtifactCount: Int {
        store.snapshot.tasks.filter { $0.state == "Completed" && !$0.artifacts.isEmpty }.count
    }

    private func maybeOpenArtifact() {
        guard LaunchOptions.openArtifact, sheet == nil,
              let task = store.snapshot.tasks.first(where: {
                  $0.state == "Completed" && !$0.artifacts.isEmpty
              })
        else { return }
        sheet = .artifacts(task)
    }

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
        // CI 截图用：-uiOpenArtifact 时，任务一出现产物就自动打开查看器
        // （模拟器点不了任务行里的「查看产物」）。
        .onAppear { maybeOpenArtifact() }
        .onChange(of: completedArtifactCount) { _, _ in maybeOpenArtifact() }
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

                    if task.createdAtMs > 0 {
                        // 耗时每秒自己走，不依赖任务快照变化：模型加载时可能几十秒
                        // 没有进度事件，等快照重算的话耗时会停住（Android
                        // TaskList.kt 也是独立计时刷新）。
                        TimelineView(.periodic(from: .now, by: 1)) { context in
                            Text(task.elapsedText(
                                nowMs: Int64(context.date.timeIntervalSince1970 * 1000)
                            ))
                            .font(.system(size: 9.5, design: .monospaced))
                            .foregroundStyle(palette.textDim)
                        }
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
    @EnvironmentObject private var updates: UpdateStore

    /// 和安卓一样落在 SharedPreferences 对位的地方：UserDefaults。
    @AppStorage("forceSubmit") private var forceSubmit = false
    @AppStorage("weakNetwork") private var weakNetwork = false
    @AppStorage("themeMode") private var themeMode = "system"
    @AppStorage("updatePromptMode") private var updatePromptMode = "always"
    @State private var diagnostics = false
    @State private var updateModeChooser = false
    /// CI 截图用：-uiScreen about 直接弹关于页（模拟器点不了设置入口）。
    /// 设置页的全屏子页：about 与 probe 共用一个 fullScreenCover
    /// （SwiftUI 同一视图叠两个 fullScreenCover 只有最后一个稳定生效）。
    enum SettingsSheet: String, Identifiable {
        case about
        case probe
        var id: String { rawValue }
    }

    @State private var settingsSheet: SettingsSheet? =
        LaunchOptions.screen == "about" ? .about : nil

    /// 备份导出 / 导入的确认（同一个 alert 按枚举切文案，避免多个 alert 互相顶掉）。
    enum BackupConfirm: Equatable { case export, restore }

    @State private var backupConfirm: BackupConfirm?
    @State private var backupBusy = false
    @State private var backupExportDoc: BackupFileDocument?
    @State private var backupExportOpen = false
    @State private var backupImportOpen = false
    @State private var backupExportName = "ComfyPilot-backup.json"

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
                                Button {
                                    settingsSheet = .probe
                                } label: {
                                    HStack(spacing: 10) {
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text("网络测速")
                                                .font(.system(size: 13))
                                                .foregroundStyle(palette.text)
                                            Text("测延迟、稳定性、下载速度，区分「网络慢」和「显卡忙」")
                                                .font(.system(size: 10.5))
                                                .foregroundStyle(palette.textDim)
                                                .lineLimit(2)
                                        }
                                        Spacer(minLength: 0)
                                        Image(systemName: "chevron.right")
                                            .font(.system(size: 11, weight: .semibold))
                                            .foregroundStyle(palette.textDim)
                                    }
                                    .padding(.vertical, 2)
                                }
                                .buttonStyle(.plain)
                                .accessibilityIdentifier("btnNetworkProbe")
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

                            Divider().overlay(palette.border)

                            Button {
                                Task { await updates.check(manual: true) }
                            } label: {
                                HStack(spacing: 10) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("检查更新")
                                            .font(.system(size: 13))
                                            .foregroundStyle(palette.text)
                                        Text(updates.manualNote.isEmpty ? "手动检查一次，不受提醒设置影响" : updates.manualNote)
                                            .font(.system(size: 10.5))
                                            .foregroundStyle(palette.textDim)
                                    }
                                    Spacer(minLength: 0)
                                    if updates.checking {
                                        ProgressView()
                                            .controlSize(.small)
                                            .tint(palette.accent)
                                    } else {
                                        Image(systemName: "chevron.right")
                                            .font(.system(size: 11, weight: .semibold))
                                            .foregroundStyle(palette.textDim)
                                    }
                                }
                                .padding(.vertical, 8)
                            }
                            .buttonStyle(.plain)
                            .disabled(updates.checking)

                            Divider().overlay(palette.border)

                            Button {
                                updateModeChooser = true
                            } label: {
                                HStack(spacing: 10) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("更新提醒")
                                            .font(.system(size: 13))
                                            .foregroundStyle(palette.text)
                                        Text(updateMode.label)
                                            .font(.system(size: 10.5))
                                            .foregroundStyle(palette.textDim)
                                    }
                                    Spacer(minLength: 0)
                                    Image(systemName: "chevron.right")
                                        .font(.system(size: 11, weight: .semibold))
                                        .foregroundStyle(palette.textDim)
                                }
                                .padding(.vertical, 8)
                            }
                            .buttonStyle(.plain)
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

                        section("数据")
                        GlassCard(palette: palette, padding: 12) {
                            VStack(spacing: 0) {
                                Button {
                                    guard !backupBusy else { return }
                                    backupConfirm = .export
                                } label: {
                                    HStack(spacing: 10) {
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text("导出本地备份")
                                                .font(.system(size: 13))
                                                .foregroundStyle(palette.text)
                                            Text(backupBusy ? "处理中…" : "保存为 JSON 文件，可跨设备迁移")
                                                .font(.system(size: 10.5))
                                                .foregroundStyle(palette.textDim)
                                                .lineLimit(2)
                                        }
                                        Spacer(minLength: 0)
                                        Image(systemName: "square.and.arrow.up")
                                            .font(.system(size: 12, weight: .medium))
                                            .foregroundStyle(palette.textDim)
                                    }
                                    .padding(.vertical, 8)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .disabled(backupBusy)
                                .accessibilityIdentifier("btnBackupExport")

                                Divider().overlay(palette.border)

                                Button {
                                    guard !backupBusy else { return }
                                    // 状态机在跑（连接中/已连接/重连/恢复）时导入会互相打架：
                                    // 与安卓「有 lease 就拦」一致，要求完全空闲。
                                    if store.isSessionActive {
                                        store.showToast("请先断开当前连接")
                                        return
                                    }
                                    backupConfirm = .restore
                                } label: {
                                    HStack(spacing: 10) {
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text("导入本地备份")
                                                .font(.system(size: 13))
                                                .foregroundStyle(palette.text)
                                            Text(backupBusy ? "处理中…" : "覆盖本机记录，不可撤回")
                                                .font(.system(size: 10.5))
                                                .foregroundStyle(palette.textDim)
                                                .lineLimit(2)
                                        }
                                        Spacer(minLength: 0)
                                        Image(systemName: "square.and.arrow.down")
                                            .font(.system(size: 12, weight: .medium))
                                            .foregroundStyle(palette.textDim)
                                    }
                                    .padding(.vertical, 8)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .disabled(backupBusy)
                                .accessibilityIdentifier("btnBackupImport")
                            }
                        }
                        Text("备份只包含本机记录，不含 AI API Key、密码或诊断日志。")
                            .font(.system(size: 10.5))
                            .foregroundStyle(palette.textDim)
                            .padding(.horizontal, 2)

                        section("通知")
                        GlassCard(palette: palette) {
                            HStack(spacing: 10) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("任务完成 / 失败")
                                        .font(.system(size: 13))
                                        .foregroundStyle(palette.text)
                                    Text("前台或刚退到后台时提醒；长时间后台受 iOS 限制收不到")
                                        .font(.system(size: 10.5))
                                        .foregroundStyle(palette.textDim)
                                        .lineLimit(2)
                                }
                                Spacer(minLength: 0)
                                Toggle("", isOn: Binding(
                                    get: { TaskNotifier.enabled },
                                    set: { value in
                                        TaskNotifier.enabled = value
                                        if value { TaskNotifier.requestAuthorizationIfNeeded() }
                                    }
                                ))
                                .labelsHidden()
                                .tint(palette.accent)
                            }
                            .padding(.vertical, 8)
                        }
                        .padding(.top, 4)

                        section("关于")
                        GlassCard(palette: palette) {
                            VStack(spacing: 0) {
                                Button {
                                    settingsSheet = .about
                                } label: {
                                    HStack(spacing: 10) {
                                        Text("关于 ComfyPilot")
                                            .font(.system(size: 13))
                                            .foregroundStyle(palette.text)
                                        Spacer(minLength: 0)
                                        Text(bundleVersion)
                                            .font(.system(size: 11))
                                            .foregroundStyle(palette.textDim)
                                        Image(systemName: "chevron.right")
                                            .font(.system(size: 11, weight: .semibold))
                                            .foregroundStyle(palette.textDim)
                                    }
                                    .padding(.vertical, 8)
                                }
                                .buttonStyle(.plain)

                                Divider().overlay(palette.border)

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
        .fullScreenCover(item: $settingsSheet) { item in
            switch item {
            case .about:
                AboutScreen(palette: palette)
            case .probe:
                NetworkProbeSheet(
                    palette: palette,
                    baseUrl: store.snapshot.baseUrl,
                    lastFrameAtMs: store.lastPreviewFrameAtMs,
                )
            }
        }
        // 备份：导出 / 导入共用一个 alert（同一视图叠多个 alert 只有最后一个生效）。
        .alert(
            backupConfirm == .export ? "导出本地备份？" : "导入本地备份？",
            isPresented: Binding(
                get: { backupConfirm != nil },
                set: { if !$0 { backupConfirm = nil } }
            ),
        ) {
            Button("取消", role: .cancel) { backupConfirm = nil }
            if backupConfirm == .export {
                Button("导出") {
                    backupConfirm = nil
                    startBackupExport()
                }
            } else {
                Button("继续导入", role: .destructive) {
                    backupConfirm = nil
                    backupImportOpen = true
                }
            }
        } message: {
            Text(backupConfirm == .export
                 ? "生成 JSON 文件，可保存到「文件」或发给其他设备。"
                 : "本地记录将被覆盖，该操作不可撤回。是否确认继续？")
        }
        .fileExporter(
            isPresented: $backupExportOpen,
            document: backupExportDoc,
            contentType: .json,
            defaultFilename: backupExportName,
        ) { result in
            backupExportDoc = nil
            switch result {
            case .success:
                store.showToast("已导出本地备份")
            case .failure(let error):
                store.showToast("导出失败：\(error.localizedDescription)")
            }
        }
        .fileImporter(
            isPresented: $backupImportOpen,
            allowedContentTypes: [.json],
            allowsMultipleSelection: false,
        ) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                readBackupFile(url)
            case .failure:
                store.showToast("选择文件失败")
            }
        }
        .confirmationDialog(
            "更新提醒",
            isPresented: $updateModeChooser,
            titleVisibility: .visible
        ) {
            Button("每次启动都提示") {
                updatePromptMode = UpdatePromptMode.always.rawValue
            }
            Button("每个版本只提示一次") {
                updatePromptMode = UpdatePromptMode.once.rawValue
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("当前：\(updateMode.label)。手动检查始终会检查。")
        }
    }

    private var updateMode: UpdatePromptMode {
        UpdatePromptMode(rawValue: updatePromptMode) ?? .always
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

    // MARK: - 本地备份

    /// 导出：快照必须在主线程取（任务字典由主线程的 persist 命令维护，
    /// 后台读会数据竞争），后台只做 JSON 编码与文件写入。
    private func startBackupExport() {
        guard !backupBusy else { return }
        backupBusy = true
        let snapshot = BackupManager.makeSnapshot()
        DispatchQueue.global(qos: .userInitiated).async {
            let result = Result { try BackupManager.encode(snapshot) }
            DispatchQueue.main.async {
                backupBusy = false
                switch result {
                case .success(let text):
                    backupExportName = BackupManager.defaultFileName()
                    backupExportDoc = BackupFileDocument(text: text)
                    backupExportOpen = true
                case .failure(let error):
                    store.showToast("导出失败：\(error.localizedDescription)")
                }
            }
        }
    }

    /// 读文件 → 后台解析并覆盖本机记录 → 回到主线程把设置同步进界面。
    private func readBackupFile(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .utf8) else {
            store.showToast("读取备份文件失败")
            return
        }
        backupBusy = true
        DispatchQueue.global(qos: .userInitiated).async {
            let result = Result { try BackupManager.restore(text: text) }
            DispatchQueue.main.async {
                backupBusy = false
                switch result {
                case .success(let summary):
                    // 设置项是 @AppStorage，重新赋值即可让界面与存储同步。
                    themeMode = summary.theme
                    forceSubmit = Prefs.forceSubmit
                    weakNetwork = Prefs.weakNetwork
                    updatePromptMode = UserDefaults.standard.string(forKey: "updatePromptMode") ?? "always"
                    store.showToast(
                        "已导入备份（本地工作流 \(summary.localCount) 个、任务 \(summary.taskCount) 条）；任务历史重连后生效"
                    )
                case .failure(let error):
                    store.showToast("导入失败：\(error.localizedDescription)")
                }
            }
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
