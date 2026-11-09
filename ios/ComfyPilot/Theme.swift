import SwiftUI

// 移植自 app/src/main/kotlin/com/comfypilot/app/ui/theme/Spectrum.kt 与 SpectrumUi.kt。
// 数值一律照抄实现，不做"看起来差不多"的估算——两端要的是同一套规格。

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0,
            opacity: alpha
        )
    }
}

struct SpectrumPalette {
    let isDark: Bool
    let background: Color
    let glowTop: Color
    let glowSide: Color
    let glowBottom: Color
    let card: Color
    let cardStrong: Color
    let dialog: Color
    let border: Color
    let borderStrong: Color
    let text: Color
    let textMuted: Color
    let textDim: Color
    let accent: Color
    let accentInk: Color
    let accentSoft: Color
    let accentBorder: Color
    let ok: Color
    let okSoft: Color
    let warn: Color
    let warnSoft: Color
    let err: Color
    let errSoft: Color
    let gradient: [Color]
    let barTrack: Color

    /// 与 Brush.linearGradient(gradient) 一致：三色横向渐变。
    var gradientBrush: LinearGradient {
        LinearGradient(colors: gradient, startPoint: .leading, endPoint: .trailing)
    }
}

enum Spectrum {
    static let dark = SpectrumPalette(
        isDark: true,
        background: Color(hex: 0x0A0B10),
        glowTop: Color(hex: 0x8B5CF6, alpha: 0.38),
        glowSide: Color(hex: 0x5EEAD4, alpha: 0.22),
        glowBottom: Color(hex: 0xF472B6, alpha: 0.24),
        card: Color(hex: 0xFFFFFF, alpha: 0.055),
        cardStrong: Color(hex: 0xFFFFFF, alpha: 0.094),
        dialog: Color(hex: 0x14151B),
        border: Color(hex: 0xFFFFFF, alpha: 0.09),
        borderStrong: Color(hex: 0xFFFFFF, alpha: 0.16),
        text: Color(hex: 0xF3F4F8),
        textMuted: Color(hex: 0xA9AEBE),
        textDim: Color(hex: 0x7C8196),
        accent: Color(hex: 0xA78BFA),
        accentInk: Color(hex: 0x0B0614),
        accentSoft: Color(hex: 0x8B5CF6, alpha: 0.16),
        accentBorder: Color(hex: 0x8B5CF6, alpha: 0.44),
        ok: Color(hex: 0x5EEAD4),
        okSoft: Color(hex: 0x5EEAD4, alpha: 0.10),
        warn: Color(hex: 0xFBBF24),
        warnSoft: Color(hex: 0xFBBF24, alpha: 0.10),
        err: Color(hex: 0xFB7185),
        errSoft: Color(hex: 0xFB7185, alpha: 0.12),
        gradient: [Color(hex: 0x5EEAD4), Color(hex: 0x8B5CF6), Color(hex: 0xF472B6)],
        barTrack: Color(hex: 0xFFFFFF, alpha: 0.078)
    )

    static let light = SpectrumPalette(
        isDark: false,
        background: Color(hex: 0xF5F4F8),
        glowTop: Color(hex: 0x8B5CF6, alpha: 0.26),
        glowSide: Color(hex: 0x2DD4BF, alpha: 0.30),
        glowBottom: Color(hex: 0xF472B6, alpha: 0.24),
        card: Color(hex: 0xFFFFFF, alpha: 0.65),
        cardStrong: Color(hex: 0xFFFFFF, alpha: 0.85),
        dialog: Color(hex: 0xFFFFFF),
        border: Color(hex: 0x14151A, alpha: 0.09),
        borderStrong: Color(hex: 0x14151A, alpha: 0.16),
        text: Color(hex: 0x14151A),
        textMuted: Color(hex: 0x545666),
        textDim: Color(hex: 0x6F7182),
        accent: Color(hex: 0x7C3AED),
        accentInk: Color(hex: 0xFFFFFF),
        accentSoft: Color(hex: 0x7C3AED, alpha: 0.10),
        accentBorder: Color(hex: 0x7C3AED, alpha: 0.30),
        ok: Color(hex: 0x0B7F6A),
        okSoft: Color(hex: 0x0B7F6A, alpha: 0.08),
        warn: Color(hex: 0xA16207),
        warnSoft: Color(hex: 0xA16207, alpha: 0.08),
        err: Color(hex: 0xBE123C),
        errSoft: Color(hex: 0xBE123C, alpha: 0.08),
        gradient: [Color(hex: 0x2DD4BF), Color(hex: 0x7C3AED), Color(hex: 0xDB2777)],
        barTrack: Color(hex: 0x14151A, alpha: 0.078)
    )

