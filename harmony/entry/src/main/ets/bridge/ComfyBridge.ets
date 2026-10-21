import { nativeSessionCreate, nativeSessionDestroy, nativeSessionPump, nativeSessionSend } from 'libentry.so';
import { connection, http, webSocket } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { BusinessError } from '@kit.BasicServicesKit';

/**
 * 鸿蒙侧的宿主循环。
 *
 * 分工是硬边界：Kotlin 决定"要做什么"（命令），这里只负责"怎么做"（系统调用）。
 * 所有网络请求、WebSocket、网络状态都从这里进 Kotlin，Kotlin 从不直接碰 @ohos.net。
 */

export interface BridgeTask {
  id: string;
  promptId: string | null;
  name: string | null;
  state: string;
  percent: number;
  outputs: number;
  error: string | null;
  updatedAtMs: number;
}

export interface BridgeState {
  phase: string;
  label: string;
  generation: number;
  leaseId: number;
  network: string;
  validated: boolean;
  baseUrl: string;
  queueRunning: number;
  queuePending: number;
  tasks: BridgeTask[];
}

interface BridgeNotice {
  kind: string;
  generation?: number;
  reason?: string;
  promptId?: string;
  message?: string;
}

interface BridgeCommand {
  t: string;
  id?: number;
  method?: string;
  url?: string;
  timeoutMs?: number;
  contentType?: string;
  headers?: Record<string, string>;
  body?: string;
  workflow?: string;
  code?: number;
  reason?: string;
  transport?: string;
  validated?: boolean;
  task?: BridgeTask;
  notice?: BridgeNotice;
  // 回投结果用：与 Kotlin 侧 HostMessage 的字段一一对应。
  status?: number;
  failure?: string;
  text?: string;
  message?: string;
}

interface BridgeFrame {
  revision: number;
  commands: BridgeCommand[];
  state: BridgeState | null;
  nextDelayMs: number;
  error?: string;
}

interface BridgeConfig {
  baseUrl: string;
  tickIntervalMs: number;
  progressSilenceMs: number;
}

const MIN_PUMP_MS = 16;
const MAX_PUMP_MS = 500;

/**
 * 联调诊断：打开后每帧状态、每条命令、每次 WS/HTTP 往返都打一行 hilog（tag `CP>`）。
 * 真机排查靠它区分"共享层没走"和"UI 没刷新"，正式发布前关掉即可。
 */
const CP_TRACE: boolean = true;

function trace(line: string): void {
  if (CP_TRACE) {
    console.info(`CP> ${line}`);
  }
}

export class ComfyBridge {
  onState: (state: BridgeState) => void = () => {};
  onNotice: (text: string) => void = () => {};
  onLog: (line: string) => void = () => {};

  private timerId: number = -1;
  private running: boolean = false;
  private revision: number = -1;
  /** 本轮 tick 里执行命令时产生了回投结果，需要马上再 pump 一次。 */
  private repump: boolean = false;
  private sockets: Map<number, webSocket.WebSocket> = new Map();
  private base64: util.Base64Helper = new util.Base64Helper();
  /** 这个 SDK 上网络事件挂在 createNetConnection() 返回的对象上，不在 connection 命名空间上。 */
  private netConnection: connection.NetConnection | null = null;

  start(baseUrl: string): void {
    this.stop();
    const config: BridgeConfig = {
      baseUrl: baseUrl,
      tickIntervalMs: 500,
      progressSilenceMs: 120000,
    };
    const created: number = nativeSessionCreate(JSON.stringify(config));
    if (created !== 1) {
      this.onLog('共享层拒绝创建会话（配置无效）');
      return;
    }
    this.running = true;
    this.revision = -1;
    this.subscribeNetwork();
    // 建会话只是把状态机装好；真正发起连接的是这条 start，少了它状态会一直停在 Idle。
    this.send({ t: 'start' });
    this.tick();
  }

  stop(): void {
    this.running = false;
    if (this.timerId !== -1) {
      clearTimeout(this.timerId);
      this.timerId = -1;
    }
    this.sockets.forEach((socket: webSocket.WebSocket) => {
      socket.close().catch(() => {});
    });
    this.sockets.clear();
    this.unsubscribeNetwork();
    nativeSessionDestroy();
    this.revision = -1;
  }

  submit(workflow: string): void {
    this.send({ t: 'submit', workflow: workflow });
  }

  reconnect(): void {
    this.send({ t: 'reconnect' });
  }

  private send(command: BridgeCommand): void {
    if (!this.running) {
      return;
    }
    nativeSessionSend(JSON.stringify(command));
    // 结果已经进来，立刻推进一轮，不用等下一个 tick。
    this.repump = true;
    this.schedule(0);
  }

