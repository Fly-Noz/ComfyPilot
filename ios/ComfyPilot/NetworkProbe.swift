import SwiftUI

struct NetworkProbeResult {
    let verdict: String
    let score: Int
    let rows: [(String, String)]
    let reason: String
    let suggestion: String
}

/// 网络质量检测：延迟 8 次、下载最近产物、检查服务端队列。
/// 采样与分档对齐 Android `NetworkCheck.kt`（p50 / 稳定性 / 下载三项加权）。
enum NetworkProbe {
    static func run(baseUrl: String, lastFrameAtMs: Int64?) async -> NetworkProbeResult {
        let base = baseUrl.hasSuffix("/") ? String(baseUrl.dropLast()) : baseUrl
        var rows: [(String, String)] = []

        func get(_ path: String) async -> (ok: Bool, ms: Int64, data: Data?) {
            guard let url = URL(string: base + path) else { return (false, 0, nil) }
            var request = URLRequest(url: url)
            request.timeoutInterval = 12
            let started = Date()
            do {
                let (data, response) = try await URLSession.shared.data(for: request)
                let ms = Int64(Date().timeIntervalSince(started) * 1000)
                let ok = (response as? HTTPURLResponse).map { (200...299).contains($0.statusCode) } ?? false
                return (ok, ms, data)
            } catch {
                return (false, Int64(Date().timeIntervalSince(started) * 1000), nil)
            }
        }

        let first = await get("/system_stats")
        guard first.ok else {
            rows.append(("连接", "失败（\(first.ms)ms）"))
            return NetworkProbeResult(
                verdict: "差",
                score: 0,
                rows: rows,
                reason: "连不上 ComfyUI。",
                suggestion: "检查地址、端口和网络是否互通；如果走了中继，先直连试试。"
            )
        }
        rows.append(("建连", "\(first.ms)ms"))

        var samples: [Int64] = []
        var failures = 0
        for index in 0..<8 {
            let probe = await get("/system_stats")
            if probe.ok { samples.append(probe.ms) } else { failures += 1 }
            if index < 7 { try? await Task.sleep(nanoseconds: 200_000_000) }
        }
        let sorted = samples.sorted()
        let p50 = sorted.isEmpty ? 0 : sorted[sorted.count / 2]
        let p95 = sorted.isEmpty ? 0 : sorted[min(sorted.count - 1, sorted.count * 95 / 100)]
        let jitter = sorted.isEmpty ? 0 : (sorted.last! - sorted.first!)
        rows.append(("延迟", "p50 \(p50)ms · p95 \(p95)ms · 抖动 \(jitter)ms"))
        rows.append(("稳定性", failures == 0 ? "8/8 成功" : "\(failures)/8 失败"))

        // 下载：取 /history 里最近一个产物的文件名，测 /view 的吞吐。
        var mbps = 0.0
        if let history = await get("/history").data,
           let body = String(data: history, encoding: .utf8),
           let range = body.range(of: "\"filename\"\\s*:\\s*\"([^\"]+)\"", options: .regularExpression) {
            let filename = body[range].split(separator: "\"").dropFirst().first.map(String.init) ?? ""
            if !filename.isEmpty,
               let encoded = filename.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
               let url = URL(string: base + "/view?filename=" + encoded + "&type=output&subfolder=") {
                var request = URLRequest(url: url)
                request.timeoutInterval = 15
                let started = Date()
                if let (data, response) = try? await URLSession.shared.data(for: request),
                   (response as? HTTPURLResponse).map({ (200...299).contains($0.statusCode) }) ?? false,
                   !data.isEmpty {
                    let seconds = max(0.001, Date().timeIntervalSince(started))
                    let bytes = min(data.count, 4 * 1024 * 1024)
                    mbps = Double(bytes) * 8.0 / seconds / 1_000_000
                }
            }
            rows.append(("下载", mbps > 0 ? String(format: "%.1f Mbps", mbps) : "拿不到产物，跳过"))
        } else {
            rows.append(("下载", "没有可下载的产物，跳过"))
        }

        var running = 0
        var pending = 0
        if let queueData = await get("/queue").data,
           let object = (try? JSONSerialization.jsonObject(with: queueData)) as? [String: Any] {
            running = (object["queue_running"] as? [Any])?.count ?? 0
            pending = (object["queue_pending"] as? [Any])?.count ?? 0
        }
        rows.append(("服务端", "运行 \(running) · 排队 \(pending)"))

        if let last = lastFrameAtMs, last > 0 {
            let silenceMs = Int64(Date().timeIntervalSince1970 * 1000) - last
            if silenceMs > 5 * 60_000 {
                rows.append(("长连接", "5 分钟没收到帧，疑似静默断链"))
            } else {
                rows.append(("长连接", "最后帧 \(silenceMs / 1000)s 前"))
            }
        } else {
            rows.append(("长连接", "本次无采样任务，跳过"))
        }

        var score = 0
        score += p50 <= 40 ? 35 : (p50 <= 120 ? 22 : 5)
        score += failures == 0 ? 35 : (failures == 1 ? 15 : 0)
        score += mbps > 0 ? (mbps >= 8 ? 30 : (mbps >= 2 ? 16 : 4)) : 20

        let verdict = score >= 85 ? "优" : (score >= 60 ? "良" : "差")
        let reason: String
        let suggestion: String
        if score >= 85 {
            reason = "网络质量很好，采样预览和产物下载都会很顺。"
            suggestion = "没有需要处理的。"
        } else if score >= 60 {
            reason = "网络基本可用，大图 / 视频下载和实时预览可能会有等待。"
            suggestion = "靠近路由器或用 5GHz；主机不在身边时开弱网模式能省带宽。"
        } else {
            reason = "网络质量偏弱，实时预览和产物下载容易卡顿或超时。"
            suggestion = "优先排查 Wi-Fi 信号 / 中继；主机忙时先等队列空闲再测，排除显卡因素。"
        }
        return NetworkProbeResult(verdict: verdict, score: score, rows: rows, reason: reason, suggestion: suggestion)
    }
}

