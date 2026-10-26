import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';

const STORE_NAME: string = 'node_notes';

/**
 * 节点备注是纯本机 UI 偏好：给节点起一个好认的名字，不进入工作流或任务协议。
 *
 * 编码与 Android 端一致（`<nodeId>\t<label>` 每行一条，key 为 `workflow:<path>`），
 * 所以同一个工作流在两端备注可以互相通用。制表符和换行会被压成空格，
 * 避免一条备注把整段记录拆坏。
 */
export class NodeNoteStore {
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

  load(host: string, workflowPath: string): Map<number, string> {
    const result: Map<number, string> = new Map();
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
      raw.split('\n').forEach((line: string) => {
        const split = line.indexOf('\t');
        if (split <= 0) {
          return;
        }
        const id = Number(line.substring(0, split).trim());
        const label = line.substring(split + 1).trim();
        if (Number.isInteger(id) && id > 0 && label.length > 0) {
          result.set(id, label);
        }
      });
    } catch (err) {
      this.lastError = (err as Error).message;
    }
    return result;
  }

  async save(host: string, workflowPath: string, notes: Map<number, string>): Promise<void> {
    const store = this.store;
    if (store === null || workflowPath.length === 0) {
      return;
    }
    const key = this.key(host, workflowPath);
    try {
      if (notes.size === 0) {
        await store.delete(key);
      } else {
        const ids: number[] = Array.from(notes.keys()).sort((a: number, b: number) => a - b);
        const lines: string[] = ids.map((id: number) => `${id}\t${this.clean(notes.get(id) ?? '')}`);
        await store.put(key, lines.join('\n'));
      }
      await store.flush();
    } catch (err) {
      this.lastError = (err as Error).message;
    }
  }

  private clean(label: string): string {
    return label.replace(/[\t\n\r]+/g, ' ').trim();
  }

  private key(host: string, workflowPath: string): string {
    return host.length > 0 ? `${host}|workflow:${workflowPath}` : `workflow:${workflowPath}`;
  }
}