    static func palette(for scheme: ColorScheme) -> SpectrumPalette {
        scheme == .dark ? dark : light
    }
}

/// SpectrumBackdrop：底色 + 三层色晕，圆心与半径照抄 Compose 版本。
/// 那边有 6.5 秒一次的呼吸动画，这里先留静态——不影响布局对齐。
struct SpectrumBackdrop: View {
    let palette: SpectrumPalette

    var body: some View {
        Canvas { context, size in
            context.fill(
                Path(CGRect(origin: .zero, size: size)),
                with: .color(palette.background)
            )

            func glow(_ color: Color, _ cx: CGFloat, _ cy: CGFloat, _ radius: CGFloat) {
                let rect = CGRect(
                    x: cx - radius,
                    y: cy - radius,
                    width: radius * 2,
                    height: radius * 2
                )
                context.fill(
                    Path(ellipseIn: rect),
                    with: .radialGradient(
                        Gradient(colors: [color, color.opacity(0)]),
                        center: CGPoint(x: cx, y: cy),
                        startRadius: 0,
                        endRadius: radius
                    )
                )
            }

            let w = size.width
            let h = size.height
            glow(palette.glowTop, w * 0.86, -h * 0.01, w * 1.0)
            glow(palette.glowSide, w * 0.02, h * 0.13, w * 0.95)
            glow(palette.glowBottom, w * 0.55, h * 1.02, w * 1.1)
        }
        .ignoresSafeArea()
    }
}

// MARK: - 基础组件

/// GlassCard：圆角 18、内边距 14、card 填充 + 1pt border。
struct GlassCard<Content: View>: View {
    let palette: SpectrumPalette
    var padding: CGFloat = 14
    var strong: Bool = false
    let content: Content

    init(
        palette: SpectrumPalette,
        padding: CGFloat = 14,
        strong: Bool = false,
        @ViewBuilder content: () -> Content
    ) {
        self.palette = palette
        self.padding = padding
        self.strong = strong
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(strong ? palette.cardStrong : palette.card)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(palette.border, lineWidth: 1)
            )
    }
}

/// GradientButton：高 46、圆角 14、三色渐变、字重 Bold、字距 0.2。
struct GradientButton: View {
    let title: String
    let palette: SpectrumPalette
    var height: CGFloat = 46
    var enabled: Bool = true
    /// 安卓那边 GradientButton 是内容宽度，只有传了 weight 才撑满。
    var expand: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 14, weight: .bold))
                .tracking(0.2)
                .lineLimit(1)
                .foregroundStyle(palette.accentInk)
                .padding(.horizontal, expand ? 0 : 18)
                .frame(maxWidth: expand ? .infinity : nil)
                .frame(height: height)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(palette.gradientBrush)
                )
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.42)
    }
}

/// GhostButton：高 42、圆角 14、card 填充 + borderStrong 描边、文字 textMuted 12.5。
struct GhostButton: View {
    let title: String
    let palette: SpectrumPalette
    var height: CGFloat = 42
    var selected: Bool = false
    var enabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 12.5, weight: selected ? .semibold : .regular))
                .lineLimit(1)
                .foregroundStyle(selected ? palette.text : palette.textMuted)
                .padding(.horizontal, 16)
                .frame(height: height)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(selected ? palette.cardStrong : palette.card)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(selected ? palette.accentBorder : palette.borderStrong, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
    }
}

/// SpectrumProgress：胶囊轨道 + 渐变填充。轨道用 barTrack，不是 border。
struct SpectrumProgress: View {
    let fraction: Double
    let palette: SpectrumPalette
    var height: CGFloat = 7

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(palette.barTrack)
                Capsule()
                    .fill(palette.gradientBrush)
                    .frame(width: geo.size.width * min(max(fraction, 0), 1))
            }
        }
        .frame(height: height)
    }
}

enum PillTone {
    case neutral, ok, warn, err
}

/// SpectrumPill：胶囊、内边距 8/3、圆点 5、间距 5、字号 10。
struct SpectrumPill: View {
    let text: String
    let palette: SpectrumPalette
    var tone: PillTone = .neutral

    private var foreground: Color {
        switch tone {
        case .neutral: return palette.textMuted
        case .ok: return palette.ok
        case .warn: return palette.warn
        case .err: return palette.err
        }
    }

