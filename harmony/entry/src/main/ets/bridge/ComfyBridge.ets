import {
  nativeSessionCreate,
  nativeSessionDestroy,
  nativeSessionPreview,
  nativeSessionPump,
  nativeSessionSend,
  nativeSessionWorkflow,
} from 'libentry.so';
import { connection, http, webSocket } from '@kit.NetworkKit';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { BusinessError } from '@kit.BasicServicesKit';
import { StoredTask, TaskStorage } from './TaskStorage';

/**
 * 鸿蒙侧的宿主循环。
 *
 * 分工是硬边界：Kotlin 决定"要做什么"（命令），这里只负责"怎么做"（系统调用）。
 * 所有网络请求、WebSocket、网络状态都从这里进 Kotlin，Kotlin 从不直接碰 @ohos.net。
 */

export interface BridgeOutput {
  nodeId: string;
  kind: string;
  filename: string;
  subfolder: string;
  type: string;
  displayName: string;
  mime: string;
  image: boolean;
  video: boolean;
  audio: boolean;
  path: string;
}

export interface BridgeTask {
  id: string;
  promptId: string | null;
  name: string | null;
  workflowPath: string | null;
  state: string;
  percent: number;
  progressValue: number;
  progressMax: number;
  progressNode: string | null;
  outputs: number;
  outputRefs: BridgeOutput[];
  error: string | null;
  updatedAtMs: number;
}

export interface CatalogEntry {
  path: string;
  name: string;
  size: number;
  modifiedMs: number;
}

export interface BridgeCatalog {
  state: string;
  error?: string;
  entries?: CatalogEntry[];
}

export interface BridgeWorkflow {
  loadingPath: string | null;
  path: string | null;
  name: string | null;
  format: string;
  error: string | null;
  nodeClasses: number;
  nodeInfo: number;
}

export interface BridgePreview {
  promptId: string | null;
  nodeId: string | null;
  receivedAtMs: number;
  bytes: number;
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
  catalog: BridgeCatalog;
  workflow: BridgeWorkflow;
  preview: BridgePreview | null;
}

/**
 * `persist` 命令里的任务快照。宿主只用 `id` 当主键、`state` 打日志；
 * 其余字段不解释，原样存进 RDB（schema 归 Kotlin）。
 */
interface PersistPayload {
  id: string;
  state?: string;
}

/** 本地历史任务的恢复结果，用于页面显示"到底有没有落盘"。 */
export interface TaskRestoreResult {
  storage: boolean;
  restored: number;
  error: string;
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
  /** `http` / `ws.*` 用数字 id；`persist.remove` 用字符串任务 id。 */
  id?: number | string;
  method?: string;
  url?: string;
  timeoutMs?: number;
  contentType?: string;
  headers?: Record<string, string>;
  body?: string;
  /** WebSocket 二进制帧的 base64；只用于 `ws.binary`。 */
  base64?: string;
  workflow?: string;
  workflowName?: string;
  workflowPath?: string;
  /** `cancel` 用：要取消的 ComfyUI promptId（不是宿主侧的任务 id）。 */
  promptId?: string;
  path?: string;
  code?: number;
  reason?: string;
  transport?: string;
  validated?: boolean;
  task?: PersistPayload;
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

const MIN_PUMP_MS = 16;
const MAX_PUMP_MS = 500;

/**
 * 只判断"这段字节是不是一个 JSON 对象"，不看内容。
 * 用来挡住被截断的坏行：一条坏记录会让整份 `tasks` 配置解析失败，连接都建不起来。
 */
function isJsonObject(raw: string): boolean {
  try {
    const parsed: object | null = JSON.parse(raw) as object | null;
    return parsed !== null && typeof parsed === 'object';
  } catch (err) {
    return false;
  }
}

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
  /** 任务快照缓存：就是"库里的内容"的内存副本，重连时重新喂给共享层。 */
  private taskCache: Map<string, string> = new Map();
  private storage: TaskStorage = new TaskStorage();
  /** persist 日志只记状态变化；进度每 500ms 一条会把日志刷没。 */
  private loggedStates: Map<string, string> = new Map();

