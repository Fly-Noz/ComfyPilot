import { preferences } from '@kit.ArkData';
import { http } from '@kit.NetworkKit';
import { common } from '@kit.AbilityKit';
import { AiSecretStore } from './AiSecretStore';

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
}

const TOOLS_JSON: string = `[
  {"type":"function","function":{"name":"propose_edits","description":"对当前工作流提交一组声明式修改","parameters":{"type":"object","properties":{"summary":{"type":"string"},"ops":{"type":"array","items":{"type":"object","properties":{"op":{"type":"string"},"node":{"type":"integer"},"input":{"type":"string"},"enabled":{"type":"boolean"},"graphs":{"type":"array","items":{"type":"string"}},"path":{"type":"string"},"value":{}},"required":["op"]}}},"required":["summary","ops"]}}},
  {"type":"function","function":{"name":"ask_user","description":"需要用户确认或选择时提问","parameters":{"type":"object","properties":{"question":{"type":"string"},"options":{"type":"array","items":{"type":"string"}}},"required":["question","options"]}}},
  {"type":"function","function":{"name":"submit","description":"提交当前工作流","parameters":{"type":"object","properties":{}}}},
  {"type":"function","function":{"name":"open_workflow","description":"切换到另一个工作流","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}}
]`;

const SYSTEM_PROMPT: string = [
  '你是 ComfyPilot 的工作流助手，通过工具操作 ComfyUI 工作流。',
  '可用工具：propose_edits（set_input / toggle_node / select_graphs / open_workflow）、submit、open_workflow、ask_user。',
  '规则：只改用户明确要求的内容；值必须匹配字段类型；节点 id 与字段名必须来自工作流摘要，不确定先问；',
  '用户说「当前/这个工作流」指上下文里的当前工作流，没有打开时说明无法修改并建议先打开；',
  '有歧义、多个可行方案或改动影响较大时，先调用 ask_user 给出 2-4 个选项，不要自己拍板；',
  '需要提交时调用 submit；用中文简短说明改了什么。',
].join('');

/**
 * 鸿蒙侧 AI 会话：OpenAI 兼容请求 + 工具循环 + 配置。
 *
 * Key 存在系统 Asset Store，只在请求头里使用，不写日志。
 */
export class AiAssistant {
  config: AiConfig = { baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat', apiKey: '', enabled: false, autoSubmit: false };
  messages: AiChatMessage[] = [];
  pending: AiPending | null = null;
  question: AiQuestion | null = null;
  busy: boolean = false;
  error: string = '';
  tokens: number = 0;
  host: AiHost | null = null;

  private store: preferences.Preferences | null = null;
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
    return this.version;
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
    this.busy = true;
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
    this.messages = [...this.messages, { role: 'assistant', content: `已应用：${pending.summary}` }];
    if (submit && !host.submit()) {
      this.error = '提交失败：工作流还有未填字段';
    }
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
      this.bump();
    }
  }

  private async loop(host: AiHost): Promise<void> {
    for (let round = 0; round < 5; round++) {
      const digest = host.buildViewJson();
      this.tokens = Math.floor(digest.length / 4);
      const paths = host.availableWorkflows();
      const systemMessages: AiChatMessage[] = [{ role: 'system', content: SYSTEM_PROMPT }];
      const currentPath = host.currentWorkflow();
      if (currentPath.length > 0) {
        systemMessages.push({ role: 'system', content: `用户当前停留的工作流是：${currentPath}` });
      }
      systemMessages.push({ role: 'system', content: `当前工作流摘要：${digest}` });
      if (paths.length > 0) {
        systemMessages.push({ role: 'system', content: `可切换的工作流（open_workflow 用）：${paths.slice(0, 60).join('、')}` });
      }
      const payload = this.buildRequest([...systemMessages, ...this.messages]);
      const message = await this.request(payload);
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
        const result = this.executeTool(call.function.name, call.function.arguments, host);
        results.push(`${call.function.name} -> ${result}`);
      }
      this.messages = [...this.messages, { role: 'user', content: `（工具结果）${results.join('\n')}` }];
      if (this.pending !== null || this.question !== null) {
        return;
      }
    }
    this.error = '工具调用轮数过多，已停止';
  }

  private executeTool(name: string, args: string, host: AiHost): string {
    if (name === 'propose_edits') {
      const probe = JSON.parse(args) as Record<string, Object>;
      const summary = (probe.summary as string) ?? '';
      if (this.config.autoSubmit) {
        const outcome = host.applyProposal(args);
        if (!outcome.ok) {
          return `{"ok":false,"errors":${JSON.stringify(outcome.errors.join('；'))}}`;
        }
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
      return `{"ok":${host.submit()}}`;
    }
    if (name === 'open_workflow') {
      const probe = JSON.parse(args) as Record<string, Object>;
      const path = (probe.path as string) ?? '';
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
