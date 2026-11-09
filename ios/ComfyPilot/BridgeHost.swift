import Foundation
import Network
import ComfyCore

/// 宿主侧 IO。Kotlin 只跑状态机，把「要发什么请求」写成命令交出来；
/// 这里负责用 URLSession 执行，再把结果喂回去。
///
/// 线程纪律：`IosHost` 底层的 PumpDispatcher 是单线程无锁的，所有调用必须落在主线程。
/// URLSession 的回调都在后台队列，因此统一先 `onMain` 再碰 host。
final class BridgeHost {
    enum Event {
        case state([String: Any])
        case notice([String: Any])
    }

    var onEvent: ((Event) -> Void)?

    private let host: IosHost
    private let session: URLSession
    private let network = NetworkMonitor()
    private var timer: Timer?
    private var sockets: [Int64: URLSessionWebSocketTask] = [:]
    private var tickScheduled = false
    private var running = false

    init(configJson: String, tasksJson: String) {
        host = IosHost(configJson: configJson, tasksJson: tasksJson)
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 30
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: configuration)
    }

    deinit {
        timer?.invalidate()
        session.invalidateAndCancel()
    }

    func start() {
        guard !running else { return }
        running = true
        host.start()

        // 固定 250ms 节拍。Kotlin 返回的 nextDelayMs 只是建议值，这一版先不跟随——
        // 节奏稳定比省电重要，等真机验证过再改成自适应。
        let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
            self?.tick()
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
        tick()

        network.start { [weak self] transport, validated in
            guard let self else { return }
            self.onMain {
                self.deliver([
                    "t": "net",
                    "transport": transport,
                    "validated": validated ? 1 : 0,
                ])
            }
        }
    }

    func stop() {
        guard running else { return }
        running = false
        timer?.invalidate()
        timer = nil
        network.stop()
        for (_, task) in sockets {
            task.cancel(with: .goingAway, reason: nil)
        }
        sockets.removeAll()
        host.shutdown()
    }

    /// 给状态机投一条命令：start / stop / reconnect / submit / cancel / workflows / workflow.open
    func send(_ message: [String: Any]) {
        deliver(message)
    }

    /// 同步取工作流视图。共享层只是把内存里的模型序列化，不涉及 IO。
    /// 和其他调用一样，必须在主线程。
    func workflowView(requestJson: String) -> String {
        host.workflowView(requestJson: requestJson)
    }

    // MARK: - 节拍

    private func tick() {
        guard running else { return }
        // Kotlin 用的是墙上时间；宿主被冻结后由 SessionHost 自己扣掉空档。
        let nowMs = Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
        let frame = host.pump(nowMs: nowMs)
        guard let data = frame.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return
        }
        if let state = object["state"] as? [String: Any] {
            onEvent?(.state(state))
        }
        if let commands = object["commands"] as? [[String: Any]] {
            for command in commands {
                handle(command)
            }
        }
    }

    private func handle(_ command: [String: Any]) {
        guard let type = command["t"] as? String else { return }
        switch type {
        case "http":
            runHttp(command)
        case "ws.open":
            openSocket(command)
        case "ws.send":
            sendFrame(command)
        case "ws.close":
            closeSocket(command)
        case "notice":
            if let notice = command["notice"] as? [String: Any] {
                onEvent?(.notice(notice))
            }
        default:
            break
        }
    }

    // MARK: - HTTP

    private func runHttp(_ command: [String: Any]) {
        guard let id = int64(command["id"]),
              let rawUrl = command["url"] as? String,
              let url = URL(string: rawUrl) else { return }

        var request = URLRequest(url: url)
        request.httpMethod = (command["method"] as? String) ?? "GET"
        request.timeoutInterval = Double(int64(command["timeoutMs"]) ?? 20_000) / 1000.0
        if let contentType = command["contentType"] as? String {
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        if let headers = command["headers"] as? [String: Any] {
            for (key, value) in headers {
                if let text = value as? String {
                    request.setValue(text, forHTTPHeaderField: key)
                }
            }
        }
        if let encoded = command["body"] as? String, let body = Data(base64Encoded: encoded) {
            request.httpBody = body
        }

        session.dataTask(with: request) { [weak self] data, response, error in
            guard let self else { return }
            self.onMain {
                if let error {
                    self.deliver([
                        "t": "http.result",
                        "id": id,
                        "status": 0,
                        "failure": error.localizedDescription,
                    ])
                    return
                }
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                var payload: [String: Any] = ["t": "http.result", "id": id, "status": status]
                // 桥协议只传 UTF-8 文本；图片等二进制由原生侧自己下载，不走这里。
                if let data, let text = String(data: data, encoding: .utf8) {
                    payload["body"] = text
                }
                self.deliver(payload)
            }
        }.resume()
    }

    // MARK: - WebSocket

    private func openSocket(_ command: [String: Any]) {
        guard let id = int64(command["id"]),
              let rawUrl = command["url"] as? String,
              let url = URL(string: rawUrl) else { return }

        let task = session.webSocketTask(with: url)
        sockets[id] = task
        task.resume()
        // URLSessionWebSocketTask 没有「已连接」回调。先按已打开告诉 Kotlin，
        // 真正连不上时 receive 会立刻失败，以 ws.failed 纠正。
        deliver(["t": "ws.opened", "id": id])
        receive(id: id, task: task)
    }

    private func receive(id: Int64, task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            guard let self else { return }
            self.onMain {
                guard self.sockets[id] != nil else { return }
                switch result {
                case .success(let message):
                    switch message {
                    case .string(let text):
                        self.deliver(["t": "ws.text", "id": id, "text": text])
                    case .data(let data):
                        self.deliver(["t": "ws.binary", "id": id, "base64": data.base64EncodedString()])
                    @unknown default:
                        break
                    }
                    self.receive(id: id, task: task)
                case .failure(let error):
                    self.sockets.removeValue(forKey: id)
                    self.deliver([
                        "t": "ws.failed",
                        "id": id,
                        "message": error.localizedDescription,
                    ])
                }
            }
        }
    }

    private func sendFrame(_ command: [String: Any]) {
        guard let id = int64(command["id"]),
              let text = command["text"] as? String,
              let task = sockets[id] else { return }

        task.send(.string(text)) { [weak self] error in
            guard let error, let self else { return }
            self.onMain {
                guard self.sockets[id] != nil else { return }
                self.sockets.removeValue(forKey: id)
                self.deliver([
                    "t": "ws.failed",
                    "id": id,
                    "message": error.localizedDescription,
                ])
            }
        }
    }

    private func closeSocket(_ command: [String: Any]) {
        guard let id = int64(command["id"]) else { return }
        let reason = (command["reason"] as? String) ?? ""
        sockets.removeValue(forKey: id)?
            .cancel(with: .normalClosure, reason: reason.data(using: .utf8))
        deliver(["t": "ws.closed", "id": id, "code": 1000, "reason": reason])
    }

    // MARK: - 桥

    private func deliver(_ payload: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let raw = String(data: data, encoding: .utf8) else { return }

        onMain { [weak self] in
            guard let self else { return }
            _ = self.host.deliver(raw: raw)
            // 不要在命令处理途中递归 pump：推迟到下一轮主队列。
            self.scheduleTick()
        }
    }

    private func scheduleTick() {
        guard !tickScheduled else { return }
        tickScheduled = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.tickScheduled = false
            self.tick()
        }
    }

    private func onMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread {
            block()
        } else {
            DispatchQueue.main.async(execute: block)
        }
    }

    private func int64(_ value: Any?) -> Int64? {
        if let number = value as? NSNumber { return number.int64Value }
        if let text = value as? String { return Int64(text) }
        return nil
    }
}

/// 网络变化要告诉共享层：它靠这个递增 generation，决定要不要重连。
final class NetworkMonitor {
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.fly.comfypilot.network")
    private var started = false

    func start(onChange: @escaping (String, Bool) -> Void) {
        guard !started else { return }
        started = true
        monitor.pathUpdateHandler = { path in
            let transport: String
            if path.status != .satisfied {
                transport = "none"
            } else if path.usesInterfaceType(.wifi) {
                transport = "wifi"
            } else if path.usesInterfaceType(.cellular) {
                transport = "cellular"
            } else if path.usesInterfaceType(.wiredEthernet) {
                transport = "ethernet"
            } else {
                transport = "other"
            }
            onChange(transport, path.status == .satisfied)
        }
        monitor.start(queue: queue)
    }

    func stop() {
        monitor.cancel()
    }
}
