import { preferences } from '@kit.ArkData';
import { http } from '@kit.NetworkKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { common } from '@kit.AbilityKit';
import { AiSecretStore } from './AiSecretStore';
import { aiSystemPrompt } from './AiPrompt';
import { fetchDoc, lookupPromptGuide } from './AiLookup';

const STORE_NAME: string = 'ai_assistant';

export interface AiConfig {
  baseUrl: string;
  model: string;
  apiKey: string;
  enabled: boolean;
  autoSubmit: boolean;
}

export interface AiChatMessage {
  role: string;
  content: string;
}

/** 随消息发给多模态模型的图片：dataUrl 是 data:image/jpeg;base64,... */
export interface AiImage {
  name: string;
  dataUrl: string;
}

/** 一个历史会话：消息与操作记录各自独立，切换时不会串台。 */
export interface AiSessionInfo {
  id: string;
  title: string;
  updatedAt: number;
  active: boolean;
}

interface AiSession {
  id: string;
  title: string;
  messages: AiChatMessage[];
  actions: string[];
  updatedAt: number;
}

/** 多模态 content 数组里的一块；text 与 image_url 二选一。 */
interface AiContentPart {
  type: string;
  text?: string;
  image_url?: AiImageUrl;
}

interface AiImageUrl {
  url: string;
}

interface AiPayloadMessage {
  role: string;
  content: string | AiContentPart[];
}

/** 共享层循环的上下文：system 行由宿主给（它知道当前工作流与摘要）。 */
interface AiLoopContextRequest {
  model: string;
  system: string[];
  temperature: number;
}

/** 开一轮：之前的对话 + 这一句。 */
interface AiStartRequest {
  op: string;
  context: AiLoopContextRequest;
  history: AiChatMessage[];
  userTurn: AiPayloadMessage;
}

/** 推进循环：reply（模型响应）/ tool（工具结果）/ resume（用户回答了提问）。 */
interface AiSendRequest {
  op: string;
  context: AiLoopContextRequest;
  message?: AiResponseMessage;
  callId?: string;
  result?: string;
  pause?: string;
  question?: string;
  userTurn?: AiPayloadMessage;
}

/** 共享层给的下一步：http（发请求）/ call_host（执行工具）/ done（收工）。 */
interface AiLoopAction {
  action: string;
  body?: string;
  callId?: string;
  name?: string;
  args?: string;
  reason?: string;
  text?: string;
}

export interface AiChangeView {
  label: string;
}

export interface AiApplyOutcome {
  ok: boolean;
  errors: string[];
  changes: string[];
  openWorkflow: string;
  /** 桥实际写回的字段条数；0 且有 changes 说明校验过了但没落地。 */
  editCount: number;
}

export interface AiQuestion {
  question: string;
  options: string[];
}

export interface AiPending {
  proposalJson: string;
  summary: string;
  changes: string[];
  /** 提案时各字段的旧值（JSON：key -> 显示值），用于检测用户期间改过。 */
  oldValuesJson: string;
  /** 提案时所在工作流路径。 */
  basePath: string;
}

/** 宿主（Index 页面）提供的桥：视图、应用编辑、提交、切换工作流。 */
export interface AiHost {
  buildViewJson(): string;
  stateJson(): string;
  applyProposal(proposalJson: string): AiApplyOutcome;
  submit(): boolean;
  openWorkflow(path: string): boolean;
  availableWorkflows(): string[];
  currentWorkflow(): string;
  queryGraphs(): string;
  queryNodes(args: string): string;
  queryNode(args: string): string;
  digestJson(): string;
  /** 当前工作流的生成模型写提示词的习惯（由宿主按节点推断）。 */
  promptGuide(): string;
  /** 按 key（"节点id.输入名"）取当前显示值，返回 JSON 对象。 */
  fieldValues(keysJson: string): string;
  /** 最近任务摘要；waitSeconds > 0 时等到没有活动任务或超时。 */
  taskSummary(waitSeconds: number): Promise<string>;
}

