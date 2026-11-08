import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';

const STORE_NAME: string = 'comfypilot_update';
const KEY_MODE: string = 'prompt_mode';
const KEY_DISMISSED: string = 'dismissed_version';

export const UPDATE_MODE_ALWAYS: string = 'always';
export const UPDATE_MODE_ONCE: string = 'once';

/**
 * 更新提示偏好：只影响启动时的静默检查，手动检查始终会给出结果。
 */
export class UpdatePrefs {
  private store: preferences.Preferences | null = null;

  private async open(context: common.Context): Promise<preferences.Preferences | null> {
    if (this.store !== null) {
      return this.store;
    }
    try {
      this.store = await preferences.getPreferences(context, STORE_NAME);
      return this.store;
    } catch (err) {
      return null;
    }
  }

  async loadMode(context: common.Context): Promise<string> {
    const store = await this.open(context);
    if (store === null) {
      return UPDATE_MODE_ALWAYS;
    }
    try {
      const value = store.getSync(KEY_MODE, UPDATE_MODE_ALWAYS) as string;
      return value === UPDATE_MODE_ONCE ? UPDATE_MODE_ONCE : UPDATE_MODE_ALWAYS;
    } catch (err) {
      return UPDATE_MODE_ALWAYS;
    }
  }

  async saveMode(context: common.Context, mode: string): Promise<void> {
    const store = await this.open(context);
    if (store === null) {
      return;
    }
    try {
      await store.put(KEY_MODE, mode);
      await store.flush();
    } catch (err) {
      // 内存里已生效，只影响下次启动。
    }
  }

  async loadDismissed(context: common.Context): Promise<number> {
    const store = await this.open(context);
    if (store === null) {
      return 0;
    }
    try {
      return store.getSync(KEY_DISMISSED, 0) as number;
    } catch (err) {
      return 0;
    }
  }

  async markDismissed(context: common.Context, versionCode: number): Promise<void> {
    if (versionCode <= 0) {
      return;
    }
    const store = await this.open(context);
    if (store === null) {
      return;
    }
    try {
      await store.put(KEY_DISMISSED, versionCode);
      await store.flush();
    } catch (err) {
      // 忽略：下次启动仍会提示。
    }
  }
}
