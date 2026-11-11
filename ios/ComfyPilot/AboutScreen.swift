import SwiftUI

/// 关于页：与 Android / 鸿蒙对齐——App 图标、名称、版本、简介，
/// 以及「检查更新 / 项目地址 / 开源许可证」三行。
/// 隐私政策入口等正式版本发布、政策有公网 URL 后再加。
struct AboutScreen: View {
    let palette: SpectrumPalette

    @EnvironmentObject private var store: SessionStore
    @EnvironmentObject private var updates: UpdateStore
    @Environment(\.dismiss) private var dismiss
    @State private var licenseOpen = false

    private static let projectURL = "https://github.com/Fly-Noz/ComfyPilot"
    private static let licenseName = "GPL-3.0"
    private static let aboutDescription =
        "一个 ComfyUI 远程客户端，支持工作流浏览、参数调节、任务队列与产物预览。" +
        "共享核心由 Kotlin Multiplatform 实现，界面为 SwiftUI 原生。"
    private static let licenseBody =
        "本项目以 GNU General Public License v3.0 发布；你可以自由使用、修改与分发，" +
        "但衍生作品需同样以 GPL-3.0 开源；完整协议文本见仓库根目录的 LICENSE 文件；" +
        "SwiftUI、Kotlin/Native 等第三方组件的许可见 THIRD_PARTY_NOTICES.md。"
    private static let aiFootnote = "本项目的部分界面、文案与图标由 AI 辅助生成。"

    var body: some View {
        NavigationStack {
            ZStack {
                palette.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 0) {
                        Image("AppLogo")
                            .resizable()
                            .scaledToFit()
                            .frame(width: 96, height: 96)
                            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 24, style: .continuous)
                                    .stroke(palette.border, lineWidth: 1)
                            )
                            .padding(.top, 24)

                        Text("ComfyPilot")
                            .font(.system(size: 27, weight: .semibold))
                            .foregroundStyle(palette.text)
                            .padding(.top, 14)

                        Text("版本 \(bundleVersion) · iOS 版")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(palette.textDim)
                            .padding(.top, 5)

                        Text(Self.aboutDescription)
                            .font(.system(size: 13))
                            .lineSpacing(7)
                            .multilineTextAlignment(.center)
                            .foregroundStyle(palette.textMuted)
                            .padding(.top, 20)
                            .padding(.horizontal, 6)

                        GlassCard(palette: palette, padding: 0) {
                            AboutRow(
                                palette: palette,
                                label: "检查更新",
                                value: updateValue,
                                busy: updates.checking,
                                action: { Task { await updates.check(manual: true) } }
                            )
                            .disabled(updates.checking)

                            divider

                            AboutRow(
                                palette: palette,
                                label: "项目地址",
                                value: "github.com/Fly-Noz/ComfyPilot",
                                action: {
                                    UIPasteboard.general.string = Self.projectURL
                                    store.showToast("已复制项目地址")
                                }
                            )

                            divider

                            AboutRow(
                                palette: palette,
                                label: "开源许可证",
                                value: Self.licenseName,
                                action: { licenseOpen = true }
                            )
                        }
                        .padding(.top, 22)

                        Text(Self.aiFootnote)
                            .font(.system(size: 10.5))
                            .foregroundStyle(palette.textDim)
                            .padding(.top, 18)
                    }
                    .padding(.horizontal, 18)
                    .padding(.bottom, 28)
                }
            }
            .navigationTitle("关于")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        dismiss()
                    } label: {
                        HStack(spacing: 3) {
                            Image(systemName: "chevron.left")
                                .font(.system(size: 12, weight: .semibold))
                            Text("返回")
                                .font(.system(size: 15))
                        }
                        .foregroundStyle(palette.text)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .sheet(isPresented: $licenseOpen) {
            LicenseSheet(palette: palette, text: Self.licenseBody)
        }
    }

    private var divider: some View {
        Rectangle()
            .fill(palette.border)
            .frame(height: 1)
            .padding(.leading, 14)
    }

    private var updateValue: String {
        if updates.checking { return "检查中…" }
        return updates.manualNote.isEmpty ? "当前 \(bundleVersion)" : updates.manualNote
    }

    private var bundleVersion: String {
        let short = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "—"
        return "\(short) (\(build))"
    }
}

/// 行：左侧标题 + 副标题，右侧箭头或进度。和设置页的行风格一致。
private struct AboutRow: View {
    let palette: SpectrumPalette
    let label: String
    let value: String
    var busy: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                        .font(.system(size: 13))
                        .foregroundStyle(palette.text)
                    Text(value)
                        .font(.system(size: 10.5))
                        .foregroundStyle(palette.textDim)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                if busy {
                    ProgressView()
                        .controlSize(.small)
                        .tint(palette.accent)
                } else {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(palette.textDim)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// 开源许可证浮层。
private struct LicenseSheet: View {
    let palette: SpectrumPalette
    let text: String

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()
            VStack(spacing: 16) {
                Text("开源许可证")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(palette.text)
                    .padding(.top, 26)

                GlassCard(palette: palette) {
                    Text(text)
                        .font(.system(size: 12.5))
                        .lineSpacing(6)
                        .foregroundStyle(palette.textMuted)
                }

                Button {
                    dismiss()
                } label: {
                    Text("关闭")
                        .font(.system(size: 14, weight: .semibold))
                        .frame(maxWidth: .infinity)
                        .frame(height: 44)
                        .background(
                            RoundedRectangle(cornerRadius: 13, style: .continuous)
                                .fill(palette.cardStrong)
                        )
                        .foregroundStyle(palette.text)
                }
                .buttonStyle(.plain)

                Spacer(minLength: 0)
            }
            .padding(.horizontal, 18)
        }
        .presentationDetents([.medium, .large])
    }
}
