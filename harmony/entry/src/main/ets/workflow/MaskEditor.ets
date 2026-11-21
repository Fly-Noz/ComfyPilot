import { image } from '@kit.ImageKit';
import { fileIo } from '@kit.CoreFileKit';
import { http } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { Palette, Spectrum } from '../theme/Spectrum';

interface MaskPoint {
  x: number;
  y: number;
}

interface MaskStroke {
  points: MaskPoint[];
  size: number;
  erase: boolean;
}

const ICON_BACK = 'M17.77 3.77L16 2 6 12l10 10 1.77-1.77L9.54 12z';
const ICON_CHECK = 'M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z';
const ICON_BRUSH = 'M7 14c-1.66 0-3 1.34-3 3 0 1.31-1.16 2-2 2 .92 1.22 2.49 2 4 2 2.21 0 4-1.79 4-4 0-1.66-1.34-3-3-3zm13.71-9.37l-1.34-1.34a.996.996 0 00-1.41 0L9 12.25 11.75 15l8.96-8.96a.996.996 0 000-1.41z';
const ICON_ERASER = 'M17.25 18H22v2h-6.75zm-12.5 2l-2.125-2.125q-.575-.575-.587-1.425T2.6 15l11-11.4q.575-.6 1.413-.6t1.412.575L21.4 8.55q.575.575.575 1.425T21.4 11.4L13 20z';
const ICON_UNDO = 'M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z';
const ICON_CLEAR = 'M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z';

@Component
export struct MaskEditor {
  @Prop sourceUri: string = '';
  @Prop sourceName: string = '';
  @Prop baseUrl: string = '';
  @Prop initialBase64: string = '';
  @Prop @Watch('onSessionChanged') sessionKey: number = 0;
  @Prop deriveMask: boolean = false;
  @Prop isDark: boolean = false;
  onClose: () => void = () => {};
  onSave: (bytes: Uint8Array) => void = () => {};

  @State loading: boolean = true;
  @State error: string = '';
  @State viewW: number = 0;
  @State viewH: number = 0;
  @State zoom: number = 1;
  @State panX: number = 0;
  @State panY: number = 0;
  @State brush: number = 32;
  @State @Watch('onToolChanged') erasing: boolean = false;
  @State overlayTick: number = 0;
  @State saving: boolean = false;

  private sourceW: number = 1;
  private sourceH: number = 1;
  private basePm: image.PixelMap | null = null;
  private baseBitmap: ImageBitmap | null = null;
  private initialOverlay: ImageBitmap | null = null;
  private initialMaskBitmap: ImageBitmap | null = null;
  private offCanvas: OffscreenCanvas | null = null;
  private offCtx: OffscreenCanvasRenderingContext2D | null = null;
  private overlayCtx: CanvasRenderingContext2D = new CanvasRenderingContext2D(new RenderingContextSettings(true));
  private strokes: MaskStroke[] = [];
  private active: MaskStroke | null = null;
  private drawing: boolean = false;
  private pinching: boolean = false;
  private pinchStartZoom: number = 1;
  private pinchStartDist: number = 0;
  private pinchLastX: number = 0;
  private pinchLastY: number = 0;
  private backCtx: CanvasRenderingContext2D = new CanvasRenderingContext2D(new RenderingContextSettings(true));
  private checkCtx: CanvasRenderingContext2D = new CanvasRenderingContext2D(new RenderingContextSettings(true));
  private brushCtx: CanvasRenderingContext2D = new CanvasRenderingContext2D(new RenderingContextSettings(true));
  private eraserCtx: CanvasRenderingContext2D = new CanvasRenderingContext2D(new RenderingContextSettings(true));
  private undoCtx: CanvasRenderingContext2D = new CanvasRenderingContext2D(new RenderingContextSettings(true));
  private clearCtx: CanvasRenderingContext2D = new CanvasRenderingContext2D(new RenderingContextSettings(true));

