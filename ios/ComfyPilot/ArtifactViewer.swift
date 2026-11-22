import AVKit
import Foundation
import SwiftUI
import UIKit

/// 产物查看器。图片走 AsyncImage 取 `/view`，视频用 AVPlayer 直接播。
///
/// 视频和图片同源：都是 `/view?filename=...`，ComfyUI 返回的 Content-Type
/// 决定播放器能不能解。VHS 的 mp4/webm 一般没问题，gif 会走图片分支。
struct ArtifactViewer: View {
    let task: TaskRow
    let baseUrl: String
    let palette: SpectrumPalette
    /// 任务页当前全部任务：用于越过当前任务边界继续看其他任务的产物。
    var tasks: [TaskRow] = []

    @Environment(\.dismiss) private var dismiss
    /// 正在下载/保存的产物 id，以及每个产物旁边的结果提示。
    @State private var saving: String?
    @State private var saveNotes: [String: String] = [:]
    @State private var shareItem: ShareItem?

    var body: some View {
        ZStack {
            SpectrumBackdrop(palette: palette)

            VStack(spacing: 10) {
                header

                if let crossNotice {
                    Text(crossNotice)
                        .font(.system(size: 10.5))
                        .foregroundStyle(palette.ok)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 20)
                }

                ScrollView {
                    VStack(spacing: 14) {
                        if let artifact = currentArtifact {
                            // 按产物 id 重建卡片：否则切到下一条时 ZoomableImage 的
                            // scale/offset 会留在上一条的缩放位置，AVPlayer 也不会重来。
                            artifactCard(artifact)
                                .id(artifact.id)
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.bottom, 26)
                }

                if groups.count > 1 || activeTask.artifacts.count > 1 {
                    switchBar
                }
            }
        }
        .onAppear {
            if activeTaskId == nil { activeTaskId = task.id }
        }
        .onChange(of: activeTask.artifacts.count) { _, count in
            // 任务刷新导致产物变少时把下标夹回范围内。
            if count > 0, index > count - 1 {
                index = count - 1
            }
        }
        .sheet(item: $shareItem) { item in
            ShareSheet(items: [item.url])
        }
    }

    @State private var index = 0
    @State private var activeTaskId: String?
    @State private var crossNotice: String?

    private var groups: [TaskRow] {
        var list = tasks
            .filter { !$0.artifacts.isEmpty }
            .sorted { $0.updatedAtMs > $1.updatedAtMs }
        if !list.contains(where: { $0.id == task.id }), !task.artifacts.isEmpty {
            list.insert(task, at: 0)
        }
        return list
    }

    private var activeTask: TaskRow {
        if let id = activeTaskId,
           let found = tasks.first(where: { $0.id == id }) ?? (task.id == id ? task : nil) {
            return found
        }
        return task
    }

    private var currentArtifact: ArtifactRef? {
        let artifacts = activeTask.artifacts
        guard !artifacts.isEmpty else { return nil }
        return artifacts[max(0, min(index, artifacts.count - 1))]
    }

