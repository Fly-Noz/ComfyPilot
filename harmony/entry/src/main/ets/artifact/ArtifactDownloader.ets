import { common } from '@kit.AbilityKit';
import { fileIo, fileUri, picker } from '@kit.CoreFileKit';
import { image } from '@kit.ImageKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { request } from '@kit.BasicServicesKit';
import { BridgeOutput } from '../bridge/ComfyBridge';

export interface ArtifactDownloadResult {
  ok: boolean;
  name: string;
  message: string;
  cancelled?: boolean;
}

/**
 * 把 ComfyUI 的 `/view` 产物保存到用户可见位置。
 *
 * 图片/视频走 `showAssetsCreationDialog`：系统弹出保存确认框，用户允许后
 * 返回相册 URI，再把缓存文件写进去。该方式不申请 WRITE_IMAGEVIDEO 受限权限。
 *
 * 音频和其他文件仍走 FilePicker 的 DOWNLOAD 模式，保存到 `Download/<包名>/`。
 */
export class ArtifactDownloader {

  async download(
    context: common.UIAbilityContext,
    baseUrl: string,
    output: BridgeOutput,
  ): Promise<ArtifactDownloadResult> {
    let base = baseUrl.trim();
    while (base.endsWith('/')) {
      base = base.substring(0, base.length - 1);
    }
    if (base.length === 0) {
      return { ok: false, name: '', message: '未连接 ComfyUI' };
    }

    const extension = this.extensionOf(output);
    const tempName = `comfypilot-${Date.now()}-${Math.floor(Math.random() * 100000)}.${extension}`;
    const tempPath = `${context.cacheDir}/${tempName}`;

    try {
      await this.downloadToCache(context, base, output, tempPath, output.displayName);
      if (output.image) {
        // 相册对 RGBA PNG 的"中等预览"会解出块状色带（全屏才正常）。
        // 统一转成 JPEG 再写入相册，避开这条解码路径。
        const pngPath = await this.convertToPng(tempPath);
        return await this.saveToGallery(context, pngPath, output, true);
      }
      if (output.video) {
        return await this.saveToGallery(context, tempPath, output, false);
      }
      return await this.saveToDownloads(context, tempPath, output);
    } catch (err) {
      const message = this.errorText(err);
      console.info(`CPERR download/save failed: ${message}`);
      return { ok: false, name: '', message };
    } finally {
      try {
        fileIo.unlinkSync(tempPath);
      } catch (ignored) {
        // 临时文件不存在或已被系统清理时，不影响已经保存的文件。
      }
      try {
        fileIo.unlinkSync(`${tempPath}.png`);
      } catch (ignored) {
        // 临时文件不存在或已被系统清理时，不影响已经保存的文件。
      }
    }
  }

