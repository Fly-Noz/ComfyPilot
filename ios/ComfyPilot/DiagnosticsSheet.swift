import SwiftUI

/// 诊断页：只放连接层的事实，不做第二个调试台（照 `DebugScreen.kt` 的定位）。
///
/// 这些值都来自 250ms 的状态帧，真机上出问题时报这里的两三个数字就能定位：
/// 是不是没连上、有没有收到 WS 帧、预览有没有字节、节点定义拉了几个。
struct DiagnosticsSheet: View {
    let palette: SpectrumPalette
    @EnvironmentObject private var store: SessionStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            SpectrumBackdrop(palette: palette)

            VStack(spacing: 0) {
                SpectrumTopBar(palette: palette, title: "诊断", onBack: { dismiss() }) {
                    TopBarAction(text: "关闭", palette: palette) { dismiss() }
                }

                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        GlassCard(palette: palette, padding: 14) {
                            VStack(alignment: .leading, spacing: 6) {
                                row("阶段", store.snapshot.phase)
                                row("标签", store.snapshot.label.isEmpty ? "—" : store.snapshot.label)
                                row("地址", store.snapshot.baseUrl.isEmpty ? "—" : store.snapshot.baseUrl)
                                row("网络", networkText)
                                row("已校验", store.snapshot.validated ? "是" : "否")
                                row("连接代数", "\(store.snapshot.generation)")
                                row("队列", "运行 \(store.snapshot.queueRunning) / 等待 \(store.snapshot.queuePending)")
                            }
                        }

                        GlassCard(palette: palette, padding: 14) {
                            VStack(alignment: .leading, spacing: 6) {
                                row("WS 文本帧", "\(store.snapshot.wsTextFrames)")
                                row("WS 二进制帧", "\(store.snapshot.wsBinaryFrames)")
                                row(
                                    "预览",
                                    store.snapshot.previewBytes == 0
                                        ? "无"
                                        : "\(store.snapshot.previewBytes) 字节 · #\(store.snapshot.previewNodeId)"
                                )
                                row("动画帧", "\(store.snapshot.animationCount)（\(store.snapshot.animationRate) fps）")
                                row("节点定义", "\(store.snapshot.nodeInfoCount) 个类")
                                row("当前工作流", store.snapshot.workflowPath.isEmpty ? "—" : store.snapshot.workflowPath)
                                row("任务数", "\(store.snapshot.tasks.count)")
                            }
                        }

                        if !store.notices.isEmpty {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("最近事件")
                                    .font(.system(size: 9.5, design: .monospaced))
                                    .tracking(1.1)
                                    .foregroundStyle(palette.textDim)

                                GlassCard(palette: palette, padding: 12) {
                                    VStack(alignment: .leading, spacing: 5) {
                                        ForEach(Array(store.notices.prefix(12).enumerated()), id: \.offset) { index, notice in
                                            Text("\(index == 0 ? "›" : "·") \(notice)")
                                                .font(.system(size: 10.5, design: .monospaced))
                                                .foregroundStyle(index == 0 ? palette.textMuted : palette.textDim)
                                                .lineLimit(2)
                                        }
                                    }
                                }
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

    private var networkText: String {
        switch store.snapshot.network {
        case "wifi": return "WIFI"
        case "cellular": return "蜂窝"
        case "ethernet": return "以太网"
        case "none": return "无网络"
        default: return "其他"
        }
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text(label)
                .font(.system(size: 10.5))
                .foregroundStyle(palette.textDim)
                .frame(width: 74, alignment: .leading)
            Text(value)
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(palette.textMuted)
                .lineLimit(2)
            Spacer(minLength: 0)
        }
    }
}
