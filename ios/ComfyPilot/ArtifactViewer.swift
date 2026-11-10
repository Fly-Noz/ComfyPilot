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

                ScrollView {
                    VStack(spacing: 14) {
                        ForEach(task.artifacts) { artifact in
                            artifactCard(artifact)
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.bottom, 26)
                }
            }
        }
        .sheet(item: $shareItem) { item in
            ShareSheet(items: [item.url])
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(task.name.isEmpty ? "产物" : task.name)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(palette.text)
                    .lineLimit(1)
                Text("\(task.artifacts.count) 个文件")
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

                if artifact.isImage {
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
            let rawName = artifact.filename.isEmpty ? "artifact" : artifact.filename
            // 服务器给的文件名只取最后一段，防止 ../ 或子目录把文件写出临时目录；
            // 每次放进独立子目录，避免不同产物同名互相覆盖。
            let normalized = rawName.replacingOccurrences(of: "\\", with: "/")
            let last = (normalized as NSString).lastPathComponent
            let name = (last.isEmpty || last == "." || last == "..") ? "artifact" : last
            let folder = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString, isDirectory: true)
            let tmp = folder.appendingPathComponent(name)
            do {
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                try data.write(to: tmp, options: .atomic)
                DispatchQueue.main.async {
                    saving = nil
                    shareItem = ShareItem(url: tmp)
                }
            } catch {
                DispatchQueue.main.async {
                    saving = nil
                    saveNotes[artifact.id] = "写入临时文件失败"
                }
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
            guard let data, let image = UIImage(data: data) else {
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
