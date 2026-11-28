import SwiftUI

/// 连接页的图文教程。图片来自随包的 5 张教程幻灯片。
struct ConnectTutorialView: View {
    let palette: SpectrumPalette
    let onClose: () -> Void

    private struct Step: Identifiable {
        let id: Int
        let title: String
        let body: String
        let image: String
    }

    private static let steps: [Step] = [
        Step(id: 1, title: "1. 下载启动脚本",
             body: "打开 GitHub Release 的 Assets，下载 run_remote.bat。不要用 run_local.bat：它只监听 127.0.0.1，手机连不上。",
             image: "TutorialConnectDownload"),
        Step(id: 2, title: "2. 放进 ComfyUI 根目录",
             body: "和 ComfyUI、python_embedded 两个文件夹放在同一层，不是放进 ComfyUI 文件夹里面。放错会提示找不到 Python 或 main.py。",
             image: "TutorialConnectPlace"),
        Step(id: 3, title: "3. 双击运行",
             body: "首次运行 Windows 防火墙弹窗时点「允许访问」，建议勾选专用网络。黑色控制台窗口不要关，关了 ComfyUI 就停了。",
             image: "TutorialConnectRun"),
        Step(id: 4, title: "4. 手机填写地址",
             body: "在控制台找到 http://IP:8188，填回本页并点连接。同一 Wi-Fi 选 192.168.x.x；不在同一网络时，电脑和手机都要安装 Tailscale（或 ZeroTier 等）并登录同一账号，再选 100.x 地址。只装一端连不上。",
             image: "TutorialConnectAddress"),
        Step(id: 5, title: "5. 看到工作流列表",
             body: "手机显示「已连接」，并且能刷新服务器工作流，就成功了。连不上先检查：同一网络、防火墙、地址是否包含 http:// 和 :8188。",
             image: "TutorialConnectSuccess"),
    ]

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color.black.opacity(0.45)
                    .ignoresSafeArea()
                    .onTapGesture { onClose() }
                VStack(spacing: 0) {
                    HStack(spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("连接 ComfyUI 教程")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundStyle(palette.text)
                                .accessibilityIdentifier("tutorial-title")
                            Text("主机端 4 步，约 2 分钟")
                                .font(.system(size: 10.5, design: .monospaced))
                                .foregroundStyle(palette.textDim)
                        }
                        Spacer(minLength: 0)
                        Button("关闭") { onClose() }
                            .font(.system(size: 12.5, weight: .medium))
                            .foregroundStyle(palette.accent)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 14)
                    .padding(.bottom, 6)

                    ScrollView {
                        VStack(alignment: .leading, spacing: 14) {
                            Text("不用改 ComfyUI 配置，也不用装插件。手机和电脑在同一 Wi-Fi 可直接访问；如果要走公网，必须使用 Tailscale、ZeroTier 等组网工具，并且电脑和手机两端都要安装并登录同一账号。")
                                .font(.system(size: 12.5))
                                .lineSpacing(5)
                                .foregroundStyle(palette.textMuted)
                                .padding(14)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(RoundedRectangle(cornerRadius: 14).fill(palette.card))
                                .overlay(RoundedRectangle(cornerRadius: 14).stroke(palette.border, lineWidth: 1))

                            ForEach(Self.steps) { step in
                                VStack(alignment: .leading, spacing: 8) {
                                    Text(step.title)
                                        .font(.system(size: 14, weight: .semibold))
                                        .foregroundStyle(palette.text)
                                    Text(step.body)
                                        .font(.system(size: 12))
                                        .lineSpacing(5)
                                        .foregroundStyle(palette.textMuted)
                                    Image(step.image)
                                        .resizable()
                                        .aspectRatio(16 / 9, contentMode: .fit)
                                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                                .stroke(palette.border, lineWidth: 1)
                                        )
                                }
                            }

                            VStack(alignment: .leading, spacing: 6) {
                                Text("连不上时按顺序检查")
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundStyle(palette.text)
                                Text("① 手机和主机是否在同一 Wi-Fi；公网访问时两端是否都装了 Tailscale 等工具并登录同一账号；\n② 控制台列出的 IP 是否抄全，末尾是否有 :8188；\n③ Windows 防火墙是否允许了 run_remote.bat；\n④ 是否误用了 run_local.bat。")
                                    .font(.system(size: 12))
                                    .lineSpacing(5)
                                    .foregroundStyle(palette.textMuted)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(14)
                            .background(RoundedRectangle(cornerRadius: 14).fill(palette.card))
                            .overlay(RoundedRectangle(cornerRadius: 14).stroke(palette.border, lineWidth: 1))
                        }
                        .padding(16)
                    }
                }
                .frame(width: proxy.size.width * 0.94, height: proxy.size.height * 0.92)
                .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(palette.dialog))
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .stroke(palette.borderStrong, lineWidth: 1)
                )
            }
        }
    }
}
