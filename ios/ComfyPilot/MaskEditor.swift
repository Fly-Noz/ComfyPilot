import SwiftUI
import UIKit

/// 一次笔画：坐标与笔宽都在原图像素域，缩放只影响屏幕显示。
private struct MaskStroke {
    var points: [CGPoint]
    let size: CGFloat
    let erase: Bool
}

/// 蒙版编辑器状态。语义与安卓/鸿蒙一致：底图保留原 RGB，蒙版层的 alpha 表示被涂掉的量，
/// ComfyUI 的 MASK 输出 = 1 - alpha，所以导出的 PNG 是「涂抹区透明」。
final class MaskCanvasState: ObservableObject, Identifiable {
    var id: ObjectIdentifier { ObjectIdentifier(self) }

    @Published var brush: CGFloat = 34
    @Published var erasing = false
    @Published var zoom: CGFloat = 1
    @Published var pan: CGPoint = .zero
    @Published private(set) var strokeCount = 0

    /// 归一化到 scale=1 的原图，尺寸即像素尺寸。
    private let base: UIImage
    let pixelSize: CGSize

    private var maskContext: CGContext?
    private var strokes: [MaskStroke] = []
    /// 重开时恢复的历史蒙版：撤销/清空只回滚本次笔画，不能把这层一起清掉。
    private let initialMask: UIImage?

    init(base image: UIImage, initialMask: UIImage?) {
        let maxSide: CGFloat = 2048
        let longest = max(image.size.width, image.size.height)
        let factor = longest > maxSide ? maxSide / longest : 1
        let size = CGSize(
            width: max(1, round(image.size.width * factor)),
            height: max(1, round(image.size.height * factor))
        )
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        self.base = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        self.pixelSize = size
        self.initialMask = initialMask
        self.maskContext = Self.makeContext(size: size)
        if let initialMask {
            Self.drawImage(initialMask, in: CGRect(origin: .zero, size: size), into: maskContext, erase: false)
        }
    }

    private static func makeContext(size: CGSize) -> CGContext? {
        CGContext(
            data: nil,
            width: Int(size.width),
            height: Int(size.height),
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )
    }

    private static func drawImage(
        _ image: UIImage,
        in rect: CGRect,
        into context: CGContext?,
        erase: Bool
    ) {
        guard let context, let cgImage = image.cgImage else { return }
        context.saveGState()
        if erase {
            context.setBlendMode(.destinationOut)
        } else {
            context.setBlendMode(.normal)
        }
        // 必须显式画到传入的 context：UIImage.draw(in:) 只会画到「当前」上下文。
        context.draw(cgImage, in: rect)
        context.restoreGState()
    }

    /// 屏幕点 → 图像点，并夹在图像范围内。
    func imagePoint(_ point: CGPoint, viewBounds: CGRect) -> CGPoint {
        let fitted = fitScale(viewBounds: viewBounds)
        let w = pixelSize.width * fitted
        let h = pixelSize.height * fitted
        let originX = (viewBounds.width - w) / 2 + pan.x
        let originY = (viewBounds.height - h) / 2 + pan.y
        return CGPoint(
            x: min(max(0, (point.x - originX) / fitted), pixelSize.width),
            y: min(max(0, (point.y - originY) / fitted), pixelSize.height)
        )
    }

    func fitScale(viewBounds: CGRect) -> CGFloat {
        guard pixelSize.width > 0, pixelSize.height > 0,
              viewBounds.width > 0, viewBounds.height > 0 else { return 1 }
        return min(viewBounds.width / pixelSize.width, viewBounds.height / pixelSize.height) * zoom
    }

    // MARK: 笔画

    func begin(at point: CGPoint) {
        let width = max(2, brush / max(zoom, 0.01))
        strokes.append(MaskStroke(points: [point], size: width, erase: erasing))
        strokeCount = strokes.count
        redrawStroke(strokes[strokes.count - 1])
    }

    func move(to point: CGPoint) {
        guard !strokes.isEmpty else { return }
        let last = strokes[strokes.count - 1].points.last ?? point
        strokes[strokes.count - 1].points.append(point)
        drawSegment(from: last, to: point, erase: strokes[strokes.count - 1].erase, size: strokes[strokes.count - 1].size)
    }

    func endStroke() {}

    private func drawSegment(from: CGPoint, to: CGPoint, erase: Bool, size: CGFloat) {
        guard let context = maskContext else { return }
        context.saveGState()
        context.setLineCap(.round)
        context.setLineJoin(.round)
        context.setLineWidth(size)
        context.setStrokeColor(UIColor.white.cgColor)
        context.setFillColor(UIColor.white.cgColor)
        if erase {
            context.setBlendMode(.destinationOut)
        } else {
            context.setBlendMode(.normal)
        }
        context.beginPath()
        context.move(to: from)
        context.addLine(to: to)
        context.strokePath()
        if from == to {
            context.fillEllipse(in: CGRect(x: from.x - size / 2, y: from.y - size / 2, width: size, height: size))
        }
        context.restoreGState()
    }

