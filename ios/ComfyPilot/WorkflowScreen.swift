import PhotosUI
import SwiftUI

/// 工作流列表。导航由 RootView 的页面栈负责，这里只发事件——
/// 和安卓的 WorkflowCatalogScreen 一样，顶栏是「返回 / 工作流 / 任务」。
struct WorkflowListScreen: View {
    let palette: SpectrumPalette
    let onBack: () -> Void
    let onOpen: (String) -> Void
    let onTasks: () -> Void
    @EnvironmentObject private var store: SessionStore

    var body: some View {
        ScreenScaffold(palette: palette) {
            VStack(spacing: 0) {
                SpectrumTopBar(palette: palette, title: "工作流", onBack: onBack) {
                    TopBarAction(text: "刷新", palette: palette) {
                        store.refreshWorkflows()
                    }
                    TopBarAction(text: "任务", palette: palette, action: onTasks)
                }

                ScrollView {
                    VStack(alignment: .leading, spacing: 9) {
                        Text(store.snapshot.catalog.isEmpty
                             ? "服务器上的工作流"
                             : "共 \(store.snapshot.catalog.count) 个，点一个打开。")
                            .font(.system(size: 10.5, design: .monospaced))
                            .foregroundStyle(palette.textDim)
                            .padding(.bottom, 2)

                        if !store.snapshot.catalogError.isEmpty {
                            errorCard(store.snapshot.catalogError)
                        }

                        if store.snapshot.catalog.isEmpty {
                            emptyState
                        } else {
                            ForEach(store.snapshot.catalog) { entry in
                                Button {
                                    onOpen(entry.path)
                                } label: {
                                    row(entry)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 8)
                    .padding(.bottom, 24)
                }
            }
        }
    }

    private func row(_ entry: CatalogRow) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.name)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(palette.text)
                    .lineLimit(1)
                Text(entry.path)
                    .font(.caption2.monospaced())
                    .foregroundStyle(palette.textDim)
                    .lineLimit(1)
            }
            Spacer(minLength: 6)

            if entry.sizeBytes > 0 {
                Text(sizeText(entry.sizeBytes))
                    .font(.system(size: 9.5, design: .monospaced))
                    .foregroundStyle(palette.textDim)
            }

            Image(systemName: "chevron.right")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(palette.textDim)
        }
        .glassCard(palette)
    }

    private func sizeText(_ bytes: Int64) -> String {
        if bytes >= 1024 * 1024 {
            return String(format: "%.1f MB", Double(bytes) / 1024 / 1024)
        }
        return String(format: "%.0f KB", max(Double(bytes) / 1024, 0.1))
    }

    /// 读取失败：和安卓一样给原因 + 重试，不用退出重进。
    private func errorCard(_ message: String) -> some View {
        GlassCard(palette: palette, padding: 12) {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("读取失败")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(palette.err)
                    Text(message)
                        .font(.system(size: 10.5))
                        .lineSpacing(3)
                        .foregroundStyle(palette.textDim)
                        .lineLimit(3)
                }
                Spacer(minLength: 0)
                GhostButton(title: "重试", palette: palette, height: 30) {
                    store.refreshWorkflows()
                }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "tray")
                .font(.system(size: 28, weight: .light))
                .foregroundStyle(palette.textDim)
            Text(store.snapshot.isConnected ? "服务端没有工作流" : "未连接")
                .font(.subheadline)
                .foregroundStyle(palette.textMuted)
            Text("工作流放在 ComfyUI 的 user/default/workflows 下")
                .font(.caption)
                .foregroundStyle(palette.textDim)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .glassCard(palette)
    }
}

