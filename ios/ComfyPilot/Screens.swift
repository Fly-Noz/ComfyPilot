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

/// 每个页面自带色晕背景：TabView 会给内容区铺自己的底色，
/// 背景放在 TabView 外面会被盖住。
struct ScreenScaffold<Content: View>: View {
    let palette: SpectrumPalette
    let content: Content

    init(palette: SpectrumPalette, @ViewBuilder content: () -> Content) {
        self.palette = palette
        self.content = content()
    }

    var body: some View {
        ZStack {
            SpectrumBackground(palette: palette)
            content
        }
    }
}

// MARK: - 连接

struct ConnectScreen: View {
    let palette: SpectrumPalette
    @EnvironmentObject private var store: SessionStore

    var body: some View {
        ScreenScaffold(palette: palette) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    statusCard
                    addressCard
                    metricsCard
                    noticesCard
                }
                .padding(.horizontal, 20)
                .padding(.top, 12)
                .padding(.bottom, 28)
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("ComfyPilot")
                .font(.system(size: 32, weight: .semibold))
                .foregroundStyle(
                    LinearGradient(
                        colors: palette.gradient,
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                )
            Text("ComfyUI 移动客户端 · iOS")
                .font(.footnote)
                .foregroundStyle(palette.textMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var statusCard: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(phaseColor)
                .frame(width: 9, height: 9)
            VStack(alignment: .leading, spacing: 2) {
                Text(phaseTitle)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(palette.text)
                Text(statusSubtitle)
                    .font(.caption)
                    .foregroundStyle(palette.textMuted)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer()
            Text("gen \(store.snapshot.generation)")
                .font(.caption2.monospacedDigit())
                .foregroundStyle(palette.textDim)
        }
        .spectrumCard(palette)
    }

    private var addressCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("服务端地址")
                .font(.caption)
                .foregroundStyle(palette.textMuted)

            TextField("http://192.168.1.42:8188", text: $store.address)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .font(.callout.monospaced())
                .foregroundStyle(palette.text)
                .padding(12)
                .background(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(palette.cardStrong)
                )

            HStack(spacing: 10) {
                Button {
                    store.toggleConnection()
                } label: {
                    Text(store.running ? "断开" : "连接")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 11)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(palette.accent)
                        )
                        .foregroundStyle(palette.accentInk)
                }

                Button {
                    store.reconnect()
                } label: {
                    Text("重连")
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 18)
                        .padding(.vertical, 11)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .stroke(palette.border, lineWidth: 1)
                        )
                        .foregroundStyle(palette.text)
                }
            }
        }
        .spectrumCard(palette)
    }

    private var metricsCard: some View {
        HStack(spacing: 0) {
            metric("运行", "\(store.snapshot.queueRunning)")
            divider
            metric("排队", "\(store.snapshot.queuePending)")
            divider
            metric("任务", "\(store.snapshot.tasks.count)")
            divider
            metric("帧", "\(store.snapshot.wsTextFrames)")
        }
        .spectrumCard(palette)
    }

    private var divider: some View {
        Rectangle()
            .fill(palette.border)
            .frame(width: 1, height: 28)
    }

    private func metric(_ title: String, _ value: String) -> some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.system(size: 20, weight: .medium).monospacedDigit())
                .foregroundStyle(palette.text)
            Text(title)
                .font(.caption2)
                .foregroundStyle(palette.textDim)
        }
        .frame(maxWidth: .infinity)
    }

    private var noticesCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("最近事件")
                .font(.caption)
                .foregroundStyle(palette.textMuted)
            if store.notices.isEmpty {
                Text("暂无")
                    .font(.caption)
                    .foregroundStyle(palette.textDim)
            } else {
                ForEach(Array(store.notices.prefix(4).enumerated()), id: \.offset) { _, notice in
                    Text(notice)
                        .font(.caption.monospaced())
                        .foregroundStyle(palette.textMuted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .spectrumCard(palette)
    }

    /// 连上之后副标题显示实际地址：写「已连接」当副标题只是重复，写地址才有信息量。
    private var statusSubtitle: String {
        if !store.snapshot.baseUrl.isEmpty { return store.snapshot.baseUrl }
        return store.snapshot.label.isEmpty ? "等待连接" : store.snapshot.label
    }

    private var phaseTitle: String {
        switch store.snapshot.phase {
        case "Connected": return "已连接"
        case "Connecting": return "正在连接"
        case "Reconnecting": return "重连中"
        case "Recovering": return "正在对账"
        default: return "未连接"
        }
    }

    private var phaseColor: Color {
        switch store.snapshot.phase {
        case "Connected": return palette.ok
        case "Connecting": return palette.accent
        case "Reconnecting", "Recovering": return palette.warn
        default: return palette.textDim
        }
    }
}

// MARK: - 任务

struct TasksScreen: View {
    let palette: SpectrumPalette
    @EnvironmentObject private var store: SessionStore

    var body: some View {
        ScreenScaffold(palette: palette) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text("任务")
                            .font(.title2.weight(.semibold))
                            .foregroundStyle(palette.text)
                        Spacer()
                        Text("\(store.snapshot.tasks.count)")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(palette.textDim)
                    }
                    .padding(.bottom, 4)

                    if store.snapshot.tasks.isEmpty {
                        emptyState
                    } else {
                        ForEach(store.snapshot.tasks) { task in
                            TaskCard(task: task, palette: palette) {
                                store.cancel(task)
                            }
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 28)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "tray")
                .font(.system(size: 28, weight: .light))
                .foregroundStyle(palette.textDim)
            Text("还没有任务")
                .font(.subheadline)
                .foregroundStyle(palette.textMuted)
            Text("连接后在网页端提交，或等 AI 助手接手")
                .font(.caption)
                .foregroundStyle(palette.textDim)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .spectrumCard(palette)
    }
}

