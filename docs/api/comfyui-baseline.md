# ComfyUI HTTP / WebSocket 契约基线（阶段一）

> 本文件记录 ComfyPilot 当前实现所依赖的上游契约。所有编解码集中在
> `shared/src/commonMain/kotlin/com/comfypilot/core/api` 与 `core/event`，
> 上游变更时只改这一处，并同步更新本文件与 `tools/mock-comfyui`。

## 1. 基线来源

| 来源 | 用途 |
|---|---|
| <https://docs.comfy.org/development/comfyui-server/comms_routes> | 官方路由清单（`/prompt`、`/queue`、`/history/{prompt_id}`、`/interrupt`、`/upload/image`、`/view`、`/ws`） |
| `tools/mock-comfyui` | 阶段一可复现的请求/响应样本，覆盖正常、失败、静默、丢失四种场景 |
| 真实 ComfyUI 实例 | **尚未接入**，阶段二联调时补录；在此之前不得声称"已对真实服务验证" |

### 关于 "v2 API" 的说明

官方文档中这些路由**没有版本号命名**（只有 `/v2/userdata` 是独立路由）。
本项目按"官方 HTTP / WebSocket API"理解；若上游将来引入版本前缀，只需改
`ApiPaths` 的常量与 `SessionConfig.baseUrl` 的拼接规则。

## 2. 路由与建模

| 路由 | 方法 | 我们的模型 | 备注 |
|---|---|---|---|
| `/prompt` | POST | `PromptRequestBody` → `PromptResponse` | 请求体 `{prompt, client_id}`；成功返回 `prompt_id` + `number`；校验失败返回 `error` + `node_errors` |
| `/queue` | GET | `QueueResponse` → `QueueSnapshot` | 条目是**位置数组**而非对象，见下 |
| `/history/{prompt_id}` | GET | `Map<String, HistoryEntry>` | 未知 prompt 返回 `{}` |
| `/interrupt` | POST | `ApiCall.Interrupt` | 可选 `prompt_id` |
| `/upload/image` | POST | `UploadImageResponse` | multipart；阶段二再启用 |
| `/view` | GET | `ApiPaths.viewUrl` | 图片不跨设备搬运，接续方直接来取 |
| `/ws?clientId=...` | WS | `ComfyEvent` / `EventDecoder` | 事件是广播，客户端按自己的 lease 过滤 |

## 3. 响应样本

### 3.1 `/prompt` 成功

```json
{ "prompt_id": "8f0c1f7a-...", "number": 3, "node_errors": {} }
```

### 3.2 `/prompt` 校验失败（HTTP 400）

```json
{
  "error": {
    "type": "prompt_outputs_failed_validation",
    "message": "Prompt outputs failed validation",
    "details": "KSampler: value not in list"
  },
  "node_errors": {}
}
```

`PromptResponse.failureMessage` 会把 `type / message / details` 拼成一条可读文本。

### 3.3 `/queue`

```json
{
  "queue_running": [[0, "prompt-id-running", {}, {}, []]],
  "queue_pending": [[1, "prompt-id-pending", {}, {}, []]]
}
```

每行是 `[number, prompt_id, prompt, extra_data, outputs_to_execute]`。
`QueueSnapshot.from` 只取 `[0]` 与 `[1]`，其余位置忽略——上游扩展数组长度不会破解析。

### 3.4 `/history/{id}` 成功

```json
{
  "prompt-id": {
    "outputs": {
      "9": { "images": [{ "filename": "ComfyUI_0001.png", "subfolder": "", "type": "output" }] }
    },
    "status": {
      "status_str": "success",
      "completed": true,
      "messages": [["execution_start", {}], ["execution_success", {}]]
    }
  }
}
```

### 3.5 `/history/{id}` 失败

```json
{
  "prompt-id": {
    "outputs": {},
    "status": {
      "status_str": "error",
      "completed": false,
      "messages": [
        ["execution_error", { "node_id": "3", "exception_message": "CUDA out of memory" }]
      ]
    }
  }
}
```

判定顺序（`ReconcilePolicy.classify`）：
`error` → 立即失败；`execution_interrupted` → 取消；有输出/`completed` → 完成；
否则查 `/queue` 区分"进行中"与"丢失"。

### 3.6 WebSocket 事件

| type | 我们读取的字段 | 用途 |
|---|---|---|
| `status` | `data.status.exec_info.queue_remaining` | 仅参考，不改变任务真相 |
| `execution_start` | `data.prompt_id` | 任务进入执行中 |
| `execution_cached` | `data.nodes[]` | 忽略 |
| `executing` | `data.prompt_id`, `data.node` | 存活信号；`node=null` 表示空闲 |
| `progress` | `data.prompt_id`, `node`, `value`, `max` | 进度 + 重置静默计时 |
| `executed` | `data.prompt_id`, `node`, `output` | 累积产物引用 |
| `execution_error` | `data.prompt_id`, `node_id`, `exception_message` | 立即置失败（随后仍会被 /history 复核） |
| `execution_interrupted` | `data.prompt_id` | 置取消 |
| `b_preview` / `b_preview_with_metadata` | `data.node` | 忽略（二进制预览不参与状态） |
| 其他 | — | `ComfyEvent.Unknown`，不崩溃 |

## 4. 已明确的不确定性（阶段二需用真实实例核对）

1. **`/history` 的 `status` 形状**：当前按 `{status_str, completed, messages[]}` 建模，并用
   `ignoreUnknownKeys` 容忍多余字段；若上游改成对象数组需要改 `HistoryStatus`。
2. **`/queue` 行长度与字段顺序**：当前只信任位置 0/1，若未来改成对象需改
   `QueueSnapshot.from`。
3. **`execution_error` 的 message 位置**：mock 与文档一致放在 `data.exception_message`；
   真实实例可能带 `traceback`，已忽略。
4. **`clientId` 约定**：ComfyUI 接受客户端自带的 `clientId`，服务端不回传；因此
   "WS 会话 id"由我们生成并在 `ConnectionLease` 中携带，不依赖服务端下发。
5. **WS 事件不会携带接收者标识**：事件是广播，旧代际过滤只能靠本地 lease 校验 +
   "建新连接前先关旧连接"。
6. **认证**：当前只支持无鉴权的局域网部署。若需要 token，需在 `HttpRequest.headers`
   与 WS 握手 header 上扩展（阶段二再定）。

## 5. 回归方式

```powershell
# 1) 单元测试（不需要真实 ComfyUI）
.\gradlew.bat :shared:jvmTest

# 2) mock 服务（可选，手工联调）
cd tools\mock-comfyui
npm install
node server.mjs
# POST /mock/scenario {"scenario":"error"|"silent"|"lost"|"normal"|"slow"}
```
