# 阶段一 · 真机验证记录（Android）

> 只记录实际执行过的操作与观察到的结果。未执行的项目单独列在"未验证"一节，
> 不以单测结果冒充实机结论。

- 日期：2026-10-21
- 设备：iQOO 7（型号 `V2049A`），Android 14 / API 34
- 应用：`:app` debug 构建（`assembleDebug`），`adb install -r` 安装
- 服务端：`tools/mock-comfyui`（宿主机），通过 `adb reverse tcp:18188 tcp:8188` 暴露为
  `http://127.0.0.1:18188`
- 调试参数：`--es baseUrl ... --ez autoConnect true --el silenceMs 20000`
  （仅调试入口，用于把默认 120s 的静默预算缩短到 20s）
- 单元测试：`:shared:jvmTest` 71 个用例全部通过（新增 `CompletionVerifyTest` 6 个）

## 结论摘要

| # | 场景 | 结果 | 关键证据 |
|---|---|---|---|
| 1 | 正常提交 → 完成 | 通过 | 已完成 + 输出文件名；全程 `clientId` 不变 |
| 2 | `/history` 可读 + error | 通过 | 显示原始错误；`hits.history = 1`，无 `/queue`、无重试 |
| 3 | WebSocket 静默（进度看门狗） | 通过 | 20s 后重连；任务保持"执行中"；`history`/`queue` 各 1 |
| 4 | ComfyUI 重启（在途任务） | 通过 | 任务变"状态未知"；未自动重新提交 |
| 5 | WiFi 断开/恢复 | 通过 | 断开→重连中且 `generation` 不变；恢复→`generation` 仍为 1 |
| 6 | WiFi ↔ 蜂窝切换 | **未验证** | 设备无 SIM（`gsm.sim.state = ABSENT`） |

## 逐项记录

### 1. 正常完成

- mock `normal`，点"提交"。
- 观察到：`执行中 87%` → `执行中 100%` → `已完成`，并显示
  `输出：ComfyPilot_mock_<id>.png`。
- 完成态在 `executing(node=null)` 到达后约 1s 内出现，**不是**等静默预算超时；
  完成前后 `clientId` 未变化（`a09a262f`），即没有为了判定完成而重连。
- 服务端 `/history/{id}` 返回 `status_str=success` 与 `outputs`，客户端展示的输出
  文件名与之对应。

### 2. `/history` 可读且报错

- mock 新增 `history_error`：WebSocket 只发 `executing(node=null)`，错误只写进
  `/history`（`status_str=error`）。
- 观察到：任务显示 `失败` 与原始错误 `history-only error (mock)`，约 3s 内完成判定。
- `GET /mock/state` 的请求计数：`{"prompt":1,"history":1}` —— 失败后**没有重试**，
  也没有额外的 `/queue` 请求。

### 3. WebSocket 静默（进度看门狗）

- mock `silent`：先发 `execution_start` 与一次 `progress`（1%），随后彻底静默，
  任务仍留在 `/queue` 的 running 里。
- 观察到：任务停在 `执行中 1%`；约 20s（调试静默预算）后自动重连：
  `clientId 5ac9bbba → e3295c10`，`generation` 仍为 1。
- 重连对账后：状态回到 `已连接`，任务仍是 `执行中 1%`（未被误判完成/失败），
  状态卡显示 `队列：运行 1 / 等待 0`。
- 请求计数：`history=1, queue=1`。

### 4. ComfyUI 重启（在途任务）

- mock `slow`（每步 10s）提交任务，2s 后杀掉 `node server.mjs`，随即重启服务端。
- 观察到：重连后该任务由 `执行中 12%` 变为 `状态未知`；新服务端 `history`/`queue`
  各被查询 1 次，两者都没有该 prompt。
- 未发生自动重新提交。

### 5. WiFi 断开与恢复（同类网络）

- `adb shell svc wifi disable` 后：`状态：重连中`、`网络：NONE`、`generation：1`、
  `clientId：—`，最近事件为"连接断开：网络已断开"。
- `adb shell svc wifi enable` 后：`状态：已连接`、`网络：WIFI · 已验证`、
  **`generation` 仍为 1**、`clientId` 变为 `19acff65`。
- 说明同类网络的断开/恢复只重建连接租约，不递增网络代际。

## 未验证（不得对外声称）

1. **WiFi ↔ 蜂窝切换递增 generation**：该设备未插 SIM（`gsm.sim.state=ABSENT`），
   没有蜂窝数据通道，无法在真机复现类别切换。仅由 `:shared:jvmTest` 的
   `NetworkSwitchTest` / `ScenarioTest` 覆盖。
2. **旧 generation 响应污染**：需要精确控制响应时序，真机难以稳定构造；
   仅由 `GenerationIsolationTest` 覆盖。
3. **长时间后台/灭屏保活**：本轮验证期间屏幕保持点亮、应用在前台。
   Android 14 对 `dataSync` 前台服务有额外约束，本阶段未做长时间挂机测试。
4. **前台服务通知内容**：阶段一未实现进度通知（属阶段二范围）。
5. **`/upload/image`**：按设计在阶段一未实现（调用会得到 `Failed`）。

## 真机暴露并已修复的问题

1. **首个网络回调丢失**：`AndroidNetworkMonitor` 原先在 `runtime` 赋值前注册，
   `registerDefaultNetworkCallback` 的首次同步回调被丢弃，界面长期显示
   `网络：NONE`，并会导致第一次类别切换不递增代际。
   修复：`SessionHost.connect` 先创建并持有 runtime，再注册/启动监视器。
2. **完成只能靠静默看门狗**：`executing(node=null)`（ComfyUI 宣告 prompt 执行结束）
   原先只被当作心跳，任务完成要等默认 120s 静默预算，还会把"已完成"误判成
   "连接卡住"而白重连一次。
   修复：`SessionReducer.beginVerification` 在**同一租约**上立即请求 `/history`
   复核（不关 socket、不递增 generation），复核完成即回到 `Connected`。
3. **`/queue` 驱动的对账丢弃 effects**：由队列快照触发的 `resolveSlot` 只取状态、
   丢掉 `Persist`/`Notify`，导致"判定丢失"时既不落库也无提示。
   修复：`onQueueResult` 与硬超时分支都收集并返回 effects。
4. **mock 场景不够贴近**：`silent` 原先完全不发事件，任务停在"排队中"，触发不到
   只针对 `Running` 的看门狗；改为先跑起来再静默。另新增 `history_error` 场景与
   `hits` 请求计数，便于核对"零重试"。

## 复现命令（摘要）

```powershell
# 宿主机
cd tools\mock-comfyui; node server.mjs

# 隧道与安装
adb reverse tcp:18188 tcp:8188
adb install -r app\build\outputs\apk\debug\app-debug.apk

# 启动并自动连接（silenceMs 仅用于缩短看门狗等待）
adb shell am start -n com.comfypilot.app/.MainActivity `
  --es baseUrl "http://127.0.0.1:18188" --ez autoConnect true --el silenceMs 20000

# 场景切换与计数
curl -X POST http://127.0.0.1:8188/mock/scenario -H "content-type: application/json" -d "{\"scenario\":\"history_error\"}"
curl http://127.0.0.1:8188/mock/state
```
