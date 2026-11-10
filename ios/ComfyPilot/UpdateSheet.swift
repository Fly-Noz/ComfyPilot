import Foundation
import SwiftUI

/// iOS 更新提示。自签 IPA 不能自动安装，这里只负责展示变更并打开下载页。
struct UpdateSheet: View {
    let info: UpdateInfo
    let palette: SpectrumPalette
    let onLater: () -> Void
    let onOpen: (String?) -> Void

    private var currentVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
    }

    var body: some View {
        ZStack {
            SpectrumBackdrop(palette: palette)

            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(alignment: .top, spacing: 10) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("发现新版本 \(info.versionName)")
                                .font(.system(size: 19, weight: .bold))
                                .foregroundStyle(palette.text)
                            Text("当前 \(currentVersion) · 最新 \(info.versionName)")
                                .font(.system(size: 10.5))
                                .foregroundStyle(palette.textDim)
                        }
                        Spacer(minLength: 0)
                        Button {
                            onLater()
                        } label: {
                            Image(systemName: "xmark")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(palette.textMuted)
                                .padding(9)
                                .background(Circle().fill(palette.cardStrong))
                        }
                        .buttonStyle(.plain)
                    }

                    if !info.notes.isEmpty {
                        GlassCard(palette: palette, padding: 12) {
                            Text(info.notes)
                                .font(.system(size: 12.5))
                                .lineSpacing(5)
                                .foregroundStyle(palette.textMuted)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }

                    VStack(alignment: .leading, spacing: 5) {
                        Text("iOS 版是未签名 IPA")
                            .font(.system(size: 11.5, weight: .medium))
                            .foregroundStyle(palette.warn)
                        Text("下载后需要自己用 Sideloadly / AltStore 等工具签名安装；手机直接点 IPA 不会有反应。")
                            .font(.system(size: 10.5))
                            .lineSpacing(3)
                            .foregroundStyle(palette.textDim)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(11)
                    .background(palette.warnSoft)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .stroke(palette.warn.opacity(0.6), lineWidth: 1)
                    )

                    HStack(spacing: 8) {
                        GhostButton(title: "稍后", palette: palette) {
                            onLater()
                        }
                        Spacer(minLength: 0)
                        if let github = info.github, !github.isEmpty {
                            GhostButton(title: "GitHub", palette: palette) {
                                onOpen(github)
                            }
                        }
                        if let gitee = info.gitee, !gitee.isEmpty {
                            GradientButton(
                                title: "Gitee",
                                palette: palette,
                                height: 40,
                                expand: false
                            ) {
                                onOpen(gitee)
                            }
                        }
                    }
                    .padding(.top, 2)
                }
                .padding(.horizontal, 20)
                .padding(.top, 22)
                .padding(.bottom, 26)
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}
