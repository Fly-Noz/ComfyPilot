import SwiftUI

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

/// 光谱设计语言的 iOS 版 token，取值与 `app/.../ui/theme/Spectrum.kt` 对齐。
/// 两端看同一套规范，iOS 这边不另起炉灶。
struct SpectrumPalette {
    let background: Color
    let glowTop: Color
    let glowSide: Color
    let glowBottom: Color
    let card: Color
    let cardStrong: Color
    let border: Color
    let text: Color
    let textMuted: Color
    let textDim: Color
    let accent: Color
    let accentInk: Color
    let accentSoft: Color
    let ok: Color
    let warn: Color
    let err: Color
    let gradient: [Color]
}

enum Spectrum {
    static let dark = SpectrumPalette(
        background: Color(hex: 0x0A0B10),
        glowTop: Color(hex: 0x8B5CF6, alpha: 0.38),
        glowSide: Color(hex: 0x5EEAD4, alpha: 0.22),
        glowBottom: Color(hex: 0xF472B6, alpha: 0.24),
        card: Color(hex: 0xFFFFFF, alpha: 0.055),
        cardStrong: Color(hex: 0xFFFFFF, alpha: 0.094),
        border: Color(hex: 0xFFFFFF, alpha: 0.09),
        text: Color(hex: 0xF3F4F8),
        textMuted: Color(hex: 0xA9AEBE),
        textDim: Color(hex: 0x7C8196),
        accent: Color(hex: 0xA78BFA),
        accentInk: Color(hex: 0x0B0614),
        accentSoft: Color(hex: 0x8B5CF6, alpha: 0.16),
        ok: Color(hex: 0x5EEAD4),
        warn: Color(hex: 0xFBBF24),
        err: Color(hex: 0xFB7185),
        gradient: [Color(hex: 0x5EEAD4), Color(hex: 0x8B5CF6), Color(hex: 0xF472B6)]
    )

    static let light = SpectrumPalette(
        background: Color(hex: 0xF5F4F8),
        glowTop: Color(hex: 0x8B5CF6, alpha: 0.26),
        glowSide: Color(hex: 0x2DD4BF, alpha: 0.30),
        glowBottom: Color(hex: 0xF472B6, alpha: 0.24),
        card: Color(hex: 0xFFFFFF, alpha: 0.65),
        cardStrong: Color(hex: 0xFFFFFF, alpha: 0.85),
        border: Color(hex: 0x14151A, alpha: 0.09),
        text: Color(hex: 0x14151A),
        textMuted: Color(hex: 0x545666),
        textDim: Color(hex: 0x6F7182),
        accent: Color(hex: 0x7C3AED),
        accentInk: Color(hex: 0xFFFFFF),
        accentSoft: Color(hex: 0x7C3AED, alpha: 0.10),
        ok: Color(hex: 0x0B7F6A),
        warn: Color(hex: 0xA16207),
        err: Color(hex: 0xBE123C),
        gradient: [Color(hex: 0x2DD4BF), Color(hex: 0x7C3AED), Color(hex: 0xDB2777)]
    )

    static func palette(for scheme: ColorScheme) -> SpectrumPalette {
        scheme == .dark ? dark : light
    }
}

/// 色晕背景：三个大半径光斑叠在底色上。Android 那边用 Brush 画，这里用径向渐变。
struct SpectrumBackground: View {
    let palette: SpectrumPalette

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h = geo.size.height
            ZStack {
                palette.background
                glow(palette.glowTop, size: w * 1.5)
                    .offset(x: -w * 0.25, y: -h * 0.28)
                glow(palette.glowSide, size: w * 1.1)
                    .offset(x: w * 0.45, y: h * 0.12)
                glow(palette.glowBottom, size: w * 1.2)
                    .offset(x: -w * 0.1, y: h * 0.52)
            }
            .frame(width: w, height: h)
        }
        .ignoresSafeArea()
    }

    private func glow(_ color: Color, size: CGFloat) -> some View {
        Circle()
            .fill(
                RadialGradient(
                    colors: [color, color.opacity(0)],
                    center: .center,
                    startRadius: 0,
                    endRadius: size / 2
                )
            )
            .frame(width: size, height: size)
            .blur(radius: 8)
    }
}

struct SpectrumCard: ViewModifier {
    let palette: SpectrumPalette
    var strong: Bool = false

    func body(content: Content) -> some View {
        content
            .padding(16)
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

extension View {
    func spectrumCard(_ palette: SpectrumPalette, strong: Bool = false) -> some View {
        modifier(SpectrumCard(palette: palette, strong: strong))
    }
}