  private tick(): void {
    if (!this.running) {
      return;
    }
    let delay = MAX_PUMP_MS;
    try {
      const raw: string = nativeSessionPump(Date.now());
      const frame: BridgeFrame = JSON.parse(raw) as BridgeFrame;
      if (frame.error !== undefined) {
        this.onLog(`共享层错误：${frame.error}`);
      }
      if (frame.nextDelayMs !== undefined) {
        delay = frame.nextDelayMs;
      }
      if (frame.state !== null && frame.state !== undefined && frame.revision !== this.revision) {
        this.revision = frame.revision;
        // 联调诊断：只在状态帧变化时打一行，便于用 hilog 对照 mock 的 hits。
        trace(`frame rev=${frame.revision} phase=${frame.state.phase} tasks=` +
          frame.state.tasks.map((t: BridgeTask) => `${t.state}:${t.percent}`).join(','));
        this.onState(frame.state);
      }
      if (frame.commands !== undefined) {
        frame.commands.forEach((command: BridgeCommand) => this.execute(command));
      }
    } catch (err) {
      const error = err as BusinessError;
      this.onLog(`pump 失败：${error.message}`);
    }
    // 命令执行期间产生过回投结果的话，别等 500ms 的定时器，马上再推进一轮。
    const wait = this.repump ? MIN_PUMP_MS : delay;
    this.repump = false;
    this.schedule(wait);
  }

  private schedule(delayMs: number): void {
    if (!this.running) {
      return;
    }
    const wait = Math.max(MIN_PUMP_MS, Math.min(delayMs, MAX_PUMP_MS));
    if (this.timerId !== -1) {
      clearTimeout(this.timerId);
    }
    this.timerId = setTimeout(() => this.tick(), wait);
  }

  private execute(command: BridgeCommand): void {
    trace(`cmd ${command.t}${command.task ? ` task=${command.task.state}` : ''}`);
    switch (command.t) {
      case 'http':
        this.runHttp(command);
        break;
      case 'ws.open':
        this.openSocket(command);
        break;
      case 'ws.close':
        this.closeSocket(command);
        break;
      case 'persist':
        // 阶段二把任务落库交给 RDB，这里先只记录，不假装已经持久化。
        this.onLog(`任务变更：${command.task?.id ?? '?'} ${command.task?.state ?? ''}`);
        break;
      case 'persist.remove':
        this.onLog(`任务移除：${command.id ?? '?'}`);
        break;
      case 'notice':
        this.handleNotice(command.notice);
        break;
      default:
        this.onLog(`未知命令：${command.t}`);
        break;
    }
  }

  private handleNotice(notice?: BridgeNotice): void {
    if (notice === undefined) {
      return;
    }
    switch (notice.kind) {
      case 'connected':
        this.onNotice(`已连接（第 ${notice.generation ?? 0} 代）`);
        break;
      case 'disconnected':
        this.onNotice(`连接断开：${notice.reason ?? ''}`);
        break;
      case 'task_failed':
        this.onNotice(`任务失败：${notice.message ?? '未知原因'}`);
        break;
      case 'task_lost':
        this.onNotice(`任务失去跟踪：${notice.message ?? ''}`);
        break;
      case 'progress_timeout':
        this.onNotice('长时间没有进度，正在重连确认');
        break;
      case 'reconcile_gave_up':
        this.onNotice('对账失败，任务状态保持未知，不会自动重投');
        break;
      case 'submit_rejected':
        this.onNotice(`提交被拒绝：${notice.message ?? ''}`);
        break;
      default:
        this.onLog(`通知：${notice.kind}`);
        break;
    }
  }

  private runHttp(command: BridgeCommand): void {
    if (command.url === undefined || command.id === undefined) {
      return;
    }
    const id: number = command.id;
    const request = http.createHttp();
    const header: Record<string, string> = {};
    if (command.contentType !== undefined) {
      header['Content-Type'] = command.contentType;
    }
    if (command.headers !== undefined) {
      const extra = command.headers;
      Object.keys(extra).forEach((key: string) => {
        header[key] = extra[key];
      });
    }
    const timeout: number = command.timeoutMs ?? 20000;
    const options: http.HttpRequestOptions = {
      method: command.method === 'POST' ? http.RequestMethod.POST : http.RequestMethod.GET,
      header: header,
      connectTimeout: timeout,
      readTimeout: timeout,
      expectDataType: http.HttpDataType.STRING,
      usingCache: false,
    };
    if (command.body !== undefined && command.body.length > 0) {
      const bytes: Uint8Array = this.base64.decodeSync(command.body);
      options.extraData = bytes.buffer as ArrayBuffer;
    }
    request.request(command.url, options).then((response: http.HttpResponse) => {
      request.destroy();
      const body: string = typeof response.result === 'string' ? response.result : '';
      trace(`http ${command.method ?? 'GET'} ${command.url} -> ${response.responseCode} len=${body.length}`);
      this.send({ t: 'http.result', id: id, status: response.responseCode, body: body });
    }).catch((err: BusinessError) => {
      request.destroy();
      trace(`http fail ${command.url} ${err.message}`);
      this.send({ t: 'http.result', id: id, failure: err.message ?? 'HTTP 请求失败' });
    });
  }