  aboutToAppear(): void {
    this.load();
  }

  private onSessionChanged(): void {
    if (this.sessionKey <= 0) {
      return;
    }
    if (this.basePm !== null) {
      this.basePm.release();
      this.basePm = null;
    }
    this.baseBitmap = null;
    this.initialOverlay = null;
    this.initialMaskBitmap = null;
    this.offCanvas = null;
    this.offCtx = null;
    this.strokes = [];
    this.active = null;
    this.drawing = false;
    this.zoom = 1;
    this.panX = 0;
    this.panY = 0;
    this.loading = true;
    this.error = '';
    this.load();
  }

  aboutToDisappear(): void {
    if (this.basePm !== null) {
      this.basePm.release();
      this.basePm = null;
    }
  }

  private palette(): Palette {
    return Spectrum.palette(this.isDark);
  }

  private async load(): Promise<void> {
    try {
      let bytes: Uint8Array | null = this.initialBase64.length > 0
        ? new util.Base64Helper().decodeSync(this.initialBase64)
        : null;
      if (bytes === null || bytes.length === 0) {
        if (this.sourceUri.length > 0) {
          bytes = this.readUri(this.sourceUri);
        } else if (this.sourceName.length > 0 && this.baseUrl.length > 0) {
          bytes = await this.fetchInput(this.sourceName);
        }
      }
      if (bytes === null || bytes.length === 0) {
        throw new Error('读不到原图');
      }
      const source = image.createImageSource(bytes.buffer as ArrayBuffer);
      const info = await source.getImageInfo();
      const maxSide = Math.max(info.size.width, info.size.height);
      const scale = maxSide > 1600 ? 1600 / maxSide : 1;
      const width = Math.max(1, Math.round(info.size.width * scale));
      const height = Math.max(1, Math.round(info.size.height * scale));
      const pm = await source.createPixelMap({
        desiredSize: { width: width, height: height },
        desiredPixelFormat: image.PixelMapFormat.RGBA_8888,
      });
      await source.release();
      const buffer = new ArrayBuffer(width * height * 4);
      await pm.readPixelsToBuffer(buffer);
      await pm.release();
      const pixels = new Uint8Array(buffer);
      let hasAlpha = false;
      for (let i = 3; i < pixels.length; i += 4) {
        if (pixels[i] < 255) {
          hasAlpha = true;
          break;
        }
      }
      const baseData = new Uint8Array(pixels.length);
      baseData.set(pixels);
      for (let i = 3; i < baseData.length; i += 4) {
        baseData[i] = 255;
      }
      this.basePm = await image.createPixelMap(baseData.buffer, {
        size: { width: width, height: height },
        srcPixelFormat: image.PixelMapFormat.RGBA_8888,
        pixelFormat: image.PixelMapFormat.RGBA_8888,
        editable: true,
      });
      this.baseBitmap = new ImageBitmap(this.basePm);
      this.sourceW = width;
      this.sourceH = height;
      this.offCanvas = new OffscreenCanvas(width, height);
      this.offCtx = this.offCanvas.getContext('2d', new RenderingContextSettings(true));
      this.offCtx.drawImage(this.baseBitmap, 0, 0);
      if (hasAlpha && this.deriveMask) {
        const maskData = new Uint8Array(pixels.length);
        for (let i = 0; i < pixels.length; i += 4) {
          const alpha = 255 - pixels[i + 3];
          maskData[i] = 255;
          maskData[i + 1] = 255;
          maskData[i + 2] = 255;
          maskData[i + 3] = alpha;
        }
        const maskPm = await image.createPixelMap(maskData.buffer, {
          size: { width: width, height: height },
          srcPixelFormat: image.PixelMapFormat.RGBA_8888,
          pixelFormat: image.PixelMapFormat.RGBA_8888,
          editable: false,
        });
        const maskBitmap = new ImageBitmap(maskPm);
        const redCanvas = new OffscreenCanvas(width, height);
        const redCtx = redCanvas.getContext('2d', new RenderingContextSettings(true));
        redCtx.drawImage(maskBitmap, 0, 0);
        redCtx.globalCompositeOperation = 'source-in';
        redCtx.fillStyle = this.palette().accent;
        redCtx.fillRect(0, 0, width, height);
        const redPm = redCtx.getPixelMap(0, 0, width, height);
        this.initialOverlay = new ImageBitmap(redPm);
        this.initialMaskBitmap = maskBitmap;
        this.offCtx.globalCompositeOperation = 'destination-out';
        this.offCtx.drawImage(maskBitmap, 0, 0);
        this.offCtx.globalCompositeOperation = 'source-over';
        await maskPm.release();
      }
      this.loading = false;
      this.overlayTick++;
      // initialOverlay 是异步算出来的，Canvas 的 onReady 可能已经跑过了；
      // 这里主动重绘一次，否则再次打开时只会显示原图、看不到上次蒙版。
      setTimeout(() => this.drawOverlay(), 80);
    } catch (err) {
      this.error = (err as Error).message;
      this.loading = false;
    }
  }

