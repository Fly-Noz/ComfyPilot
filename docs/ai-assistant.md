# AI 助手技术方案（设计稿）

状态：**已实现 Android / 鸿蒙第一版**（决策见第 14 节；已知缺口见第 15 节）。目标是把"用户自己填 API Key"的大模型接进 ComfyPilot，
用自然语言修改当前工作流并提交。

## 1. 目标与非目标

**目标**

- 用自然语言修改**当前已打开**的工作流：参数、节点开关、节点图选择、连线改接
- 修改前展示 diff，用户确认后才应用
- 应用后走现有校验与提交链路，并把任务结果回报给对话

**非目标（第一期不做）**

- 新建/删除节点、从零搭工作流、改拓扑
- 修改主机上的工作流文件（只做会话级覆盖）
- 云端中转、共享额度、内置厂商 Key

## 2. 能力边界

| 能力 | 复用的现有机制 | 备注 |
|---|---|---|
| 改参数 | `overrides: Map<nodeId, Map<input, JsonElement>>` | 已按「主机+路径」持久化 |
| 开关节点 | `bypassedNodeIds` / `enabledNodeIds` | 关闭关键节点已有试算确认 |
| 选节点图 | `enabledGraphIds` | |
| 改连线 | `linkOverrides` / `removedLinkIds` | 第二期再做，容易接错 |
| 校验 | `WorkflowDecisions.problems/warnings` | 与手动提交同一套 |
| 提交 / 取消 / 查任务 | 现有 `submit` / `cancel` / `/history` | |

## 3. 架构

```
┌──────────────────────────────┐
│ 聊天 UI（Android Compose / Harmony ArkUI）│
└──────────────┬───────────────┘
               │ 用户消息 / 确认
               ▼
┌──────────────────────────────┐
│ AI 会话（宿主侧）              │
│  · Provider 客户端（OpenAI 兼容）│
│  · Key 安全存储                │
└──────────────┬───────────────┘
               │ 结构化操作（Proposal）
               ▼
┌──────────────────────────────┐
│ 工具执行（共享层）             │
│  · 工具循环 / 轮数限额（AiLoopState）│
│  · 参数校验 / 工作区白名单      │
│  · 工作流摘要 get_workflow     │
│  · 操作应用 / diff / undo      │
└──────────────┬───────────────┘
               ▼
        现有 SessionHost / SessionRuntime → ComfyUI
```

设计原则：

- **Provider 客户端放宿主**（Android/Harmony），因为要流式输出和系统安全存储；
- **工具语义与校验放共享层**，保证两端行为一致；
- AI 只能通过"与手动编辑相同的入口"改状态，不新增旁路。

## 3.5 交互形态：悬浮球

- 只在**已连接**（有 lease）后显示，未连接时完全隐藏；
- 可拖动，松手后**自动吸附到屏幕左/右边缘**，记住上次吸附侧与纵向比例；
- 点击打开/收起悬浮对话框（对话面板），不跳页、不打断当前界面；
- 对话框内：消息列表、输入框、当前模型与 token 估算、自动提交开关；
- 连接断开时悬浮球隐藏，对话内容保留在主机的 AI 会话内存里；
- 悬浮球与对话框都只覆盖内容区，不遮挡状态栏与系统导航。

## 4. 上下文模型

发给模型的是压缩后的 `WorkflowDigest`：

```json
{
  "path": "MiniMaxH3.json",
  "format": "UI",
  "graphs": [{"id": "g1", "title": "图生视频", "selected": true}],
  "nodes": [
    {
      "id": 195,
      "class": "SamplerCustomAdvanced",
      "title": "采样器",
      "enabled": true,
      "reachable": true,
      "fields": [
        {"name": "steps", "type": "INT", "value": 4, "editable": true},
        {"name": "seed", "type": "INT", "value": 123, "editable": true}
      ]
    }
  ]
}
```

压缩规则：

1. 只发 `reachable = true` 的节点（默认），用户可要求"全部"；
2. 字段只给 `name/type/当前值`，不发节点定义全文；
3. 连线用 `{node, input} ← {originNode, slot}` 列表；
4. 超过预算（建议 12k tokens）时按"置顶节点 → 启用图 → 其余"截断，并告知模型"已截断"。

## 5. 操作协议（Proposal）

模型输出、App 执行的是**声明式操作列表**，不是自由文本：

```json
{
  "summary": "把采样步数改成 6，并关闭 Save Video 节点",
  "ops": [
    {"op": "set_input", "node": 195, "input": "steps", "value": 6},
    {"op": "toggle_node", "node": 189, "enabled": false}
  ],
  "questions": []
}
```

支持的 op：

| op | 参数 | 说明 |
|---|---|---|
| `set_input` | `node`, `input`, `value` | 值按字段类型转换，失败则整条拒绝 |
| `toggle_node` | `node`, `enabled` | 等价于现有旁路开关 |
| `select_graphs` | `graphs: string[]` | 只能选已存在的图 |
| `link_input` | `node`, `input`, `originNode`, `slot` | 第二期开放 |
| `clear_overrides` | `nodes?: number[]` | 恢复默认值 |
| `open_workflow` | `path` | 切换到指定工作流（需用户同意） |

执行前逐条校验：节点存在、字段可编辑、值类型匹配；任意一条失败就整批拒绝并回给模型。

## 6. 会话状态机

```
idle
 → collecting_context     拉取工作流摘要
 → awaiting_model         请求模型
 → proposing              收到 ops，生成 diff
 → awaiting_confirm       用户确认（默认必须）
 → applying               写 overrides / bypass
 → validating             试算 problems/warnings
 → submitting             提交
 → watching               轮询任务
 → done / failed / cancelled
```

