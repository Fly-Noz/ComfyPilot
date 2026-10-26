import { image } from '@kit.ImageKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { fileIo } from '@kit.CoreFileKit';
import { http } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';

const MAX_UPLOAD_BYTES = 50 * 1024 * 1024;

export interface PickedImage {
  filename: string;
  bytes: Uint8Array;
  /** 相册 URI，只用于本机缩略图；上传后会话内有效。 */
  uri: string;
}

export interface ImageUploadResult {
  ok: boolean;
  reference: string;
  message: string;
}

interface CompressedImage {
  name: string;
  bytes: Uint8Array;
}

interface UploadImageResponse {
  name?: string;
  subfolder?: string;
  type?: string;
}

/**
 * 鸿蒙相册选图 + ComfyUI /upload/image。
 *
 * 选中的媒体 URI 只在本进程内读取；上传成功后把服务器返回的文件名交回
 * LoadImage.image。上传不进入任务状态机，失败也不会影响已有任务。
 */
export class ImageUploader {

  async pick(): Promise<PickedImage | null> {
    console.info('CP> upload picker open');
    const picker = new photoAccessHelper.PhotoViewPicker();
    const options = new photoAccessHelper.PhotoSelectOptions();
    options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
    options.maxSelectNumber = 1;
    const result = await picker.select(options);
    if (result.photoUris.length === 0) {
      console.info('CP> upload picker cancelled');
      return null;
    }

    const uri = result.photoUris[0];
    const stat = fileIo.statSync(uri);
    console.info(`CP> upload picked size=${stat.size} uri=${uri.substring(0, 80)}`);
    if (stat.size <= 0) {
      throw new Error('图片内容为空');
    }
    if (stat.size > MAX_UPLOAD_BYTES) {
      throw new Error('图片超过 50 MB');
    }

    const file = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
    try {
      const buffer = new ArrayBuffer(stat.size);
      const read = fileIo.readSync(file.fd, buffer, { offset: 0, length: stat.size });
      if (read !== stat.size) {
        throw new Error('读取图片不完整');
      }
      const original = new Uint8Array(buffer);
      const compressed = await this.compressForUpload(uri, original);
      return {
        filename: compressed.name,
        bytes: compressed.bytes,
        uri: uri,
      };
    } finally {
      fileIo.closeSync(file);
    }
  }

  /**
   * 上传前压缩：最长边限制 2048、JPEG q90。
   *
   * 相册原图经常 5–20 MB，经 Tailscale/热点上传会直接撞上 60s 超时；
   * 压到 1 MB 左右既快又够 LoadImage 用。小图/已很小的图保持原样。
   */
  private async compressForUpload(
    uri: string,
    original: Uint8Array,
  ): Promise<CompressedImage> {
    const baseName = this.filenameFromUri(uri);
    if (original.length <= 2 * 1024 * 1024) {
      // 仍然检查尺寸：2MB 以内但 6000px 的图也压一下
    }
    // 相册返回的是 media URI，createImageSource(string) 拿到的是 undefined；
    // 必须先打开 fd，再用 fd 创建 ImageSource。
    const file = await fileIo.open(uri, fileIo.OpenMode.READ_ONLY);
    const source = image.createImageSource(file.fd);
    let pixelMap: image.PixelMap | undefined = undefined;
    const packer = image.createImagePacker();
    try {
      const info = await source.getImageInfo();
      const width = info.size.width;
      const height = info.size.height;
      const maxSide = Math.max(width, height);
      if (original.length <= 2 * 1024 * 1024 && maxSide <= 2048) {
        return { name: baseName, bytes: original };
      }
      const scale = maxSide > 2048 ? 2048 / maxSide : 1;
      const targetWidth = Math.max(1, Math.round(width * scale));
      const targetHeight = Math.max(1, Math.round(height * scale));
      const size: image.Size = { width: targetWidth, height: targetHeight };
      const options: image.DecodingOptions = { desiredSize: size };
      pixelMap = await source.createPixelMap(options);
      const packOption: image.PackingOption = { format: 'image/jpeg', quality: 90 };
      const data = await packer.packToData(pixelMap, packOption);
      const bytes = new Uint8Array(data);
      const dot = baseName.lastIndexOf('.');
      const name = `${dot > 0 ? baseName.substring(0, dot) : baseName}.jpg`;
      console.info(
        `CP> upload compressed ${width}x${height} ${original.length}B -> ` +
        `${targetWidth}x${targetHeight} ${bytes.length}B`,
      );
      return { name, bytes };
    } catch (err) {
      console.info(`CP> upload compress failed ${(err as Error).message}, 使用原图`);
      return { name: baseName, bytes: original };
    } finally {
      await packer.release();
      if (pixelMap !== undefined) {
        await pixelMap.release();
      }
      if (source !== undefined) {
        await source.release();
      }
      fileIo.closeSync(file);
    }
  }