  private readUri(uri: string): Uint8Array {
    const stat = fileIo.statSync(uri);
    const file = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
    try {
      const buffer = new ArrayBuffer(stat.size);
      const read = fileIo.readSync(file.fd, buffer, { offset: 0, length: stat.size });
      if (read !== stat.size) {
        throw new Error('读取原图不完整');
      }
      return new Uint8Array(buffer);
    } finally {
      fileIo.closeSync(file);
    }
  }

  private async fetchInput(name: string): Promise<Uint8Array> {
    const base = this.baseUrl.trim().replace(/\/+$/, '');
    const encoded = encodeURIComponent(name);
    const client = http.createHttp();
    try {
      const response = await client.request(`${base}/view?filename=${encoded}&type=input`, {
        method: http.RequestMethod.GET,
        expectDataType: http.HttpDataType.ARRAY_BUFFER,
        connectTimeout: 8000,
        readTimeout: 15000,
      });
      if (response.responseCode < 200 || response.responseCode >= 300) {
        throw new Error(`原图读取失败 HTTP ${response.responseCode}`);
      }
      return new Uint8Array(response.result as ArrayBuffer);
    } finally {
      client.destroy();
    }
  }

  private fitScale(): number {
    if (this.viewW <= 0 || this.viewH <= 0) {
      return 1;
    }
    return Math.min(this.viewW / this.sourceW, this.viewH / this.sourceH) * this.zoom;
  }

  private imageX(): number {
    return (this.viewW - this.sourceW * this.fitScale()) / 2 + this.panX;
  }

  private imageY(): number {
    return (this.viewH - this.sourceH * this.fitScale()) / 2 + this.panY;
  }

  private drawOverlay(): void {
    const s = this.fitScale();
    const dx = this.imageX();
    const dy = this.imageY();
    this.overlayCtx.clearRect(0, 0, this.viewW, this.viewH);
    if (this.initialOverlay !== null) {
      this.overlayCtx.globalAlpha = 0.50;
      this.overlayCtx.drawImage(this.initialOverlay, dx, dy, this.sourceW * s, this.sourceH * s);
      this.overlayCtx.globalAlpha = 1;
    }
    for (const stroke of this.strokes) {
      this.paintOverlayStroke(stroke);
    }
    if (this.active !== null) {
      this.paintOverlayStroke(this.active);
    }
    this.overlayCtx.globalCompositeOperation = 'source-over';
  }