    private func redrawStroke(_ stroke: MaskStroke) {
        guard let first = stroke.points.first else { return }
        drawSegment(from: first, to: first, erase: stroke.erase, size: stroke.size)
    }

    private func rebuild() {
        maskContext?.clear(CGRect(origin: .zero, size: pixelSize))
        if let initialMask {
            Self.drawImage(initialMask, in: CGRect(origin: .zero, size: pixelSize), into: maskContext, erase: false)
        }
        for stroke in strokes {
            for index in 1..<max(1, stroke.points.count) {
                let from = stroke.points[index - 1]
                let to = stroke.points[min(index, stroke.points.count - 1)]
                drawSegment(from: from, to: to, erase: stroke.erase, size: stroke.size)
            }
            if stroke.points.count == 1 {
                redrawStroke(stroke)
            }
        }
    }

    func undo() {
        guard !strokes.isEmpty else { return }
        strokes.removeLast()
        strokeCount = strokes.count
        rebuild()
    }

    func clear() {
        strokes.removeAll()
        strokeCount = 0
        rebuild()
    }

    // MARK: 导出

    /// 当前蒙版位图，供下次打开时恢复（RGB 无意义，只用 alpha）。
    func snapshotMask() -> UIImage? {
        guard let image = maskContext?.makeImage() else { return nil }
        return UIImage(cgImage: image, scale: 1, orientation: .up)
    }

    /// 导出给 ComfyUI 的 PNG：涂抹区透明，其余保留原图 RGB；PNG 必须保留 alpha 通道。
    func exportPNG() -> Data? {
        let rect = CGRect(origin: .zero, size: pixelSize)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let image = UIGraphicsImageRenderer(size: pixelSize, format: format).image { ctx in
            base.draw(in: rect)
            if let maskImage = maskContext?.makeImage() {
                ctx.cgContext.setBlendMode(.destinationOut)
                UIImage(cgImage: maskImage, scale: 1, orientation: .up).draw(in: rect)
                ctx.cgContext.setBlendMode(.normal)
            }
        }
        return image.pngData()
    }
}

/// 蒙版画布：UIKit 手势比 SwiftUI 的多指同时识别稳定，兼顾低端模拟器。
/// 画布视图；internal 可见，避免 UIViewRepresentable 的 associatedtype 用 private 类型。
final class MaskDrawView: UIView, UIGestureRecognizerDelegate {
    weak var state: MaskCanvasState?
    private var drawing = false
    private var pinching = false
    private var pinchStartZoom: CGFloat = 1
    private var pinchStartPan: CGPoint = .zero
    private var pinchCenter: CGPoint = .zero

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = UIColor.black.withAlphaComponent(0.85)
        // SwiftUI 加在 UIViewRepresentable 上的 accessibilityIdentifier 不会传到
        // 内层 UIView；直接在视图上设，XCUITest 才查得到画布。
        isAccessibilityElement = true
        accessibilityIdentifier = "mask-canvas"

        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        pan.delegate = self
        addGestureRecognizer(pan)

        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(handlePinch(_:)))
        pinch.delegate = self
        addGestureRecognizer(pinch)
    }

    required init?(coder: NSCoder) { nil }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        true
    }

    override func draw(_ rect: CGRect) {
        guard let state else { return }
        UIColor.black.withAlphaComponent(0.85).setFill()
        UIRectFill(rect)
        let fitted = state.fitScale(viewBounds: bounds)
        let w = state.pixelSize.width * fitted
        let h = state.pixelSize.height * fitted
        let imageRect = CGRect(
            x: (bounds.width - w) / 2 + state.pan.x,
            y: (bounds.height - h) / 2 + state.pan.y,
            width: w,
            height: h
        )
        guard let base = state.baseImage, let ctx = UIGraphicsGetCurrentContext() else { return }
        base.draw(in: imageRect)
        if let mask = state.snapshotMask()?.cgImage {
            // 只把蒙版的 alpha 当覆盖层：裁剪后用半透明白填充，观感与两端一致。
            ctx.saveGState()
            ctx.setAlpha(0.62)
            ctx.clip(to: imageRect, mask: mask)
            UIColor.white.setFill()
            ctx.fill(imageRect)
            ctx.restoreGState()
        }
    }

    @objc private func handlePinch(_ recognizer: UIPinchGestureRecognizer) {
        guard let state else { return }
        switch recognizer.state {
        case .began:
            pinching = true
            drawing = false
            pinchStartZoom = state.zoom
            pinchStartPan = state.pan
            pinchCenter = recognizer.location(in: self)
        case .changed:
            let next = min(5, max(1, pinchStartZoom * recognizer.scale))
            state.zoom = next
            let moved = recognizer.location(in: self)
            state.pan = CGPoint(
                x: pinchStartPan.x + (moved.x - pinchCenter.x),
                y: pinchStartPan.y + (moved.y - pinchCenter.y)
            )
            setNeedsDisplay()
        case .ended, .cancelled, .failed:
            pinching = false
        @unknown default:
            pinching = false
        }
    }

    @objc private func handlePan(_ recognizer: UIPanGestureRecognizer) {
        guard let state else { return }
        switch recognizer.state {
        case .began:
            if pinching { return }
            drawing = true
            state.begin(at: state.imagePoint(recognizer.location(in: self), viewBounds: bounds))
        case .changed:
            if !drawing { return }
            state.move(to: state.imagePoint(recognizer.location(in: self), viewBounds: bounds))
            setNeedsDisplay()
        case .ended, .cancelled, .failed:
            if drawing { state.endStroke() }
            drawing = false
        @unknown default:
            drawing = false
        }
    }
}