    private var background: Color {
        switch tone {
        case .neutral: return palette.cardStrong
        case .ok: return palette.okSoft
        case .warn: return palette.warnSoft
        case .err: return palette.errSoft
        }
    }

    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(foreground).frame(width: 5, height: 5)
            Text(text)
                .font(.system(size: 10))
                .lineLimit(1)
                .foregroundStyle(foreground)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(Capsule().fill(background))
    }
}

/// SpectrumChip：胶囊、内边距 12/7、字号 11.5；选中态整块渐变 + accentInk。
struct SpectrumChip: View {
    let text: String
    let selected: Bool
    let palette: SpectrumPalette
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(.system(size: 11.5, weight: selected ? .semibold : .regular))
                .lineLimit(1)
                .foregroundStyle(selected ? palette.accentInk : palette.textMuted)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(
                    Capsule().fill(
                        selected ? AnyShapeStyle(palette.gradientBrush) : AnyShapeStyle(palette.card)
                    )
                )
                .overlay(
                    Capsule().stroke(selected ? palette.accentBorder : palette.border, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }
}

/// SpectrumTabs：容器圆角 14、内衬 3、滑块 cardStrong 圆角 11、高 38、字号 12.5。
struct SpectrumTabs: View {
    let labels: [String]
    let selected: Int
    let palette: SpectrumPalette
    let onSelect: (Int) -> Void

    var body: some View {
        HStack(spacing: 3) {
            ForEach(Array(labels.enumerated()), id: \.offset) { index, label in
                Button {
                    onSelect(index)
                } label: {
                    Text(label)
                        .font(.system(size: 12.5, weight: index == selected ? .semibold : .regular))
                        .lineLimit(1)
                        .foregroundStyle(index == selected ? palette.text : palette.textMuted)
                        .frame(maxWidth: .infinity)
                        .frame(height: 38)
                        .background(
                            RoundedRectangle(cornerRadius: 11, style: .continuous)
                                .fill(index == selected ? palette.cardStrong : Color.clear)
                        )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(3)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(palette.card)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(palette.border, lineWidth: 1)
        )
    }
}

/// SpectrumTopBar：高 ≥46、左右 12、上下 4、标题 15 Bold 字距 -0.2。
struct SpectrumTopBar<Trailing: View>: View {
    let palette: SpectrumPalette
    let title: String
    var onBack: (() -> Void)?
    var trailing: Trailing

    init(
        palette: SpectrumPalette,
        title: String,
        onBack: (() -> Void)? = nil,
        @ViewBuilder trailing: () -> Trailing
    ) {
        self.palette = palette
        self.title = title
        self.onBack = onBack
        self.trailing = trailing()
    }

    var body: some View {
        HStack(spacing: 2) {
            if let onBack {
                TopBarAction(text: "返回", palette: palette, action: onBack)
                Spacer().frame(width: 2)
            }
            Text(title)
                .font(.system(size: 15, weight: .bold))
                .tracking(-0.2)
                .lineLimit(1)
                .truncationMode(.tail)
                .foregroundStyle(palette.text)
                .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 2) { trailing }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
        .frame(minHeight: 46)
    }
}

extension SpectrumTopBar where Trailing == EmptyView {
    init(palette: SpectrumPalette, title: String, onBack: (() -> Void)? = nil) {
        self.palette = palette
        self.title = title
        self.onBack = onBack
        self.trailing = EmptyView()
    }
}

/// TopBarAction：内边距 7/7、字号 12、默认 textMuted。
struct TopBarAction: View {
    let text: String
    let palette: SpectrumPalette
    var emphasized: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(.system(size: 12))
                .foregroundStyle(emphasized ? palette.text : palette.textMuted)
                .padding(.horizontal, 7)
                .padding(.vertical, 7)
        }
        .buttonStyle(.plain)
    }
}

/// SpectrumTextField：圆角 12、card 填充 + border、内边距 12/12、字号 12.5。
struct SpectrumTextField: View {
    let placeholder: String
    @Binding var text: String
    let palette: SpectrumPalette
    var keyboard: UIKeyboardType = .default

    var body: some View {
        ZStack(alignment: .leading) {
            if text.isEmpty {
                Text(placeholder)
                    .font(.system(size: 12.5, design: .monospaced))
                    .foregroundStyle(palette.textDim)
            }
            TextField("", text: $text)
                .font(.system(size: 12.5, design: .monospaced))
                .foregroundStyle(palette.text)
                .keyboardType(keyboard)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(palette.card)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(palette.border, lineWidth: 1)
        )
    }
}
