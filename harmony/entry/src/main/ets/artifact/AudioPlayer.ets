import { media } from '@kit.MediaKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { Palette, Spectrum } from '../theme/Spectrum';

/**
 * 音频产物播放器：AVPlayer 直接播 /view 的流，不需要 XComponent 表面。
 * 与 VideoPlayer 一样，离开页面必须 release，否则下一次打开会报解码器占用。
 */
@Component
export struct AudioPlayer {
  url: string = '';
  isDark: boolean = true;
  @State private playing: boolean = false;
  @State private ready: boolean = false;
  @State private failed: boolean = false;
  @State private positionMs: number = 0;
  @State private durationMs: number = 0;
  private player: media.AVPlayer | null = null;

  aboutToDisappear(): void {
    this.releasePlayer();
  }

  private c(): Palette {
    return Spectrum.palette(this.isDark);
  }

  private releasePlayer(): void {
    const player: media.AVPlayer | null = this.player;
    this.player = null;
    if (player !== null) {
      player.release().catch((err: BusinessError) => {
        console.warn(`[ComfyPilot] audio release failed: ${err.message}`);
      });
    }
  }

  private async initPlayer(): Promise<void> {
    try {
      const player: media.AVPlayer = await media.createAVPlayer();
      this.player = player;
      player.on('stateChange', (state: string) => {
        if (state === 'initialized') {
          player.prepare();
        } else if (state === 'prepared') {
          this.durationMs = player.duration;
          this.ready = true;
          player.play();
          this.playing = true;
        } else if (state === 'completed') {
          this.playing = false;
          this.positionMs = this.durationMs;
        } else if (state === 'error') {
          this.failed = true;
        }
      });
      player.on('timeUpdate', (time: number) => {
        this.positionMs = time;
      });
      player.on('error', (err: BusinessError) => {
        console.warn(`[ComfyPilot] audio error: ${err.message}`);
        this.failed = true;
      });
      player.url = this.url;
    } catch (err) {
      this.failed = true;
    }
  }

  private togglePlay(): void {
    const player: media.AVPlayer | null = this.player;
    if (player === null || !this.ready) {
      return;
    }
    if (this.playing) {
      player.pause();
      this.playing = false;
    } else {
      player.play();
      this.playing = true;
    }
  }

  private clock(ms: number): string {
    const total: number = Math.max(0, Math.round(ms / 1000));
    const minutes: number = Math.floor(total / 60);
    const seconds: number = total % 60;
    return `${minutes}:${seconds < 10 ? '0' : ''}${seconds}`;
  }

  aboutToAppear(): void {
    this.initPlayer();
  }

  build() {
    Column({ space: 10 }) {
      Row({ space: 10 }) {
        Text(this.playing ? '暂停' : '播放')
          .fontSize(13)
          .fontColor('#F2F4F7')
          .padding({ left: 18, right: 18, top: 9, bottom: 9 })
          .backgroundColor(this.c().accent)
          .borderRadius(10)
          .enabled(this.ready)
          .opacity(this.ready ? 1 : 0.5)
          .onClick(() => this.togglePlay())
        Text(`${this.clock(this.positionMs)} / ${this.clock(this.durationMs)}`)
          .fontSize(11)
          .fontFamily('monospace')
          .fontColor(this.c().textMuted)
          .layoutWeight(1)
        Text(this.failed ? '加载失败' : (this.ready ? '音频' : '加载中…'))
          .fontSize(10.5)
          .fontColor(this.failed ? this.c().err : this.c().textDim)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)

      Progress({ value: this.durationMs > 0 ? this.positionMs / this.durationMs * 100 : 0, total: 100 })
        .width('100%')
        .height(5)
        .color(this.c().accent)
        .backgroundColor(this.c().cardStrong)
    }
    .width('100%')
    .padding(14)
    .backgroundColor(this.c().card)
    .borderRadius(16)
    .border({ width: 1, color: this.c().border })
    .justifyContent(FlexAlign.Center)
  }
}
