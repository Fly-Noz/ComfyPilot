import SwiftUI

/// 只读节点图。对应安卓从节点卡「连线」图标打开的那层浮层。
///
/// 坐标和连线都来自共享层的 `graph` 字段（已按本次会话的开关算过模式），
/// Swift 只负责缩放和画线，不重算任何规则。
/// 交互只有缩放与平移：这是「看一眼结构」的面板，不做编辑。
struct NodeGraphSheet: View {
    let palette: SpectrumPalette
    let graph: WorkflowGraph
    var focusNodeId: Int?

    @Environment(\.dismiss) private var dismiss
    @State private var zoom: CGFloat = 1
    @State private var lastZoom: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        ZStack {
            SpectrumBackdrop(palette: palette)

            VStack(spacing: 0) {
                SpectrumTopBar(palette: palette, title: "节点图", onBack: { dismiss() }) {
                    TopBarAction(text: "复位", palette: palette) {
                        withAnimation(.easeOut(duration: 0.2)) {
                            zoom = 1
                            lastZoom = 1
                            offset = .zero
                            lastOffset = .zero
                        }
                    }
                    TopBarAction(text: "关闭", palette: palette) { dismiss() }
                }

                if graph.nodes.isEmpty {
                    emptyState
                } else {
                    GeometryReader { geo in
                        canvas(size: geo.size)
                            .scaleEffect(zoom)
                            .offset(offset)
                            .gesture(
                                MagnificationGesture()
                                    .onChanged { value in
                                        zoom = min(max(lastZoom * value, 0.5), 6)
                                    }
                                    .onEnded { _ in lastZoom = zoom }
                            )
                            .simultaneousGesture(
                                DragGesture()
                                    .onChanged { value in
                                        offset = CGSize(
                                            width: lastOffset.width + value.translation.width,
                                            height: lastOffset.height + value.translation.height
                                        )
                                    }
                                    .onEnded { _ in lastOffset = offset }
                            )
                    }
                    .clipped()

                    legend
                }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "point.topleft.down.curvedto.point.bottomright.up")
                .font(.system(size: 26, weight: .light))
                .foregroundStyle(palette.textDim)
            Text("这个工作流没有节点图数据")
                .font(.system(size: 12))
                .foregroundStyle(palette.textMuted)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func canvas(size: CGSize) -> some View {
        // 先按节点包围盒算一个「铺满但不溢出」的基准比例，用户缩放叠加在它上面。
        let bounds = graphBounds
        let fit = min(
            (size.width - 32) / max(bounds.width, 1),
            (size.height - 32) / max(bounds.height, 1)
        )
        let scale = min(max(fit, 0.05), 1.6)
        let originX = (size.width - bounds.width * scale) / 2 - bounds.minX * scale
        let originY = (size.height - bounds.height * scale) / 2 - bounds.minY * scale

        return Canvas { context, _ in
            for link in graph.links {
                guard let path = linkPath(link, scale: scale, originX: originX, originY: originY) else {
                    continue
                }
                context.stroke(
                    path,
                    with: .color(palette.borderStrong),
                    style: StrokeStyle(lineWidth: 1, lineCap: .round)
                )
            }

            for node in graph.nodes {
                let rect = CGRect(
                    x: originX + node.x * scale,
                    y: originY + node.y * scale,
                    width: node.width * scale,
                    height: node.height * scale
                )
                let focused = node.id == focusNodeId
                let muted = node.mode == 2 || node.mode == 4

                let shape = Path(roundedRect: rect, cornerRadius: 6 * scale)
                context.fill(shape, with: .color(muted ? palette.cardStrong : palette.card))
                context.stroke(
                    shape,
                    with: .color(focused ? palette.accent : palette.border),
                    lineWidth: focused ? 2 : 0.8
                )

                // 节点太小时只画块，不画字——画了也是糊的。
                guard rect.width > 46, rect.height > 16 else { continue }
                let text = Text(node.label)
                    .font(.system(size: max(6, min(9, node.width * scale / 12)), weight: focused ? .semibold : .regular))
                    .foregroundColor(focused ? palette.accent : palette.textMuted)
                context.draw(
                    text,
                    at: CGPoint(x: rect.minX + 5, y: rect.minY + 4),
                    anchor: .topLeading
                )
            }
        }
    }

    private var legend: some View {
        HStack(spacing: 12) {
            Text("\(graph.nodes.count) 个节点 · \(graph.links.count) 条连线")
                .font(.system(size: 9.5, design: .monospaced))
                .foregroundStyle(palette.textDim)
            Spacer(minLength: 0)
            Text("双指缩放 · 拖动平移")
                .font(.system(size: 9.5))
                .foregroundStyle(palette.textDim)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 10)
    }

    // MARK: - 几何

    private var graphBounds: CGRect {
        var minX = Double.greatestFiniteMagnitude
        var minY = Double.greatestFiniteMagnitude
        var maxX = -Double.greatestFiniteMagnitude
        var maxY = -Double.greatestFiniteMagnitude
        for node in graph.nodes {
            minX = min(minX, node.x)
            minY = min(minY, node.y)
            maxX = max(maxX, node.x + node.width)
            maxY = max(maxY, node.y + node.height)
        }
        guard minX.isFinite, minY.isFinite else { return CGRect(x: 0, y: 0, width: 1, height: 1) }
        return CGRect(x: minX, y: minY, width: max(maxX - minX, 1), height: max(maxY - minY, 1))
    }

    /// 连线端点按槽位估算：输入槽的 y 从节点顶部标题栏下开始均分，
    /// 输出槽同理从右侧出去。共享层只给了 input 的 linkId，没有每槽精确坐标，
    /// 作为只读概览这已经够看结构了。
    private func linkPath(
        _ link: GraphLink,
        scale: CGFloat,
        originX: CGFloat,
        originY: CGFloat
    ) -> Path? {
        guard
            let origin = graph.nodes.first(where: { $0.id == link.originId }),
            let target = graph.nodes.first(where: { $0.id == link.targetId })
        else { return nil }

        let header = 22.0
        let originYLocal = header + Double(link.originSlot) * 16 + 8
        let targetYLocal = header + Double(link.targetSlot) * 16 + 8

        let start = CGPoint(
            x: originX + (origin.x + origin.width) * scale,
            y: originY + (origin.y + min(originYLocal, origin.height - 4)) * scale
        )
        let end = CGPoint(
            x: originX + target.x * scale,
            y: originY + (target.y + min(targetYLocal, target.height - 4)) * scale
        )

        var path = Path()
        path.move(to: start)
        let dx = max(abs(end.x - start.x) * 0.45, 18)
        path.addCurve(
            to: end,
            control1: CGPoint(x: start.x + dx, y: start.y),
            control2: CGPoint(x: end.x - dx, y: end.y)
        )
        return path
    }
}