  private paintOverlayStroke(stroke: MaskStroke): void {
    if (stroke.points.length === 0) {
      return;
    }
    const s = this.fitScale();
    const dx = this.imageX();
    const dy = this.imageY();
    const ctx = this.overlayCtx;
    ctx.globalCompositeOperation = stroke.erase ? 'destination-out' : 'source-over';
    ctx.globalAlpha = stroke.erase ? 1 : 0.45;
    ctx.strokeStyle = this.palette().accent;
    ctx.fillStyle = this.palette().accent;
    ctx.lineWidth = Math.max(1, stroke.size * s);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    const points = stroke.points;
    if (points.length === 1) {
      ctx.beginPath();
      ctx.arc(dx + points[0].x * s, dy + points[0].y * s, ctx.lineWidth / 2, 0, Math.PI * 2);
      ctx.fill();
      ctx.globalAlpha = 1;
      return;
    }
    ctx.beginPath();
    ctx.moveTo(dx + points[0].x * s, dy + points[0].y * s);
    for (let i = 1; i < points.length - 1; i++) {
      const control = points[i];
      const next = points[i + 1];
      const midX = (points[i].x + next.x) / 2;
      const midY = (points[i].y + next.y) / 2;
      ctx.quadraticCurveTo(
        dx + control.x * s,
        dy + control.y * s,
        dx + midX * s,
        dy + midY * s,
      );
    }
    const last = points[points.length - 1];
    ctx.lineTo(dx + last.x * s, dy + last.y * s);
    ctx.stroke();
    ctx.globalAlpha = 1;
  }

  private paintOverlaySegment(from: MaskPoint, to: MaskPoint, size: number, erase: boolean): void {
    const s = this.fitScale();
    const dx = this.imageX();
    const dy = this.imageY();
    const ctx = this.overlayCtx;
    ctx.globalCompositeOperation = erase ? 'destination-out' : 'source-over';
    ctx.strokeStyle = this.palette().accent;
    ctx.lineWidth = Math.max(1, size * s);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.moveTo(dx + from.x * s, dy + from.y * s);
    ctx.lineTo(dx + to.x * s, dy + to.y * s);
    ctx.stroke();
  }

  private toImage(x: number, y: number): MaskPoint {
    const s = this.fitScale();
    return {
      x: Math.max(0, Math.min(this.sourceW, (x - this.imageX()) / s)),
      y: Math.max(0, Math.min(this.sourceH, (y - this.imageY()) / s)),
    };
  }

  private beginStroke(x: number, y: number): void {
    const p = this.toImage(x, y);
    this.active = { points: [p], size: Math.max(2, this.brush / this.fitScale()), erase: this.erasing };
    this.drawing = true;
    this.overlayTick++;
    this.drawOverlay();
  }

  private extendStroke(x: number, y: number): void {
    if (this.active === null) {
      return;
    }
    const p = this.toImage(x, y);
    this.active.points.push(p);
    // 拖动时只更新屏幕上的蒙版层；离屏图在抬笔后一次画完，避免每帧操作大图。
    this.drawOverlay();
    this.overlayTick++;
  }

  private endStroke(): void {
    const finished = this.active;
    if (finished !== null) {
      this.applyStroke(finished);
      this.strokes.push(finished);
      this.active = null;
    }
    this.drawing = false;
    this.overlayTick++;
    this.drawOverlay();
  }

  private applySegment(from: MaskPoint, to: MaskPoint, size: number, erase: boolean): void {
    const ctx = this.offCtx;
    if (ctx === null || this.baseBitmap === null) {
      return;
    }
    if (erase) {
      const minX = Math.max(0, Math.min(from.x, to.x) - size);
      const minY = Math.max(0, Math.min(from.y, to.y) - size);
      const maxX = Math.min(this.sourceW, Math.max(from.x, to.x) + size);
      const maxY = Math.min(this.sourceH, Math.max(from.y, to.y) + size);
      ctx.save();
      ctx.beginPath();
      ctx.rect(minX, minY, maxX - minX, maxY - minY);
      ctx.clip();
      ctx.globalCompositeOperation = 'source-over';
      ctx.drawImage(this.baseBitmap, 0, 0);
      ctx.restore();
      return;
    }
    ctx.globalCompositeOperation = 'destination-out';
    ctx.strokeStyle = '#FFFFFF';
    ctx.lineWidth = size;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.moveTo(from.x, from.y);
    ctx.lineTo(to.x, to.y);
    ctx.stroke();
    ctx.globalCompositeOperation = 'source-over';
  }

