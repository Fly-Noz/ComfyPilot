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

  load(workflowPath: string): Map<string, string | number | boolean | null> {
    const result: Map<string, string | number | boolean | null> = new Map();
    const store = this.store;
    if (store === null || workflowPath.length === 0) {
      return result;
    }
    try {
      const raw = store.getSync(this.key(workflowPath), '') as string;
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

  async save(workflowPath: string, edits: Map<string, string | number | boolean | null>): Promise<void> {
    const store = this.store;
    if (store === null || workflowPath.length === 0) {
      return;
    }
    const key = this.key(workflowPath);
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

  private key(workflowPath: string): string {
    return `workflow:${workflowPath}`;
  }
}
