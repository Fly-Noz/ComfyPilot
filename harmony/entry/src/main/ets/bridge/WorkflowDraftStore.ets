import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';

const STORE_NAME: string = 'workflow_drafts';

/**
 * 工作流参数草稿（鸿蒙侧）。
 *
 * 与 Android 的 WorkflowDraftStore 对应：按工作流路径保存"节点 id + 输入名 + 值"，
 * 只存本机，不写回主机；提交/刷新仍走共享层的 overrides。
 */
export class WorkflowDraftStore {
  private store: preferences.Preferences | null = null;
  private lastError: string = '';

  get error(): string {
    return this.lastError;
  }

  async open(context: common.Context): Promise<boolean> {
    try {
      this.store = await preferences.getPreferences(context, STORE_NAME);
      return true;
    } catch (err) {
      this.lastError = (err as Error).message;
      this.store = null;
      return false;
    }
  }

  load(host: string, workflowPath: string): Map<string, string | number | boolean | null> {
    const result: Map<string, string | number | boolean | null> = new Map();
    const store = this.store;
    if (store === null || workflowPath.length === 0) {
      return result;
    }
    try {
      const scopedKey = this.key(host, workflowPath);
      let raw = store.getSync(scopedKey, '') as string;
      if (raw.length === 0) {
        const legacy = store.getSync(`workflow:${workflowPath}`, '') as string;
        if (legacy.length > 0) {
          raw = legacy;
          store.putSync(scopedKey, legacy);
          store.delete(`workflow:${workflowPath}`);
        }
      }
      if (raw.length === 0) {
        return result;
      }
      const parsed = JSON.parse(raw) as Record<string, Record<string, string | number | boolean | null>>;
      Object.keys(parsed).forEach((nodeId: string) => {
        const fields = parsed[nodeId];
        Object.keys(fields).forEach((inputName: string) => {
          result.set(`${nodeId}\u0000${inputName}`, fields[inputName]);
        });
      });
    } catch (err) {
      this.lastError = (err as Error).message;
    }
    return result;
  }

  async save(host: string, workflowPath: string, edits: Map<string, string | number | boolean | null>): Promise<void> {
    const store = this.store;
    if (store === null || workflowPath.length === 0) {
      return;
    }
    const key = this.key(host, workflowPath);
    try {
      if (edits.size === 0) {
        await store.delete(key);
      } else {
        const grouped: Record<string, Record<string, string | number | boolean | null>> = {};
        edits.forEach((value: string | number | boolean | null, editKey: string) => {
          const split = editKey.split('\u0000');
          if (split.length !== 2) {
            return;
          }
          const nodeId = split[0];
          if (grouped[nodeId] === undefined) {
            grouped[nodeId] = {};
          }
          grouped[nodeId][split[1]] = value;
        });
        await store.put(key, JSON.stringify(grouped));
      }
      await store.flush();
    } catch (err) {
      this.lastError = (err as Error).message;
    }
  }

  /** 节点开关与节点图选择：和参数草稿同一套 host+path 作用域，重启后仍生效。 */
  loadSession(host: string, workflowPath: string): string {
    const store = this.store;
    if (store === null || workflowPath.length === 0) {
      return '';
    }
    try {
      return store.getSync(this.sessionKey(host, workflowPath), '') as string;
    } catch (err) {
      this.lastError = (err as Error).message;
      return '';
    }
  }

  async saveSession(host: string, workflowPath: string, json: string): Promise<void> {
    const store = this.store;
    if (store === null || workflowPath.length === 0) {
      return;
    }
    try {
      store.putSync(this.sessionKey(host, workflowPath), json);
      await store.flush();
    } catch (err) {
      this.lastError = (err as Error).message;
    }
  }

  /** 恢复默认：清掉全部工作流草稿。 */
  async clearAll(): Promise<void> {
    const store = this.store;
    if (store === null) {
      return;
    }
    try {
      await store.clear();
      await store.flush();
    } catch (err) {
      this.lastError = (err as Error).message;
    }
  }

  private key(host: string, workflowPath: string): string {
    return host.length > 0 ? `${host}|workflow:${workflowPath}` : `workflow:${workflowPath}`;
  }

  private sessionKey(host: string, workflowPath: string): string {
    return `session|${this.key(host, workflowPath)}`;
  }
}