/**
 * 鸿蒙侧 AI 会话：OpenAI 兼容请求 + 工具循环 + 配置。
 *
 * Key 存在系统 Asset Store，只在请求头里使用，不写日志。
 */
export class AiAssistant {
  config: AiConfig = { baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat', apiKey: '', enabled: false, autoSubmit: false };
  messages: AiChatMessage[] = [];
  actions: string[] = [];
  pending: AiPending | null = null;
  question: AiQuestion | null = null;
  busy: boolean = false;
  error: string = '';
  tokens: number = 0;
  canUndo: boolean = false;
  host: AiHost | null = null;

  private store: preferences.Preferences | null = null;
  private client: http.HttpRequest | null = null;
  private cancelled: boolean = false;
  private secrets: AiSecretStore = new AiSecretStore();
  private version: number = 0;

  /** 本轮要发给多模态模型的图片；只用于当前请求，不写进历史，避免越存越大。 */
  private pendingImages: AiImage[] = [];
  /** 明确拒绝过图片的模型名；下次直接跳过图片，省一次失败请求。 */
  private visionOffModel: string = '';
  /** 上一次 AI 修改的反向补丁 JSON；用于「撤销这次修改」。 */
  private lastUndoJson: string = '';
  /** 全部会话；当前会话的 messages/actions 在 persist 时同步进来。 */
  private sessions: AiSession[] = [];
  private activeId: string = '';

  async open(context: common.Context): Promise<void> {
    try {
      this.store = await preferences.getPreferences(context, STORE_NAME);
      this.config = {
        baseUrl: this.store.getSync('base_url', this.config.baseUrl) as string,
        model: this.store.getSync('model', this.config.model) as string,
        apiKey: this.secrets.load(),
        enabled: this.store.getSync('enabled', false) as boolean,
        autoSubmit: this.store.getSync('auto_submit', false) as boolean,
      };
      this.visionOffModel = this.store.getSync('vision_off_model', '') as string;
      this.loadSessions();
    } catch (err) {
      this.error = (err as Error).message;
    }
  }

  /** 恢复会话列表；老版本只有单条 messages/actions 的，迁移成一个会话。 */
  private loadSessions(): void {
    const store = this.store;
    if (store === null) {
      return;
    }
    try {
      const raw: string = store.getSync('sessions', '') as string;
      if (raw.length > 0) {
        this.sessions = JSON.parse(raw) as AiSession[];
      }
    } catch (err) {
      this.sessions = [];
    }
    if (this.sessions.length === 0) {
      let legacyMessages: AiChatMessage[] = [];
      let legacyActions: string[] = [];
      try {
        const rawMessages: string = store.getSync('messages', '') as string;
        if (rawMessages.length > 0) {
          legacyMessages = JSON.parse(rawMessages) as AiChatMessage[];
        }
        const rawActions: string = store.getSync('actions', '') as string;
        if (rawActions.length > 0) {
          legacyActions = JSON.parse(rawActions) as string[];
        }
      } catch (err) {
        legacyMessages = [];
      }
      this.sessions = [{
        id: this.newSessionId(),
        title: this.titleFor(legacyMessages),
        messages: legacyMessages,
        actions: legacyActions,
        updatedAt: Date.now(),
      }];
    }
    this.activeId = this.sessions[0].id;
    this.messages = [...this.sessions[0].messages];
    this.actions = [...this.sessions[0].actions];
  }

  private newSessionId(): string {
    return `s${Date.now().toString(36)}${Math.floor(Math.random() * 1000).toString(36)}`;
  }

  private titleFor(messages: AiChatMessage[]): string {
    for (const message of messages) {
      if (message.role === 'user' && !message.content.startsWith('（工具结果）')) {
        const text = message.content.replace(/\s+/g, ' ').trim();
        if (text.length > 0) {
          return text.length > 16 ? `${text.substring(0, 16)}…` : text;
        }
      }
    }
    return '新会话';
  }

  /** 把当前 messages/actions 写回活动会话。 */
  private syncActiveSession(): void {
    const index = this.sessions.findIndex((item: AiSession) => item.id === this.activeId);
    if (index < 0) {
      return;
    }
    this.sessions[index].messages = this.messages.slice(-40);
    this.sessions[index].actions = this.actions.slice(-50);
    this.sessions[index].title = this.titleFor(this.messages);
    this.sessions[index].updatedAt = Date.now();
  }

  sessionInfos(): AiSessionInfo[] {
    this.syncActiveSession();
    return this.sessions
      .slice()
      .sort((a: AiSession, b: AiSession) => b.updatedAt - a.updatedAt)
      .map((item: AiSession): AiSessionInfo => {
        const info: AiSessionInfo = {
          id: item.id,
          title: item.title,
          updatedAt: item.updatedAt,
          active: item.id === this.activeId,
        };
        return info;
      });
  }

  newSession(): void {
    this.syncActiveSession();
    const session: AiSession = {
      id: this.newSessionId(),
      title: '新会话',
      messages: [],
      actions: [],
      updatedAt: Date.now(),
    };
    this.sessions = [...this.sessions, session].slice(-20);
    this.activeId = session.id;
    this.messages = [];
    this.actions = [];
    this.pending = null;
    this.question = null;
    this.error = '';
    this.bump();
  }

  switchSession(id: string): void {
    const target = this.sessions.find((item: AiSession) => item.id === id);
    if (target === undefined || id === this.activeId) {
      return;
    }
    this.syncActiveSession();
    this.activeId = id;
    this.messages = [...target.messages];
    this.actions = [...target.actions];
    this.pending = null;
    this.question = null;
    this.error = '';
    this.bump();
  }

  deleteSession(id: string): void {
    const remaining = this.sessions.filter((item: AiSession) => item.id !== id);
    if (remaining.length === 0) {
      this.sessions = [];
      this.activeId = '';
      this.newSession();
      return;
    }
    this.sessions = remaining;
    if (this.activeId === id) {
      const next = remaining[remaining.length - 1];
      this.activeId = next.id;
      this.messages = [...next.messages];
      this.actions = [...next.actions];
      this.pending = null;
      this.question = null;
      this.error = '';
    }
    this.bump();
  }

  saveConfig(next: AiConfig): void {
    this.config = next;
    const store = this.store;
    if (store === null) {
      return;
    }
    store.putSync('base_url', next.baseUrl);
    store.putSync('model', next.model);
    store.putSync('enabled', next.enabled);
    store.putSync('auto_submit', next.autoSubmit);
    this.secrets.save(next.apiKey);
    store.flush();
  }

  bump(): number {
    this.version++;
    this.persist();
    return this.version;
  }

  /** 对话与操作记录落盘：进程被杀后回来还能看到上一轮改了什么。 */
  private persist(): void {
    const store = this.store;
    if (store === null) {
      return;
    }
    try {
      this.syncActiveSession();
      store.putSync('sessions', JSON.stringify(this.sessions.slice(-20)));
      store.flush();
    } catch (err) {
      // 存不下不影响本次会话
    }
  }

  get revision(): number {
    return this.version;
  }

  clearError(): void {
    this.error = '';
    this.bump();
  }

  reset(): void {
    this.messages = [];
    this.pending = null;
    this.error = '';
    this.tokens = 0;
    this.bump();
  }

  async send(text: string, images: AiImage[] = []): Promise<void> {
    const prompt = text.trim();
    const host = this.host;
    if (prompt.length === 0 || this.busy) {
      return;
    }
    if (host === null) {
      this.error = '先打开一个工作流';
      this.bump();
      return;
    }
    if (this.config.apiKey.length === 0 || this.config.baseUrl.length === 0) {
      this.error = '先在设置里填好接口地址、模型和 API Key';
      this.bump();
      return;
    }
    this.log(`send: ${prompt} images=${images.length}`);
    this.busy = true;
    this.cancelled = false;
    this.error = '';
    this.pending = null;
    // 上次这个模型明确拒绝过图片，就不再重复撞墙，只发文件名。
    this.pendingImages = this.visionOffModel === this.config.model ? [] : images;
    this.messages = [...this.messages, { role: 'user', content: prompt }];
    this.bump();
    const history = this.messages.slice(0, this.messages.length - 1);
    const first = this.startAction(host, history, this.buildTurn(prompt));
    try {
      await this.loop(host, first, prompt);
    } catch (err) {
      this.error = (err as Error).message;
    } finally {
      this.busy = false;
      this.log(`loop done msgs=${this.messages.length} error=${this.error.substring(0, 120)}`);
      this.bump();
    }
  }

  confirmPending(submit: boolean): void {
    const pending = this.pending;
    const host = this.host;
    if (pending === null || host === null) {
      return;
    }
    // 状态过期：工作流已经换了，这份提案作废。
    const currentPath = host.currentWorkflow();
    if (currentPath.length > 0 && pending.basePath.length > 0 && currentPath !== pending.basePath) {
      this.pending = null;
      this.error = '工作流已经切换，这次修改作废，请重新让 AI 生成';
      this.bump();
      return;
    }
    let proposalJson = pending.proposalJson;
    let skipped: string[] = [];
    if (pending.oldValuesJson.length > 0) {
      const oldValues = JSON.parse(pending.oldValuesJson) as Record<string, string>;
      const keys = Object.keys(oldValues);
      if (keys.length > 0) {
        const currentValues = JSON.parse(host.fieldValues(JSON.stringify(keys))) as Record<string, string>;
        const conflicts = keys.filter((key: string) => currentValues[key] !== oldValues[key]);
        if (conflicts.length > 0) {
          skipped = conflicts;
          proposalJson = this.filterConflicts(pending.proposalJson, conflicts);
          if (proposalJson.length === 0) {
            this.pending = null;
            this.error = '这些字段你都改过了，本次修改已全部跳过';
            this.bump();
            return;
          }
        }
      }
    }
    this.pending = null;
    this.lastUndoJson = this.buildInverse(proposalJson, pending.oldValuesJson);
    const outcome = host.applyProposal(proposalJson);
    if (!outcome.ok) {
      this.error = outcome.errors.join('；');
      this.bump();
      return;
    }
    // 保障层：桥说有改动、却一条字段都没写回，才算没生效。
    // 不再按「应用前后 fieldValues 是否变化」判断——那条回读在真机上不可靠，
    // 会把已经改好的情况误报成失败。
    if (outcome.changes.length > 0 && outcome.editCount === 0 && this.setInputKeys(proposalJson).length > 0) {
      this.error = '这些改动没有生效（桥没有写回任何字段），请重试';
    }
    this.canUndo = this.lastUndoJson.length > 0;
    this.actions = [...this.actions, ...outcome.changes].slice(-50);
    const suffix = skipped.length > 0 ? `（跳过你期间改过的：${skipped.join('、')}）` : '';
    this.messages = [...this.messages, { role: 'assistant', content: `已应用：${pending.summary}${suffix}` }];
    if (submit && !host.submit()) {
      this.error = '提交失败：工作流还有未填字段';
    }
    this.bump();
  }

  /** 撤销上一次 AI 修改：用提案前记下的旧值构造反向补丁。 */
  undoLast(): boolean {
    const host = this.host;
    const inverse = this.lastUndoJson;
    if (host === null || inverse.length === 0) {
      return false;
    }
    this.lastUndoJson = '';
    this.canUndo = false;
    const outcome = host.applyProposal(inverse);
    if (!outcome.ok) {
      this.error = `撤销失败：${outcome.errors.join('；')}`;
      this.bump();
      return false;
    }
    this.actions = [...this.actions, '撤销上一次修改'].slice(-50);
    this.messages = [...this.messages, { role: 'assistant', content: '已撤销上一次 AI 修改' }];
    this.bump();
    return true;
  }

  /** 提案里所有 set_input 的 "node.input" 键，用于应用后的回读校验。 */
  private setInputKeys(proposalJson: string): string[] {
    try {
      const proposal = JSON.parse(proposalJson) as Record<string, Object>;
      const ops = (proposal.ops as Record<string, Object>[]) ?? [];
      const keys: string[] = [];
      for (const op of ops) {
        if (op.op === 'set_input' && op.node !== undefined && op.input !== undefined) {
          keys.push(`${op.node}.${op.input}`);
        }
      }
      return keys;
    } catch (err) {
      return [];
    }
  }

  /** 去掉与用户手改冲突的 op，其余照常应用。 */
  private filterConflicts(proposalJson: string, conflicts: string[]): string {
    try {
      const proposal = JSON.parse(proposalJson) as Record<string, Object>;
      const ops = (proposal.ops as Record<string, Object>[]) ?? [];
      const kept = ops.filter((op: Record<string, Object>) => {
        if (op.op !== 'set_input') {
          return true;
        }
        return !conflicts.includes(`${op.node}.${op.input}`);
      });
      if (kept.length === 0) {
        return '';
      }
      return JSON.stringify({ summary: proposal.summary, ops: kept });
    } catch (err) {
      return '';
    }
  }

  /** 由提案 + 旧值构造反向补丁；只覆盖 set_input 与 toggle_node。 */
  private buildInverse(proposalJson: string, oldValuesJson: string): string {
    try {
      if (oldValuesJson.length === 0) {
        return '';
      }
      const oldValues = JSON.parse(oldValuesJson) as Record<string, string>;
      const proposal = JSON.parse(proposalJson) as Record<string, Object>;
      const ops = (proposal.ops as Record<string, Object>[]) ?? [];
      const inverse: string[] = [];
      for (const op of ops) {
        if (op.op === 'set_input') {
          const key = `${op.node}.${op.input}`;
          if (oldValues[key] !== undefined) {
            inverse.push(
              `{"op":"set_input","node":${op.node},"input":${JSON.stringify(op.input)},"value":${JSON.stringify(oldValues[key])}}`,
            );
          }
        } else if (op.op === 'toggle_node') {
          inverse.push(`{"op":"toggle_node","node":${op.node},"enabled":${!(op.enabled as boolean)}}`);
        }
      }
      if (inverse.length === 0) {
        return '';
      }
      return `{"summary":${JSON.stringify('撤销上一次修改')},"ops":[${inverse.join(',')}]}`;
    } catch (err) {
      return '';
    }
  }

  stop(): void {
    this.log('stop requested');
    this.cancelled = true;
    const client = this.client;
    if (client !== null) {
      client.destroy();
      this.client = null;
    }
    this.busy = false;
    this.error = '已停止';
    this.bump();
  }

  cancelPending(): void {
    this.pending = null;
    this.bump();
  }

  async answerQuestion(answer: string): Promise<void> {
    const host = this.host;
    this.question = null;
    if (host === null) {
      return;
    }
    this.messages = [...this.messages, { role: 'user', content: answer }];
    this.busy = true;
    this.bump();
    // 循环还在共享层里等着（pending/question 只是暂停），交回回答就继续，不必重开。
    const first = this.sendAction({
      op: 'resume',
      context: this.context(host),
      userTurn: { role: 'user', content: answer },
    });
    try {
      await this.loop(host, first, '');
    } catch (err) {
      this.error = (err as Error).message;
    } finally {
      this.busy = false;
      this.log(`loop done msgs=${this.messages.length} error=${this.error.substring(0, 120)}`);
      this.bump();
    }
  }

  /**
   * 一轮对话：下一步做什么由共享层决定。这里只执行两种动作——
   * 发一次 HTTP、执行一个宿主能力。等待用户确认/回答时共享层会返回 done，循环自然停下。
   */
  private async loop(host: AiHost, first: AiLoopAction, userText: string): Promise<void> {
    // 重试用：重开一轮时要拿「这条用户消息之前」的对话，不能把提示语混进去。
    const history = this.messages.slice(0, this.messages.length - 1);
    let action = first;
    let httpSeen = 0;
    while (true) {
      if (this.cancelled) {
        return;
      }
      if (action.action === 'http') {
        this.log(`request ${httpSeen + 1} images=${this.pendingImages.length} tokens≈${this.tokens}`);
        let message: AiResponseMessage;
        try {
          message = await this.request(action.body ?? '');
        } catch (err) {
          // 模型不认图片：记下这个模型，用纯文本重开一轮（共享层不支持中途换用户消息）。
          if (httpSeen === 0 && this.pendingImages.length > 0 && userText.length > 0) {
            this.log(`vision rejected: ${(err as Error).message}`);
            this.visionOffModel = this.config.model;
            this.saveVisionOff();
            this.pendingImages = [];
            this.messages = [...this.messages, {
              role: 'assistant',
              content: '（当前模型不接受图片输入，已改用文件名描述继续；要让它看图，请在设置里换支持视觉的模型）',
            }];
            action = this.startAction(host, history, this.buildTurn(userText));
            continue;
          }
          throw new Error((err as Error).message);
        }
        httpSeen++;
        action = this.sendAction({ op: 'reply', context: this.context(host), message: message });
        continue;
      }
      if (action.action === 'call_host') {
        const result = await this.executeTool(action.name ?? '', action.args ?? '{}', host);
        const ask = this.question;
        let pause = '';
        if (this.pending !== null) {
          pause = 'pending';
        } else if (ask !== null) {
          pause = 'question';
        }
        action = this.sendAction({
          op: 'tool',
          context: this.context(host),
          callId: action.callId ?? '',
          result: result,
          pause: pause,
          question: ask === null ? '' : ask.question,
        });
        continue;
      }
      this.finish(action);
      return;
    }
  }

  private finish(action: AiLoopAction): void {
    const reason = action.reason ?? '';
    if (reason === 'answer') {
      const text = action.text ?? '';
      this.messages = [...this.messages, { role: 'assistant', content: text.length > 0 ? text : '（空响应）' }];
      return;
    }
    if (reason === 'round_limit' || reason === 'error') {
      this.error = action.text ?? 'AI 请求失败';
    }
    // pending / question：界面已经切到确认或提问；stopped：stop() 已经写了「已停止」。
  }

  /** 每一步的上下文：system 行 + 模型。摘要必须重算，否则模型对着旧值改。 */
  private context(host: AiHost): AiLoopContextRequest {
    const digest = host.digestJson();
    this.tokens = Math.floor(digest.length / 4);
    const system: string[] = [aiSystemPrompt(this.config.model)];
    const currentPath = host.currentWorkflow();
    if (currentPath.length > 0) {
      system.push(`用户当前停留的工作流是：${currentPath}`);
    }
    system.push(`当前工作流摘要：${digest}`);
    const guide = host.promptGuide();
    if (guide.length > 0) {
      this.log(`prompt guide ${guide.substring(0, 80)}`);
      system.push(`生成提示词规范（写 text/prompt 字段时必须遵守）：${guide}`);
    }
    const paths = host.availableWorkflows();
    if (paths.length > 0) {
      system.push(`可切换的工作流（open_workflow 用）：${paths.slice(0, 60).join('、')}`);
    }
    return { model: this.config.model, system: system, temperature: 0.2 };
  }

  /** 用户这一轮的消息：有图时给多模态数组。 */
  private buildTurn(text: string): AiPayloadMessage {
    const images = this.pendingImages;
    if (images.length === 0) {
      return { role: 'user', content: text };
    }
    const parts: AiContentPart[] = [{ type: 'text', text: text }];
    for (const image of images) {
      const url: AiImageUrl = { url: image.dataUrl };
      parts.push({ type: 'image_url', image_url: url });
    }
    return { role: 'user', content: parts };
  }

  private startAction(host: AiHost, history: AiChatMessage[], turn: AiPayloadMessage): AiLoopAction {
    const request: AiStartRequest = {
      op: 'start',
      context: this.context(host),
      history: history,
      userTurn: turn,
    };
    return this.callNative(false, JSON.stringify(request));
  }

  private sendAction(request: AiSendRequest): AiLoopAction {
    return this.callNative(true, JSON.stringify(request));
  }

  private callNative(send: boolean, request: string): AiLoopAction {
    const call = send ? this.sendFn : this.stepFn;
    if (call === null) {
      return { action: 'done', reason: 'error', text: 'AI 循环没有接上共享层（native 桥未注入）' };
    }
    try {
      return JSON.parse(call(request)) as AiLoopAction;
    } catch (err) {
      return { action: 'done', reason: 'error', text: (err as Error).message };
    }
  }

  /** 宿主可挂一个 sink，把 AI 日志并进 App 内诊断页（没电脑时直接看手机）。 */
  logSink: ((line: string) => void) | null = null;

  /**
   * 共享层循环的两个入口（cp_ai_step / cp_ai_send），由 Index 在初始化时注入。
   * 循环、消息拼装、工具分派都在 Kotlin；这里只负责执行 HTTP 与宿主动作。
   */
  stepFn: ((request: string) => string) | null = null;
  sendFn: ((request: string) => string) | null = null;

  private log(message: string): void {
    const line: string = message.substring(0, 2000);
    hilog.info(0x0000, 'ComfyPilotAI', line);
    const sink = this.logSink;
    if (sink !== null) {
      sink(line);
    }
  }

  private async executeTool(name: string, args: string, host: AiHost): Promise<string> {
    this.log(`tool ${name} args=${args.substring(0, 300)}`);
    if (this.host !== host) {
      return '{"ok":false,"error":"工作流已关闭，请重新打开后再试"}';
    }
    if (name === 'propose_edits') {
      const probe = JSON.parse(args) as Record<string, Object>;
      const summary = (probe.summary as string) ?? '';
      if (this.config.autoSubmit) {
        const outcome = host.applyProposal(args);
        if (!outcome.ok) {
          return `{"ok":false,"errors":${JSON.stringify(outcome.errors.join('；'))}}`;
        }
        this.actions = [...this.actions, ...outcome.changes, '提交任务'].slice(-50);
        host.submit();
        this.messages = [...this.messages, { role: 'assistant', content: `已自动应用并提交：${summary}` }];
        return '{"ok":true,"applied":true,"submitted":true}';
      }
      const outcome = host.applyProposal(args);
      if (!outcome.ok) {
        return `{"ok":false,"errors":${JSON.stringify(outcome.errors.join('；'))}}`;
      }
      // 记下这些字段的旧值：确认时检测用户手改，撤销时用它们反向写回。
      const keys: string[] = [];
      const ops = (probe.ops as Record<string, Object>[]) ?? [];
      for (const op of ops) {
        if (op.op === 'set_input' && op.node !== undefined && op.input !== undefined) {
          keys.push(`${op.node}.${op.input}`);
        }
      }
      this.pending = {
        proposalJson: args,
        summary: summary,
        changes: outcome.changes,
        oldValuesJson: keys.length > 0 ? host.fieldValues(JSON.stringify(keys)) : '',
        basePath: host.currentWorkflow(),
      };
      return `{"ok":true,"pending":true}`;
    }
    if (name === 'list_graphs') {
      return host.queryGraphs();
    }
    if (name === 'list_nodes') {
      return host.queryNodes(args);
    }
    if (name === 'get_node') {
      return host.queryNode(args);
    }
    if (name === 'lookup_prompt_guide') {
      const probe = JSON.parse(args) as Record<string, Object>;
      const model = (probe.model as string) ?? '';
      if (model.length === 0) {
        return '{"ok":false,"error":"model 为空"}';
      }
      return await lookupPromptGuide(model);
    }
    if (name === 'fetch_doc') {
      const probe = JSON.parse(args) as Record<string, Object>;
      const url = (probe.url as string) ?? '';
      if (url.length === 0) {
        return '{"ok":false,"error":"url 为空"}';
      }
      return await fetchDoc(url);
    }
    if (name === 'ask_user') {
      const probe = JSON.parse(args) as AskProbe;
      const options = probe.options ?? [];
      if (probe.question === undefined || probe.question.length === 0 || options.length === 0) {
        return '{"ok":false,"error":"question 或 options 为空"}';
      }
      this.question = { question: probe.question, options: options.slice(0, 4) };
      this.messages = [...this.messages, { role: 'assistant', content: probe.question }];
      return '{"ok":true,"waiting":true}';
    }
    if (name === 'submit') {
      this.actions = [...this.actions, '提交任务'].slice(-50);
      // 只回原始结果：核实提示由共享层的 formatResult 统一加，两端不再各写一份。
      return `{"ok":${host.submit()}}`;
    }
    if (name === 'task_status') {
      const probe = JSON.parse(args) as Record<string, Object>;
      const wait = (probe.wait_seconds as number) ?? 0;
      return await host.taskSummary(wait);
    }
    if (name === 'open_workflow') {
      const probe = JSON.parse(args) as Record<string, Object>;
      const path = (probe.path as string) ?? '';
      this.actions = [...this.actions, `切换工作流：${path}`].slice(-50);
      return `{"ok":${host.openWorkflow(path)}}`;
    }
    return `{"ok":false,"error":"未知工具 ${name}"}`;
  }

  private saveVisionOff(): void {
    const store = this.store;
    if (store === null) {
      return;
    }
    try {
      store.putSync('vision_off_model', this.visionOffModel);
      store.flush();
    } catch (err) {
      // 存不下只影响下次是否重试
    }
  }

  private async request(body: string): Promise<AiResponseMessage> {
    const base = this.config.baseUrl.trim().replace(/\/+$/, '');
    const client = http.createHttp();
    this.client = client;
    try {
      const response = await client.request(`${base}/chat/completions`, {
        method: http.RequestMethod.POST,
        header: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${this.config.apiKey}`,
        },
        extraData: body,
        expectDataType: http.HttpDataType.STRING,
        connectTimeout: 30000,
        readTimeout: 120000,
      });
      if (response.responseCode < 200 || response.responseCode >= 300) {
        throw new Error(`HTTP ${response.responseCode}: ${(response.result as string).substring(0, 200)}`);
      }
      const root = JSON.parse(response.result as string) as AiChatResponse;
      const choice = root.choices !== undefined && root.choices.length > 0 ? root.choices[0] : null;
      if (choice === null) {
        throw new Error('响应里没有 choices');
      }
      return choice.message;
    } finally {
      client.destroy();
      if (this.client === client) {
        this.client = null;
      }
    }
  }
}

interface AskProbe {
  question?: string;
  options?: string[];
}

interface AiToolFunction {
  name: string;
  arguments: string;
}

interface AiToolCall {
  id?: string;
  function: AiToolFunction;
}

interface AiResponseMessage {
  content?: string;
  tool_calls?: AiToolCall[];
}

interface AiChatChoice {
  message: AiResponseMessage;
}

interface AiChatResponse {
  choices?: AiChatChoice[];
}