  private applyStroke(stroke: MaskStroke): void {
    if (stroke.points.length === 0) {
      return;
    }
    if (stroke.erase) {
      for (let i = 1; i < stroke.points.length; i++) {
        this.applySegment(stroke.points[i - 1], stroke.points[i], stroke.size, true);
      }
      return;
    }
    const ctx = this.offCtx;
    if (ctx === null) {
      return;
    }
    const points = stroke.points;
    ctx.globalCompositeOperation = 'destination-out';
    ctx.strokeStyle = '#FFFFFF';
    ctx.lineWidth = stroke.size;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.moveTo(points[0].x, points[0].y);
    if (points.length === 1) {
      ctx.arc(points[0].x, points[0].y, stroke.size / 2, 0, Math.PI * 2);
      ctx.fillStyle = '#FFFFFF';
      ctx.fill();
    } else {
      for (let i = 1; i < points.length - 1; i++) {
        const control = points[i];
        const next = points[i + 1];
        ctx.quadraticCurveTo(
          control.x,
          control.y,
          (points[i].x + next.x) / 2,
          (points[i].y + next.y) / 2,
        );
      }
      const last = points[points.length - 1];
      ctx.lineTo(last.x, last.y);
      ctx.stroke();
    }
    ctx.globalCompositeOperation = 'source-over';
  }

  private rebuildOffscreen(): void {
    const ctx = this.offCtx;
    if (ctx === null || this.baseBitmap === null) {
      return;
    }
    ctx.globalCompositeOperation = 'source-over';
    ctx.clearRect(0, 0, this.sourceW, this.sourceH);
    ctx.drawImage(this.baseBitmap, 0, 0);
    if (this.initialMaskBitmap !== null) {
      ctx.globalCompositeOperation = 'destination-out';
      ctx.drawImage(this.initialMaskBitmap, 0, 0);
      ctx.globalCompositeOperation = 'source-over';
    }
    for (const stroke of this.strokes) {
      this.applyStroke(stroke);
    }
  }

