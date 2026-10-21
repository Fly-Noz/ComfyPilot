import { relationalStore } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';

/**
 * 任务快照的本地存储（RDB）。
 *
 * 这里**不解释任务结构**：`payload` 就是 Kotlin `toPersistJson()` 原样写出来的 JSON，
 * `id` 只是从命令里抄过来的主键。schema 归 Kotlin，宿主只负责搬字节——
 * 两边各写一份状态映射，早晚会不一致。
 *
 * 写操作按 id 合并：进度每 500ms 一条 `persist`，逐条排队会越积越多，
 * 最后落盘的往往还是同一个任务的中间态。
 */

const DB_NAME: string = 'comfypilot.db';
const TABLE: string = 'cp_tasks';
const MAX_ROWS: number = 200;
const CREATE_SQL: string =
  `CREATE TABLE IF NOT EXISTS ${TABLE} (` +
  'id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at INTEGER NOT NULL)';

export interface StoredTask {
  id: string;
  payload: string;
}

export class TaskStorage {
  private store: relationalStore.RdbStore | null = null;
  private lastError: string = '';
  private pending: Map<string, string | null> = new Map();
  private flushing: boolean = false;

  get available(): boolean {
    return this.store !== null;
  }

  get error(): string {
    return this.lastError;
  }

  /** 打不开就降级为内存存储：连不上库不该挡住连服务器。 */
  async open(context: common.Context): Promise<boolean> {
    if (this.store !== null) {
      return true;
    }
    const config: relationalStore.StoreConfig = {
      name: DB_NAME,
      securityLevel: relationalStore.SecurityLevel.S1,
    };
    try {
      const store: relationalStore.RdbStore = await relationalStore.getRdbStore(context, config);
      await store.executeSql(CREATE_SQL);
      this.store = store;
      return true;
    } catch (err) {
      this.lastError = (err as BusinessError).message ?? 'RDB 打开失败';
      this.store = null;
      return false;
    }
  }

  /** 按最近更新倒序返回；Kotlin 侧自己会再排序并截到 50 条。 */
  async load(): Promise<StoredTask[]> {
    const store = this.store;
    const rows: StoredTask[] = [];
    if (store === null) {
      return rows;
    }
    try {
      const sql: string = `SELECT id, payload FROM ${TABLE} ORDER BY updated_at DESC LIMIT ${MAX_ROWS}`;
      const result: relationalStore.ResultSet = await store.querySql(sql);
      try {
        const idIndex: number = result.getColumnIndex('id');
        const payloadIndex: number = result.getColumnIndex('payload');
        while (result.goToNextRow()) {
          rows.push({ id: result.getString(idIndex), payload: result.getString(payloadIndex) });
        }
      } finally {
        result.close();
      }
    } catch (err) {
      this.lastError = (err as BusinessError).message ?? 'RDB 读取失败';
    }
    return rows;
  }

  save(id: string, payload: string): void {
    this.pending.set(id, payload);
    this.flush();
  }

  remove(id: string): void {
    this.pending.set(id, null);
    this.flush();
  }

  private flush(): void {
    if (this.flushing || this.store === null) {
      return;
    }
    this.flushing = true;
    this.drain().then(() => {
      this.flushing = false;
      // 最后一次 await 和复位之间可能又进了新写入，补一轮。
      if (this.pending.size > 0) {
        this.flush();
      }
    }).catch((err: BusinessError) => {
      this.flushing = false;
      this.lastError = err.message ?? 'RDB 写入失败';
    });
  }

  private async drain(): Promise<void> {
    const store = this.store;
    if (store === null) {
      return;
    }
    while (this.pending.size > 0) {
      const ids: string[] = [];
      this.pending.forEach((_value: string | null, key: string) => {
        ids.push(key);
      });
      for (let i = 0; i < ids.length; i++) {
        const id: string = ids[i];
        const payload: string | null | undefined = this.pending.get(id);
        this.pending.delete(id);
        if (payload === undefined) {
          continue;
        }
        try {
          if (payload === null) {
            const predicates = new relationalStore.RdbPredicates(TABLE);
            predicates.equalTo('id', id);
            await store.delete(predicates);
          } else {
            const bucket: relationalStore.ValuesBucket = {
              'id': id,
              'payload': payload,
              'updated_at': Date.now(),
            };
            await store.insert(TABLE, bucket, relationalStore.ConflictResolution.ON_CONFLICT_REPLACE);
          }
        } catch (err) {
          // 一条写失败不拖垮后续任务：记下原因，界面上会显示“RDB 出错”。
          this.lastError = (err as BusinessError).message ?? 'RDB 写入失败';
        }
      }
    }
  }
}
