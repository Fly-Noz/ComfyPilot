import SwiftUI
import UIKit

/// 系统分享盘。产物在 ComfyUI 服务器上，直接把远端地址分享出去没有意义
/// （对方不在同一局域网就打不开），所以先落到临时文件再分享——
/// 分享盘里的「存储到文件」也随之可用。
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

struct ShareItem: Identifiable {
    let id = UUID()
    let url: URL
}