struct WorkflowDetailScreen: View {
    let path: String
    let palette: SpectrumPalette
    let onBack: () -> Void
    let onTasks: () -> Void
    @EnvironmentObject private var store: SessionStore
    /// 同一层视图只挂一个 sheet：SwiftUI 叠两个 .sheet 只有最后一个稳定生效。
    @State private var sheet: DetailSheet?
    /// 节点展开状态。nil = 还没手动改过，按默认规则算。
    @State private var expandedOverride: Set<Int>?
    @State private var noteNodeId: Int?
    @State private var noteText = ""

    enum DetailSheet: Identifiable {
        case nodes
        case graph(Int)

        var id: String {
            switch self {
            case .nodes: return "nodes"
            case .graph(let nodeId): return "graph-\(nodeId)"
            }
        }
    }
    /// 0 = 输入，1 = 预览。照 WorkflowDetailScreen.kt 的 SpectrumTabs。
    @State private var tab = LaunchOptions.detailTab

    var body: some View {
        ScreenScaffold(palette: palette) {
            VStack(spacing: 0) {
                SpectrumTopBar(
                    palette: palette,
                    title: store.workflowView?.name ?? "工作流",
                    onBack: onBack
                ) {
                    TopBarAction(text: "刷新", palette: palette) {
                        store.reloadWorkflow()
                    }
                    TopBarAction(text: "任务", palette: palette, action: onTasks)
                }

                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        if let view = store.workflowView {
                            summary(view)
                            // 主机状态跟着工作流走：安卓只在这一页放它。
                            ServerStatsStrip(palette: palette, snapshot: store.snapshot)

                            SpectrumTabs(
                                labels: ["输入 \(fieldCount(view))", "预览"],
                                selected: tab,
                                palette: palette
                            ) { tab = $0 }
                            .padding(.vertical, 2)

                            if tab == 0 {
                                ForEach(orderedNodes(view)) { node in
                                    nodeCard(node)
                                }
                            } else {
                                PreviewPane(palette: palette, workflowPath: path)
                            }
                        } else if !store.snapshot.workflowError.isEmpty {
                            errorCard(store.snapshot.workflowError)
                        } else {
                            loadingCard
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 8)
                    .padding(.bottom, 16)
                }
            }
        }
        .safeAreaInset(edge: .bottom) { submitBar }
        .alert("节点备注", isPresented: noteAlertBinding) {
            TextField("比如：这里控制构图", text: $noteText)
            Button("保存") {
                if let id = noteNodeId { store.setNote(nodeId: id, text: noteText) }
                noteNodeId = nil
            }
            Button("清除", role: .destructive) {
                if let id = noteNodeId { store.setNote(nodeId: id, text: "") }
                noteNodeId = nil
            }
            Button("取消", role: .cancel) { noteNodeId = nil }
        }
        .sheet(item: $sheet) { item in
            switch item {
            case .nodes:
                NodeListSheet(
                    palette: palette,
                    onToggle: { nodeId, bypassed in
                        store.setNodeBypassed(nodeId: nodeId, bypassed: bypassed)
                    },
                    onNote: { nodeId, text in
                        store.setNote(nodeId: nodeId, text: text)
                    }
                )
            case .graph(let nodeId):
                NodeGraphSheet(
                    palette: palette,
                    graph: store.workflowView?.graph ?? WorkflowGraph(),
                    focusNodeId: nodeId
                )
            }
        }
        .onAppear { store.openWorkflow(path: path) }
        .onChange(of: store.workflowView?.graph?.nodes.count ?? 0) { _, count in
            // CI 点不了「连线」图标：带 -uiGraph 时等图数据到了自动弹出来。
            guard LaunchOptions.showGraph, count > 0, sheet == nil else { return }
            sheet = .graph(store.workflowView?.nodes.first?.id ?? 0)
        }
    }


    /// 置顶的排在最前，其余保持共享层给的顺序。
    private func orderedNodes(_ view: WorkflowView) -> [WorkflowNode] {
        let nodes = view.visibleNodes
        let pinned = store.pinnedNodeIds
        guard !pinned.isEmpty else { return nodes }
        return nodes.filter { pinned.contains($0.id) } + nodes.filter { !pinned.contains($0.id) }
    }

    private func fieldCount(_ view: WorkflowView) -> Int {
        view.nodes.reduce(0) { $0 + $1.fields.count }
    }

    private func summary(_ view: WorkflowView) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text(view.name.isEmpty ? path : view.name)
                    .font(.headline)
                    .foregroundStyle(palette.text)
                    .lineLimit(1)
                Spacer()
                Text("\(view.visibleNodes.count) 个可调节点")
                    .font(.caption2)
                    .foregroundStyle(palette.textDim)
            }

            if !view.graphs.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(view.graphs) { graph in
                            SpectrumChip(
                                text: graph.title,
                                selected: graph.enabled,
                                palette: palette
                            ) {
                                store.toggleGraph(graph.id)
                            }
                        }
                    }
                }
            }

            ForEach(Array(view.problems.enumerated()), id: \.offset) { _, problem in
                Text(problem)
                    .font(.caption2)
                    .foregroundStyle(palette.err)
            }
            ForEach(Array(view.warnings.prefix(3).enumerated()), id: \.offset) { _, warning in
                Text(warning)
                    .font(.caption2)
                    .foregroundStyle(palette.warn)
            }
        }
        .glassCard(palette)
    }

    /// 节点卡：标题行 + 状态说明 + 可折叠的字段区。
    /// 折叠规则照抄安卓：默认展开「置顶」或「含常用可调控件」的节点，其余收起。
    private func nodeCard(_ node: WorkflowNode) -> some View {
        let expanded = isExpanded(node)
        let note = store.nodeNotes[String(node.id)] ?? ""
        let pinned = store.pinnedNodeIds.contains(node.id)

        return VStack(alignment: .leading, spacing: 10) {
            Button {
                toggleExpanded(node)
            } label: {
                HStack(alignment: .top, spacing: 6) {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Text("#\(node.id)")
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(palette.textDim)
                            Text(node.title.isEmpty ? node.type : node.title)
                                .font(.system(size: 13.5, weight: .semibold))
                                .foregroundStyle(node.enabled ? palette.text : palette.textMuted)
                                .lineLimit(1)
                        }
                        Text("\(node.type) · \(node.fields.count) 个输入")
                            .font(.system(size: 9.5, design: .monospaced))
                            .foregroundStyle(node.enabled ? palette.textDim : palette.textMuted)
                            .lineLimit(1)
                    }

                    Spacer(minLength: 4)

                    if node.needsInput {
                        SpectrumPill(text: "待填写", palette: palette, tone: .err)
                    }
                }
            }
            .buttonStyle(.plain)

            // 图标行单独放：点卡片本体是展开/收起，图标各有各的动作。
            HStack(spacing: 12) {
                if !note.isEmpty {
                    Text(note)
                        .font(.system(size: 10.5))
                        .foregroundStyle(palette.textMuted)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    Spacer(minLength: 0)
                }

                iconButton("text.bubble", active: !note.isEmpty) {
                    noteText = note
                    noteNodeId = node.id
                }
                iconButton("point.topleft.down.curvedto.point.bottomright.up", active: false) {
                    sheet = .graph(node.id)
                }
                iconButton(pinned ? "pin.fill" : "pin", active: pinned) {
                    store.togglePin(node.id)
                }
                iconButton(node.enabled ? "power.circle.fill" : "power.circle", active: node.enabled) {
                    store.setNodeBypassed(nodeId: node.id, bypassed: node.enabled)
                }
                iconButton(expanded ? "chevron.up" : "chevron.down", active: false) {
                    toggleExpanded(node)
                }
            }

            if let reason = statusNote(node) {
                Text(reason)
                    .font(.system(size: 10.5))
                    .lineSpacing(3)
                    .foregroundStyle(node.missing ? palette.err : palette.textDim)
            }

            if expanded {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(node.fields) { field in
                        FieldRow(field: field, palette: palette) { value in
                            store.setOverride(field, value: value)
                        }
                    }
                }
            }
        }
        .glassCard(palette)
    }

    private var noteAlertBinding: Binding<Bool> {
        Binding(
            get: { noteNodeId != nil },
            set: { if !$0 { noteNodeId = nil } }
        )
    }

    private func iconButton(_ systemName: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 14))
                .foregroundStyle(active ? palette.accent : palette.textDim)
                .frame(width: 26, height: 24)
        }
        .buttonStyle(.plain)
    }

    /// 卡片上的一行说明，对应安卓那段 when：缺定义 / 已关闭 / 不可达。
    private func statusNote(_ node: WorkflowNode) -> String? {
        if node.missing { return "服务端没有该节点的定义，不能调参；提交会被拒绝" }
        if !node.enabled { return "已关闭，提交时会跳过该节点" }
        if !node.reachable { return "未连接到输出节点，不会被提交" }
        if node.needsInput { return "还有必填项没填" }
        return nil
    }

    /// 展开状态：用户手动改过就以手动为准，否则按默认规则算。
    private func isExpanded(_ node: WorkflowNode) -> Bool {
        let set = expandedOverride ?? autoExpandedIds
        return set.contains(node.id)
    }

    private func toggleExpanded(_ node: WorkflowNode) {
        var set = expandedOverride ?? autoExpandedIds
        if set.contains(node.id) {
            set.remove(node.id)
        } else {
            set.insert(node.id)
        }
        expandedOverride = set
    }

    private var autoExpandedIds: Set<Int> {
        let nodes = store.workflowView?.nodes ?? []
        return Set(nodes.filter { store.pinnedNodeIds.contains($0.id) || $0.autoExpanded }.map { $0.id })
    }

    private func errorCard(_ message: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 24, weight: .light))
                .foregroundStyle(palette.err)
            Text("打不开这个工作流")
                .font(.subheadline)
                .foregroundStyle(palette.text)
            Text(message)
                .font(.caption)
                .foregroundStyle(palette.textMuted)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
        .glassCard(palette)
    }

    private var loadingCard: some View {        VStack(spacing: 8) {
            ProgressView()
                .tint(palette.accent)
            Text(store.snapshot.isConnected ? "正在读取工作流" : "未连接")
                .font(.caption)
                .foregroundStyle(palette.textDim)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .glassCard(palette)
    }

    /// 照抄 WorkflowDetailScreen.kt 的底栏：顶部 1pt 分隔线 + 半透明底色，
    /// 一行 [全部节点] … 「N 个会提交」[提交任务]。
    private var submitBar: some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(palette.border)
                .frame(height: 1)

            VStack(alignment: .leading, spacing: 8) {
                if !canSubmit, let reason = store.workflowView?.blockReason, !reason.isEmpty {
                    Text(reason)
                        .font(.system(size: 10.5))
                        .lineSpacing(4)
                        .foregroundStyle(palette.err)
                }

                HStack(spacing: 10) {
                    GhostButton(title: "全部节点", palette: palette) { sheet = .nodes }

                    Spacer(minLength: 0)

                    Text("\(submitNodeCount) 个会提交")
                        .font(.system(size: 9.5, design: .monospaced))
                        .foregroundStyle(palette.textDim)

                    GradientButton(
                        title: "提交任务",
                        palette: palette,
                        height: 44,
                        enabled: canSubmit,
                        expand: false
                    ) {
                        store.submitWorkflow()
                    }
                }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
        }
        .background(palette.background.opacity(0.86))
    }

    private var submitNodeCount: Int {
        store.workflowView?.nodes.filter { $0.enabled }.count ?? 0
    }

    private var canSubmit: Bool {
        (store.workflowView?.canSubmit ?? false) && store.snapshot.isConnected
    }

}

