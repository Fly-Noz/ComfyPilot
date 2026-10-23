import { common } from '@kit.AbilityKit';
import { fileIo, fileUri, picker } from '@kit.CoreFileKit';
import { request } from '@kit.BasicServicesKit';
import { BridgeOutput } from '../bridge/ComfyBridge';

export interface ArtifactDownloadResult {
  ok: boolean;
  name: string;
  message: string;
}

/**
 * 把 ComfyUI 的 `/view` 产物落到系统下载目录。
 *
 * 下载本身交给系统下载服务（避免把视频整体读进 ArkTS 内存），再用 FilePicker 的
 * DOWNLOAD 模式取得 `Download/<包名>/` 目录 URI，最后复制到该目录。
 *
 * 这里不使用 READ_WRITE_DOWNLOAD_DIRECTORY：该权限在当前 HarmonyOS 版本属于
 * 受限权限，需要 ACL；DOWNLOAD 模式由 Picker 授权，普通应用无需申请权限。
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
    try {
      const directoryUri = await this.downloadDirectory(context);
      const directory = new fileUri.FileUri(directoryUri).path;
      const name = this.uniqueName(directory, this.safeName(output.displayName));
      const target = `${directory}/${name}`;
      const tempName = `comfypilot-${Date.now()}-${Math.floor(Math.random() * 100000)}.bin`;
      const tempPath = `${context.cacheDir}/${tempName}`;

      const task = await request.downloadFile(context, {
        url: base + output.path,
        filePath: tempPath,
        enableMetered: true,
        enableRoaming: true,
        title: name,
      });
      await new Promise<void>((resolve, reject) => {
        let settled = false;
        const timer = setTimeout(() => {
          if (settled) {
            return;
          }
          settled = true;
          task.delete().catch((ignored: object) => {
            // 取消失败时删除临时文件的 finally 分支仍会清理。
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

      try {
        // 异步复制，避免大视频把 ArkTS UI 线程卡住。
        await fileIo.copyFile(tempPath, target);
      } finally {
        try {
          fileIo.unlinkSync(tempPath);
        } catch (ignored) {
          // 临时文件已被系统清理时不影响已经复制好的下载文件。
        }
      }
      return { ok: true, name: name, message: `已保存到下载目录：${name}` };
    } catch (err) {
      return { ok: false, name: '', message: this.errorText(err) };
    }
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
    return message !== undefined && message.length > 0 ? message : '下载失败';
  }
}