  async upload(baseUrl: string, image: PickedImage): Promise<ImageUploadResult> {
    const base = baseUrl.trim().replace(/\/+$/, '');
    if (base.length === 0) {
      return { ok: false, reference: '', message: '未连接 ComfyUI' };
    }
    if (image.bytes.length === 0) {
      return { ok: false, reference: '', message: '图片内容为空' };
    }

    const boundary = `----ComfyPilot${Date.now().toString(16)}${Math.floor(Math.random() * 0x7fffffff).toString(16)}`;
    const body = this.multipartBody(boundary, image);
    console.info(`CP> upload POST ${base}/upload/image bytes=${body.byteLength} filename=${image.filename}`);
    const client = http.createHttp();
    try {
      const response = await client.request(`${base}/upload/image`, {
        method: http.RequestMethod.POST,
        header: {
          'Content-Type': `multipart/form-data; boundary=${boundary}`,
        },
        extraData: body,
        expectDataType: http.HttpDataType.STRING,
        connectTimeout: 60_000,
        readTimeout: 180_000,
      });
      if (response.responseCode < 200 || response.responseCode >= 300) {
        console.error(`CP> upload HTTP ${response.responseCode}`);
        return {
          ok: false,
          reference: '',
          message: `服务器返回 ${response.responseCode}`,
        };
      }
      const text = typeof response.result === 'string' ? response.result : '';
      console.info(`CP> upload HTTP ${response.responseCode} result=${text.substring(0, 200)}`);
      let parsed: UploadImageResponse;
      try {
        parsed = JSON.parse(text) as UploadImageResponse;
      } catch (ignored) {
        return { ok: false, reference: '', message: '无法解析上传响应' };
      }
      const name = typeof parsed.name === 'string' ? parsed.name.trim() : '';
      if (name.length === 0) {
        return { ok: false, reference: '', message: '服务器没有返回文件名' };
      }
      const subfolder = typeof parsed.subfolder === 'string' ? parsed.subfolder.trim() : '';
      const reference = subfolder.length > 0 ? `${subfolder}/${name}` : name;
      return { ok: true, reference: reference, message: `已上传：${reference}` };
    } catch (err) {
      const message = (err as Error).message;
      console.error(`CP> upload failed ${message}`);
      return {
        ok: false,
        reference: '',
        message: message !== undefined && message.length > 0 ? message : '上传失败',
      };
    } finally {
      client.destroy();
    }
  }

  private multipartBody(boundary: string, image: PickedImage): ArrayBuffer {
    const encoder = new util.TextEncoder();
    const filename = this.safeFilename(image.filename);
    const head = encoder.encodeInto(
      `--${boundary}\r\n` +
      `Content-Disposition: form-data; name="type"\r\n\r\n` +
      `input\r\n` +
      `--${boundary}\r\n` +
      `Content-Disposition: form-data; name="image"; filename="${filename}"\r\n` +
      `Content-Type: application/octet-stream\r\n\r\n`,
    );
    const tail = encoder.encodeInto(`\r\n--${boundary}--\r\n`);
    const output = new Uint8Array(head.length + image.bytes.length + tail.length);
    output.set(head, 0);
    output.set(image.bytes, head.length);
    output.set(tail, head.length + image.bytes.length);
    return output.buffer;
  }

  private filenameFromUri(uri: string): string {
    const raw = uri.substring(uri.lastIndexOf('/') + 1);
    let decoded = raw;
    try {
      decoded = decodeURIComponent(raw);
    } catch (ignored) {
      // 保留原始 URI 片段即可。
    }
    if (decoded.length === 0) {
      return `image-${Date.now()}.jpg`;
    }
    if (decoded.lastIndexOf('.') < 0) {
      return `${decoded}-${Date.now()}.jpg`;
    }
    return decoded;
  }

  private safeFilename(name: string): string {
    const cleaned = name
      .replace(/[\r\n"\\/:*?<>|]/g, '_')
      .trim();
    return cleaned.length > 0 ? cleaned.slice(0, 120) : `image-${Date.now()}.jpg`;
  }
}