    private func moveArtifact(_ delta: Int) {
        let artifacts = activeTask.artifacts
        guard !artifacts.isEmpty else { return }
        let next = index + delta
        if next >= 0, next < artifacts.count {
            index = next
            crossNotice = nil
            return
        }
        let list = groups
        guard let groupIndex = list.firstIndex(where: { $0.id == activeTask.id }) else { return }
        let targetIndex = groupIndex + delta
        guard targetIndex >= 0, targetIndex < list.count else { return }
        let target = list[targetIndex]
        guard !target.artifacts.isEmpty else { return }
        activeTaskId = target.id
        index = delta < 0 ? target.artifacts.count - 1 : 0
        crossNotice = delta < 0
            ? "已切到上一任务 · \(target.name.isEmpty ? target.id : target.name)"
            : "已切到下一任务 · \(target.name.isEmpty ? target.id : target.name)"
        let notice = crossNotice
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) {
            if crossNotice == notice { crossNotice = nil }
        }
    }

    /// 产物间切换：与安卓/鸿蒙同款「上一个 / 下一个」，首尾禁用。
    private var switchBar: some View {
        HStack(spacing: 16) {
            let list = groups
            let groupIndex = list.firstIndex(where: { $0.id == activeTask.id }) ?? 0
            switchButton(left: true, enabled: index > 0 || groupIndex > 0) {
                moveArtifact(-1)
            }
            Text("\(min(index, max(0, activeTask.artifacts.count - 1)) + 1) / \(activeTask.artifacts.count)")
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(palette.textMuted)
            switchButton(left: false,
                         enabled: index < activeTask.artifacts.count - 1 ||
                            groupIndex < list.count - 1) {
                moveArtifact(1)
            }
        }
        .padding(.bottom, 12)
    }

    private func switchButton(left: Bool, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: left ? "chevron.left" : "chevron.right")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(enabled ? palette.text : palette.textDim.opacity(0.4))
                .frame(width: 44, height: 44)
                .background(Circle().fill(palette.cardStrong))
                .overlay(Circle().stroke(palette.border, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(left ? "上一个产物" : "下一个产物")
    }

    private var header: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(activeTask.name.isEmpty ? "产物" : activeTask.name)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(palette.text)
                    .lineLimit(1)
                Text(activeTask.artifacts.count > 1
                     ? "\(min(index, max(0, activeTask.artifacts.count - 1)) + 1) / \(activeTask.artifacts.count) 个文件"
                     : "\(activeTask.artifacts.count) 个文件")
                    .font(.system(size: 10.5))
                    .foregroundStyle(palette.textDim)
            }
            Spacer()
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(palette.textMuted)
                    .padding(8)
                    .background(Circle().fill(palette.cardStrong))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 18)
        .padding(.top, 16)
        .padding(.bottom, 4)
    }

    @ViewBuilder
    private func artifactCard(_ artifact: ArtifactRef) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if artifact.isImage, let imageUrl = url(for: artifact) {
                ZoomableImage(
                    url: imageUrl,
                    palette: palette,
                    placeholder: { AnyView(placeholder($0, icon: $1)) }
                )
            } else if artifact.isVideo, let url = url(for: artifact) {
                VideoPlayer(player: AVPlayer(url: url))
                    .frame(maxWidth: .infinity)
                    .frame(height: 260)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(palette.cardStrong)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            } else if artifact.isAudio, let url = url(for: artifact) {
                AudioPlayerView(url: url, palette: palette)
                    .padding(12)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(palette.cardStrong)
                    )
            } else {
                placeholder(artifact.displayName, icon: "doc")
            }

            HStack(spacing: 8) {
                Text(artifact.filename)
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(palette.textDim)
                    .lineLimit(1)
                    .truncationMode(.middle)

                Spacer(minLength: 0)

                if artifact.isImage || artifact.isVideo || artifact.isAudio {
                    if let note = saveNotes[artifact.id] {
                        Text(note)
                            .font(.system(size: 9.5))
                            .foregroundStyle(note.hasPrefix("已") ? palette.ok : palette.err)
                            .lineLimit(1)
                    }
                    GhostButton(
                        title: saving == artifact.id ? "处理中…" : "分享",
                        palette: palette,
                        height: 30
                    ) {
                        share(artifact)
                    }
                    .disabled(saving != nil)

                    // 音频不能存相册（那是图片/视频的容器），只给分享。
                    if artifact.isImage || artifact.isVideo {
                        GhostButton(
                            title: saving == artifact.id ? "保存中…" : "保存到相册",
                            palette: palette,
                            height: 30
                        ) {
                            save(artifact)
                        }
                        .disabled(saving != nil)
                    }
                }
            }
        }
    }

    /// 先落到临时文件再交给系统分享盘：远端地址对不在同一局域网的人没意义。
    private func share(_ artifact: ArtifactRef) {
        guard let url = url(for: artifact) else {
            saveNotes[artifact.id] = "地址无效"
            return
        }
        saving = artifact.id
        saveNotes[artifact.id] = nil
        URLSession.shared.dataTask(with: url) { data, _, error in
            guard let data, !data.isEmpty else {
                DispatchQueue.main.async {
                    saving = nil
                    saveNotes[artifact.id] = error == nil ? "文件为空" : "下载失败"
                }
                return
            }
            guard let tmp = writeTemp(data: data, name: artifact.filename) else {
                DispatchQueue.main.async {
                    saving = nil
                    saveNotes[artifact.id] = "写入临时文件失败"
                }
                return
            }
            DispatchQueue.main.async {
                saving = nil
                shareItem = ShareItem(url: tmp)
            }
        }.resume()
    }

    /// 直接重新下一遍原图再存：AsyncImage 只给了 SwiftUI 的 Image，
    /// 拿不到原始字节；产物通常几百 KB，多下一次比缓存一层更简单。
    private func save(_ artifact: ArtifactRef) {
        guard let url = url(for: artifact) else {
            saveNotes[artifact.id] = "地址无效"
            return
        }
        saving = artifact.id
        saveNotes[artifact.id] = nil
        URLSession.shared.dataTask(with: url) { data, _, error in
            guard let data, !data.isEmpty else {
                DispatchQueue.main.async {
                    saving = nil
                    saveNotes[artifact.id] = error == nil ? "文件为空" : "下载失败"
                }
                return
            }
            if artifact.isVideo {
                // 视频按 video 资源写入，保留音轨与编码。
                guard let tmp = writeTemp(data: data, name: artifact.filename) else {
                    DispatchQueue.main.async {
                        saving = nil
                        saveNotes[artifact.id] = "写入临时文件失败"
                    }
                    return
                }
                PhotoSaver.saveVideo(fileURL: tmp) { note in
                    DispatchQueue.main.async {
                        saving = nil
                        saveNotes[artifact.id] = note
                    }
                }
                return
            }
            if artifact.mime == "image/gif" || artifact.filename.lowercased().hasSuffix(".gif") {
                // GIF 用原始字节保存，保动图。
                PhotoSaver.saveGIF(data: data) { note in
                    DispatchQueue.main.async {
                        saving = nil
                        saveNotes[artifact.id] = note
                    }
                }
                return
            }
            guard let image = UIImage(data: data) else {
                DispatchQueue.main.async {
                    saving = nil
                    saveNotes[artifact.id] = error == nil ? "图片解码失败" : "下载失败"
                }
                return
            }
            PhotoSaver.save(image) { note in
                DispatchQueue.main.async {
                    saving = nil
                    saveNotes[artifact.id] = note
                }
            }
        }.resume()
    }

    /// 把一个产物落进独立临时子目录：服务器文件名只取最后一段，
    /// 防止 ../ 或子目录把文件写出临时目录；每个产物独立目录避免同名覆盖。
    private func writeTemp(data: Data, name: String) -> URL? {
        let normalized = (name.isEmpty ? "artifact" : name)
            .replacingOccurrences(of: "\\", with: "/")
        let last = (normalized as NSString).lastPathComponent
        let safeName = (last.isEmpty || last == "." || last == "..") ? "artifact" : last
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let target = folder.appendingPathComponent(safeName)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try data.write(to: target, options: .atomic)
            return target
        } catch {
            return nil
        }
    }

    private func placeholder(_ text: String, icon: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 24, weight: .light))
                .foregroundStyle(palette.textDim)
            Text(text)
                .font(.system(size: 11))
                .foregroundStyle(palette.textMuted)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 44)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(palette.cardStrong)
        )
    }

    /// 共享层给的 `path` 已经带好查询参数（/view?filename=...&type=output），
    /// 这里只负责拼上主机。baseUrl 没有尾斜杠，path 有前导斜杠。
    private func url(for artifact: ArtifactRef) -> URL? {
        guard !artifact.path.isEmpty else { return nil }
        return URL(string: baseUrl + artifact.path)
    }
}

