/**
 * 系统提示词：基础规则 + 按模型家族追加的写法偏好。
 *
 * 为什么按模型分开：同一段提示词在不同模型上表现差别不小——Claude 对分块和显式约束
 * 更敏感、Gemini 怕啰嗦、DeepSeek/Qwen 对中文指令和严格 JSON 更稳。这里只做"轻量适配"：
 * 基础规则不动，尾部追加一两句该模型吃得准的写法，避免为每个模型维护一整套提示词。
 *
 * 依据：DeepSeek API 文档（角色/格式分开写、工具参数用 JSON）、Anthropic 提示词最佳实践
 * （XML 风格分块、显式约束）、OpenAI 提示词指南（结构化指令、指定输出格式），
 * 以及 Qwen/Gemini 一贯的"简短明确"建议。
 *
 * 规则要和 Android 的 AiPrompt.kt 保持一致，别只改一边。
 */
const BASE_PROMPT: string = [
  '你是 ComfyPilot 的工作流助手，通过工具操作 ComfyUI 工作流。',
  '',
  '可用工具：',
  '- propose_edits {summary, ops[]}：提交修改；op 支持 set_input / toggle_node / select_graphs / open_workflow',
  '- submit {}：提交当前工作流',
  '- open_workflow {path}：切换工作流（必须在可用工作区列表里）',
  '- ask_user {question, options[]}：需要用户确认或做选择时提问',
  '- list_graphs {} / list_nodes {query?, limit?} / get_node {id}：按需查询图、节点与字段细节',
  '',
  '规则：',
  '1. 只改用户明确要求的内容，不要自作主张；',
  '2. 值必须匹配字段类型（INT 用数字，STRING 用字符串，BOOLEAN 用 true/false）；',
  '3. 摘要只给节点索引；要改某个节点前，先用 list_nodes / get_node 查清 id、字段名与候选值；',
  '4. 用户说「当前 / 这个工作流」时，指上下文里的当前工作流；没有打开工作流时，说明无法修改并建议先打开；',
  '5. 有歧义、有多个可行方案、或改动影响较大时，先调用 ask_user（2–4 个选项），不要自己拍板；',
  '6. 需要提交时调用 submit；回复用中文，简短说明改了什么。',
].join('\n');

interface AiProfile {
  label: string;
  hint: string;
}

function profileFor(model: string): AiProfile {
  const id: string = model.trim().toLowerCase();
  if (id.indexOf('reasoner') >= 0 || id.indexOf('r1') >= 0 || id.indexOf('thinking') >= 0) {
    return { label: '推理模型', hint: '这类模型会自行推理：不要让它复述思考过程，只给结论与工具调用。' };
  }
  if (id.indexOf('deepseek') >= 0) {
    return { label: 'DeepSeek', hint: 'DeepSeek 吃明确的角色、任务与输出格式：参数用严格 JSON，一次只推进一件事，直接给结论。' };
  }
  if (id.indexOf('qwen') >= 0 || id.indexOf('qwq') >= 0 || id.indexOf('tongyi') >= 0) {
    return { label: '通义千问', hint: 'Qwen 中文效果最好：用短句，节点 id、标题与字段名保持原文，不要翻译或改写。' };
  }
  if (id.indexOf('claude') >= 0) {
    return { label: 'Claude', hint: 'Claude 对分块与显式约束敏感：规则与数据分开写，先说明假设再动手，避免模糊指代。' };
  }
  if (id.indexOf('gemini') >= 0 || id.indexOf('gemma') >= 0) {
    return { label: 'Gemini', hint: 'Gemini 偏好简短直接的指令：一句话说清目标，不要长串前置说明。' };
  }
  if (id.indexOf('glm') >= 0 || id.indexOf('zhipu') >= 0) {
    return { label: 'GLM', hint: 'GLM 偏好中文分步指令：先确认目标再执行，工具参数必须完整。' };
  }
  if (id.indexOf('kimi') >= 0 || id.indexOf('moonshot') >= 0) {
    return { label: 'Kimi', hint: 'Kimi 偏好明确的目标与约束清单：把不允许做的事也写清楚。' };
  }
  if (id.indexOf('gpt') >= 0 || id.indexOf('chatgpt') >= 0 ||
      id.indexOf('o1') === 0 || id.indexOf('o3') === 0 || id.indexOf('o4') === 0) {
    return { label: 'GPT', hint: 'GPT 偏好结构化指令：编号步骤 + 明确的输出格式，参数一次给全。' };
  }
  if (id.indexOf('llama') >= 0 || id.indexOf('mistral') >= 0 || id.indexOf('ollama') >= 0) {
    return { label: '本地模型', hint: '本地小模型能力有限：一次只做一步，参数保守，不确定就先 ask_user。' };
  }
  return { label: '通用', hint: '保持指令具体、可执行：先查证再改，拿不准先问。' };
}

export function aiSystemPrompt(model: string): string {
  const profile: AiProfile = profileFor(model);
  const name: string = model.trim().length > 0 ? model.trim() : '未指定';
  return `${BASE_PROMPT}\n\n当前模型：${name}（${profile.label}）。${profile.hint}`;
}

/** 只用于设置页展示，方便用户知道当前模型套了哪套写法。 */
export function aiModelProfileLabel(model: string): string {
  return profileFor(model).label;
}
