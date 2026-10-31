import { http } from '@kit.NetworkKit';
import { bundleManager } from '@kit.AbilityKit';

/**
 * 更新检查：读仓库根目录的 update.json，判断有没有新版。
 *
 * 判定口径与共享层 `shared/.../core/update/UpdateManifest.kt` 一致：
 * 只比 versionCode，不比版本名字符串；坏数据一律当「检查失败」。
 * 之所以在 ArkTS 再写一遍而不是经 ABI 调 Kotlin：这段逻辑只有几十行，
 * 而新增一个跨语言入口要动 .so、napi、d.ts 三处，代价远大于收益。
 *
 * 源站是 Gitee（国内直连），GitHub 只做第二条源。
 */

interface PlatformLinks {
  github?: string;
  gitee?: string;
}

interface UpdateFile {
  versionCode?: number;
  versionName?: string;
  publishedAt?: string;
  notes?: string;
  github?: string;
  gitee?: string;
  android?: PlatformLinks;
  harmony?: PlatformLinks;
}

/** 检查结果：available 为 true 时其余字段才有意义；否则看 message。 */
export class UpdateResult {
  available: boolean = false;
  versionCode: number = 0;
  versionName: string = '';
  notes: string = '';
  github: string = '';
  gitee: string = '';
  message: string = '';
}

const GITEE: string = 'https://gitee.com/Fly-Noz/ComfyPilot/raw/main/update.json';
const GITHUB: string = 'https://raw.githubusercontent.com/Fly-Noz/ComfyPilot/main/update.json';

/** 装机的 versionCode；读不到当 0，这样任何有效清单都算「有新版」，不会误报「已最新」。 */
export function installedVersionCode(): number {
  try {
    const info = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
    return info.versionCode;
  } catch (err) {
    return 0;
  }
}

async function fetchText(url: string): Promise<string> {
  const client = http.createHttp();
  try {
    const response = await client.request(url, {
      method: http.RequestMethod.GET,
      header: { 'Cache-Control': 'no-cache' },
      expectDataType: http.HttpDataType.STRING,
      connectTimeout: 6000,
      readTimeout: 6000,
    });
    if (response.responseCode !== 200) {
      throw new Error(`HTTP ${response.responseCode}`);
    }
    return response.result as string;
  } finally {
    client.destroy();
  }
}

function hasText(value?: string): boolean {
  return value !== undefined && value.length > 0;
}

function parse(text: string): UpdateFile | null {
  try {
    const file = JSON.parse(text) as UpdateFile;
    const code: number = file.versionCode ?? 0;
    const name: string = file.versionName ?? '';
    if (code <= 0 || name.length === 0) {
      return null;
    }
    return file;
  } catch (err) {
    return null;
  }
}

/** 平台专属链接优先，没有（或两条都是空）就回落到顶层的 github / gitee。 */
function pickLinks(file: UpdateFile, platform: string): PlatformLinks {
  const own: PlatformLinks | undefined = platform === 'harmony' ? file.harmony : file.android;
  if (own !== undefined && (hasText(own.github) || hasText(own.gitee))) {
    return own;
  }
  const fallback: PlatformLinks = { github: file.github, gitee: file.gitee };
  return fallback;
}

export async function checkUpdate(platform: string): Promise<UpdateResult> {
  const result = new UpdateResult();
  const current: number = installedVersionCode();
  const sources: string[] = [GITEE, GITHUB];

  for (const url of sources) {
    let text: string = '';
    try {
      text = await fetchText(url);
    } catch (err) {
      continue;
    }
    const file = parse(text);
    if (file === null) {
      continue;
    }
    if ((file.versionCode ?? 0) <= current) {
      result.message = `已是最新版本 ${file.versionName ?? ''}`;
      return result;
    }
    const links = pickLinks(file, platform);
    result.available = true;
    result.versionCode = file.versionCode ?? 0;
    result.versionName = file.versionName ?? '';
    result.notes = file.notes ?? '';
    result.github = links.github ?? '';
    result.gitee = links.gitee ?? '';
    result.message = `发现新版本 ${result.versionName}`;
    return result;
  }

  result.message = '检查失败，请检查网络';
  return result;
}