/// 单个可编辑输入项。控件类型只看字段自己的 type / options / valueKind。
struct FieldRow: View {
    let field: WorkflowField
    let palette: SpectrumPalette
    let onCommit: (Any) -> Void

    @EnvironmentObject private var store: SessionStore
    @State private var text: String
    @State private var showPicker = false
    @State private var pickedItem: PhotosPickerItem?
    @FocusState private var focused: Bool

    init(field: WorkflowField, palette: SpectrumPalette, onCommit: @escaping (Any) -> Void) {
        self.field = field
        self.palette = palette
        self.onCommit = onCommit
        _text = State(initialValue: field.valueText)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text(field.label)
                    .font(.caption)
                    .foregroundStyle(palette.textMuted)
                    .lineLimit(1)
                if field.required {
                    Text("必填")
                        .font(.system(size: 9, weight: .medium))
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(Capsule().fill(palette.warn.opacity(0.16)))
                        .foregroundStyle(palette.warn)
                }
                Spacer()
                if field.linked {
                    Text(field.sourceLabel.isEmpty ? "由连线提供" : "由 \(field.sourceLabel) 提供")
                        .font(.system(size: 10))
                        .foregroundStyle(palette.textDim)
                        .lineLimit(1)
                } else {
                    Text(field.type)
                        .font(.system(size: 9.5, design: .monospaced))
                        .foregroundStyle(palette.textDim)
                        .lineLimit(1)
                }
            }
            control
            if store.uploadErrorFieldId == field.id, !store.uploadError.isEmpty {
                Text(store.uploadError)
                    .font(.system(size: 10.5))
                    .foregroundStyle(palette.err)
                    .lineLimit(2)
            }
        }
        .photosPicker(isPresented: $showPicker, selection: $pickedItem, matching: .images)
        .onChange(of: pickedItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self) {
                    store.uploadImage(data: data, filename: "photo.jpg", field: field)
                }
                pickedItem = nil
            }
        }
    }

    @ViewBuilder
    private var control: some View {
        if !field.editable {
            Text(field.valueText.isEmpty ? "—" : field.valueText)
                .font(.callout.monospaced())
                .foregroundStyle(palette.textDim)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(10)
                .background(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(palette.cardStrong.opacity(0.5))
                )
        } else if field.isImageUpload {
            HStack(spacing: 8) {
                Text(text.isEmpty ? "—" : text)
                    .font(.system(size: 12.5, design: .monospaced))
                    .foregroundStyle(text.isEmpty ? palette.textDim : palette.text)
                    .lineLimit(1)
                    .truncationMode(.middle)

                Spacer(minLength: 0)

                if store.uploadingFieldId == field.id {
                    ProgressView()
                        .controlSize(.small)
                        .tint(palette.accent)
                } else {
                    GhostButton(title: text.isEmpty ? "选图" : "换图", palette: palette, height: 30) {
                        showPicker = true
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(palette.card)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(palette.border, lineWidth: 1)
            )
        } else if field.isSelect {
            Menu {
                ForEach(field.options, id: \.self) { option in
                    Button(option) {
                        text = option
                        onCommit(option)
                    }
                }
            } label: {
                HStack {
                    Text(text.isEmpty ? "—" : text)
                        .font(.callout.monospaced())
                        .foregroundStyle(palette.text)
                        .lineLimit(1)
                    Spacer()
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.caption2)
                        .foregroundStyle(palette.textDim)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 12)
                .background(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(palette.card)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .stroke(palette.border, lineWidth: 1)
                )
            }
        } else if field.isBool {
            HStack {
                Text(text == "true" ? "开启" : "关闭")
                    .font(.callout.monospaced())
                    .foregroundStyle(palette.text)
                Spacer()
                Toggle("", isOn: Binding(
                    get: { text == "true" },
                    set: { newValue in
                        text = newValue ? "true" : "false"
                        onCommit(newValue)
                    }
                ))
                .labelsHidden()
                .tint(palette.accent)
            }
            .padding(10)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(palette.cardStrong)
            )
        } else {
            TextField("", text: $text)
                .font(.callout.monospaced())
                .foregroundStyle(palette.text)
                .keyboardType(field.isNumber ? .numbersAndPunctuation : .default)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focused)
                .onSubmit { commit() }
                .onChange(of: focused) { _, active in
                    if !active { commit() }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(palette.card)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .stroke(palette.border, lineWidth: 1)
                )
        }
    }

    private func commit() {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if field.isBool {
            onCommit(trimmed == "true")
        } else if field.isNumber, let value = Double(trimmed) {
            // 数字按数字送，共享层还要按字段类型再做一次校验。
            onCommit(value)
        } else {
            onCommit(trimmed)
        }
    }
}

