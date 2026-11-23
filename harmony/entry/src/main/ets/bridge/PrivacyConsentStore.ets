import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';

const STORE_NAME: string = 'privacy_consent';
const KEY_ACCEPTED: string = 'accepted';

/** 首次启动隐私确认的本机记录；同意后不再重复弹出。 */
export class PrivacyConsentStore {
  private store: preferences.Preferences | null = null;

  async load(context: common.Context): Promise<boolean> {
    try {
      this.store = await preferences.getPreferences(context, STORE_NAME);
      return this.store.getSync(KEY_ACCEPTED, false) as boolean;
    } catch (err) {
      this.store = null;
      return false;
    }
  }

  async save(context: common.Context): Promise<void> {
    try {
      if (this.store === null) {
        this.store = await preferences.getPreferences(context, STORE_NAME);
      }
      const store = this.store;
      if (store === null) {
        return;
      }
      await store.put(KEY_ACCEPTED, true);
      await store.flush();
    } catch (err) {
      // 保存失败只影响下次启动是否再次提示，不阻断当前使用。
    }
  }
}
