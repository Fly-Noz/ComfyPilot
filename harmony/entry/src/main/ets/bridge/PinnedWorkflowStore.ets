import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';

const STORE_NAME: string = 'pinned_workflows';
const KEY: string = 'paths';

/**
 * 工作流置顶是纯本机 UI 偏好，不写回服务器，也不进入任务/工作流协议。
 *
 * 只保存工作流路径；列表排序时置顶的排前面，其余仍按路径排序。
 */
export class PinnedWorkflowStore {
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

  load(): string[] {
    const store = this.store;
    if (store === null) {
      return [];
    }
    try {
      const raw = store.getSync(KEY, '') as string;
      return raw.split('\n')
        .map((part: string) => part.trim())
        .filter((path: string) => path.length > 0);
    } catch (err) {
      this.lastError = (err as Error).message;
      return [];
    }
  }

  async save(paths: string[]): Promise<void> {
    const store = this.store;
    if (store === null) {
      return;
    }
    try {
      const unique = Array.from(new Set(paths))
        .filter((path: string) => path.length > 0);
      if (unique.length === 0) {
        await store.delete(KEY);
      } else {
        await store.put(KEY, unique.join('\n'));
      }
      await store.flush();
    } catch (err) {
      this.lastError = (err as Error).message;
    }
  }
}
