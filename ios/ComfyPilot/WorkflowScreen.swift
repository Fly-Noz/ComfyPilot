import SwiftUI

/// 工作流 tab。列表 → 详情，详情页的参数模型全部来自共享层的 `workflowView`，
/// Swift 这边不做任何字段规则（哪些能改、哪些是连线提供、类型怎么转，都由 Kotlin 决定）。
struct WorkflowTab: View {
    let palette: SpectrumPalette

    @State private var route: [String] = {
        // CI 没法点屏幕：带 -uiOpenWorkflow 时直接把详情压进导航栈。
        if let path = LaunchOptions.workflowPath, !path.isEmpty { return [path] }
        return []
    }()

    var body: some View {
        NavigationStack(path: $route) {
            WorkflowListScreen(palette: palette)
                .navigationDestination(for: String.self) { path in
                    WorkflowDetailScreen(path: path, palette: palette)
                }
        }
        .tint(palette.accent)
    }
}

struct WorkflowListScreen: View {
    let palette: SpectrumPalette
    @EnvironmentObject private var store: SessionStore

    var body: some View {
        ScreenScaffold(palette: palette) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text("工作流")
                            .font(.title2.weight(.semibold))
                            .foregroundStyle(palette.text)
                        Spacer()
                        Button {
                            store.refreshWorkflows()
                        } label: {
                            Image(systemName: "arrow.clockwise")
                                .font(.footnote.weight(.medium))
                                .foregroundStyle(palette.textMuted)
                        }
                    }
                    .padding(.bottom, 4)

                    if store.snapshot.catalog.isEmpty {
                        emptyState
                    } else {
                        ForEach(store.snapshot.catalog) { entry in
                            NavigationLink(value: entry.path) {
                                row(entry)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 28)
            }
        }
        .toolbar(.hidden, for: .navigationBar)
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
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(palette.textDim)
        }
        .spectrumCard(palette)
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
        .spectrumCard(palette)
    }
}

struct WorkflowDetailScreen: View {
    let path: String
    let palette: SpectrumPalette
    @EnvironmentObject private var store: SessionStore

    var body: some View {
        ScreenScaffold(palette: palette) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let view = store.workflowView {
                        summary(view)
                        ForEach(view.visibleNodes) { node in
                            nodeCard(node)
                        }
                    } else if store.snapshot.isConnected {
                        loadingCard
                    } else {
                        loadingCard
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 120)
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .toolbarBackground(.hidden, for: .navigationBar)
        .safeAreaInset(edge: .bottom) { submitBar }
        .onAppear { store.openWorkflow(path: path) }
    }

    private var title: String {
        let name = store.workflowView?.name ?? ""
        return name.isEmpty ? "工作流" : name
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
                            Text(graph.title)
                                .font(.caption2)
                                .padding(.horizontal, 9)
                                .padding(.vertical, 4)
                                .background(
                                    Capsule().fill(graph.enabled ? palette.accentSoft : palette.cardStrong)
                                )
                                .foregroundStyle(graph.enabled ? palette.accent : palette.textDim)
                                .lineLimit(1)
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
        .spectrumCard(palette)
    }

    private func nodeCard(_ node: WorkflowNode) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Text(node.title.isEmpty ? node.type : node.title)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(node.missing ? palette.err : palette.text)
                    .lineLimit(1)
                Spacer()
                Text("#\(node.id)")
                    .font(.caption2.monospaced())
                    .foregroundStyle(palette.textDim)
            }

            ForEach(node.fields) { field in
                FieldRow(field: field, palette: palette) { value in
                    store.setOverride(field, value: value)
                }
            }
        }
        .spectrumCard(palette)
    }

    private var loadingCard: some View {
        VStack(spacing: 8) {
            ProgressView()
                .tint(palette.accent)
            Text(store.snapshot.isConnected ? "正在读取工作流" : "未连接")
                .font(.caption)
                .foregroundStyle(palette.textDim)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .spectrumCard(palette)
    }

    private var submitBar: some View {
        VStack(spacing: 0) {
            Divider().overlay(palette.border)
            Button {
                store.submitWorkflow()
            } label: {
                Text(submitTitle)
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(canSubmit ? palette.accent : palette.cardStrong)
                    )
                    .foregroundStyle(canSubmit ? palette.accentInk : palette.textDim)
            }
            .disabled(!canSubmit)
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
        }
        .background(.ultraThinMaterial)
    }

    private var canSubmit: Bool {
        (store.workflowView?.canSubmit ?? false) && store.snapshot.isConnected
    }

    private var submitTitle: String {
        if let reason = store.workflowView?.blockReason, !reason.isEmpty, !canSubmit {
            return reason
        }
        return canSubmit ? "提交" : "不可提交"
    }
}

/// 单个可编辑输入项。控件类型只看字段自己的 type / options / valueKind。
struct FieldRow: View {
    let field: WorkflowField
    let palette: SpectrumPalette
    let onCommit: (Any) -> Void

    @State private var text: String
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
                }
            }
            control
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
                .padding(10)
                .background(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(palette.cardStrong)
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
                .padding(10)
                .background(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(palette.cardStrong)
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