约束：

- 单次对话最多 **6 轮**工具调用（`AiLoopState.maxRounds`，两端同一份）；
- 用户可随时"停止"，不产生副作用；
- **自动提交由用户设置**：默认「先确认」，可切换为「AI 修改后自动提交」；
- 无论哪种模式，删除/关闭关键节点这类破坏性操作仍然要单独确认。

## 7. Diff 与撤销

- 应用前对所有变更生成人类可读 diff：

```
节点 195 采样器
  steps: 4 → 6
节点 189 Save Video
  启用 → 关闭
```

- 应用前保存一份 `overrides/bypass` 快照；
- 提供「撤销本次 AI 修改」，回滚到快照；
- 同一会话最多保留 20 份快照，超出丢弃最旧。

## 8. 安全与隐私

### API Key

| 平台 | 存储 |
|---|---|
| Android | `EncryptedSharedPreferences` + Keystore 主密钥；不写入诊断日志 |
| HarmonyOS | `preferences` + HUKS 包装的密钥，或系统 `asset` 存储 |

- 只允许 HTTPS；`http://127.0.0.1` / `localhost` 例外（本地 Ollama）；
- Key 只在本机，请求直连用户填写的服务商，App 不做中转；
- 日志、崩溃上报、诊断导出中一律脱敏（`sk-***`）。

### 数据提示

- 首次启用 AI 时明确告知：**工作流结构、参数值和提示词会发送给你配置的模型服务商**；
- 提示词里可能含用户创作内容，需要用户知情同意；
- 不把 API Key、任务产物二进制发给模型（只给文件名/尺寸等元数据）。

### 成本

- 显示本轮 token 消耗估算；
- 支持设置"单次最多 N 轮 / 每次最多 M tokens"；
- 429/超时给出明确提示，不自动无限重试。

## 9. Provider 适配

统一按 **OpenAI 兼容协议**：

- `POST {baseUrl}/chat/completions`
- 支持 `tools` / `tool_choice`；不支持时降级为 `response_format: json_object` + 提示词约束
- 流式 SSE 仅用于展示"正在思考"，工具调用仍按完整响应解析

预设项（用户仍可自填）：

| 服务 | baseUrl 示例 |
|---|---|
| OpenAI | `https://api.openai.com/v1` |
| DeepSeek | `https://api.deepseek.com/v1` |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| Ollama | `http://127.0.0.1:11434/v1` |

## 10. 鸿蒙侧差异

- 用 `http.requestInStream` 接收 SSE，`on('dataReceive')` 增量解析；
- Key 走 HUKS 或加密 preferences；
- 工具执行通过现有 `nativeSessionSend` 命令回到共享层，不新增网络旁路；
- 页面不可见时暂停流式请求，回来再续（或提示"已中断"）。

## 11. 错误处理

| 场景 | 处理 |
|---|---|
| 401 | 提示 Key 无效，跳设置 |
| 429 | 退避 1 次，仍失败则提示限流 |
| 模型输出非法 JSON | 重试 1 次，仍失败则展示原文并请用户重述 |
| op 校验失败 | 整批拒绝，把失败原因回给模型让它修正 |
| validate 产生 error | 不提交，展示 problems，让用户决定 |
| 提交被服务器拒绝 | 展示 ComfyUI 返回的 node_errors |

## 12. 测试

- 共享层：op 校验、diff 生成、undo 的单元测试（`shared/src/commonTest`）；
- Provider：录制真实响应做回放，不依赖网络；
- UI：确认/取消/撤销三条路径的截图测试；
- 手工用例：改种子、关闭 Save 节点、选图、超预算工作流。

## 13. 路线图

| 阶段 | 内容 | 预估 |
|---|---|---|
| 0 | 本文档 + 工具 schema 评审 | 已完成 |
| 1 | 改参数/开关/选图 + diff + 确认 + 提交 | 主要工作量 |
| 2 | 连线改接、根据报错自动重试、偏好记忆 | 中 |
| 3 | 新建节点 / 从零搭工作流 | 不做（超出产品范围） |

## 14. 已定方案

| 问题 | 决定 |
|---|---|
| 自动提交 | 用户可设置：默认先确认，也可开启"AI 修改后自动提交"；破坏性操作仍需确认 |
| 切换工作流 | 允许，通过 `open_workflow` 工具，执行前弹一次确认 |
| 模型配置 | **全局一份**（baseUrl / Key / 模型名），所有工作流共用；暂不做按工作流配置 |
| 指令模板 | 不做 |
| 费用提示 | 显示本轮与累计 token **估算**（字符数 / 4），并提示以服务商账单为准 |
## 15. 实现现状（第一版）

已完成：

- 共享层：`AiWorkflowView` / `AiEditState` / `AiDigest` / `AiEdits`，以及 JSON 桥 `AiBridge`（含测试）
- Android：悬浮球 + 对话、OpenAI 兼容客户端、Key 走 AndroidKeyStore 加密、diff 确认、自动提交开关、切换工作流
- 鸿蒙：悬浮球 + 对话、OpenAI 兼容客户端、Key 走系统 Asset Store、经 `cp_ai_digest` / `cp_ai_apply` 复用共享层
- 工具往返：两端都按标准 `tool_calls` / `tool_call_id` 协议（循环在 `AiLoopState`，鸿蒙经 `cp_ai_step` / `cp_ai_send`）

已知缺口：

- 鸿蒙真机没验过 AI 发送（注入式输入进不了面板输入框，见 `docs/ai-loop-migration.md`）
- 未做真机联调（按计划由用户测试）
- 未做多工作流上下文缓存，每次只带当前工作流
- 悬浮球位置记忆只在本次会话内，重启后回到默认位置
