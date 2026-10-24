import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';

const STORE_NAME: string = 'appearance';
const KEY_MODE: string = 'mode';
const DEFAULT_MODE: string = 'system';

/**
 * 外观模式（跟随系统 / 深色 / 浅色）的本机记忆。
 *
 * PersistentStorage 在真机上冷启动会丢（force-stop 后回到默认值），
 * 这里改用和地址历史同一套 preferences + flush：写入即落盘，重启后恢复。
 * 读不到存储时退回"跟随系统"，不影响页面显示。
 */
export class AppearanceStore {
  private store: preferences.Preferences | null = null;

  /** 读上次保存的模式；没有记录或打不开存储时返回 null。 */
  async load(context: common.Context): Promise<string | null> {
    try {
      this.store = await preferences.getPreferences(context, STORE_NAME);
    } catch (err) {
      this.store = null;
      return null;
    }
    const store = this.store;
    if (store === null) {
      return null;
    }
    try {
      const value = store.getSync(KEY_MODE, DEFAULT_MODE) as string;
      return value.length > 0 ? value : DEFAULT_MODE;
    } catch (err) {
      return null;
    }
  }

  /** 保存模式；失败只影响下次启动的默认值，不打断当前界面。 */
  async save(context: common.Context, mode: string): Promise<void> {
    try {
      if (this.store === null) {
        this.store = await preferences.getPreferences(context, STORE_NAME);
      }
      const store = this.store;
      if (store === null) {
        return;
      }
      await store.put(KEY_MODE, mode);
      await store.flush();
    } catch (err) {
      // 忽略：内存里的 appearanceMode 已经生效，只是重启后回到跟随系统。
    }
  }
}