  /**
   * 打开本地存储并读回历史任务。
   *
   * 只在进程启动时读一次，之后由 `persist` / `persist.remove` 命令维护缓存。
   * 打不开库不算致命：降级为内存存储，连接和任务跟踪照旧，只是重启会丢。
   */
  async init(context: common.Context): Promise<TaskRestoreResult> {
    const opened: boolean = await this.storage.open(context);
    if (!opened) {
      return { storage: false, restored: 0, error: this.storage.error };
    }
    const rows: StoredTask[] = await this.storage.load();
    let skipped: number = 0;
    for (let i = 0; i < rows.length; i++) {
      const row: StoredTask = rows[i];
      if (row.id.length === 0 || row.payload.length === 0 || !isJsonObject(row.payload)) {
        // 坏行不能带进 cp_session_create：整个配置解析失败会连连接都建不起来。
        // 宁可少一条历史（和 Kotlin 侧"不认识就丢弃"的策略一致），顺手删掉。
        skipped++;
        this.storage.remove(row.id);
        continue;
      }
      this.taskCache.set(row.id, row.payload);
    }
    if (skipped > 0) {
      this.onLog(`跳过 ${skipped} 条读不出来的历史记录`);
    }
    return { storage: true, restored: this.taskCache.size, error: '' };
  }

  /**
   * 不连接，只按任务缓存建一个会话并推进一帧，让恢复出来的历史先显示。
   * 用户点"连接"时会重建会话（配置里仍带着这些快照），所以这里不订阅网络、不起循环。
   */
  prepare(baseUrl: string): void {
    this.stop();
    const created: number = nativeSessionCreate(this.configJson(baseUrl));
    if (created !== 1) {
      this.onLog('共享层拒绝创建会话（配置无效）');
      return;
    }
    this.revision = -1;
    try {
      this.applyFrame(JSON.parse(nativeSessionPump(Date.now())) as BridgeFrame);
    } catch (err) {
      this.onLog(`pump 失败：${(err as BusinessError).message}`);
    }
  }

  start(baseUrl: string): boolean {
    this.stop();
    const created: number = nativeSessionCreate(this.configJson(baseUrl));
    if (created !== 1) {
      this.onLog('共享层拒绝创建会话（配置无效）');
      return false;
    }
    this.running = true;
    this.revision = -1;
    this.subscribeNetwork();
    // 建会话只是把状态机装好；真正发起连接的是这条 start，少了它状态会一直停在 Idle。
    this.send({ t: 'start' });
    this.tick();
    return true;
  }

  /**
   * 配置里带上历史任务。快照是 Kotlin 写出来的原文，这里只做字符串拼接，
   * 不解析、不筛选——哪条能恢复、恢复成什么状态由 `restoreTasks` 决定。
   */
  private configJson(baseUrl: string): string {
    const head: string = `{"baseUrl":${JSON.stringify(baseUrl)},` +
      '"tickIntervalMs":500,"progressSilenceMs":120000';
    if (this.taskCache.size === 0) {
      return `${head}}`;
    }
    const payloads: string[] = [];
    this.taskCache.forEach((payload: string) => {
      payloads.push(payload);
    });
    return `${head},"tasks":[${payloads.join(',')}]}`;
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

  submit(workflow: string, workflowName?: string, workflowPath?: string): void {
    const command: BridgeCommand = { t: 'submit', workflow: workflow };
    if (workflowName !== undefined && workflowName.length > 0) {
      command.workflowName = workflowName;
    }
    if (workflowPath !== undefined && workflowPath.length > 0) {
      command.workflowPath = workflowPath;
    }
    this.send(command);
  }

  /** 拉工作流目录；只在已连接时由用户触发或连接成功后自动触发。 */
  refreshWorkflows(): void {
    this.send({ t: 'workflows' });
  }

  openWorkflow(path: string): void {
    this.send({ t: 'workflow.open', path: path });
  }

  /**
   * 按需工作流视图/构建 prompt。共享层负责字段、节点图和 overrides 校验；
   * 宿主只把 ArkTS 的 JSON 请求原样送过去。
   */
  workflow(requestJson: string): string {
    return nativeSessionWorkflow(requestJson);
  }

  /** 按需取最新采样预览；返回带 base64 图片的 JSON，不进状态帧。 */
  preview(): string {
    return nativeSessionPreview();
  }

  reconnect(): void {
    this.send({ t: 'reconnect' });
  }

  /**
   * 取消任务。
   *
   * 共享层 `BridgeProtocol.kt` 只认 `{"t":"cancel","promptId":"..."}`：`promptId` 是
   * ComfyUI 返回的 prompt 号，共享层按它找任务、置为 Cancelled 并发 `/interrupt`。
   * 还没拿到 promptId 的草稿任务无法取消，调用方需要自己禁用按钮。
   */
  cancelTask(promptId: string): void {
    if (promptId.length === 0) {
      return;
    }
    this.send({ t: 'cancel', promptId: promptId });
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
      delay = this.applyFrame(JSON.parse(nativeSessionPump(Date.now())) as BridgeFrame);
    } catch (err) {
      const error = err as BusinessError;
      this.onLog(`pump 失败：${error.message}`);
    }
    // 命令执行期间产生过回投结果的话，别等 500ms 的定时器，马上再推进一轮。
    const wait = this.repump ? MIN_PUMP_MS : delay;
    this.repump = false;
    this.schedule(wait);
  }

