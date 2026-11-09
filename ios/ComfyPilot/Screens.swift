import SwiftUI

enum Screen: String {
    case connect
    case workflow
    case tasks
    case settings

    static func from(_ raw: String) -> Screen {
        Screen(rawValue: raw) ?? .connect
    }
}

struct RootView: View {
    @EnvironmentObject private var store: SessionStore
    @Environment(\.colorScheme) private var scheme
    @State private var screen: Screen = Screen.from(LaunchOptions.screen)

    var body: some View {
        let palette = Spectrum.palette(for: scheme)
        TabView(selection: $screen) {
            ConnectScreen(palette: palette)
                .tabItem { Label("连接", systemImage: "antenna.radiowaves.left.and.right") }
                .tag(Screen.connect)
            WorkflowTab(palette: palette)
                .tabItem { Label("工作流", systemImage: "square.on.square") }
                .tag(Screen.workflow)
            TasksScreen(palette: palette)
                .tabItem { Label("任务", systemImage: "square.stack.3d.up") }
                .tag(Screen.tasks)
            SettingsScreen(palette: palette)
                .tabItem { Label("设置", systemImage: "gearshape") }
                .tag(Screen.settings)
        }
        .tint(palette.accent)
        .preferredColorScheme(preferredScheme)
        .onAppear { store.boot() }
        .onChange(of: store.pendingScreen) { _, target in
            if let raw = target, let next = Screen(rawValue: raw) {
                screen = next
            }
        }
    }

    private var preferredScheme: ColorScheme? {
        if LaunchOptions.theme == "dark" { return .dark }
        if LaunchOptions.theme == "light" { return .light }
        return nil
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
    @EnvironmentObject private var store: SessionStore

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
    }

    private var topBar: some View {
        ZStack(alignment: .leading) {
            SpectrumTopBar(palette: palette, title: "") {
                TopBarAction(text: "设置", palette: palette) {}
                TopBarAction(text: "诊断", palette: palette) {}
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
                    }
                }
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
                monoRow("地址", store.snapshot.baseUrl.isEmpty ? "—" : store.snapshot.baseUrl)
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
    @EnvironmentObject private var store: SessionStore
    @State private var viewing: TaskRow?

    var body: some View {
        ScreenScaffold(palette: palette) {
            VStack(spacing: 0) {
                SpectrumTopBar(palette: palette, title: "任务") {
                    TopBarAction(text: "诊断", palette: palette) {}
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
                        LazyVStack(spacing: 9) {
                            ForEach(store.snapshot.tasks) { task in
                                TaskCard(
                                    task: task,
                                    palette: palette,
                                    onView: task.artifacts.isEmpty ? nil : { viewing = task },
                                    onCancel: { store.cancel(task) }
                                )
                            }
                        }
                        .padding(.horizontal, 18)
                        .padding(.bottom, 24)
                    }
                }
            }
        }
        .sheet(item: $viewing) { task in
            ArtifactViewer(task: task, baseUrl: store.snapshot.baseUrl, palette: palette)
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
                        SpectrumProgress(fraction: task.percent, palette: palette, height: 5)
                        Text("\(Int(task.percent * 100))%")
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
    @EnvironmentObject private var store: SessionStore

    var body: some View {
        ScreenScaffold(palette: palette) {
            VStack(spacing: 0) {
                SpectrumTopBar(palette: palette, title: "设置")

                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        GlassCard(palette: palette) {
                            VStack(spacing: 0) {
                                row("工作流", store.snapshot.workflowName.isEmpty ? "未加载" : store.snapshot.workflowName)
                                row("服务端", store.snapshot.baseUrl.isEmpty ? "—" : store.snapshot.baseUrl)
                                row("连接", store.snapshot.isConnected ? "已连接" : "未连接")
                            }
                        }

                        GlassCard(palette: palette) {
                            VStack(spacing: 0) {
                                row("主题", themeName)
                                row("弱网模式", "关闭")
                                row("后台保活", "不支持（iOS 限制）")
                            }
                        }

                        GlassCard(palette: palette) {
                            VStack(spacing: 0) {
                                row("版本", bundleVersion)
                                row("共享核心", "Kotlin/Native · ComfyCore")
                            }
                        }

                        Text("iOS 端仍在施工：连接、工作流参数、提交、产物查看已通；采样预览、节点开关、AI 助手还没搬过来。")
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
    }

    private var themeName: String {
        switch LaunchOptions.theme {
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