/// 检测弹窗：进度 → 结论 + 明细 + 建议（对齐 Android 的检测弹窗）。
struct NetworkProbeSheet: View {
    let palette: SpectrumPalette
    let baseUrl: String
    let lastFrameAtMs: Int64?

    @Environment(\.dismiss) private var dismiss
    @State private var result: NetworkProbeResult?
    @State private var error = ""

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("网络质量检测")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(palette.text)
                    Spacer(minLength: 0)
                    Button("关闭") { dismiss() }
                        .font(.system(size: 12))
                        .foregroundStyle(palette.textMuted)
                        .buttonStyle(.plain)
                }

                if !error.isEmpty {
                    Text(error)
                        .font(.system(size: 12.5))
                        .foregroundStyle(palette.err)
                } else if let result {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(result.verdict)
                            .font(.system(size: 24, weight: .semibold))
                            .foregroundStyle(palette.accent)
                        Text("\(result.score) 分")
                            .font(.system(size: 12))
                            .foregroundStyle(palette.textMuted)
                    }
                    Text(result.reason)
                        .font(.system(size: 12.5))
                        .lineSpacing(4)
                        .foregroundStyle(palette.text)

                    ScrollView {
                        VStack(alignment: .leading, spacing: 5) {
                            ForEach(result.rows.indices, id: \.self) { index in
                                let row = result.rows[index]
                                HStack(alignment: .top, spacing: 8) {
                                    Text(row.0)
                                        .font(.system(size: 11))
                                        .foregroundStyle(palette.textDim)
                                        .frame(width: 60, alignment: .leading)
                                    Text(row.1)
                                        .font(.system(size: 11, design: .monospaced))
                                        .foregroundStyle(palette.text)
                                }
                            }
                        }
                    }

                    Text("建议：" + result.suggestion)
                        .font(.system(size: 11.5))
                        .lineSpacing(4)
                        .foregroundStyle(palette.textMuted)
                } else {
                    HStack(spacing: 10) {
                        ProgressView()
                            .tint(palette.accent)
                        Text("正在检测：延迟 8 次、下载最近产物、检查队列…")
                            .font(.system(size: 12.5))
                            .foregroundStyle(palette.textMuted)
                    }
                    Text("预计 8–15 秒。")
                        .font(.system(size: 10.5))
                        .foregroundStyle(palette.textDim)
                }
                Spacer(minLength: 0)
            }
            .padding(18)
        }
        .task {
            if baseUrl.trimmingCharacters(in: .whitespaces).isEmpty {
                error = "还没有连接 ComfyUI，先连上再测。"
                return
            }
            result = await NetworkProbe.run(baseUrl: baseUrl, lastFrameAtMs: lastFrameAtMs)
        }
        .presentationDetents([.medium, .large])
    }
}