  private async downloadToCache(
    context: common.UIAbilityContext,
    base: string,
    output: BridgeOutput,
    tempPath: string,
    title: string,
  ): Promise<void> {
    const task = await request.downloadFile(context, {
      url: base + output.path,
      filePath: tempPath,
      enableMetered: true,
      enableRoaming: true,
      title: title,
    });
    await new Promise<void>((resolve, reject) => {
      let settled = false;
      const timer = setTimeout(() => {
        if (settled) {
          return;
        }
        settled = true;
        task.delete().catch((ignored: object) => {
          // 取消失败时外层 finally 仍会清理临时文件。
        });
        reject(new Error('下载超时'));
      }, 10 * 60 * 1000);
      task.on('complete', () => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(timer);
        resolve();
      });
      task.on('fail', (err: number) => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(timer);
        reject(new Error(`下载失败（${err}）`));
      });
    });
  }

  /**
   * 解码后自己编码成 8-bit RGB PNG（colorType=2，无 alpha）。
   *
   * 原因：相册的"中等预览"对 RGBA PNG 会解出色块，全屏才正常；
   * ImageKit 的 PNG 打包器不提供去 alpha 的选项（RGB_888 解码在该机也不支持），
   * 所以这里读出像素、丢掉 alpha、拼 PNG 块，再用系统 zlib 压缩 IDAT。
   */
  private async convertToPng(tempPath: string): Promise<string> {
    const source = image.createImageSource(tempPath);
    let pixelMap: image.PixelMap | undefined = undefined;
    try {
      const info = await source.getImageInfo();
      const width = info.size.width;
      const height = info.size.height;
      console.info(`CPIMG convert start ${width}x${height}`);
      pixelMap = await source.createPixelMap();
      const pixelInfo = await pixelMap.getImageInfo();
      console.info(
        `CPIMG pixel ${pixelInfo.size.width}x${pixelInfo.size.height} format=${pixelInfo.pixelFormat}`,
      );
      const rgba = new ArrayBuffer(width * height * 4);
      await pixelMap.readPixelsToBuffer(rgba);
      console.info(`CPIMG readPixels ok bytes=${rgba.byteLength}`);
      const pixels = new Uint8Array(rgba);

      // 每行：filter byte 0 + RGB
      const stride = width * 3 + 1;
      const raw = new Uint8Array(stride * height);
      for (let y = 0; y < height; y++) {
        const srcRow = y * width * 4;
        const dstRow = y * stride;
        raw[dstRow] = 0;
        for (let x = 0; x < width; x++) {
          const s = srcRow + x * 4;
          const d = dstRow + 1 + x * 3;
          raw[d] = pixels[s];
          raw[d + 1] = pixels[s + 1];
          raw[d + 2] = pixels[s + 2];
        }
      }

      console.info(`CPIMG raw built stride=${stride} bytes=${raw.length}`);
      // 不依赖 zlib 文件接口（compressFile 在此机型不产出目标文件）：
      // 用 deflate 的 stored 块 + zlib 头 + adler32，PNG 规范完全合法。
      const deflated = this.zlibStored(raw);
      console.info(`CPIMG deflate stored bytes=${deflated.length}`);
      const png = this.buildPng(width, height, deflated);
      const pngPath = `${tempPath}.png`;
      await this.writeBytes(pngPath, png);
      await this.logPngHeader(pngPath);
      return pngPath;
    } finally {
      if (pixelMap !== undefined) {
        await pixelMap.release();
      }
      await source.release();
    }
  }

  private async writeBytes(path: string, data: Uint8Array): Promise<void> {
    const file = await fileIo.open(
      path,
      fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC,
    );
    try {
      await fileIo.write(file.fd, data.buffer);
    } finally {
      fileIo.closeSync(file);
    }
  }

  private async readBytes(path: string): Promise<Uint8Array> {
    const stat = await fileIo.stat(path);
    const buffer = new ArrayBuffer(stat.size);
    const file = await fileIo.open(path, fileIo.OpenMode.READ_ONLY);
    try {
      await fileIo.read(file.fd, buffer);
    } finally {
      fileIo.closeSync(file);
    }
    return new Uint8Array(buffer);
  }

  /** zlib 容器 + deflate stored 块（不压缩），不依赖系统 zlib。 */
  private zlibStored(raw: Uint8Array): Uint8Array {
    const blocks = Math.max(1, Math.ceil(raw.length / 65535));
    const out = new Uint8Array(2 + raw.length + blocks * 5 + 4);
    out[0] = 0x78;
    out[1] = 0x01;
    let offset = 2;
    let position = 0;
    while (position < raw.length) {
      const length = Math.min(65535, raw.length - position);
      const final = position + length >= raw.length ? 1 : 0;
      out[offset++] = final;
      out[offset++] = length & 0xff;
      out[offset++] = (length >>> 8) & 0xff;
      out[offset++] = (~length) & 0xff;
      out[offset++] = ((~length) >>> 8) & 0xff;
      out.set(raw.subarray(position, position + length), offset);
      offset += length;
      position += length;
    }
    const adler = this.adler32(raw);
    out[offset++] = (adler >>> 24) & 0xff;
    out[offset++] = (adler >>> 16) & 0xff;
    out[offset++] = (adler >>> 8) & 0xff;
    out[offset++] = adler & 0xff;
    return out.subarray(0, offset);
  }

  private adler32(data: Uint8Array): number {
    let a = 1;
    let b = 0;
    for (let i = 0; i < data.length; i++) {
      a = (a + data[i]) % 65521;
      b = (b + a) % 65521;
    }
    return ((b << 16) | a) >>> 0;
  }

  /** 组装 PNG：签名 + IHDR(colorType=2) + IDAT + IEND，每块带 CRC32。 */
  private buildPng(width: number, height: number, deflated: Uint8Array): Uint8Array {
    const ihdr = new Uint8Array(13);
    const view = new DataView(ihdr.buffer);
    view.setUint32(0, width);
    view.setUint32(4, height);
    ihdr[8] = 8;
    ihdr[9] = 2;
    ihdr[10] = 0;
    ihdr[11] = 0;
    ihdr[12] = 0;
    const ihdrChunk = this.pngChunk('IHDR', ihdr);
    const idatChunk = this.pngChunk('IDAT', deflated);
    const iendChunk = this.pngChunk('IEND', new Uint8Array(0));
    const signature = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
    const total = signature.length + ihdrChunk.length + idatChunk.length + iendChunk.length;
    const out = new Uint8Array(total);
    let offset = 0;
    out.set(signature, offset);
    offset += signature.length;
    out.set(ihdrChunk, offset);
    offset += ihdrChunk.length;
    out.set(idatChunk, offset);
    offset += idatChunk.length;
    out.set(iendChunk, offset);
    return out;
  }

  private pngChunk(type: string, data: Uint8Array): Uint8Array {
    const chunk = new Uint8Array(4 + 4 + data.length + 4);
    const view = new DataView(chunk.buffer);
    view.setUint32(0, data.length);
    for (let i = 0; i < 4; i++) {
      chunk[4 + i] = type.charCodeAt(i);
    }
    chunk.set(data, 8);
    let crc = 0xffffffff;
    for (let i = 4; i < 8 + data.length; i++) {
      crc = this.crcTable[(crc ^ chunk[i]) & 0xff] ^ (crc >>> 8);
    }
    crc = (crc ^ 0xffffffff) >>> 0;
    view.setUint32(8 + data.length, crc);
    return chunk;
  }

  private crcTable: number[] = (() => {
    const table: number[] = [];
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) {
        c = (c & 1) !== 0 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      }
      table.push(c >>> 0);
    }
    return table;
  })();

  /** 打一行转换后 PNG 的 IHDR，确认 colorType 已经是 2（RGB）。 */
  private async logPngHeader(path: string): Promise<void> {
    try {
      const file = await fileIo.open(path, fileIo.OpenMode.READ_ONLY);
      try {
        const buf = new ArrayBuffer(64);
        const n = await fileIo.read(file.fd, buf);
        const bytes = new Uint8Array(buf.slice(0, n));
        if (bytes.length >= 29) {
          console.info(
            `CPIMG converted bitDepth=${bytes[24]} colorType=${bytes[25]} interlace=${bytes[28]}`,
          );
        }
      } finally {
        fileIo.closeSync(file);
      }
    } catch (err) {
      console.info(`CPIMG converted head failed ${(err as Error).message}`);
    }
  }

  private async saveToGallery(
    context: common.UIAbilityContext,
    tempPath: string,
    output: BridgeOutput,
    asImage: boolean,
  ): Promise<ArtifactDownloadResult> {
    // 记录文件头：PNG 的位深/颜色类型/隔行，JPEG/WebP 只记魔数，
    // 用于判断相册预览色块是否来自 16bit / 隔行 / 异常色彩配置。
    try {
      const head = await fileIo.open(tempPath, fileIo.OpenMode.READ_ONLY);
      try {
        const buf = new ArrayBuffer(64);
        const n = await fileIo.read(head.fd, buf);
        const bytes = new Uint8Array(buf.slice(0, n));
        let hex = '';
        for (let i = 0; i < Math.min(16, bytes.length); i++) {
          hex += bytes[i].toString(16).padStart(2, '0');
        }
        let detail = `head=${hex}`;
        if (bytes.length >= 33 &&
          bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) {
          detail += ` png width=${(bytes[16] << 24) | (bytes[17] << 16) | (bytes[18] << 8) | bytes[19]}` +
            ` height=${(bytes[20] << 24) | (bytes[21] << 16) | (bytes[22] << 8) | bytes[23]}` +
            ` bitDepth=${bytes[24]} colorType=${bytes[25]} interlace=${bytes[28]}`;
        }
        console.info(`CPIMG ${detail}`);
      } finally {
        fileIo.closeSync(head);
      }
    } catch (err) {
      console.info(`CPIMG head failed ${(err as Error).message}`);
    }

    const helper = photoAccessHelper.getPhotoAccessHelper(context);
    const srcUri = fileUri.getUriFromPath(tempPath);
    const configs: photoAccessHelper.PhotoCreationConfig[] = [{
      title: `${this.safeTitle(output)}_${Date.now()}`,
      fileNameExtension: asImage ? 'png' : this.extensionOf(output),
      photoType: asImage ? photoAccessHelper.PhotoType.IMAGE : photoAccessHelper.PhotoType.VIDEO,
      subtype: photoAccessHelper.PhotoSubtype.DEFAULT,
    }];
    const targets = await helper.showAssetsCreationDialog([srcUri], configs);
    if (targets.length === 0 || targets[0].length === 0) {
      return { ok: false, name: '', message: '已取消保存到相册', cancelled: true };
    }

    // 不用 fd 版 copyFile：相册资产创建后目标文件可能带着预分配/旧尾部，
    // 直接 copy 偶发写出带色块的坏图。这里先 TRUNC 再分块写满，并 fsync。
    const srcFile = await fileIo.open(srcUri, fileIo.OpenMode.READ_ONLY);
    const destFile = await fileIo.open(
      targets[0],
      fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.TRUNC,
    );
    try {
      const chunkSize = 1024 * 1024;
      const buffer = new ArrayBuffer(chunkSize);
      let written = 0;
      while (true) {
        const read = await fileIo.read(srcFile.fd, buffer);
        if (read <= 0) {
          break;
        }
        await fileIo.write(destFile.fd, buffer.slice(0, read));
        written += read;
      }
      await fileIo.fsync(destFile.fd);
      if (written === 0) {
        throw new Error('写入相册失败：没有写入任何数据');
      }
      console.info(`CPSAVE write bytes=${written} target=${targets[0]}`);
    } finally {
      fileIo.closeSync(srcFile);
      fileIo.closeSync(destFile);
    }

    // 回读校验：相册里那份和缓存源文件逐字节比较大小与简单校验和。
    try {
      const sourceStat = await fileIo.stat(tempPath);
      const checkSrc = await fileIo.open(srcUri, fileIo.OpenMode.READ_ONLY);
      const checkDst = await fileIo.open(targets[0], fileIo.OpenMode.READ_ONLY);
      try {
        const buffer = new ArrayBuffer(1024 * 1024);
        let sumSrc = 0;
        let sumDst = 0;
        let bytesSrc = 0;
        let bytesDst = 0;
        while (true) {
          const n = await fileIo.read(checkSrc.fd, buffer);
          if (n <= 0) {
            break;
          }
          const view = new Uint8Array(buffer.slice(0, n));
          for (let i = 0; i < view.length; i++) {
            sumSrc = (sumSrc + view[i]) % 1000003;
          }
          bytesSrc += n;
        }
        while (true) {
          const n = await fileIo.read(checkDst.fd, buffer);
          if (n <= 0) {
            break;
          }
          const view = new Uint8Array(buffer.slice(0, n));
          for (let i = 0; i < view.length; i++) {
            sumDst = (sumDst + view[i]) % 1000003;
          }
          bytesDst += n;
        }
        console.info(
          `CPSAVE verify src=${bytesSrc}/${sumSrc} dst=${bytesDst}/${sumDst} ` +
          `expect=${sourceStat.size} match=${bytesSrc === bytesDst && sumSrc === sumDst}`,
        );
      } finally {
        fileIo.closeSync(checkSrc);
        fileIo.closeSync(checkDst);
      }
    } catch (err) {
      console.info(`CPSAVE verify failed ${(err as Error).message}`);
    }
    const savedName = asImage ? `${this.safeTitle(output)}.png` : output.displayName;
    return { ok: true, name: savedName, message: `已保存到相册：${savedName}` };
  }

  private async saveToDownloads(
    context: common.UIAbilityContext,
    tempPath: string,
    output: BridgeOutput,
  ): Promise<ArtifactDownloadResult> {
    const directoryUri = await this.downloadDirectory(context);
    const directory = new fileUri.FileUri(directoryUri).path;
    const name = this.uniqueName(directory, this.safeName(output.displayName));
    const target = `${directory}/${name}`;
    await fileIo.copyFile(tempPath, target);
    return { ok: true, name: name, message: `已保存到下载目录：${name}` };
  }

  private async downloadDirectory(context: common.UIAbilityContext): Promise<string> {
    const options = new picker.DocumentSaveOptions();
    options.pickerMode = picker.DocumentPickerMode.DOWNLOAD;
    const viewPicker = new picker.DocumentViewPicker(context);
    const uris = await viewPicker.save(options);
    if (uris.length === 0 || uris[0].length === 0) {
      throw new Error('未获得下载目录');
    }
    return uris[0];
  }

  private extensionOf(output: BridgeOutput): string {
    const name = output.displayName.toLowerCase();
    const known = ['png', 'jpg', 'jpeg', 'webp', 'gif', 'bmp', 'heic', 'mp4', 'webm', 'mov', 'mkv'];
    for (const ext of known) {
      if (name.endsWith(`.${ext}`)) {
        return ext;
      }
    }
    const mime = output.mime.toLowerCase();
    if (mime === 'image/jpeg') {
      return 'jpg';
    }
    if (mime === 'image/png') {
      return 'png';
    }
    if (mime === 'image/webp') {
      return 'webp';
    }
    if (mime === 'image/gif') {
      return 'gif';
    }
    if (mime === 'video/mp4') {
      return 'mp4';
    }
    if (mime === 'video/webm') {
      return 'webm';
    }
    return output.video ? 'mp4' : 'png';
  }

  private safeTitle(output: BridgeOutput): string {
    const name = output.displayName;
    const dot = name.lastIndexOf('.');
    const raw = dot > 0 ? name.substring(0, dot) : name;
    const invalid = '\\/:*?"\'`<>|{}[]';
    let result = '';
    for (let i = 0; i < raw.length; i++) {
      const ch = raw.charAt(i);
      result += invalid.includes(ch) ? '_' : ch;
    }
    if (result.length === 0) {
      result = 'ComfyPilot';
    }
    return result.length > 100 ? result.substring(0, 100) : result;
  }

  private safeName(original: string): string {
    const raw = original.length > 0 ? original : 'artifact';
    const slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
    const base = slash >= 0 ? raw.substring(slash + 1) : raw;
    return base.length > 0 ? base : 'artifact';
  }

  private uniqueName(directory: string, original: string): string {
    if (!fileIo.accessSync(`${directory}/${original}`)) {
      return original;
    }
    const dot = original.lastIndexOf('.');
    const stem = dot > 0 ? original.substring(0, dot) : original;
    const extension = dot > 0 ? original.substring(dot) : '';
    let index = 1;
    while (true) {
      const candidate = `${stem} (${index})${extension}`;
      if (!fileIo.accessSync(`${directory}/${candidate}`)) {
        return candidate;
      }
      index++;
    }
  }

  private errorText(err: object): string {
    const message = (err as Error).message;
    return message !== undefined && message.length > 0 ? message : '保存失败';
  }
}
