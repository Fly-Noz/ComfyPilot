import SwiftUI

/// 主机状态条：照 `ServerStatsStrip.kt` 的交互（一行摘要，点开铺两列明细）。
///
/// 数据来源只接了 WebSocket 推来的 Crystools 采样（`monitor` 字段），
/// 安卓那边还有 `/system_stats` 轮询兜底与“释放显存”按钮，iOS 这版没接：
/// 前者要多一条 HTTP 轮询，后者是有副作用的 POST，等真机验证过再加。
struct ServerStatsStrip: View {
    let palette: SpectrumPalette
    let snapshot: SessionSnapshot

    @State private var expanded = false

    var body: some View {
        let cells = statCells
        if !cells.isEmpty {
            VStack(spacing: 0) {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() }
                } label: {
                    HStack(spacing: 8) {
                        Text("主机状态")
                            .font(.system(size: 11.5))
                            .tracking(0.6)
                            .foregroundStyle(palette.textMuted)

                        Spacer(minLength: 0)

                        if !summary.isEmpty {
                            Text(summary)
                                .font(.system(size: 10.5, design: .monospaced))
                                .foregroundStyle(palette.textDim)
                                .lineLimit(1)
                        }

                        Text(expanded ? "收起 ▾" : "展开 ▸")
                            .font(.system(size: 10))
                            .foregroundStyle(palette.textDim)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                }
                .buttonStyle(.plain)

                if expanded {
                    // 两列 × N 行，等宽平分；和安卓的 chunked(2) 一致。
                    VStack(spacing: 2) {
                        ForEach(rows.indices, id: \.self) { index in
                            let row = rows[index]
                            HStack(spacing: 0) {
                                ForEach(row) { cell in
                                    statCell(cell)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                }
                                if row.count == 1 {
                                    Color.clear.frame(maxWidth: .infinity)
                                }
                            }
                        }
                    }
                    .padding(.leading, 12)
                    .padding(.trailing, 12)
                    .padding(.bottom, 9)
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(palette.card)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(palette.border, lineWidth: 1)
            )
        }
    }

    private struct StatValue: Identifiable {
        var id: String { label }
        let label: String
        let value: String
    }

    private var statCells: [StatValue] {
        var cells: [StatValue] = []
        if let ram = snapshot.ramPercent { cells.append(StatValue(label: "内存", value: percentText(ram))) }
        if let vram = snapshot.vramPercent { cells.append(StatValue(label: "显存", value: percentText(vram))) }
        if let cpu = snapshot.cpuPercent { cells.append(StatValue(label: "CPU", value: percentText(cpu))) }
        if let gpu = snapshot.gpuPercent { cells.append(StatValue(label: "GPU", value: percentText(gpu))) }
        if let temp = snapshot.temperature {
            cells.append(StatValue(label: "温度", value: "\(Int(temp.rounded()))°C"))
        }
        if !snapshot.gpuName.isEmpty { cells.append(StatValue(label: "显卡", value: snapshot.gpuName)) }
        if snapshot.queueRunning + snapshot.queuePending > 0 || snapshot.isConnected {
            cells.append(
                StatValue(label: "队列", value: "\(snapshot.queueRunning) / \(snapshot.queuePending)")
            )
        }
        return cells
    }

    private var rows: [[StatValue]] {
        stride(from: 0, to: statCells.count, by: 2).map { index in
            Array(statCells[index..<min(index + 2, statCells.count)])
        }
    }

    /// 收起时只留两条最有用的：内存、显存；没有就退回 CPU 与温度。
    private var summary: String {
        let cells = statCells
        var parts: [String] = []
        if let cell = cells.first(where: { $0.label == "内存" }) { parts.append("内存 \(cell.value)") }
        if let cell = cells.first(where: { $0.label == "显存" }) { parts.append("显存 \(cell.value)") }
        if parts.isEmpty {
            if let cell = cells.first(where: { $0.label == "CPU" }) { parts.append("CPU \(cell.value)") }
            if let cell = cells.first(where: { $0.label == "温度" }) { parts.append(cell.value) }
        }
        return parts.joined(separator: " · ")
    }

    private func statCell(_ cell: StatValue) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(cell.label)
                .font(.system(size: 9, design: .monospaced))
                .tracking(0.9)
                .foregroundStyle(palette.textDim)
            Text(cell.value)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
    }

    private func percentText(_ value: Double) -> String {
        "\(Int(value.rounded()))%"
    }
}
