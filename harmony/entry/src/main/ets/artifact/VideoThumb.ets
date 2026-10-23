import { common } from '@kit.AbilityKit';
import { image } from '@kit.ImageKit';
import { Palette, Spectrum } from '../theme/Spectrum';
import { VideoPoster } from '../bridge/VideoPoster';

/**
 * 视频产物的媒体位。
 *
 * 三种状态：
 * - pending：抽首帧中。保持原来的哑光底 + 播放键 + 「视频」胶囊，但不写「点击播放」，
 *   免得用户以为已经可以点了。
 * - ready：画 AVImageGenerator 抽到的首帧（`ImageFit.Cover` 填满同一块区域），
 *   「视频」胶囊仍然压在最上层。
 * - failed：完全退回 `VideoArtifactPlaceholder` 的样子（哑光 + 播放键 + 文案 + 胶囊）。
 *
 * 点击行为不在这里：列表行和预览的 onClick 都在外层，组件的子节点不加手势。
 */
@Component
export struct VideoThumb {
  @Prop @Watch('onUrlChanged') url: string = '';
  /** 主题深浅由页面传进来，组件内部仍用 `this.c()` 取调色板，不写死颜色。 */
  @Prop isDark: boolean = true;
  /** 占位中央的提示文案（例如「点击播放」）；抽帧进行中不显示。 */
  @Prop hint: string = '';
  @State poster: image.PixelMap | null = null;
  /** pending / ready / failed。 */
  @State phase: string = 'pending';
  private disposed: boolean = false;

  aboutToAppear(): void {
    this.loadPoster();
  }

  aboutToDisappear(): void {
    this.disposed = true;
  }

  /** 预览面板换了一个产物但组件被复用时，要重新抽帧。 */
  private onUrlChanged(): void {
    this.poster = null;
    this.phase = 'pending';
    this.loadPoster();
  }

  private async loadPoster(): Promise<void> {
    if (this.url.length === 0) {
      this.phase = 'failed';
      return;
    }
    const context: common.UIAbilityContext | null = this.abilityContext();
    try {
      const frame: image.PixelMap = await VideoPoster.shared().load(context, this.url);
      if (this.disposed) {
        return;
      }
      this.poster = frame;
      this.phase = 'ready';
    } catch (err) {
      if (this.disposed) {
        return;
      }
      this.poster = null;
      this.phase = 'failed';
    }
  }

  private abilityContext(): common.UIAbilityContext | null {
    const host = this.getUIContext().getHostContext();
    if (host === undefined) {
      return null;
    }
    return host as common.UIAbilityContext;
  }

  private c(): Palette {
    return Spectrum.palette(this.isDark);
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      if (this.phase === 'ready' && this.poster !== null) {
        Image(this.poster as image.PixelMap)
          .width('100%')
          .height('100%')
          .objectFit(ImageFit.Cover)
          .interpolation(ImageInterpolation.Medium)
      } else {
        Column({ space: 8 }) {
          Column() {
            Polygon()
              .width(15)
              .height(18)
              .points([[0, 0], [15, 9], [0, 18]])
              .fill(this.c().gradientStart)
          }
          .width(48)
          .height(48)
          .borderRadius(24)
          .justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
          .backgroundColor(this.c().accentSoft)
          .border({ width: 1, color: this.c().accentBorder })

          if (this.hint.length > 0 && this.phase === 'failed') {
            Text(this.hint)
              .fontSize(11)
              .fontColor(this.c().gradientStart)
          }
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
      }

      Row() {
        Text('视频')
          .fontSize(9)
          .fontColor(this.c().gradientStart)
          .padding({ left: 8, right: 8, top: 3, bottom: 3 })
          .borderRadius(999)
          .backgroundColor(this.c().accentSoft)
          .border({ width: 1, color: this.c().accentBorder })
      }
      .padding(8)
    }
    .width('100%')
    .height('100%')
  }
}