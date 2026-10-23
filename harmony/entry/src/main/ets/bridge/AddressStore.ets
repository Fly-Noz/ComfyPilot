import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';

const STORE_NAME: string = 'server_addresses';
const SCHEMA_VERSION: number = 1;
const KEY_SCHEMA: string = 'schema';
const KEY_LAST: string = 'last';
const KEY_HISTORY: string = 'history';
/** 和 Android 端一样只留最近 8 个地址，下拉面板不会长到屏幕外。 */
const MAX_HISTORY: number = 8;

/** 一条地址历史：规范化后的地址 + 最近一次成功连接的时间。 */
export interface AddressEntry {
  url: string;
  usedAt: number;
}

/**
 * ComfyUI 服务器地址的本机记忆。
 *
 * 只保存"用户真的连上过"的地址（由页面在连接成功的回调里调用 remember），
 * 编码为 `<usedAt>\t<url>` 每行一条，`last` 单独存一份，冷启动时优先恢复它。
 * 打不开 preferences 不影响连接：内存里仍然可用，只是重启会丢。
 */
export class AddressStore {
  private store: preferences.Preferences | null = null;
  private lastError: string = '';
  private last: string = '';
  private entries: AddressEntry[] = [];

  get error(): string {
    return this.lastError;
  }

  async load(context: common.Context): Promise<boolean> {
    try {
      this.store = await preferences.getPreferences(context, STORE_NAME);
    } catch (err) {
      this.lastError = (err as Error).message;
      this.store = null;
      return false;
    }
    const store = this.store;
    if (store === null) {
      return false;
    }
    try {
      const schema = store.getSync(KEY_SCHEMA, 0) as number;
      if (schema !== SCHEMA_VERSION) {
        // 旧格式（或第一次启动）没有可迁移的语义，按空历史处理并写入当前版本。
        this.last = '';
        this.entries = [];
        await store.put(KEY_SCHEMA, SCHEMA_VERSION);
        await store.flush();
        return true;
      }
      const lastRaw = store.getSync(KEY_LAST, '') as string;
      this.last = this.normalize(lastRaw);
      const historyRaw = store.getSync(KEY_HISTORY, '') as string;
      this.entries = this.parse(historyRaw);
    } catch (err) {
      this.lastError = (err as Error).message;
    }
    return true;
  }

  /** 最近一次成功连接的地址；没有记录时返回空串，页面保留默认地址。 */
  lastUrl(): string {
    return this.last;
  }

  /** 地址历史，最新的在最前。返回副本，调用方改不到存储内部状态。 */
  history(): AddressEntry[] {
    return this.entries.slice();
  }

  /**
   * 记住一个刚从服务器验证过的地址。
   *
   * 按规范化后的 URL 去重：同一地址只留一条，并把它提到最前面、刷新时间戳；
   * 总量超过 8 条时丢掉最旧的。
   */
  async remember(url: string): Promise<void> {
    const clean = this.normalize(url);
    if (clean.length === 0) {
      return;
    }
    const next: AddressEntry[] = [{ url: clean, usedAt: Date.now() }];
    for (let i = 0; i < this.entries.length; i++) {
      const entry = this.entries[i];
      if (entry.url === clean) {
        continue;
      }
      if (next.length >= MAX_HISTORY) {
        break;
      }
      next.push(entry);
    }
    this.entries = next;
    this.last = clean;
    await this.persist();
  }

  /** 从历史里移除一个地址（不影响输入框里已经填好的内容）。 */
  async forget(url: string): Promise<void> {
    const clean = this.normalize(url);
    if (clean.length === 0) {
      return;
    }
    this.entries = this.entries.filter((entry: AddressEntry) => entry.url !== clean);
    if (this.last === clean) {
      // 最近地址被删掉后回退到历史里的下一条，冷启动不再恢复一个用户已经放弃的地址。
      this.last = this.entries.length > 0 ? this.entries[0].url : '';
    }
    await this.persist();
  }

  async clear(): Promise<void> {
    this.last = '';
    this.entries = [];
    await this.persist();
  }

  /** 去掉首尾空白和尾部斜杠；地址里不允许出现制表符/换行，避免拆坏行格式。 */
  private normalize(url: string): string {
    let value = url.replace(/[\t\n\r]+/g, '').trim();
    while (value.endsWith('/')) {
      value = value.substring(0, value.length - 1);
    }
    return value;
  }

  private parse(raw: string): AddressEntry[] {
    const result: AddressEntry[] = [];
    if (raw.length === 0) {
      return result;
    }
    raw.split('\n').forEach((line: string) => {
      if (result.length >= MAX_HISTORY) {
        return;
      }
      const tab = line.indexOf('\t');
      if (tab <= 0) {
        return;
      }
      const usedAt = Number(line.substring(0, tab).trim());
      const url = this.normalize(line.substring(tab + 1));
      if (url.length === 0 || !Number.isFinite(usedAt)) {
        return;
      }
      if (result.some((entry: AddressEntry) => entry.url === url)) {
        return;
      }
      result.push({ url: url, usedAt: usedAt });
    });
    return result;
  }

  private encode(entries: AddressEntry[]): string {
    return entries.map((entry: AddressEntry) => `${entry.usedAt}\t${entry.url}`).join('\n');
  }

  private async persist(): Promise<void> {
    const store = this.store;
    if (store === null) {
      return;
    }
    try {
      await store.put(KEY_SCHEMA, SCHEMA_VERSION);
      await store.put(KEY_LAST, this.last);
      await store.put(KEY_HISTORY, this.encode(this.entries));
      await store.flush();
    } catch (err) {
      this.lastError = (err as Error).message;
    }
  }
}