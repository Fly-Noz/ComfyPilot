import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';

const STORE_NAME: string = 'seed_mode';
const KEY_EVERY: string = 'every';
const KEY_LAST: string = 'last';

/**
 * 种子节点的本机模式与“上次种子”。
 *
 * 不写回服务器上的工作流文件；键是 `工作流路径|节点 id`。
 */
export class SeedPrefs {
  private store: preferences.Preferences | null = null;

  async load(context: common.Context): Promise<void> {
    try {
      this.store = await preferences.getPreferences(context, STORE_NAME);
    } catch (err) {
      this.store = null;
    }
  }

  isEvery(path: string, nodeId: number): boolean {
    const key = `${path}|${nodeId}`;
    const map = this.readMap(KEY_EVERY);
    // 默认开启；只有用户明确关过（存 0）才关闭。
    return map[key] === undefined ? true : map[key] === 1;
  }

  setEvery(path: string, nodeId: number, value: boolean): void {
    const map = this.readMap(KEY_EVERY);
    map[`${path}|${nodeId}`] = value ? 1 : 0;
    this.writeMap(KEY_EVERY, map);
  }

  lastSeed(path: string, nodeId: number): number {
    const value = this.readMap(KEY_LAST)[`${path}|${nodeId}`];
    return typeof value === 'number' ? value : -1;
  }

  rememberSeed(path: string, nodeId: number, seed: number): void {
    const map = this.readMap(KEY_LAST);
    map[`${path}|${nodeId}`] = seed;
    this.writeMap(KEY_LAST, map);
  }

  private readMap(key: string): Record<string, number> {
    if (this.store === null) {
      return {};
    }
    try {
      const raw = this.store.getSync(key, '{}') as string;
      return JSON.parse(raw) as Record<string, number>;
    } catch (err) {
      return {};
    }
  }

  private writeMap(key: string, map: Record<string, number>): void {
    if (this.store === null) {
      return;
    }
    try {
      this.store.putSync(key, JSON.stringify(map));
      this.store.flush().then(() => {}).catch(() => {});
    } catch (err) {
      // 内存里已生效，仅影响下次启动。
    }
  }
}