struct TaskCard: View {
    let task: TaskRow
    let palette: SpectrumPalette
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Text(task.name.isEmpty ? task.promptId : task.name)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(palette.text)
                    .lineLimit(1)
                Spacer()
                Text(stateTitle)
                    .font(.caption2.weight(.medium))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(
                        Capsule().fill(stateColor.opacity(0.16))
                    )
                    .foregroundStyle(stateColor)
            }

            if task.showsProgress {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(palette.border)
                        Capsule()
                            .fill(
                                LinearGradient(
                                    colors: palette.gradient,
                                    startPoint: .leading,
                                    endPoint: .trailing
                                )
                            )
                            .frame(width: geo.size.width * progress)
                    }
                }
                .frame(height: 5)
            }

            HStack(spacing: 8) {
                Text(task.node.isEmpty ? String(task.promptId.prefix(8)) : task.node)
                    .font(.caption2.monospaced())
                    .foregroundStyle(palette.textDim)
                Spacer()
                if task.showsProgress {
                    Text("\(Int(progress * 100))%")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(palette.textMuted)
                }
                if task.outputs > 0 {
                    Text("\(task.outputs) 个产物")
                        .font(.caption2)
                        .foregroundStyle(palette.textMuted)
                }
                if let error = task.error, !error.isEmpty {
                    Text(error)
                        .font(.caption2)
                        .foregroundStyle(palette.err)
                        .lineLimit(1)
                }
                if task.canCancel {
                    Button {
                        onCancel()
                    } label: {
                        Text("取消")
                            .font(.caption2.weight(.medium))
                            .padding(.horizontal, 9)
                            .padding(.vertical, 4)
                            .background(
                                Capsule().stroke(palette.err.opacity(0.45), lineWidth: 1)
                            )
                            .foregroundStyle(palette.err)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .spectrumCard(palette)
    }

    private var progress: CGFloat {
        CGFloat(min(max(task.percent, 0), 1))
    }

    private var stateTitle: String {
        switch task.state {
        case "Running": return "生成中"
        case "Queued": return "排队中"
        case "Submitting": return "提交中"
        case "Completed": return "已完成"
        case "Failed": return "失败"
        case "Cancelled": return "已取消"
        default: return "未知"
        }
    }

    private var stateColor: Color {
        switch task.state {
        case "Running": return palette.accent
        case "Completed": return palette.ok
        case "Failed": return palette.err
        case "Queued", "Submitting": return palette.warn
        default: return palette.textDim
        }
    }
}

// MARK: - 设置

struct SettingsScreen: View {
    let palette: SpectrumPalette
    @EnvironmentObject private var store: SessionStore

    var body: some View {
        ScreenScaffold(palette: palette) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("设置")
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(palette.text)
                        .padding(.bottom, 4)

                    group {
                        row("工作流", store.snapshot.workflowName.isEmpty ? "未加载" : store.snapshot.workflowName)
                        row("服务端", store.address)
                        row("连接", store.running ? "已建立" : "未连接")
                    }

                    group {
                        row("主题", "跟随系统")
                        row("弱网模式", "关闭")
                        row("后台保活", "不支持（iOS 限制）")
                    }

                    group {
                        row("版本", bundleVersion)
                        row("共享核心", "Kotlin/Native · ComfyCore")
                    }

                    Text("iOS 端仍在施工：本页只有连接与任务两块是通的，参数编辑、采样预览、AI 助手还没搬过来。")
                        .font(.caption2)
                        .foregroundStyle(palette.textDim)
                        .padding(.top, 4)
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 28)
            }
        }
    }

    private var bundleVersion: String {
        let info = Bundle.main.infoDictionary
        let short = (info?["CFBundleShortVersionString"] as? String) ?? "?"
        let build = (info?["CFBundleVersion"] as? String) ?? "?"
        return "\(short) (\(build))"
    }

    @ViewBuilder
    private func group(@ViewBuilder _ content: () -> some View) -> some View {
        VStack(spacing: 0) {
            content()
        }
        .spectrumCard(palette)
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title)
                .font(.subheadline)
                .foregroundStyle(palette.text)
            Spacer()
            Text(value)
                .font(.subheadline)
                .foregroundStyle(palette.textMuted)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .padding(.vertical, 9)
    }
}
