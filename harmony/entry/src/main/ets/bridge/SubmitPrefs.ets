import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';

const STORE_NAME: string = 'submit';
const KEY_FORCE: string = 'force';
const DEFAULT_FORCE: boolean = false;

/**
 * 「忽略本地校验，强制提交」开关的本机记忆。
 *
 * 与 AppearanceStore 同一套 preferences + flush：写入即落盘，重启后恢复。
 * 读不到时默认关闭，维持原来的严格校验。
 */
export class SubmitPrefs {
  private store: preferences.Preferences | null = null;

  async load(context: common.Context): Promise<boolean> {
    try {
      this.store = await preferences.getPreferences(context, STORE_NAME);
    } catch (err) {
      this.store = null;
      return DEFAULT_FORCE;
    }
    const store = this.store;
    if (store === null) {
      return DEFAULT_FORCE;
    }
    try {
      return store.getSync(KEY_FORCE, DEFAULT_FORCE) as boolean;
    } catch (err) {
      return DEFAULT_FORCE;
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
      await store.put(KEY_FORCE, value);
      await store.flush();
    } catch (err) {
      // 忽略：内存里的开关已经生效，只是重启后回到默认。
    }
  }
}
