import { common } from '@kit.AbilityKit';
import { image } from '@kit.ImageKit';
import { media } from '@kit.MediaKit';
import { request } from '@kit.BasicServicesKit';
import { fileIo } from '@kit.CoreFileKit';

/**
 * 视频产物首帧（海报帧）提取。
 *
 * 为什么不是 ImageSource：`@ohos.multimedia.image` 的 ImageSource 解不了视频容器，
 * ComfyUI 的 `/view` 对视频也只回视频本体，没有图片预览路由。鸿蒙侧能做抽帧的只有
 * `@ohos.multimedia.media` 的 AVImageGenerator，而本机 SDK（apiVersion 26 的 d.ts）
 * 里它 **只有 fdSrc**、没有 url / dataSrc：
 *
 *     interface AVImageGenerator {
 *         fdSrc?: AVFileDescriptor;
 *         fetchFrameByTime(timeUs: number, options: AVImageQueryOptions,
 *                          param: PixelMapParams): Promise<image.PixelMap>;
 *         release(): Promise<void>;
 *     }
 *
 * 所以流程是：把 `/view` 的整段视频下到应用缓存 → `fileIo.open` 拿 fd →
 * `fdSrc = { fd }` → `fetchFrameByTime(0, CLOSEST_SYNC, …)` → 拿到 PixelMap →
 * finally 里 `release()` 生成器、关 fd、删临时文件。
 *
 * 约定：
 * - 同一 URL 同时只跑一次提取（in-flight 去重）。
 * - 成功结果进内存缓存，最多 {@link POSTER_CACHE_LIMIT} 条，最旧的先丢。
 * - 失败结果也记一份（有上限），避免列表滚动重建组件时反复整段下载。
 * - 任何失败都 reject，调用方退回原来的哑光占位。
 */
const POSTER_CACHE_LIMIT: number = 12;
const POSTER_FAIL_LIMIT: number = 32;
const POSTER_TIMEOUT_MS: number = 60 * 1000;
const POSTER_WIDTH: number = 720;
const POSTER_HEIGHT: number = 405;
const POSTER_EXTENSIONS: string[] = ['mp4', 'm4v', 'mov', 'mkv', 'webm', 'avi', 'ts', 'm2ts'];

export class VideoPoster {
  private static instance: VideoPoster = new VideoPoster();

  /** 抽帧成功的结果，`url -> PixelMap`。 */
  private cache: Map<string, image.PixelMap> = new Map<string, image.PixelMap>();
  /** 缓存插入顺序，用来淘汰最旧的一条。 */
  private order: string[] = [];
  /** 正在跑的提取，`url -> Promise`。 */
  private pending: Map<string, Promise<image.PixelMap>> = new Map<string, Promise<image.PixelMap>>();
  /** 失败过的 URL（有上限）；重试会再打一遍整段下载，滚动重建时不该重复打。 */
  private failed: string[] = [];

  static shared(): VideoPoster {
    return VideoPoster.instance;
  }

  /**
   * 取某个视频产物的首帧。
   *
   * @param context Ability 上下文（只有 `cacheDir` 和下载任务需要它）。
   * @param url 视频直链，通常就是 `artifactUrl(output)`。
   * @returns 首帧 PixelMap；失败时 reject，调用方负责退回占位。
   */
  load(context: common.UIAbilityContext | null, url: string): Promise<image.PixelMap> {
    if (url.length === 0) {
      return Promise.reject(new Error('产物地址为空'));
    }
    const cached: image.PixelMap | undefined = this.cache.get(url);
    if (cached !== undefined) {
      return Promise.resolve(cached);
    }
    const running: Promise<image.PixelMap> | undefined = this.pending.get(url);
    if (running !== undefined) {
      return running;
    }
    if (this.hasFailed(url)) {
      return Promise.reject(new Error('首帧提取此前已失败'));
    }
    if (context === null) {
      return Promise.reject(new Error('没有 Ability 上下文'));
    }

    const task: Promise<image.PixelMap> = this.extract(context, url);
    this.pending.set(url, task);
    // 成败都在这里收尾：不把 pending 留着，也不让派生 promise 变成未处理拒绝。
    task.then((pixel: image.PixelMap) => {
      this.pending.delete(url);
      this.remember(url, pixel);
    }, (err: Error) => {
      this.pending.delete(url);
      this.rememberFailure(url);
    });
    return task;
  }

