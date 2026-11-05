import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';

const STORE_NAME: string = 'weak_network';
const KEY_ENABLED: string = 'enabled';
const DEFAULT_ENABLED: boolean = false;

/**
 * 弱网模式开关：降低状态轮询频率、AI 上下文强制本地摘要。
 * 默认关闭；MVP 不涉及预览/下载的降级。
 */
export class WeakNetworkPrefs {
  private store: preferences.Preferences | null = null;

  async load(context: common.Context): Promise<boolean> {
    try {
      this.store = await preferences.getPreferences(context, STORE_NAME);
    } catch (err) {
      this.store = null;
      return DEFAULT_ENABLED;
    }
    const store = this.store;
    if (store === null) {
      return DEFAULT_ENABLED;
    }
    try {
      return store.getSync(KEY_ENABLED, DEFAULT_ENABLED) as boolean;
    } catch (err) {
      return DEFAULT_ENABLED;
    }
  }

  async save(context: common.Context, value: boolean): Promise<void> {
    try {
      if (this.store === null) {
        this.store = await preferences.getPreferences(context, STORE_NAME);
      }
      const store = this.store;
      if (store === null) {
        return;
      }
      await store.put(KEY_ENABLED, value);
      await store.flush();
    } catch (err) {
      // 内存里已生效，只影响下次启动的默认值。
    }
  }
}