  private openSocket(command: BridgeCommand): void {
    if (command.url === undefined || command.id === undefined) {
      return;
    }
    const id: number = command.id;
    const socket = webSocket.createWebSocket();
    this.sockets.set(id, socket);

    socket.on('open', () => {
      this.send({ t: 'ws.opened', id: id });
    });
    socket.on('message', (err: BusinessError, data: string | ArrayBuffer) => {
      if (typeof data === 'string') {
        const head: string = data.length > 120 ? data.substring(0, 120) : data;
        trace(`ws text len=${data.length} ${head}`);
        this.send({ t: 'ws.text', id: id, text: data });
      } else {
        // 预览图走二进制帧，阶段二暂不显示；不把它当文本塞给状态机。
        this.onLog('忽略二进制 WebSocket 帧');
      }
    });
    socket.on('close', (err: BusinessError, info: webSocket.CloseResult) => {
      this.sockets.delete(id);
      this.send({ t: 'ws.closed', id: id, code: info.code, reason: info.reason });
    });
    socket.on('error', (err: BusinessError) => {
      this.sockets.delete(id);
      this.send({ t: 'ws.failed', id: id, message: err.message ?? 'WebSocket 错误' });
    });

    socket.connect(command.url).then(() => {
      // 真正的"可用"以 open 事件为准，这里不提前通知 Kotlin。
    }).catch((err: BusinessError) => {
      this.sockets.delete(id);
      this.send({ t: 'ws.failed', id: id, message: err.message ?? 'WebSocket 连接失败' });
    });
  }

  private closeSocket(command: BridgeCommand): void {
    if (command.id === undefined) {
      return;
    }
    const socket = this.sockets.get(command.id);
    if (socket === undefined) {
      return;
    }
    this.sockets.delete(command.id);
    socket.close({ code: command.code ?? 1000, reason: command.reason ?? '' }).catch(() => {});
  }

  private subscribeNetwork(): void {
    try {
      const netConnection = connection.createNetConnection();
      netConnection.on('netCapabilitiesChange', () => this.publishNetwork());
      netConnection.on('netAvailable', () => this.publishNetwork());
      netConnection.on('netLost', () => this.publishNetwork());
      netConnection.register((error: BusinessError) => {
        if (error) {
          this.onLog(`网络状态订阅失败：${error.message}`);
        }
      });
      this.netConnection = netConnection;
    } catch (err) {
      const error = err as BusinessError;
      this.onLog(`网络状态订阅失败：${error.message}`);
    }
    this.publishNetwork();
  }

  private unsubscribeNetwork(): void {
    const netConnection = this.netConnection;
    if (netConnection === null) {
      return;
    }
    this.netConnection = null;
    try {
      netConnection.unregister((error: BusinessError) => {
        if (error) {
          this.onLog(`取消网络状态订阅失败：${error.message}`);
        }
      });
    } catch (err) {
      // 已经注销过就忽略，别在断开路径上再抛。
    }
  }

  private publishNetwork(): void {
    let transport = 'none';
    let validated = false;
    try {
      const net = connection.getDefaultNetSync();
      if (net.netId !== 0) {
        const caps = connection.getNetCapabilitiesSync(net);
        validated = true;
        if (caps.bearerTypes.includes(connection.NetBearType.BEARER_WIFI)) {
          transport = 'wifi';
        } else if (caps.bearerTypes.includes(connection.NetBearType.BEARER_CELLULAR)) {
          transport = 'cellular';
        } else if (caps.bearerTypes.includes(connection.NetBearType.BEARER_ETHERNET)) {
          transport = 'ethernet';
        } else {
          transport = 'other';
        }
      }
    } catch (err) {
      const error = err as BusinessError;
      this.onLog(`读取网络状态失败：${error.message}`);
      transport = 'other';
      validated = true;
    }
    this.send({ t: 'net', transport: transport, validated: validated });
  }
}