/// 可缩放的产物图：双指缩放、双击在 1x/2x 之间切换、放大后可拖动。
/// 和安卓的 `ZoomableImage` 行为一致——产地是本地推理图，经常要看细节。
struct ZoomableImage: View {
    let url: URL
    let palette: SpectrumPalette
    let placeholder: (String, String) -> AnyView

    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        AsyncImage(url: url) { phase in
            switch phase {
            case .success(let image):
                image
                    .resizable()
                    .scaledToFit()
                    .scaleEffect(scale)
                    .offset(offset)
                    .gesture(magnification)
                    .simultaneousGesture(doubleTap)
                    .simultaneousGesture(drag)
            case .failure:
                placeholder("加载失败", "exclamationmark.triangle")
            default:
                placeholder("加载中", "photo")
            }
        }
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(palette.cardStrong)
        )
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var magnification: some Gesture {
        MagnificationGesture()
            .onChanged { value in scale = min(max(lastScale * value, 1), 5) }
            .onEnded { _ in
                lastScale = scale
                if scale <= 1 {
                    offset = .zero
                    lastOffset = .zero
                }
            }
    }

    private var doubleTap: some Gesture {
        TapGesture(count: 2).onEnded {
            withAnimation(.easeOut(duration: 0.18)) {
                if scale > 1 {
                    scale = 1
                    lastScale = 1
                    offset = .zero
                    lastOffset = .zero
                } else {
                    scale = 2
                    lastScale = 2
                }
            }
        }
    }

    /// 只有放大后才允许拖动，否则会把「浏览列表」的手势抢掉。
    private var drag: some Gesture {
        DragGesture()
            .onChanged { value in
                guard scale > 1 else { return }
                offset = CGSize(
                    width: lastOffset.width + value.translation.width,
                    height: lastOffset.height + value.translation.height
                )
            }
            .onEnded { _ in lastOffset = offset }
    }
}
