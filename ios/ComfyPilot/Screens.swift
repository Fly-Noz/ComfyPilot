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
    @State private var viewing: TaskRow?

    var body: some View {
        ScreenScaffold(palette: palette) {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("任务")
                                .font(.system(size: 22, weight: .semibold))
                                .foregroundStyle(palette.text)
                            Text("队列：运行 \(store.snapshot.queueRunning)  /  等待 \(store.snapshot.queuePending)")
                                .font(.system(size: 10.5))
                                .foregroundStyle(palette.textDim)
                        }
                        Spacer()
                        Button {
                            store.reconcile()
                        } label: {
                            Text("刷新")
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(palette.textMuted)
                        }
                        .buttonStyle(.plain)
                    }

                    statusLine

                    if store.snapshot.tasks.isEmpty {
                        emptyState
                    } else {
                        ForEach(store.snapshot.tasks) { task in
                            TaskCard(
                                task: task,
                                palette: palette,
                                onView: task.artifacts.isEmpty ? nil : { viewing = task },
                                onCancel: { store.cancel(task) }
                            )
                        }
                    }
                }
                .padding(.horizontal, 18)
                .padding(.top, 14)
                .padding(.bottom, 28)
            }
        }
        .sheet(item: $viewing) { task in
            ArtifactViewer(
                task: task,
                baseUrl: store.snapshot.baseUrl,
                palette: palette
            )
        }
    }

    private var statusLine: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(store.snapshot.isConnected ? palette.ok : palette.textDim)
                .frame(width: 6, height: 6)
            Text(statusText)
                .font(.system(size: 10.5))
                .foregroundStyle(palette.textMuted)
            Spacer()
        }
        .padding(.bottom, 2)
    }

    private var statusText: String {
        let phase = store.snapshot.isConnected ? "已连接" : "未连接"
        let transport = transportName(store.snapshot.network)
        let validated = store.snapshot.validated ? "（已验证）" : ""
        return "\(phase) · \(transport)\(validated) · 第 \(store.snapshot.generation) 代"
    }

    private func transportName(_ raw: String) -> String {
        switch raw {
        case "wifi": return "WIFI"
        case "cellular": return "蜂窝"
        case "ethernet": return "以太网"
        case "none": return "无网络"
        default: return "未知"
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
            Text("连接后在网页端提交，或在工作流页直接提交")
                .font(.caption)
                .foregroundStyle(palette.textDim)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .spectrumCard(palette)
    }
}

/// 对齐安卓/鸿蒙的任务卡：左侧状态色条 + 名称/耗时/徽章 + 进度条 + 输出行 + 幽灵按钮。
struct TaskCard: View {
    let task: TaskRow
    let palette: SpectrumPalette
    let onView: (() -> Void)?
    let onCancel: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Rectangle()
                .fill(tone)
                .frame(width: 3)

            VStack(alignment: .leading, spacing: 7) {
                HStack(spacing: 8) {
                    Text(title)
                        .font(.system(size: 11.5, weight: .medium, design: .monospaced))
                        .foregroundStyle(palette.text)
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    Text(task.elapsedText(nowMs: nowMillis))
                        .font(.system(size: 9.5, design: .monospaced))
                        .foregroundStyle(palette.textDim)
                    statePill
                }

                if task.showsProgress {
                    HStack(spacing: 8) {
                        progressBar
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
                }

                if let error = task.error, !error.isEmpty {
                    Text(error)
                        .font(.system(size: 10.5))
                        .foregroundStyle(palette.err)
                        .lineLimit(3)
                }

                if onView != nil || task.canCancel {
                    HStack(spacing: 8) {
                        if let onView {
                            ghostButton("查看产物", action: onView)
                        }
                        Spacer(minLength: 0)
                        if task.canCancel {
                            ghostButton("取消", action: onCancel)
                        }
                    }
                    .padding(.top, 1)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 11)
        }
        .background(palette.card)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(palette.border, lineWidth: 1)
        )
    }

    private var nowMillis: Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private var title: String {
        if !task.name.isEmpty { return task.name }
        if !task.promptId.isEmpty { return task.promptId }
        return task.id
    }

    private var progressBar: some View {
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
                    .frame(width: geo.size.width * CGFloat(min(max(task.percent, 0), 1)))
            }
        }
        .frame(height: 5)
    }

    private var statePill: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(tone)
                .frame(width: 5, height: 5)
            Text(stateLabel)
                .font(.system(size: 10, weight: .medium))
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Capsule().fill(tone.opacity(0.14)))
        .foregroundStyle(tone)
    }

    private func ghostButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 11, weight: .medium))
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(Capsule().fill(palette.cardStrong))
                .overlay(Capsule().stroke(palette.border, lineWidth: 1))
                .foregroundStyle(palette.text)
        }
        .buttonStyle(.plain)
    }

    private var stateLabel: String {
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

    private var tone: Color {
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
