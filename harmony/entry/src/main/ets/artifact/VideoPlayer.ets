import { media } from '@kit.MediaKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { Palette, Spectrum } from '../theme/Spectrum';

/**
 * 产物视频的内嵌播放器：AVPlayer 解码 + XComponent 表面渲染。
 *
 * 生命周期要点：
 * - XComponent 的 onLoad 里拿到 surfaceId 才能创建播放器；早于 surface 创建会黑屏；
 * - 页面离开（aboutToDisappear）必须 release，否则解码器泄漏、下次打开报错；
 * - 播放器状态机只能按 idle → initialized → prepared → playing 推进，因此 surfaceId/prepare/play
 *   都放在 stateChange 回调里驱动，不在 init 里串行调用。
 */
@Component
export struct VideoPlayer {
  /** 产物的 /view URL。 */
  url: string = '';
  /** 页面深浅色；组件内部统一用 this.c() 取色。 */
  isDark: boolean = true;
  @State private playing: boolean = false;
  @State private ready: boolean = false;
  @State private failed: boolean = false;
  @State private positionMs: number = 0;
  @State private durationMs: number = 0;
  private player: media.AVPlayer | null = null;
  private controller: XComponentController = new XComponentController();
  private surfaceId: string = '';

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
        console.warn(`[ComfyPilot] video release failed: ${err.message}`);
      });
    }
  }

  private async initPlayer(): Promise<void> {
    try {
      const player: media.AVPlayer = await media.createAVPlayer();
      this.player = player;
      player.on('stateChange', (state: string) => {
        if (state === 'initialized') {
          player.surfaceId = this.surfaceId;
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
        console.warn(`[ComfyPilot] video error: ${err.message}`);
        this.failed = true;
      });
      player.url = this.url;
    } catch (err) {
      this.failed = true;
    }
  }

  private togglePlay(): void {
    const player: media.AVPlayer | null = this.player;
    if (player === null) {
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

  private seekRatio(ratio: number): void {
    const player: media.AVPlayer | null = this.player;
    if (player === null || this.durationMs <= 0) {
      return;
    }
    const target: number = Math.round(this.durationMs * Math.max(0, Math.min(1, ratio)));
    player.seek(target);
    this.positionMs = target;
  }

  private clock(ms: number): string {
    const total: number = Math.max(0, Math.round(ms / 1000));
    const minutes: number = Math.floor(total / 60);
    const seconds: number = total % 60;
    return `${minutes}:${seconds < 10 ? '0' : ''}${seconds}`;
  }

  build() {
    Stack({ alignContent: Alignment.Bottom }) {
      XComponent({
        id: 'comfypilot-video',
        type: XComponentType.SURFACE,
        controller: this.controller,
      })
        .width('100%')
        .height('100%')
        .onLoad(() => {
          this.surfaceId = this.controller.getXComponentSurfaceId();
          this.initPlayer();
        })

      if (!this.ready && !this.failed) {
        Column({ space: 8 }) {
          LoadingProgress()
            .width(28)
            .height(28)
            .color(this.c().accent)
          Text('正在缓冲视频…')
            .fontSize(11.5)
            .fontColor('rgba(242,244,247,0.75)')
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
      }

      if (this.failed) {
        Column({ space: 6 }) {
          Text('视频加载失败')
            .fontSize(12.5)
            .fontColor('rgba(242,244,247,0.85)')
          Text('可以下载后用系统应用播放')
            .fontSize(11)
            .fontColor('rgba(242,244,247,0.55)')
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
      }

      // 控制条：播放/暂停 + 时间 + 可拖动进度。
      Row({ space: 10 }) {
        Text(this.playing ? '暂停' : '播放')
          .fontSize(12)
          .fontColor('#F2F4F7')
          .padding({ left: 10, right: 10, top: 6, bottom: 6 })
          .borderRadius(9)
          .backgroundColor('rgba(0,0,0,0.45)')
          .onClick(() => {
            this.togglePlay();
          })
        Text(`${this.clock(this.positionMs)} / ${this.clock(this.durationMs)}`)
          .fontSize(10)
          .fontFamily('monospace')
          .fontColor('rgba(242,244,247,0.8)')
        Slider({
          value: this.durationMs > 0 ? this.positionMs : 0,
          min: 0,
          max: this.durationMs > 0 ? this.durationMs : 1,
          style: SliderStyle.OutSet,
        })
          .layoutWeight(1)
          .blockColor(this.c().accent)
          .trackColor('rgba(255,255,255,0.24)')
          .selectedColor(this.c().accent)
          .onChange((value: number) => {
            this.seekRatio(this.durationMs > 0 ? value / this.durationMs : 0);
          })
      }
      .width('100%')
      .padding({ left: 10, right: 10, top: 8, bottom: 8 })
      .backgroundColor('rgba(0,0,0,0.32)')
      .backdropBlur(18)
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#0E0F14')
  }
}
