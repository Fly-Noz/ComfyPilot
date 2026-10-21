# mock-comfyui

阶段一联调用最小 ComfyUI 兼容服务：只实现 ComfyPilot 用到的路由，用于复现
断网、失败、静默、丢失四类行为。**它不是 ComfyUI 的替代品**，也不参与单元测试
（单测用内联 fixture，不需要起服务）。

## 启动

```powershell
cd tools\mock-comfyui
npm install
node server.mjs
# 默认 http://0.0.0.0:8188 ，WS 为 ws://<host>:8188/ws?clientId=demo
```

Android 模拟器访问宿主机用 `http://10.0.2.2:8188`；真机用局域网 IP，并注意
`usesCleartextTraffic="true"` 已开启（仅调试用途）。

## 场景切换

```powershell
curl -X POST http://127.0.0.1:8188/mock/scenario -H "content-type: application/json" -d '{\"scenario\":\"error\"}'
```

| 场景 | 行为 | 对应验收点 |
|---|---|---|
| `normal` | 每 500ms 一个 `progress`，8 步后 `executed` + history 成功 | 正常完成 |
| `error` | `execution_error`（WS 直接报错） | WS 错误立即失败 |
| `history_error` | WS 只发 `executing(node=null)`，错误只写进 history | /history 可读 + error 立即失败、零重试 |
| `silent` | 先发 `execution_start` + 一次 `progress`，然后彻底静默；任务留在 /queue | 进度静默看门狗 → 重连 → 对账后仍是"执行中" |
| `lost` | 提交后立刻从 queue 消失，不写 history | 对账判定 `Unknown`，不自动重提 |
| `slow` | 每 10s 才发一次进度（8 步 = 80s） | 自适应静默预算；也用于"跑到一半重启服务端" |

辅助接口：`GET /mock/state`（含 `hits` 请求计数、`lastPrompt` 最近一次提交的
prompt 图、`lastUpload` 最近一次上传的文件名）、`POST /mock/drop-sockets`
（只掐断所有 WebSocket，不清 jobs/history/hits，用来模拟"ComfyUI 进程重启"且保留
计数以便对比对账前后的 `hits.prompt`）、`POST /mock/reset`（同时清零计数、
`lastPrompt` 与 `lastUpload`）、`GET /view`（返回 1x1 PNG）。

## 工作流夹具

`workflows/` 下的文件通过真实路径暴露，客户端不需要测试专用后门：

| 路由 | 行为 |
|---|---|
| `GET /userdata?dir=workflows&recurse=true&full_info=true` | 列出 `workflows/` 下所有 `.json`，返回 `[{path,size,modified,created}]` |
| `GET /userdata/workflows%2F{path}` | 返回文件原文；与真实前端一致把 `workflows/<path>` 整体编码，斜杠是 `%2F`；同时兼容未编码写法 |
| `GET /object_info/{class}` | 返回该节点定义；未知类返回 `{}`（与真实服务端一致） |
| `GET /system_stats` | 固定 RAM 32/12 GB、VRAM 24/8 GB；**不提供 CPU 利用率** |
| `GET /queue` | 运行/等待队列，供详情页状态条显示 |

`normal` / `slow` 场景的每次 `progress` 还会发一帧 **二进制采样预览**：
4 字节大端事件类型 4 + metadata 长度 + `{"prompt_id", "node_id"}` + 1x1 PNG。
客户端应能解码并在「预览」页显示。

夹具：

- `demo_api.json`：API 格式（`/prompt` 直接可用），txt2img 标准链路。
- `demo_ui.json`：网页端保存格式（`nodes`/`links`/`widgets_values`），用于格式识别与后续转换器。
- `demo_upload.json`：只有 `LoadImage` + `SaveImage` 的最小网页格式，用于相册上传链路。

`hits` 里对应 `userdata`（列表）、`workflow`（读原文）、`object_info`（取节点定义）、
`system`（系统状态）。

## 手工验证步骤（Android）

1. `node server.mjs`，场景保持 `normal`。
2. App 里连接 `http://10.0.2.2:8188`，点"提交"。
3. 观察任务从 提交中 → 排队中 → 执行中(0–100%) → 已完成。
4. 切到 `history_error` 再提交：任务应直接失败，`GET /mock/state` 的
   `hits.history` 为 1（零重试）、`hits.queue` 不出现。
5. 切到 `silent` 再提交：静默预算到点后客户端应主动断开重连（`clientId` 变化、
   `generation` 不变），重连后对账仍显示"执行中"，`hits.history`/`hits.queue` 各 1。
6. 切到 `lost` 再提交：对账后任务变为"状态未知"，且不会被自动重新提交。
7. 切到 `slow` 提交后立刻杀掉 `node server.mjs`，再重启：重连对账后该任务变为
   "状态未知"（服务端 history/queue 都没有它）。

## 工作流 UI 手工验证（Android）

1. 连接后应自动进入"选择工作流"，看到 `demo_api` / `demo_ui` / `demo_upload`。
2. 打开 `demo_ui`：顶部状态条显示 RAM/VRAM/队列；输入按节点分组，
   常用节点默认展开；底栏是「全部节点」和「提交任务」。
3. 把 KSampler 的 seed 改成别的值，提交；`GET /mock/state` 的
   `lastPrompt["3"].inputs.seed` 应等于界面上的值。
4. 提交后页面应保持在工作流详情并切到「预览」：执行中显示红色 1x1 采样帧，
   完成后显示 `/view` 的结果；任务页仍可看到 执行中 → 已完成。
5. 点「全部节点」弹出菜单，点 `#3 KSampler` 应关闭菜单、切回「输入」并滚到该节点。
6. 打开 `demo_upload`，点「从相册选择」上传一张图；控件值应变成
   `lastUpload.name`，提交后 `lastPrompt["1"].inputs.image` 应与之一致。

> 以上是联调清单，不代表已在真机验证。真机验证结果需在阶段报告中单独记录。
