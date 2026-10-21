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
| `error` | `execution_error` + history `status_str=error` | /history error 立即失败、零重试 |
| `silent` | 接受提交后不再发任何事件，也不写 history | 进度静默看门狗 → 重连 → 对账 |
| `lost` | 提交后立刻从 queue 消失，不写 history | 对账判定 `Unknown`，不自动重提 |
| `slow` | 每 10s 才发一次进度 | 自适应静默预算 |

辅助接口：`GET /mock/state`、`POST /mock/reset`、`GET /view`（返回 1x1 PNG）。

## 手工验证步骤（Android）

1. `node server.mjs`，场景保持 `normal`。
2. App 里连接 `http://10.0.2.2:8188`，点"提交"。
3. 观察任务从 提交中 → 排队中 → 执行中(0–100%) → 已完成。
4. 切到 `error` 再提交：任务应直接失败，日志里 `/history/p1` 只出现一次。
5. 切到 `silent` 再提交：约 2 分钟后（默认静默预算 120s）客户端应主动断开重连，
   重连后对账仍显示进行中，静默预算翻倍。
6. 切到 `lost` 再提交：对账后任务变为"状态未知"，且不会被自动重新提交。

> 以上是联调清单，不代表已在真机验证。真机验证结果需在阶段报告中单独记录。
