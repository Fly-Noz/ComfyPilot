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
        const jpegPath = await this.convertToJpeg(tempPath);
        return await this.saveToGallery(context, jpegPath, output, true);
      }
      if (output.video) {
        return await this.saveToGallery(context, tempPath, output, false);
      }
      return await this.saveToDownloads(context, tempPath, output);
    } catch (err) {
      return { ok: false, name: '', message: this.errorText(err) };
    } finally {
      try {
        fileIo.unlinkSync(tempPath);
      } catch (ignored) {
        // 临时文件不存在或已被系统清理时，不影响已经保存的文件。
      }
      try {
        fileIo.unlinkSync(`${tempPath}.jpg`);
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

  /** 解码后重新编码为 JPEG（q95），避免相册预览对 RGBA PNG 解出色块。 */
  private async convertToJpeg(tempPath: string): Promise<string> {
    const source = image.createImageSource(tempPath);
    const pixelMap = await source.createPixelMap();
    const packer = image.createImagePacker();
    try {
      const data = await packer.packToData(pixelMap, { format: 'image/jpeg', quality: 95 });
      const jpegPath = `${tempPath}.jpg`;
      const file = await fileIo.open(
        jpegPath,
        fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC,
      );
      try {
        await fileIo.write(file.fd, data);
      } finally {
        fileIo.closeSync(file);
      }
      return jpegPath;
    } finally {
      await packer.release();
      await pixelMap.release();
      await source.release();
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
      fileNameExtension: asImage ? 'jpg' : this.extensionOf(output),
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
    const savedName = asImage ? `${this.safeTitle(output)}.jpg` : output.displayName;
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