  private handleTouch(event: TouchEvent): void {
    const touches = event.touches;
    if (touches.length >= 2) {
      const first = touches[0];
      const second = touches[1];
      const dx = second.x - first.x;
      const dy = second.y - first.y;
      const dist = Math.sqrt(dx * dx + dy * dy);
      if (!this.pinching) {
        this.pinching = true;
        this.drawing = false;
        this.pinchStartZoom = this.zoom;
        this.pinchStartDist = Math.max(1, dist);
        this.pinchLastX = (first.x + second.x) / 2;
        this.pinchLastY = (first.y + second.y) / 2;
      } else {
        this.zoom = Math.max(1, Math.min(5, this.pinchStartZoom * dist / this.pinchStartDist));
        const centerX = (first.x + second.x) / 2;
        const centerY = (first.y + second.y) / 2;
        this.panX += centerX - this.pinchLastX;
        this.panY += centerY - this.pinchLastY;
        this.pinchLastX = centerX;
        this.pinchLastY = centerY;
      }
      return;
    }
    if (this.pinching) {
      if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
        this.pinching = false;
      }
      return;
    }
    if (touches.length === 0) {
      if (this.drawing) {
        this.endStroke();
      }
      return;
    }
    const x = touches[0].x;
    const y = touches[0].y;
    if (event.type === TouchType.Down) {
      this.beginStroke(x, y);
    } else if (event.type === TouchType.Move && this.drawing) {
      this.extendStroke(x, y);
    } else if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
      this.endStroke();
    }
  }

  private async save(): Promise<void> {
    if (this.saving) {
      return;
    }
    this.saving = true;
    try {
      if (this.offCtx === null) {
        throw new Error('画布还没准备好');
      }
      // 用户可能点保存时手指刚抬起，Touch Up 尚未派发；先把当前笔画落盘。
      if (this.active !== null) {
        this.applyStroke(this.active);
        this.strokes.push(this.active);
        this.active = null;
        this.drawing = false;
      }
      const exported = this.offCtx.getPixelMap(0, 0, this.sourceW, this.sourceH);
      const info = await exported.getImageInfo();
      const exportW = info.size.width;
      const exportH = info.size.height;
      let result = exported;
      if (exportW !== this.sourceW || exportH !== this.sourceH) {
        const raw = new ArrayBuffer(exportW * exportH * 4);
        await exported.readPixelsToBuffer(raw);
        const sourcePixels = new Uint8Array(raw);
        const croppedPixels = new Uint8Array(this.sourceW * this.sourceH * 4);
        for (let y = 0; y < this.sourceH; y++) {
          const from = y * exportW * 4;
          const to = y * this.sourceW * 4;
          croppedPixels.set(sourcePixels.subarray(from, from + this.sourceW * 4), to);
        }
        result = await image.createPixelMap(croppedPixels.buffer, {
          size: { width: this.sourceW, height: this.sourceH },
          srcPixelFormat: image.PixelMapFormat.RGBA_8888,
          pixelFormat: image.PixelMapFormat.RGBA_8888,
          editable: false,
        });
        await exported.release();
      }
      const packer = image.createImagePacker();
      const data = await packer.packToData(result, { format: 'image/png', quality: 100 });
      await result.release();
      await packer.release();
      this.onSave(new Uint8Array(data));
    } catch (err) {
      this.error = (err as Error).message;
    } finally {
      this.saving = false;
    }
  }

  private onToolChanged(): void {
    this.drawToolbar();
  }

  private paintIcon(ctx: CanvasRenderingContext2D, path: string, active: boolean): void {
    if (ctx === undefined || ctx === null) {
      return;
    }
    try {
      ctx.clearRect(0, 0, 22, 22);
      ctx.save();
      const iconScale = vp2px(22) / 24;
      ctx.scale(iconScale, iconScale);
      ctx.fillStyle = active ? this.palette().accent : this.palette().textMuted;
      ctx.fill(new Path2D(path));
      ctx.restore();
    } catch (err) {
      console.info(`CP> mask icon draw failed ${(err as Error).message}`);
    }
  }

  private drawToolbar(): void {
    this.paintIcon(this.backCtx, ICON_BACK, false);
    this.paintIcon(this.checkCtx, ICON_CHECK, true);
    this.paintIcon(this.brushCtx, ICON_BRUSH, !this.erasing);
    this.paintIcon(this.eraserCtx, ICON_ERASER, this.erasing);
    this.paintIcon(this.undoCtx, ICON_UNDO, false);
    this.paintIcon(this.clearCtx, ICON_CLEAR, false);
  }

  @Builder
  IconButton(ctx: CanvasRenderingContext2D, path: string, active: boolean, action: () => void) {
    Stack({ alignContent: Alignment.Center }) {
      Canvas(ctx)
        .width(22)
        .height(22)
        .onReady(() => {
          ctx.clearRect(0, 0, 22, 22);
          ctx.save();
          const iconScale = vp2px(22) / 24;
          ctx.scale(iconScale, iconScale);
          ctx.fillStyle = active ? this.palette().accent : this.palette().textMuted;
          ctx.fill(new Path2D(path));
          ctx.restore();
        })
    }
    .width(40)
    .height(40)
    .borderRadius(20)
    .backgroundColor(active ? this.palette().accentSoft : this.palette().card)
    .border({ width: 1, color: active ? this.palette().accentBorder : this.palette().border })
    .onClick(() => action())
  }

  @Builder
  ToolButton(ctx: CanvasRenderingContext2D, path: string, active: boolean, action: () => void) {
    this.IconButton(ctx, path, active, action)
  }

  build() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor('#0D000000')
        .onClick(() => this.onClose())
      Column() {
        Row() {
          this.IconButton(this.backCtx, ICON_BACK, false, () => this.onClose())
          Text('编辑蒙版')
            .fontSize(16)
            .fontWeight(FontWeight.Medium)
            .fontColor(this.palette().text)
            .layoutWeight(1)
            .margin({ left: 8 })
          this.IconButton(this.checkCtx, ICON_CHECK, true, () => {
            this.save();
          })
        }
        .width('100%')
        .padding({ left: 6, right: 6, top: 6, bottom: 6 })

        if (this.loading) {
          Column() {
            LoadingProgress().width(28).height(28).color(this.palette().accent)
            Text('正在读取原图…').fontSize(12).fontColor(this.palette().textMuted).margin({ top: 8 })
          }
          .layoutWeight(1)
          .justifyContent(FlexAlign.Center)
        } else if (this.error.length > 0) {
          Column() {
            Text(this.error).fontSize(12).fontColor(this.palette().err).textAlign(TextAlign.Center)
            Text('关闭').fontSize(12).fontColor(this.palette().accent).margin({ top: 12 }).onClick(() => this.onClose())
          }
          .layoutWeight(1)
          .justifyContent(FlexAlign.Center)
          .padding(24)
        } else {
          Stack({ alignContent: Alignment.TopStart }) {
            if (this.basePm !== null) {
              Image(this.basePm)
                .width('100%')
                .height('100%')
                .objectFit(ImageFit.Contain)
                .scale({ x: this.zoom, y: this.zoom, centerX: '50%', centerY: '50%' })
                .translate({ x: this.panX, y: this.panY })
                .interpolation(ImageInterpolation.High)
            }
            Canvas(this.overlayCtx)
              .width('100%')
              .height('100%')
              .onReady(() => this.drawOverlay())
              .onTouch((event: TouchEvent) => this.handleTouch(event))
          }
          .width('100%')
          .layoutWeight(1)
          .margin({ left: 10, right: 10 })
          .clip(true)
          .borderRadius(16)
          .backgroundColor('#000000')
          .onAreaChange((oldArea: Area, newArea: Area) => {
            this.viewW = Number(newArea.width);
            this.viewH = Number(newArea.height);
            this.drawOverlay();
          })
        }

        Row({ space: 8 }) {
          this.ToolButton(this.brushCtx, ICON_BRUSH, !this.erasing, () => { this.erasing = false; this.overlayTick++; })
          this.ToolButton(this.eraserCtx, ICON_ERASER, this.erasing, () => { this.erasing = true; this.overlayTick++; })
          this.ToolButton(this.undoCtx, ICON_UNDO, false, () => {
            if (this.strokes.length > 0) {
              this.strokes.pop();
              this.rebuildOffscreen();
              this.drawOverlay();
            }
          })
          this.ToolButton(this.clearCtx, ICON_CLEAR, false, () => {
            this.strokes = [];
            this.rebuildOffscreen();
            this.drawOverlay();
          })
          Slider({ value: this.brush, min: 8, max: 100, style: SliderStyle.OutSet })
            .layoutWeight(1)
            .selectedColor(this.palette().accent)
            .blockColor(this.palette().accent)
            .trackColor(this.palette().cardStrong)
            .onChange((value: number) => { this.brush = value; })
        }
        .width('100%')
        .padding({ left: 10, right: 10, top: 8, bottom: 12 })
      }
      .width('92%')
      .height('90%')
      .backgroundColor(this.palette().dialog)
      .borderRadius(22)
      .border({ width: 1, color: this.palette().borderStrong })
      .clip(true)
    }
    .width('100%')
    .height('100%')
  }
}