  /** 下载 + 抽帧；所有资源在 finally 里收干净。 */
  private async extract(context: common.UIAbilityContext, url: string): Promise<image.PixelMap> {
    const path: string =
      `${context.cacheDir}/poster-${Date.now()}-${Math.floor(Math.random() * 100000)}.${this.extensionOf(url)}`;
    let generator: media.AVImageGenerator | null = null;
    let fd: number = -1;
    try {
      await this.download(context, url, path);
      const opened = await fileIo.open(path, fileIo.OpenMode.READ_ONLY);
      fd = opened.fd;
      generator = await media.createAVImageGenerator();
      const descriptor: media.AVFileDescriptor = { fd: fd };
      generator.fdSrc = descriptor;
      const params: media.PixelMapParams = { width: POSTER_WIDTH, height: POSTER_HEIGHT };
      const frame: image.PixelMap = await generator.fetchFrameByTime(
        0,
        media.AVImageQueryOptions.AV_IMAGE_QUERY_CLOSEST_SYNC,
        params,
      );
      return frame;
    } finally {
      if (generator !== null) {
        try {
          await generator.release();
        } catch (err) {
          // 释放失败不影响已经抽到的帧，也不该让调用方误以为抽帧失败。
        }
      }
      if (fd >= 0) {
        try {
          fileIo.closeSync(fd);
        } catch (err) {
          // fd 已被系统关掉时 close 会抛，忽略。
        }
      }
      try {
        fileIo.unlinkSync(path);
      } catch (err) {
        // 临时文件不存在（下载就失败了）时忽略。
      }
    }
  }

  /** 把整段视频下到缓存目录；超时就取消任务并 reject。 */
  private async download(context: common.UIAbilityContext, url: string, path: string): Promise<void> {
    const task = await request.downloadFile(context, {
      url: url,
      filePath: path,
      enableMetered: true,
      enableRoaming: true,
      title: 'video-poster',
    });
    await new Promise<void>((resolve, reject) => {
      let settled = false;
      const timer = setTimeout(() => {
        if (settled) {
          return;
        }
        settled = true;
        task.delete().catch((ignored: object) => {
          // 取消失败时外层的 finally 仍会删临时文件。
        });
        reject(new Error('首帧提取超时'));
      }, POSTER_TIMEOUT_MS);
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
        reject(new Error(`首帧下载失败（${err}）`));
      });
    });
  }

  /**
   * 记一条成功结果并压缓存上限。
   *
   * 淘汰时只丢引用、不显式 `release()`：PixelMap 可能还在某个已经渲染的 Image 上，
   * 释放正在显示的位图会让那一格变黑；原生内存由 ArkTS 侧的回收负责。
   */
  private remember(url: string, pixel: image.PixelMap): void {
    if (this.cache.has(url)) {
      return;
    }
    this.cache.set(url, pixel);
    this.order.push(url);
    while (this.order.length > POSTER_CACHE_LIMIT) {
      const oldest: string | undefined = this.order.shift();
      if (oldest === undefined) {
        continue;
      }
      this.cache.delete(oldest);
    }
  }

  private hasFailed(url: string): boolean {
    for (const item of this.failed) {
      if (item === url) {
        return true;
      }
    }
    return false;
  }

  private rememberFailure(url: string): void {
    if (this.hasFailed(url)) {
      return;
    }
    this.failed.push(url);
    while (this.failed.length > POSTER_FAIL_LIMIT) {
      this.failed.shift();
    }
  }

  /**
   * 临时文件的扩展名。URL 形如 `/view?filename=clip.mp4&subfolder=&type=output`，
   * 优先取 `filename=` 的值；取不到时退回路径本身，再退回 `mp4`。
   */
  private extensionOf(url: string): string {
    let name: string = url;
    const mark: number = url.indexOf('filename=');
    if (mark >= 0) {
      const rest: string = url.substring(mark + 'filename='.length);
      const amp: number = rest.indexOf('&');
      name = amp >= 0 ? rest.substring(0, amp) : rest;
    } else {
      const query: number = name.indexOf('?');
      if (query >= 0) {
        name = name.substring(0, query);
      }
    }
    const slash: number = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    const base: string = slash >= 0 ? name.substring(slash + 1) : name;
    const dot: number = base.lastIndexOf('.');
    if (dot <= 0 || dot === base.length - 1) {
      return 'mp4';
    }
    const extension: string = base.substring(dot + 1).toLowerCase();
    for (const known of POSTER_EXTENSIONS) {
      if (known === extension) {
        return extension;
      }
    }
    return 'mp4';
  }
}