  /** 应用一帧：更新状态、执行命令，返回建议的下次间隔。 */
  private applyFrame(frame: BridgeFrame): number {
    let delay = MAX_PUMP_MS;
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
    return delay;
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
        this.persistTask(command.task);
        break;
      case 'persist.remove':
        this.dropTask(command.id);
        break;
      case 'notice':
        this.handleNotice(command.notice);
        break;
      default:
        this.onLog(`未知命令：${command.t}`);
        break;
    }
  }

  /**
   * 一条任务快照：内存缓存 + RDB。payload 原样存，宿主不看内容。
   *
   * 缓存和库一起更新，是为了"断开/重连"时能把同一个任务重新交给共享层，
   * 而不是每按一次重连就清空任务列表。
   */
  private persistTask(task?: PersistPayload): void {
    if (task === undefined || task.id.length === 0) {
      return;
    }
    const payload: string = JSON.stringify(task);
    this.taskCache.set(task.id, payload);
    this.storage.save(task.id, payload);

    const state: string = task.state ?? '';
    if (this.loggedStates.get(task.id) !== state) {
      this.loggedStates.set(task.id, state);
      this.onLog(`任务变更：${task.id} ${state}`);
    }
  }

  private dropTask(id?: string | number): void {
    if (typeof id !== 'string' || id.length === 0) {
      return;
    }
    this.taskCache.delete(id);
    this.loggedStates.delete(id);
    this.storage.remove(id);
    this.onLog(`任务移除：${id}`);
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
      case 'submit_accepted':
        this.onNotice(`已提交：${notice.promptId ?? ''}`);
        break;
      default:
        this.onLog(`通知：${notice.kind}`);
        break;
    }
  }

  private runHttp(command: BridgeCommand): void {
    if (command.url === undefined || typeof command.id !== 'number') {
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
    if (command.url === undefined || typeof command.id !== 'number') {
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
        // Crystools 每秒刷几十条监控帧，连接后还会一次性补发一大段。共享层不用这些
        // 数据，全量透传会把 JS 线程和 hilog 冲垮，长任务的 execution 事件也容易被淹。
        if (data.indexOf('"crystools.') >= 0) {
          return;
        }
        const head: string = data.length > 120 ? data.substring(0, 120) : data;
        trace(`ws text len=${data.length} ${head}`);
        this.send({ t: 'ws.text', id: id, text: data });
      } else {
        // 预览图是二进制帧；共享层需要它来生成 type=1/4 采样图。
        // JSON 通道只能传字符串，这里用 base64 透传；每帧约 20-30KB。
        try {
          const bytes: Uint8Array = new Uint8Array(data);
          const encoded: string = this.base64.encodeToStringSync(bytes);
          this.send({ t: 'ws.binary', id: id, base64: encoded });
        } catch (err) {
          this.onLog(`二进制预览帧处理失败：${(err as Error).message}`);
        }
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
    if (typeof command.id !== 'number') {
      return;
    }
    const id: number = command.id;
    const socket = this.sockets.get(id);
    if (socket === undefined) {
      return;
    }
    this.sockets.delete(id);
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
