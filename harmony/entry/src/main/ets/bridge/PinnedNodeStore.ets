import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';

const STORE_NAME: string = 'pinned_nodes';

/**
 * 节点置顶是纯本机 UI 偏好，不进入工作流或任务协议。
 *
 * 键与 Android 相同：`workflow:<path>`，因此同一个工作流在两端可以保持同样的节点集合；
 * 排序仍由共享层按“仅可达节点提前”的规则完成。
 */
export class PinnedNodeStore {
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

  load(host: string, workflowPath: string): number[] {
    const store = this.store;
    if (store === null || workflowPath.length === 0) {
      return [];
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
      return raw.split(',').map((part: string) => Number(part.trim()))
        .filter((value: number) => Number.isInteger(value) && value > 0);
    } catch (err) {
      this.lastError = (err as Error).message;
      return [];
    }
  }

  async save(host: string, workflowPath: string, nodeIds: number[]): Promise<void> {
    const store = this.store;
    if (store === null || workflowPath.length === 0) {
      return;
    }
    const key = this.key(host, workflowPath);
    try {
      if (nodeIds.length === 0) {
        await store.delete(key);
      } else {
        const sorted = Array.from(new Set(nodeIds)).sort((a: number, b: number) => a - b);
        await store.put(key, sorted.join(','));
      }
      await store.flush();
    } catch (err) {
      this.lastError = (err as Error).message;
    }
  }

  private key(host: string, workflowPath: string): string {
    return host.length > 0 ? `${host}|workflow:${workflowPath}` : `workflow:${workflowPath}`;
  }
}
