import { common } from '@kit.AbilityKit';
import { fileIo, fileUri, picker } from '@kit.CoreFileKit';
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
      if (output.image || output.video) {
        return await this.saveToGallery(context, tempPath, output);
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

  private async saveToGallery(
    context: common.UIAbilityContext,
    tempPath: string,
    output: BridgeOutput,
  ): Promise<ArtifactDownloadResult> {
    const helper = photoAccessHelper.getPhotoAccessHelper(context);
    const srcUri = fileUri.getUriFromPath(tempPath);
    const configs: photoAccessHelper.PhotoCreationConfig[] = [{
      title: this.safeTitle(output),
      fileNameExtension: this.extensionOf(output),
      photoType: output.video ? photoAccessHelper.PhotoType.VIDEO : photoAccessHelper.PhotoType.IMAGE,
      subtype: photoAccessHelper.PhotoSubtype.DEFAULT,
    }];
    const targets = await helper.showAssetsCreationDialog([srcUri], configs);
    if (targets.length === 0 || targets[0].length === 0) {
      return { ok: false, name: '', message: '已取消保存到相册', cancelled: true };
    }

    const srcFile = await fileIo.open(srcUri, fileIo.OpenMode.READ_ONLY);
    const destFile = await fileIo.open(targets[0], fileIo.OpenMode.WRITE_ONLY);
    try {
      await fileIo.copyFile(srcFile.fd, destFile.fd);
    } finally {
      fileIo.closeSync(srcFile);
      fileIo.closeSync(destFile);
    }
    return { ok: true, name: output.displayName, message: `已保存到相册：${output.displayName}` };
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
