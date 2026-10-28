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
}

const TOOLS_JSON: string = `[
  {"type":"function","function":{"name":"propose_edits","description":"对当前工作流提交一组声明式修改","parameters":{"type":"object","properties":{"summary":{"type":"string","description":"一句话说明这次改了什么"},"ops":{"type":"array","items":{"type":"object","properties":{"op":{"type":"string"},"node":{"type":"integer"},"input":{"type":"string"},"enabled":{"type":"boolean"},"graphs":{"type":"array","items":{"type":"string"}},"path":{"type":"string"},"value":{}},"required":["op"]}}},"required":["summary","ops"]}}},
  {"type":"function","function":{"name":"ask_user","description":"需要用户确认或选择时提问","parameters":{"type":"object","properties":{"question":{"type":"string"},"options":{"type":"array","items":{"type":"string"}}},"required":["question","options"]}}},
  {"type":"function","function":{"name":"submit","description":"提交当前工作流","parameters":{"type":"object","properties":{}}}},
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
  host: AiHost | null = null;

  private store: preferences.Preferences | null = null;
  private client: http.HttpRequest | null = null;
  private cancelled: boolean = false;
  private secrets: AiSecretStore = new AiSecretStore();
  private version: number = 0;

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
      // 恢复上次的对话与操作记录（只留最近一段，避免越存越大）。
      try {
        const rawMessages: string = this.store.getSync('messages', '') as string;
        if (rawMessages.length > 0) {
          this.messages = JSON.parse(rawMessages) as AiChatMessage[];
        }
        const rawActions: string = this.store.getSync('actions', '') as string;
        if (rawActions.length > 0) {
          this.actions = JSON.parse(rawActions) as string[];
        }
      } catch (err) {
        console.info('CP> AI 历史记录损坏，已忽略');
      }
    } catch (err) {
      this.error = (err as Error).message;
    }
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
      store.putSync('messages', JSON.stringify(this.messages.slice(-40)));
      store.putSync('actions', JSON.stringify(this.actions.slice(-50)));
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

  async send(text: string): Promise<void> {
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
    this.log(`send: ${prompt}`);
    this.busy = true;
    this.cancelled = false;
    this.error = '';
    this.pending = null;
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
    this.pending = null;
    const outcome = host.applyProposal(pending.proposalJson);
    if (!outcome.ok) {
      this.error = outcome.errors.join('；');
      this.bump();
      return;
    }
    this.actions = [...this.actions, ...outcome.changes].slice(-50);
    this.messages = [...this.messages, { role: 'assistant', content: `已应用：${pending.summary}` }];
    if (submit && !host.submit()) {
      this.error = '提交失败：工作流还有未填字段';
    }
    this.bump();
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
      const payload = this.buildRequest([...systemMessages, ...this.messages]);
      this.log(`round ${round} request msgs=${this.messages.length} tokens≈${this.tokens}`);
      const message = await this.request(payload);
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
      this.pending = { proposalJson: args, summary: summary, changes: outcome.changes };
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
      return `{"ok":${host.submit()}}`;
    }
    if (name === 'open_workflow') {
      const probe = JSON.parse(args) as Record<string, Object>;
      const path = (probe.path as string) ?? '';
      this.actions = [...this.actions, `切换工作流：${path}`].slice(-50);
      return `{"ok":${host.openWorkflow(path)}}`;
    }
    return `{"ok":false,"error":"未知工具 ${name}"}`;
  }

  private buildRequest(messages: AiChatMessage[]): string {
    return `{"model":${JSON.stringify(this.config.model)},"temperature":0.2,` +
      `"messages":${JSON.stringify(messages)},"tools":${TOOLS_JSON},"tool_choice":"auto"}`;
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
