import SwiftUI

/// 备案与法律文本的公网地址。域名备案完成前先使用 IP 地址。
enum LegalLinks {
    static let privacy = URL(string: "http://123.249.88.232/privacy.html")!
    static let terms = URL(string: "http://123.249.88.232/terms.html")!
    static let icp = "豫ICP备2026059527号"
    static let miit = URL(string: "https://beian.miit.gov.cn/")!
}

/// 首次启动的隐私确认。未同意时不进入主界面。
struct PrivacyConsentView: View {
    let palette: SpectrumPalette
    @Binding var accepted: Bool
    @State private var declined = false

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()
            VStack(spacing: 16) {
                Image(systemName: "hand.raised.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(palette.accent)
                Text(declined ? "尚未同意隐私政策" : "隐私政策与用户协议")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(palette.text)
                if declined {
                    Text("需要同意《隐私政策》和《用户协议》后才能使用 ComfyPilot。")
                        .font(.system(size: 13))
                        .multilineTextAlignment(.center)
                        .foregroundStyle(palette.textMuted)
                    Button("重新查看") { declined = false }
                        .buttonStyle(.plain)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(palette.accent)
                        .padding(.top, 8)
                } else {
                    Text("首次使用前，请阅读并同意以下条款。\n\n连接信息、工作流参数和任务记录只保存在本机；连接 ComfyUI 时，图片、工作流和任务数据会发送到你填写的服务器。")
                        .font(.system(size: 13))
                        .lineSpacing(5)
                        .foregroundStyle(palette.textMuted)
                        .multilineTextAlignment(.center)
                    Link("隐私政策", destination: LegalLinks.privacy)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(palette.accent)
                    Link("用户协议", destination: LegalLinks.terms)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(palette.accent)
                    Link(LegalLinks.icp, destination: LegalLinks.miit)
                        .font(.system(size: 11))
                        .foregroundStyle(palette.textDim)
                    Button {
                        accepted = true
                    } label: {
                        Text("同意并继续")
                            .font(.system(size: 15, weight: .semibold))
                            .frame(maxWidth: .infinity)
                            .frame(height: 48)
                            .foregroundStyle(Color.white)
                            .background(
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .fill(palette.accent)
                            )
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 8)
                    Button("暂不同意") { declined = true }
                        .buttonStyle(.plain)
                        .font(.system(size: 13))
                        .foregroundStyle(palette.textDim)
                }
            }
            .padding(24)
            .frame(maxWidth: 420)
            .background(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(palette.dialog)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .stroke(palette.border, lineWidth: 1)
            )
            .padding(24)
        }
    }
}
