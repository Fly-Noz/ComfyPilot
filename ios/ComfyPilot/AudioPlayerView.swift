import AVFoundation
import SwiftUI

/// 音频播放状态：AVPlayer + 0.25 秒进度回调。
///
/// 单独做成 ObservableObject 而不是塞进 View：`addPeriodicTimeObserver`
/// 的闭包会捕获 self，View 是值类型、捕获的是副本，拖动时读到的
/// `scrubbing` 会是旧值，进度条会跳。
final class AudioPlaybackModel: ObservableObject {
    @Published private(set) var isPlaying = false
    @Published private(set) var position: Double = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var isReady = false
    @Published private(set) var errorText = ""

    private var player: AVPlayer?
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var scrubbing = false

    func load(_ url: URL?) {
        teardown()
        errorText = ""
        isReady = false
        duration = 0
        position = 0
        guard let url else {
            errorText = "地址无效"
            return
        }

        // 静音键打开时也要出声：音频播放类别。
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)

        let item = AVPlayerItem(url: url)
        let player = AVPlayer(playerItem: item)
        self.player = player

        Task { @MainActor [weak self] in
            guard let self else { return }
            if let loaded = try? await item.asset.load(.duration) {
                let seconds = loaded.seconds
                if seconds.isFinite, seconds > 0 {
                    self.duration = seconds
                }
            }
            self.isReady = true
        }

        let interval = CMTime(seconds: 0.25, preferredTimescale: 600)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            guard let self, !self.scrubbing else { return }
            let seconds = time.seconds
            if seconds.isFinite {
                self.position = max(0, min(seconds, self.duration > 0 ? self.duration : seconds))
            }
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main,
        ) { [weak self] _ in
            guard let self else { return }
            self.isPlaying = false
            self.player?.pause()
            self.player?.seek(to: .zero)
            self.position = 0
        }
    }

    func toggle() {
        guard let player else { return }
        if isPlaying {
            player.pause()
            isPlaying = false
            return
        }
        // 播完再点播放：从头开始，避免停在末尾无声。
        if duration > 0, position >= duration - 0.15 {
            player.seek(to: .zero)
            position = 0
        }
        player.play()
        isPlaying = true
    }

    /// 拖动进度条：拖的时候只更新 UI，松手才真正 seek。
    func beginScrub() {
        scrubbing = true
    }

    func scrub(to seconds: Double) {
        position = seconds
    }

    func endScrub() {
        scrubbing = false
        player?.seek(
            to: CMTime(seconds: position, preferredTimescale: 600),
            toleranceBefore: .zero,
            toleranceAfter: .zero,
        )
    }

    func teardown() {
        if let timeObserver, let player {
            player.removeTimeObserver(timeObserver)
        }
        timeObserver = nil
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
        }
        endObserver = nil
        player?.pause()
        player = nil
        isPlaying = false
        isReady = false
    }

    deinit {
        if let timeObserver, let player {
            player.removeTimeObserver(timeObserver)
        }
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
        }
    }
}

/// 音频播放器：播放/暂停 + 可拖动进度条 + 当前时间/总时长。
/// 产物查看器与参数页「已上传音频试听」共用，`compact` 用于后者。
struct AudioPlayerView: View {
    let url: URL?
    let palette: SpectrumPalette
    var compact = false

    @StateObject private var model = AudioPlaybackModel()

    var body: some View {
        VStack(alignment: .leading, spacing: compact ? 6 : 10) {
            HStack(spacing: compact ? 9 : 12) {
                Button {
                    model.toggle()
                } label: {
                    Image(systemName: model.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: compact ? 12 : 15, weight: .semibold))
                        .foregroundStyle(model.isReady ? palette.text : palette.textDim)
                        .frame(width: compact ? 30 : 38, height: compact ? 30 : 38)
                        .background(
                            Circle().fill(model.isReady ? palette.cardStrong : palette.card)
                        )
                        .overlay(
                            Circle().stroke(palette.border, lineWidth: 1)
                        )
                }
                .buttonStyle(.plain)
                .disabled(!model.isReady)
                .accessibilityLabel(model.isPlaying ? "暂停" : "播放")

                VStack(spacing: 2) {
                    // 拖动：按住时先暂停进度回调，松手才 seek——否则会来回跳。
                    Slider(
                        value: Binding(
                            get: { model.position },
                            set: { model.scrub(to: $0) }
                        ),
                        in: 0...max(model.duration, 0.01),
                        onEditingChanged: { editing in
                            if editing {
                                model.beginScrub()
                            } else {
                                model.endScrub()
                            }
                        },
                    )
                    .tint(palette.accent)
                    .disabled(!model.isReady)

                    HStack(spacing: 0) {
                        Text(clock(model.position))
                        Spacer(minLength: 0)
                        Text(clock(model.duration))
                    }
                    .font(.system(size: 9.5, design: .monospaced))
                    .foregroundStyle(palette.textDim)
                }
            }

            if !model.errorText.isEmpty {
                Text(model.errorText)
                    .font(.system(size: 10.5))
                    .foregroundStyle(palette.err)
            } else if !model.isReady {
                Text("正在加载音频…")
                    .font(.system(size: 10.5))
                    .foregroundStyle(palette.textDim)
            }
        }
        .task(id: url) {
            model.load(url)
        }
        .onDisappear {
            model.teardown()
        }
    }

    private func clock(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let total = Int(seconds.rounded())
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}
