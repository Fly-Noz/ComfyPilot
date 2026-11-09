import SwiftUI
import UIKit

/// 预览页：语义照 `PreviewPane`（WorkflowDetailScreen.kt）。
///
/// 优先级：
/// 1. 这个工作流有正在跑的任务 → 实时采样帧（`ws.binary` 解出来的最近一帧）；
/// 2. 否则取最近一次任务的图片产物；
/// 3. 都没有 → 空状态。
///
/// VHS 多帧动画（`previewAnimation`）没接：要先给 IosHost 加 `previewAnimation()`
/// 并做逐帧播放，等单帧这条路过真机验证再说。
struct PreviewPane: View {
    let palette: SpectrumPalette
    let workflowPath: String
    @EnvironmentObject private var store: SessionStore

    var body: some View {
        if let active = activeTask {
            live(active)
        } else if let latest = latestTask {
            artifact(latest)
        } else {
            empty
        }
    }

    private var activeTask: TaskRow? {
        store.snapshot.tasks
            .filter { $0.isActive && ($0.workflowPath == workflowPath || workflowPath.isEmpty) }
            .max { $0.createdAtMs < $1.createdAtMs }
    }

    private var latestTask: TaskRow? {
        store.snapshot.tasks
            .filter { $0.workflowPath == workflowPath || workflowPath.isEmpty }
            .max { $0.updatedAtMs < $1.updatedAtMs }
    }

    @ViewBuilder
    private func live(_ task: TaskRow) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(palette.cardStrong)

                if let image = store.previewImage {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                } else {
                    VStack(spacing: 8) {
                        ProgressView()
                            .tint(palette.accent)
                        Text("等待第一帧")
                            .font(.system(size: 11.5))
                            .foregroundStyle(palette.textDim)
                    }
                }
            }
            .frame(maxWidth: .infinity)
            .frame(minHeight: 240)
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(palette.border, lineWidth: 1)
            )

            HStack(spacing: 8) {
                Circle()
                    .fill(palette.ok)
                    .frame(width: 5, height: 5)
                Text(liveLabel(task))
                    .font(.system(size: 10.5, design: .monospaced))
                    .foregroundStyle(palette.textMuted)
                    .lineLimit(1)

                Spacer(minLength: 0)

                if task.canCancel {
                    GhostButton(title: "取消", palette: palette, height: 34) {
                        store.cancel(task)
                    }
                }
            }
        }
    }

    private func liveLabel(_ task: TaskRow) -> String {
        var parts: [String] = ["生成中 \(Int(task.percent))%"]
        if !task.node.isEmpty { parts.append(task.node) }
        if !store.snapshot.previewNodeId.isEmpty {
            parts.append("预览 #\(store.snapshot.previewNodeId)")
        } else if store.snapshot.animationCount > 0 {
            parts.append("动画 \(store.snapshot.animationCount) 帧")
        }
        return parts.joined(separator: " · ")
    }

    @ViewBuilder
    private func artifact(_ task: TaskRow) -> some View {
        let image = task.artifacts.first { $0.isImage }
        VStack(alignment: .leading, spacing: 10) {
            if let image {
                AsyncImage(url: url(for: image)) { phase in
                    switch phase {
                    case .success(let loaded):
                        loaded
                            .resizable()
                            .scaledToFit()
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    case .failure:
                        placeholder("图片读取失败", systemImage: "exclamationmark.triangle")
                    default:
                        placeholder("读取中…", systemImage: "photo")
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(minHeight: 240)
            } else {
                placeholder("这次任务没有图片产物", systemImage: "photo.on.rectangle.angled")
            }

            HStack(spacing: 8) {
                Text(task.name.isEmpty ? task.promptId : task.name)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(palette.textMuted)
                    .lineLimit(1)

                Spacer(minLength: 0)

                Text(task.elapsedText(nowMs: Int64(Date().timeIntervalSince1970 * 1000)))
                    .font(.system(size: 9.5, design: .monospaced))
                    .foregroundStyle(palette.textDim)
            }
        }
    }

    private func placeholder(_ text: String, systemImage: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: systemImage)
                .font(.system(size: 26, weight: .light))
                .foregroundStyle(palette.textDim)
            Text(text)
                .font(.system(size: 11.5))
                .foregroundStyle(palette.textDim)
        }
        .frame(maxWidth: .infinity)
        .frame(minHeight: 240)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(palette.cardStrong)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(palette.border, lineWidth: 1)
        )
    }

    private var empty: some View {
        placeholder("还没有可预览的画面", systemImage: "sparkles")
    }

    private func url(for artifact: ArtifactRef) -> URL? {
        guard !artifact.path.isEmpty else { return nil }
        return URL(string: store.snapshot.baseUrl + artifact.path)
    }
}
