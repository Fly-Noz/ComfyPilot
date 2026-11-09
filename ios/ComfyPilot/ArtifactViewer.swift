import SwiftUI

/// 产物查看器。图片走 AsyncImage 直接取 `/view`，视频先只给占位——
/// AVPlayer 那条路等真机验证过再加，别把没验证的东西摆上去。
struct ArtifactViewer: View {
    let task: TaskRow
    let baseUrl: String
    let palette: SpectrumPalette

    @Environment(\.dismiss) private var dismiss

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
            if artifact.isImage {
                AsyncImage(url: url(for: artifact)) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFit()
                    case .failure:
                        placeholder("加载失败", icon: "exclamationmark.triangle")
                    default:
                        placeholder("加载中", icon: "photo")
                    }
                }
                .frame(maxWidth: .infinity)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(palette.cardStrong)
                )
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            } else {
                placeholder(
                    artifact.isVideo ? "视频：暂不支持播放" : artifact.displayName,
                    icon: artifact.isVideo ? "video" : "doc"
                )
            }

            Text(artifact.filename)
                .font(.system(size: 10, design: .monospaced))
                .foregroundStyle(palette.textDim)
                .lineLimit(1)
                .truncationMode(.middle)
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