extension MaskCanvasState {
    /// 供画布读取归一化后的底图；对外仍保持 private 存储。
    fileprivate var baseImage: UIImage? { base }
}

struct MaskCanvasView: UIViewRepresentable {
    @ObservedObject var state: MaskCanvasState

    func makeUIView(context: Context) -> MaskDrawView {
        let view = MaskDrawView()
        view.state = state
        return view
    }

    func updateUIView(_ uiView: MaskDrawView, context: Context) {
        uiView.setNeedsDisplay()
    }
}

/// 蒙版编辑器浮层。工具、滑块、缩放范围都与安卓/鸿蒙对齐。
struct MaskEditorSheet: View {
    @ObservedObject var state: MaskCanvasState
    let palette: SpectrumPalette
    let onSave: (Data) -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button("返回") { onCancel() }
                    .foregroundStyle(palette.textMuted)
                    .accessibilityIdentifier("mask-back")
                Spacer(minLength: 0)
                Text("编辑蒙版")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(palette.text)
                Spacer(minLength: 0)
                Button("保存") {
                    if let data = state.exportPNG() { onSave(data) }
                }
                .foregroundStyle(palette.accent)
                .accessibilityIdentifier("mask-save")
            }
            .padding(.horizontal, 16)
            .frame(height: 48)

            MaskCanvasView(state: state)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityIdentifier("mask-canvas")

            VStack(spacing: 12) {
                HStack(spacing: 10) {
                    toolButton(title: "画笔", symbol: "paintbrush", active: !state.erasing) {
                        state.erasing = false
                    }
                    .accessibilityIdentifier("mask-brush")
                    toolButton(title: "橡皮", symbol: "eraser", active: state.erasing) {
                        state.erasing = true
                    }
                    .accessibilityIdentifier("mask-eraser")
                    toolButton(title: "撤销", symbol: "arrow.uturn.backward", active: false) {
                        state.undo()
                    }
                    .accessibilityIdentifier("mask-undo")
                    toolButton(title: "清空", symbol: "trash", active: false) {
                        state.clear()
                    }
                    .accessibilityIdentifier("mask-clear")
                }

                HStack(spacing: 10) {
                    Text("笔刷")
                        .font(.system(size: 11))
                        .foregroundStyle(palette.textMuted)
                    Slider(value: $state.brush, in: 8...100)
                        .tint(palette.accent)
                        .accessibilityIdentifier("mask-brush-slider")
                    Text("\(Int(state.brush))")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(palette.textDim)
                        .frame(width: 26, alignment: .trailing)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
        }
        .background(palette.background)
        .accessibilityIdentifier("mask-editor")
    }

    @ViewBuilder
    private func toolButton(title: String, symbol: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: symbol)
                    .font(.system(size: 17))
                Text(title)
                    .font(.system(size: 10))
            }
            .frame(maxWidth: .infinity, minHeight: 50)
            .foregroundStyle(active ? palette.accent : palette.textMuted)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(active ? palette.cardStrong : palette.card)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(active ? palette.accentBorder : palette.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}

/// 蒙版文件名：沿用安卓语义（原 basename + `_mask`），加时间戳与随机后缀避免覆盖。
enum MaskNaming {
    static func filename(for name: String) -> String {
        let base = (name as NSString).deletingPathExtension
        let stem = base.hasSuffix("_mask") ? base : "\(base)_mask"
        let stamp = String(Int64(Date().timeIntervalSince1970 * 1000), radix: 36)
        let salt = String(Int.random(in: 0..<1_000_000), radix: 36)
        return "\(stem)-\(stamp)-\(salt).png"
    }
}
