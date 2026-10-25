import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';

const STORE_NAME: string = 'client_ids';

/**
 * 按服务器地址持久化的 ComfyUI clientId。
 *
 * 为什么必须落盘：ComfyUI 的 WebSocket 会话 id（sid）就是 clientId，
 * `progress` 与采样预览帧是按"提交这个任务的那条会话"定向推送的。
 * 每次重连/重启都换新 id，旧任务的进度和画面就再也收不到——现场表现是
 * "切后台回来，最后一帧还在，但进度和画面都冻住"。
 *
 * 读不到存储时退回随机 id：连接照常，只是重连后可能丢定向事件。
 */
export class ClientIdStore {
  private store: preferences.Preferences | null = null;

  async load(context: common.Context, url: string): Promise<string> {
    const key = url.trim();
    try {
      if (this.store === null) {
        this.store = await preferences.getPreferences(context, STORE_NAME);
      }
      const saved = this.store.getSync(key, '') as string;
      if (saved.length > 0) {
        return saved;
      }
      const created = this.randomId();
      await this.store.put(key, created);
      await this.store.flush();
      return created;
    } catch (err) {
      return this.randomId();
    }
  }

  private randomId(): string {
    return util.generateRandomUUID(false).replace(/-/g, '');
  }
}
