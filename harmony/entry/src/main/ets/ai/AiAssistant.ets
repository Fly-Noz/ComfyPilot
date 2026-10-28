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

export interface AiChangeView {
  label: string;
}

export interface AiApplyOutcome {
  ok: boolean;
  errors: string[];
  changes: string[];
  openWorkflow: string;
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

const TOOLS_JSON: string = `[
  {"type":"function","function":{"name":"propose_edits","description":"对当前工作流提交一组声明式修改","parameters":{"type":"object","properties":{"summary":{"type":"string","description":"一句话说明这次改了什么"},"ops":{"type":"array","items":{"type":"object","properties":{"op":{"type":"string"},"node":{"type":"integer"},"input":{"type":"string"},"enabled":{"type":"boolean"},"graphs":{"type":"array","items":{"type":"string"}},"path":{"type":"string"},"value":{}},"required":["op"]}}},"required":["summary","ops"]}}},
  {"type":"function","function":{"name":"ask_user","description":"需要用户确认或选择时提问","parameters":{"type":"object","properties":{"question":{"type":"string"},"options":{"type":"array","items":{"type":"string"}}},"required":["question","options"]}}},
  {"type":"function","function":{"name":"submit","description":"提交当前工作流","parameters":{"type":"object","properties":{}}}},
  {"type":"function","function":{"name":"task_status","description":"查看最近任务的状态/进度/产物；wait_seconds 可等待任务跑完（最多 120 秒）","parameters":{"type":"object","properties":{"wait_seconds":{"type":"integer"}}}}},
  {"type":"function","function":{"name":"open_workflow","description":"切换到另一个工作流","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}},
  {"type":"function","function":{"name":"list_graphs","description":"列出工作流里的节点图及选中状态","parameters":{"type":"object","properties":{}}}},
  {"type":"function","function":{"name":"list_nodes","description":"按标题/类型/id 搜索节点，返回轻量列表","parameters":{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer"}}}}},
  {"type":"function","function":{"name":"get_node","description":"读取单个节点的字段、当前值、COMBO 候选","parameters":{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}}},
  {"type":"function","function":{"name":"lookup_prompt_guide","description":"搜索某个生成模型的提示词规范，返回标题/链接/摘要；挑一个再用 fetch_doc 读正文","parameters":{"type":"object","properties":{"model":{"type":"string","description":"模型名或文件名，例如 flux1-dev、Qwen-Image"}},"required":["model"]}}},
  {"type":"function","function":{"name":"fetch_doc","description":"抓取一个 https 文档地址的正文；用于读官方 README/提示词指南","parameters":{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}}}
]`;

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
    try {
      await this.loop(host);
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
    const beforeState = host.stateJson();
    const outcome = host.applyProposal(proposalJson);
    if (!outcome.ok) {
      this.error = outcome.errors.join('；');
      this.bump();
      return;
    }
    // 保障层：应用后回读编辑状态，没变化说明改动没落地（宿主刷新/字段被裁剪等）。
    if (outcome.changes.length > 0 && host.stateJson() === beforeState) {
      this.error = '这些改动没有生效（工作流状态未变化），请重试';
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
    try {
      await this.loop(host);
    } catch (err) {
      this.error = (err as Error).message;
    } finally {
      this.busy = false;
      this.log(`loop done msgs=${this.messages.length} error=${this.error.substring(0, 120)}`);
      this.bump();
    }
  }

  private async loop(host: AiHost): Promise<void> {
    for (let round = 0; round < 5; round++) {
      if (this.cancelled) {
        return;
      }
      const digest = host.digestJson();
      this.tokens = Math.floor(digest.length / 4);
      const paths = host.availableWorkflows();
      const systemMessages: AiChatMessage[] = [{ role: 'system', content: aiSystemPrompt(this.config.model) }];
      const currentPath = host.currentWorkflow();
      if (currentPath.length > 0) {
        systemMessages.push({ role: 'system', content: `用户当前停留的工作流是：${currentPath}` });
      }
      systemMessages.push({ role: 'system', content: `当前工作流摘要：${digest}` });
      const guide = host.promptGuide();
      if (guide.length > 0) {
        this.log(`prompt guide ${guide.substring(0, 80)}`);
        systemMessages.push({ role: 'system', content: `生成提示词规范（写 text/prompt 字段时必须遵守）：${guide}` });
      }
      if (paths.length > 0) {
        systemMessages.push({ role: 'system', content: `可切换的工作流（open_workflow 用）：${paths.slice(0, 60).join('、')}` });
      }
      const payloadMessages: AiChatMessage[] = [...systemMessages, ...this.messages];
      const imageIndex = this.pendingImages.length > 0 ? payloadMessages.length - 1 : -1;
      this.log(`round ${round} request msgs=${this.messages.length} images=${this.pendingImages.length} tokens≈${this.tokens}`);
      let message: AiResponseMessage;
      try {
        message = await this.request(this.buildRequest(payloadMessages, imageIndex));
      } catch (err) {
        // 模型不认图片时退回纯文本，并记住这个模型，下次不再带图。
        if (round === 0 && this.pendingImages.length > 0) {
          this.log(`vision rejected: ${(err as Error).message}`);
          this.visionOffModel = this.config.model;
          this.saveVisionOff();
          this.pendingImages = [];
          this.messages = [...this.messages, {
            role: 'assistant',
            content: '（当前模型不接受图片输入，已改用文件名描述继续；要让它看图，请在设置里换支持视觉的模型）',
          }];
          const retry = [...systemMessages, ...this.messages];
          message = await this.request(this.buildRequest(retry, -1));
        } else {
          throw new Error((err as Error).message);
        }
      }
      this.log(`round ${round} reply tools=${(message.tool_calls ?? []).length} content=${(message.content ?? '').length}`);
      const toolCalls = message.tool_calls;
      if (toolCalls === undefined || toolCalls.length === 0) {
        this.messages = [...this.messages, { role: 'assistant', content: message.content ?? '（空响应）' }];
        return;
      }
      if (message.content !== undefined && message.content.length > 0) {
        this.messages = [...this.messages, { role: 'assistant', content: message.content }];
      }
      const results: string[] = [];
      for (const call of toolCalls) {
        const result = await this.executeTool(call.function.name, call.function.arguments, host);
        results.push(`${call.function.name} -> ${result}`);
      }
      this.messages = [...this.messages, { role: 'user', content: `（工具结果）${results.join('\n')}` }];
      if (this.pending !== null || this.question !== null) {
        return;
      }
    }
    this.error = '工具调用轮数过多，已停止';
  }

  /** 宿主可挂一个 sink，把 AI 日志并进 App 内诊断页（没电脑时直接看手机）。 */
  logSink: ((line: string) => void) | null = null;

  /** 工具声明：由宿主从共享层取（cp_ai_tools_json）；取不到时退回内置副本。 */
  toolsJson: string = '';

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
      const ok: boolean = host.submit();
      return ok
        ? '{"ok":true,"queued":true,"hint":"用 task_status（wait_seconds 例如 30）确认真实结果，别直接说已完成"}'
        : '{"ok":false,"error":"工作流还有未填字段或未就绪"}';
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

  /**
   * 有图时把对应那条消息的 content 换成 OpenAI 多模态数组：
   * [{type:text,text}, {type:image_url,image_url:{url}}]。
   * 图片只在本次请求里出现，历史里仍然只存文字。
   */
  private buildRequest(messages: AiChatMessage[], imageIndex: number): string {
    const images = this.pendingImages;
    const payload: AiPayloadMessage[] = [];
    for (let i = 0; i < messages.length; i++) {
      if (imageIndex >= 0 && i === imageIndex && images.length > 0) {
        const parts: AiContentPart[] = [{ type: 'text', text: messages[i].content }];
        for (const image of images) {
          const url: AiImageUrl = { url: image.dataUrl };
          parts.push({ type: 'image_url', image_url: url });
        }
        payload.push({ role: messages[i].role, content: parts });
      } else {
        payload.push({ role: messages[i].role, content: messages[i].content });
      }
    }
    const tools = this.toolsJson.length > 0 ? this.toolsJson : TOOLS_JSON;
    return `{"model":${JSON.stringify(this.config.model)},"temperature":0.2,` +
      `"messages":${JSON.stringify(payload)},"tools":${tools},"tool_choice":"auto"}`;
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
