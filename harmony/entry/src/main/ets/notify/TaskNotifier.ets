import { notificationManager } from '@kit.NotificationKit';
import { common, wantAgent } from '@kit.AbilityKit';
import { backgroundTaskManager } from '@kit.BackgroundTasksKit';
import Want from '@ohos.app.ability.Want';

const PROGRESS_ID: number = 1001;
const DONE_ID: number = 1002;

/**
 * 鸿蒙端的任务通知 + 后台保持。
 *
 * 和 Android 对齐的两条通知：
 * - 进度通知：运行中更新，常驻、不可滑动删除（文案说明原因）；
 * - 完成通知：任务结束时的提醒。
 *
 * 后台保持用连续任务（DATA_TRANSFER）：系统会在通知栏放一条"正在运行"的提示，
 * 同时让进程在切后台后不被立刻回收——这是进度能持续更新的前提。
 */
export class TaskNotifier {
  private context: common.UIAbilityContext | null = null;
  private agent: object | null = null;
  private keepAlive: boolean = false;
  private lastSignature: string = '';
  private lastAtMs: number = 0;

  init(context: common.UIAbilityContext): void {
    this.context = context;
  }

  /** 打开应用时调用：没开通知就直接请求系统授权。 */
  async ensurePermission(): Promise<void> {
    const context = this.context;
    if (context === null) {
      return;
    }
    try {
      const enabled: boolean = await notificationManager.isNotificationEnabled();
      if (!enabled) {
        await notificationManager.requestEnableNotification(context);
      }
    } catch (err) {
      // 用户拒绝授权不影响主流程，只是看不到通知
    }
  }

  private async agentOrNull(): Promise<object | null> {
    if (this.agent !== null) {
      return this.agent;
    }
    if (this.context === null) {
      return null;
    }
    try {
      const target: Want = {
        bundleName: 'com.fly.comfypilot',
        abilityName: 'EntryAbility',
      };
      const info: wantAgent.WantAgentInfo = {
        wants: [target],
        actionType: wantAgent.OperationType.START_ABILITY,
        requestCode: 0,
        actionFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG],
      };
      this.agent = await wantAgent.getWantAgent(info);
      return this.agent;
    } catch (err) {
      return null;
    }
  }

  /** 进入后台且有任务在跑时开启连续任务，避免进程被回收。 */
  async startKeepAlive(): Promise<void> {
    const context = this.context;
    if (context === null || this.keepAlive) {
      return;
    }
    const agent: object | null = await this.agentOrNull();
    if (agent === null) {
      return;
    }
    try {
      await backgroundTaskManager.startBackgroundRunning(
        context,
        backgroundTaskManager.BackgroundMode.DATA_TRANSFER,
        agent,
      );
      this.keepAlive = true;
    } catch (err) {
      this.keepAlive = false;
    }
  }

  async stopKeepAlive(): Promise<void> {
    const context = this.context;
    if (context === null || !this.keepAlive) {
      return;
    }
    try {
      await backgroundTaskManager.stopBackgroundRunning(context);
    } catch (err) {
      // 已经停了就算了
    }
    this.keepAlive = false;
  }

  /** 运行中通知：节流 900ms，内容不变就不重发。 */
  async notifyProgress(title: string, text: string): Promise<void> {
    const signature: string = `${title}|${text}`;
    const now: number = Date.now();
    if (signature === this.lastSignature && now - this.lastAtMs < 900) {
      return;
    }
    this.lastSignature = signature;
    this.lastAtMs = now;
    const agent: object | null = await this.agentOrNull();
    const request: notificationManager.NotificationRequest = {
      id: PROGRESS_ID,
      content: {
        notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
        normal: {
          title: title,
          text: text,
          additionalText: '运行期间请勿删除此通知',
        },
      },
      notificationSlotType: notificationManager.SlotType.SERVICE_INFORMATION,
      wantAgent: agent === null ? undefined : agent,
      // 常驻：这条在后台代表"任务还在跑"，被划掉会让人误以为任务结束
      isUnremovable: true,
    };
    try {
      await notificationManager.publish(request);
    } catch (err) {
      // 没有授权时 publish 会失败，忽略即可
    }
  }

  async cancelProgress(): Promise<void> {
    try {
      await notificationManager.cancel(PROGRESS_ID);
    } catch (err) {
      // ignore
    }
    this.lastSignature = '';
  }

  /** 完成/失败：单独一条提醒。 */
  async notifyFinished(title: string, text: string): Promise<void> {
    const agent: object | null = await this.agentOrNull();
    const request: notificationManager.NotificationRequest = {
      id: DONE_ID,
      content: {
        notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
        normal: {
          title: title,
          text: text,
        },
      },
      notificationSlotType: notificationManager.SlotType.OTHER_TYPES,
      wantAgent: agent === null ? undefined : agent,
    };
    try {
      await notificationManager.publish(request);
    } catch (err) {
      // ignore
    }
  }

  async cancelFinished(): Promise<void> {
    try {
      await notificationManager.cancel(DONE_ID);
    } catch (err) {
      // ignore
    }
  }

  /** 回到应用：任务相关通知全部撤掉（连续任务通知由系统管理）。 */
  async cancelTaskNotifications(): Promise<void> {
    await this.cancelProgress();
    await this.cancelFinished();
  }
}