/// 「全部节点」面板：列出工作流里的所有节点，逐个开关。
/// 对应安卓 WorkflowDetailScreen 里 `全部节点` 打开的底部 sheet。
struct NodeListSheet: View {
    let palette: SpectrumPalette
    let onToggle: (Int, Bool) -> Void
    let onNote: (Int, String) -> Void

    @EnvironmentObject private var store: SessionStore
    @Environment(\.dismiss) private var dismiss
    @State private var noteNodeId: Int?
    @State private var noteText = ""

    var body: some View {
        ZStack {
            SpectrumBackdrop(palette: palette)

            VStack(spacing: 0) {
                SpectrumTopBar(palette: palette, title: "全部节点", onBack: { dismiss() }) {
                    TopBarAction(text: "关闭", palette: palette) { dismiss() }
                }

                ScrollView {
                    VStack(spacing: 9) {
                        ForEach(nodes) { node in
                            row(node)
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 8)
                    .padding(.bottom, 24)
                }
            }
        }
        .alert("节点备注", isPresented: noteAlertBinding) {
            TextField("比如：这里控制构图", text: $noteText)
            Button("保存") {
                if let id = noteNodeId { onNote(id, noteText) }
                noteNodeId = nil
            }
            Button("清除", role: .destructive) {
                if let id = noteNodeId { onNote(id, "") }
                noteNodeId = nil
            }
            Button("取消", role: .cancel) { noteNodeId = nil }
        }
    }

    private var nodes: [WorkflowNode] {
        store.workflowView?.nodes ?? []
    }

    private func row(_ node: WorkflowNode) -> some View {
        let note = store.nodeNotes[String(node.id)] ?? ""
        return HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(node.title.isEmpty ? node.type : node.title)
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(node.enabled ? palette.text : palette.textDim)
                    .lineLimit(1)
                Text("\(node.type) · \(node.fields.count) 个输入")
                    .font(.system(size: 10))
                    .foregroundStyle(palette.textDim)
                    .lineLimit(1)
                if !note.isEmpty {
                    Text(note)
                        .font(.system(size: 10.5))
                        .foregroundStyle(palette.textMuted)
                        .lineLimit(2)
                }
            }

            Spacer(minLength: 4)

            Button {
                noteText = note
                noteNodeId = node.id
            } label: {
                Image(systemName: note.isEmpty ? "text.bubble" : "text.bubble.fill")
                    .font(.system(size: 15))
                    .foregroundStyle(note.isEmpty ? palette.textDim : palette.accent)
            }
            .buttonStyle(.plain)

            Button {
                onToggle(node.id, node.enabled)
            } label: {
                Image(systemName: node.enabled ? "power.circle.fill" : "power.circle")
                    .font(.system(size: 17))
                    .foregroundStyle(node.enabled ? palette.ok : palette.textDim)
            }
            .buttonStyle(.plain)
        }
        .glassCard(palette, padding: 12)
    }

    private var noteAlertBinding: Binding<Bool> {
        Binding(
            get: { noteNodeId != nil },
            set: { if !$0 { noteNodeId = nil } }
        )
    }
}
