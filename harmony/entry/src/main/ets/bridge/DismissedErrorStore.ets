import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';

const STORE_NAME: string = 'dismissed_errors';
const SCHEMA_VERSION: number = 1;
const KEY_SCHEMA: string = 'schema';

/**
 * 已关闭的错误提示，按工作流记住。
 *
 * 页面用一个 `taskId|errorText` 当 key：错误文案变了 key 就不一样，新错误仍会显示；
 * 同一个错误重新打开工作流时保持关闭。value 就是那串 key，key 形如 `workflow:<path>`。
 *
 * 这只是本机 UI 偏好：读不到 preferences 时页面照常显示错误，只是关掉后重启会再出现。
 */
export class DismissedErrorStore {
  private store: preferences.Preferences | null = null;
  private lastError: string = '';
  private schemaReady: boolean = false;

  get error(): string {
    return this.lastError;
  }

  async open(context: common.Context): Promise<boolean> {
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
        this.schemaReady = false;
      } else {
        this.schemaReady = true;
      }
    } catch (err) {
      this.lastError = (err as Error).message;
    }
    return true;
  }

  /** 同步读取：打开工作流时要立刻决定错误块显示还是隐藏。 */
  load(host: string, workflowPath: string): string {
    const store = this.store;
    if (store === null || workflowPath.length === 0 || !this.schemaReady) {
      return '';
    }
    try {
      // 原样返回：key 里带着错误原文，trim 会让带空白的文案在重开后对不上。
      const scopedKey = this.key(host, workflowPath);
      const scoped = store.getSync(scopedKey, '') as string;
      if (scoped.length > 0) {
        return scoped;
      }
      const legacy = store.getSync(`workflow:${workflowPath}`, '') as string;
      if (legacy.length > 0) {
        store.putSync(scopedKey, legacy);
        store.delete(`workflow:${workflowPath}`);
      }
      return legacy;
    } catch (err) {
      this.lastError = (err as Error).message;
      return '';
    }
  }

  async save(host: string, workflowPath: string, key: string): Promise<void> {
    const store = this.store;
    if (store === null || workflowPath.length === 0) {
      return;
    }
    try {
      if (key.length === 0) {
        await store.delete(this.key(host, workflowPath));
      } else {
        await store.put(this.key(host, workflowPath), key);
      }
      if (!this.schemaReady) {
        await store.put(KEY_SCHEMA, SCHEMA_VERSION);
        this.schemaReady = true;
      }
      await store.flush();
    } catch (err) {
      this.lastError = (err as Error).message;
    }
  }

  async clear(host: string, workflowPath: string): Promise<void> {
    await this.save(host, workflowPath, '');
  }

  private key(host: string, workflowPath: string): string {
    return host.length > 0 ? `${host}|workflow:${workflowPath}` : `workflow:${workflowPath}`;
  }
}