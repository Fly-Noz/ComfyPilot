import SwiftUI

/// 主机状态条：照 `ServerStatsStrip.kt` 的交互（一行摘要，点开铺两列明细）。
///
/// 数据两条来源，优先级与安卓一致：
/// 1. WebSocket 推来的 Crystools 采样（`monitor`，有 CPU/GPU/温度，但要求装了扩展）；
/// 2. `/system_stats` 轮询（官方路由，一定有内存/显存/版本）。
///
/// 轮询放在界面层而不是共享层：它只影响这一块展示，失败了也不该污染连接状态。
/// 「释放显存」对齐安卓：确认弹窗 + 队列忙/刚提交时禁用 + 30 秒冷却。
struct ServerStatsStrip: View {
    let palette: SpectrumPalette
    let snapshot: SessionSnapshot

    @State private var expanded = false
    @State private var stats: SystemStatsLite?
    @State private var errorText = ""
    @State private var freeConfirm = false
    @State private var freeing = false
    @State private var cooldownUntil: Date?
    @State private var freeNote = ""

    var body: some View {
        if !statCells.isEmpty {
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

                    if !errorText.isEmpty {
                        Text(errorText)
                            .font(.system(size: 9.5, design: .monospaced))
                            .foregroundStyle(palette.textDim)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.leading, 12)
                            .padding(.trailing, 12)
                            .padding(.bottom, 8)
                    }

                    HStack(spacing: 8) {
                        GhostButton(
                            title: freeing ? "释放中…" : "释放显存",
                            palette: palette,
                            height: 30
                        ) {
                            if let reason = freeBlockReason {
                                freeNote = reason
                            } else {
                                freeConfirm = true
                            }
                        }
                        .disabled(freeing)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 12)
                    .padding(.bottom, 6)

                    if !freeNote.isEmpty {
                        Text(freeNote)
                            .font(.system(size: 9.5))
                            .foregroundStyle(palette.textDim)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 12)
                            .padding(.bottom, 8)
                    }
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
            .task(id: snapshot.baseUrl) {
                await poll()
            }
            .alert("释放主机显存", isPresented: $freeConfirm) {
                Button("取消", role: .cancel) {}
                Button("释放", role: .destructive) { requestFreeMemory() }
            } message: {
                Text(
                    "会卸载当前加载的模型、LoRA、VAE 等，释放显存和内存。\n\n" +
                    "不会影响已保存的工作流、参数和产物。下次生成需要重新加载模型，第一轮会明显变慢。"
                )
            }
        }
    }

    /// 释放显存的不可用原因；nil 表示可以释放（对齐安卓 blockReason 的优先级）。
    private var freeBlockReason: String? {
        if snapshot.baseUrl.trimmingCharacters(in: .whitespaces).isEmpty { return "连接主机后才能释放" }
        if freeing { return "正在请求释放…" }
        if let until = cooldownUntil, Date() < until { return "刚释放过，请 30 秒后再试" }
        if snapshot.queueRunning + snapshot.queuePending > 0 { return "主机队列还有任务，空闲后可释放" }
        let localBusy = snapshot.tasks.contains {
            $0.state == "Running" || $0.state == "Queued" || $0.state == "Submitting"
        }
        if localBusy { return "任务刚提交，等结果出来后再释放" }
        return nil
    }

    /// POST /free：卸载模型并释放显存。服务端只回 200/空 body，
    /// 不知道实际释放了多少，所以成功后加 30 秒冷却防连点。
    private func requestFreeMemory() {
        let base = snapshot.baseUrl.trimmingCharacters(in: .whitespaces)
        let trimmed = base.hasSuffix("/") ? String(base.dropLast()) : base
        guard let url = URL(string: trimmed + "/free") else {
            freeNote = "地址无效"
            return
        }
        freeing = true
        freeNote = ""
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = #"{"unload_models":true,"free_memory":true}"#.data(using: .utf8)
        request.timeoutInterval = 10
        URLSession.shared.dataTask(with: request) { _, response, error in
            DispatchQueue.main.async {
                freeing = false
                if let http = response as? HTTPURLResponse {
                    if http.statusCode == 404 {
                        freeNote = "当前 ComfyUI 不支持远程释放显存"
                    } else if !(200...299).contains(http.statusCode) {
                        freeNote = "释放失败（HTTP \(http.statusCode)）"
                    } else {
                        freeNote = "已请求释放，显存可能稍后下降"
                        cooldownUntil = Date().addingTimeInterval(30)
                    }
                } else if let error {
                    freeNote = "释放失败：\(error.localizedDescription)"
                } else {
                    freeNote = "释放失败，请检查连接"
                }
            }
        }.resume()
    }

    private struct StatValue: Identifiable {
        var id: String { label }
        let label: String
        let value: String
    }

    private var statCells: [StatValue] {
        var cells: [StatValue] = []
        // 监控采样比 /system_stats 新，能拿到就用它；拿不到退回轮询结果。
        let ram = snapshot.ramPercent ?? stats?.ramPercent
        let vram = snapshot.vramPercent ?? stats?.vramPercent

        if let ram { cells.append(StatValue(label: "内存", value: percentText(ram))) }
        if let vram { cells.append(StatValue(label: "显存", value: percentText(vram))) }
        if let cpu = snapshot.cpuPercent { cells.append(StatValue(label: "CPU", value: percentText(cpu))) }
        if let gpu = snapshot.gpuPercent { cells.append(StatValue(label: "GPU", value: percentText(gpu))) }
        if let temperature = snapshot.temperature {
            cells.append(StatValue(label: "温度", value: "\(Int(temperature.rounded()))°C"))
        }

        let gpuName = snapshot.gpuName.isEmpty ? (stats?.gpuName ?? "") : snapshot.gpuName
        if !gpuName.isEmpty { cells.append(StatValue(label: "显卡", value: gpuName)) }

        if snapshot.isConnected {
            cells.append(
                StatValue(label: "队列", value: "\(snapshot.queueRunning) / \(snapshot.queuePending)")
            )
        }
        if let version = stats?.version, !version.isEmpty {
            cells.append(StatValue(label: "版本", value: version))
        }
        return cells
    }

    private var rows: [[StatValue]] {
        let cells = statCells
        return stride(from: 0, to: cells.count, by: 2).map { index in
            Array(cells[index..<min(index + 2, cells.count)])
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

    // MARK: - /system_stats

    /// 官方路由，任何 ComfyUI 都有；3 秒一轮，够用又不抢带宽。
    private func poll() async {
        let base = snapshot.baseUrl
        guard !base.isEmpty, let url = URL(string: base + "/system_stats") else { return }
        while !Task.isCancelled {
            await load(url)
            // 弱网下不抢带宽：15 秒一刷就够看趋势了。
            let interval: UInt64 = Prefs.weakNetwork ? 15_000_000_000 : 3_000_000_000
            try? await Task.sleep(nanoseconds: interval)
        }
    }

    private func load(_ url: URL) async {
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                errorText = "状态接口返回异常"
                return
            }
            guard let parsed = SystemStatsLite.parse(data) else {
                errorText = "状态响应读不懂"
                return
            }
            stats = parsed
            errorText = ""
        } catch {
            // 断线时这里会连续失败；只留一句最短的提示，不刷屏。
            errorText = "状态获取失败"
        }
    }
}

/// `/system_stats` 的宽松解析：只关心内存/显存百分比、显卡名与版本。
private struct SystemStatsLite {
    let ramPercent: Double?
    let vramPercent: Double?
    let gpuName: String?
    let version: String?

    static func parse(_ data: Data) -> SystemStatsLite? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        let system = root["system"] as? [String: Any]
        let device = (root["devices"] as? [[String: Any]])?.first

        return SystemStatsLite(
            ramPercent: percent(total: number(system?["ram_total"]), free: number(system?["ram_free"])),
            vramPercent: percent(total: number(device?["vram_total"]), free: number(device?["vram_free"])),
            gpuName: device?["name"] as? String,
            version: system?["comfyui_version"] as? String
        )
    }

    private static func number(_ value: Any?) -> Double? {
        if let number = value as? NSNumber { return number.doubleValue }
        if let text = value as? String { return Double(text) }
        return nil
    }

    /// 总量未知（<= 0）时返回 nil，调用方据此省略格子——不画 "--"。
    private static func percent(total: Double?, free: Double?) -> Double? {
        guard let total, let free, total > 0 else { return nil }
        let used = (total - free) / total * 100
        return min(max(used, 0), 100)
    }
}
