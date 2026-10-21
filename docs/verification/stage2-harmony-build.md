# 阶段二 · 鸿蒙构建与真机自检

日期：2026-10-21（会话桥、后台冻结语义、真机四场景均为同日追加）

## 结论

分两层说，不要混：

1. **构建级**：共享层 + 会话桥 + 宿主循环 + HAP 都能编出来，C ABI 导出正确，
   115 个 JVM 测试全绿（含桥协议、冻结语义、重启恢复）。
   其中 4 个是端到端测试：用 JVM 版宿主执行器真的发 HTTP、真的握 WebSocket，
   对端是 `tools/mock-comfyui`，把阶段一的四个场景跑了一遍。
2. **真机级**：`cp_version() == 2` 的 HAP 在 Pura 70 Pro+ 上跑通了会话联调——
   ArkTS 宿主循环、`@ohos.net` 的 HTTP/WebSocket、`/history` 三分类、
   `/queue` 兜底、120s 进度看门狗重连、服务端丢任务后判 `Unknown` 且不重投。
   四个场景逐条有 hilog 与 mock 计数器作为证据（见下）。

仍然**没有**真机验证的：ArkTS 侧 RDB 持久化、切前后台的冻结检测、
WiFi↔蜂窝切换时 `generation` 递增（该机 `gsm.sim.state = ABSENT`，只能单测）。

```
shared/src/commonMain (Kotlin)
  → KuiklyBase Kotlin/Native (ohosArm64)
  → libcomfypilot_core.so
  → C++ NAPI 模块 libentry.so
  → entry-default-signed.hap
  → 真机运行
```

| 项 | 值 |
|---|---|
| 设备 | HUAWEI Pura 70 Pro+（HBN-AL80） |
| 系统 | HarmonyOS 7.0.0.109，API 26 |
| hdc 序列号 | `33Z0224613077036` |
| bundle 名 | `com.fly.comfypilot` |
| HAP | `entry-default-signed.hap`，约 6.7 MB（v2，含会话桥） |

bundle 名由 DevEco 自动签名流程确定（AGC 中的应用标识），不是最初计划的
`com.comfypilot.harmony`。签名 profile 里的 bundle 名与 `AppScope/app.json5`
必须一致，否则装机报 `9568320`/`10104001`。

## 已验证

### 构建级

| 项 | 证据 |
|---|---|
| 共享层能用 KBA 工具链编译 | `:shared-ohos:linkDebugSharedOhosArm64` BUILD SUCCESSFUL |
| 导出的 C ABI 正确 | `llvm-nm -D --defined-only` 显示 `cp_version`、`cp_self_test_json`、`cp_session_create/pump/send/destroy` |
| HAP 包含 Kotlin 产物 | zip 内 `libs/arm64-v8a/libcomfypilot_core.so`（已 strip） |
| NAPI 模块链接正确 | `libentry.so` 的 `NEEDED` 含 `libcomfypilot_core.so`，`RUNPATH` 含 `$ORIGIN` |
| 自检逻辑本身正确 | `SelfTestTest` 在 JVM 上通过 |
| 桥协议与状态机正确 | `SessionHostTest`（7）+ `SessionHostFreezeTest`（4）+ `SessionHostConfigTest`（4）等，共 115 个 JVM 测试、0 失败 |
| 桥协议能真跑（四个场景） | `BridgeEndToEndTest`（4）：JVM 宿主执行器 + `tools/mock-comfyui`，真 HTTP / 真 WebSocket。正常完成、`history_error` 不重试、`silent` 触发看门狗后仍能对账、服务端丢任务后 `Unknown` 且 `hits.prompt == 1` |
| 持久化载荷可执行 | 端到端测试里 `persist` 命令写出的快照含 `state=Completed` 与产物文件名 |
| 重启恢复语义正确 | `RestoreTest`（7）+ `PersistedTaskTest`（7）+ `SessionHostRestoreTest`（4）：`Submitting` → `Unknown`、未完成任务先对账、永不自动重投 |
| 签名 HAP 可产出 | `harmony/build.ps1` → `BUILD SUCCESSFUL`，产物 `entry-default-signed.hap` |

导出的符号（本次构建实测，`.so` 9.87 MB）：

```
T cp_self_test_json
T cp_session_create
T cp_session_destroy
T cp_session_pump
T cp_session_send
T cp_version
```

### 真机级 · 共享层自检（v1，历史记录）

| 项 | 证据 |
|---|---|
| 已签名 HAP 能安装 | `hdc install -r` → `install bundle successfully` |
| Ability 能拉起 | `aa start -a EntryAbility -b com.fly.comfypilot` → `start ability successfully` |
| `.so` 在 HarmonyOS 上实际加载成功 | 页面显示 C ABI 版本，未出现 `dlopen` 失败 |
| Kotlin 逻辑在真机跑通 | 页面显示“自检通过”，JSON 与 JVM 上一致 |

真机返回的自检 JSON：

```json
{"ok":true,"generationAfterStart":1,"generationAfterSwitch":2,"connectedLabel":"已连接","phaseAfterSwitch":"Connecting","openSocketEffects":1,"queueRunning":1,"message":"共享层自检通过"}
```

截图（本地生成，不入库）：`artifacts/ohos-selftest.jpeg`。

### 真机级 · 会话联调（v2，本次）

这一轮跑的是 `cp_version() == 2` 的 HAP：ArkTS `ComfyBridge` 按 500ms 调
`cp_session_pump`，`@ohos.net` 真发 HTTP、真握 WebSocket，对端是开发机上的
`tools/mock-comfyui`，手机通过 `hdc rport tcp:18188 tcp:8188` 访问。
页面只保留连接/断开/提交与任务列表，手机不联网也能跑（走 rport）。

| 场景 | 真机结果 | 证据 |
|---|---|---|
| `normal` | 任务 `Completed`，100%，产物 1 | UI `Completed · 100% · 产物 1`；mock `hits={prompt:1, history:1}` |
| `history_error` | 任务 `Failed`，只查一次 `/history` | UI `Failed · 0% · 产物 0`；mock `hits={prompt:1, history:1}`（无重试） |
| `silent` | 120s 后看门狗重连，对账后仍 `Running`，未重投 | 最后一次进度 19:51:24 → 19:53:24 `phase=Reconnecting` + `ws.close` → 19:53:26 `ws.open`、`Recovering`，`GET /queue`、`GET /history/<id>` → `Connected` 且 `Running:1`；mock `hits={prompt:1, queue:1, history:1}` |
| `lost` | 任务被判 `Unknown`，没有二次提交 | 提交后 mock 立即从队列丢掉（`hits={prompt:1}`）→ 掐断 WS 模拟 ComfyUI 重启 → 重连对账 `GET /queue`、`GET /history/<id>`（空）→ UI `Unknown · 0%`；最终 `hits={prompt:1, queue:1, history:1}` |

`silent` 的 hilog 原文（节选，`A03D00/com.fly.comfypilot/JSAPP`）：

```
19:51:24.612 CP> frame rev=55 phase=Connected tasks=Completed:100,Failed:0,Running:1
19:51:24.612 CP> cmd persist task=Running
19:53:24.566 CP> frame rev=58 phase=Reconnecting tasks=Completed:100,Failed:0,Running:1
19:53:24.568 CP> cmd notice
19:53:24.570 CP> cmd ws.close
19:53:26.098 CP> frame rev=60 phase=Connecting ...
19:53:26.101 CP> cmd ws.open
19:53:26.168 CP> frame rev=63 phase=Recovering ...
19:53:26.234 CP> http GET http://127.0.0.1:18188/queue -> 200 len=90
19:53:26.236 CP> http GET http://127.0.0.1:18188/history/57e1fe54-... -> 200 len=2
19:53:26.268 CP> frame rev=66 phase=Connected tasks=Completed:100,Failed:0,Running:1
```

最后一次进度（19:51:24）到看门狗触发（19:53:24）恰好 120.0s，说明
`progressSilenceMs` 确实传到了共享层；对账后任务仍是 `Running`、`hits.prompt`
始终是 1，说明没有走重投分支。
预算翻倍（120s → 240s）在状态帧里不可见，这一条只有 `BridgeEndToEndTest` 的断言作证。

`lost` 的 hilog 原文（节选）：

```
19:54:52.247 CP> cmd ws.close          # 服务端掐断（/mock/drop-sockets）
19:54:53.350 CP> frame rev=92 phase=Connecting tasks=...Unknown:1,Queued:0
19:54:53.413 CP> frame rev=95 phase=Recovering ...
19:54:53.427 CP> http GET .../queue -> 200 len=39
19:54:53.434 CP> http GET .../history/6d14cbac-... -> 200 len=2
19:54:53.461 CP> frame rev=99 phase=Connected tasks=...Unknown:1,Unknown:0
19:54:53.462 CP> cmd notice            # task_lost
```

另有一次**非计划内**的真实重启：排查过程中重启了 mock 进程，旧连接被掐断，
客户端把上一个仍在 `Running` 的任务对账成 `Unknown`，新进程的 `hits` 里没有 `prompt`——
相当于“ComfyUI 重启后不自动重投”的现场验证。

截图（本地生成，不入库）：`artifacts/ohos-four-scenarios.jpeg`（四个任务并列：
`Completed` / `Failed` / `Unknown` / `Unknown`）。

#### 真机联调发现并修掉的两个 bug

1. **`ComfyBridge.start()` 从来没发 `{"t":"start"}`**。`cp_session_create` 只把状态机装好，
   真正让 `SessionRuntime.connect()` 跑起来的是这条消息；缺了它页面永远停在
   `未连接 · 第 0 代`，而 HTTP/WS 一个请求都不会发。JVM 端到端测试用宿主执行器直接
   调 `deliver(start)`，绕过了 ArkTS 宿主，所以只有真机能暴露。
2. **任务行永远停在第一次渲染的状态**。`ForEach` 的 key 生成器只用了 `task.id`，
   ArkUI 认为 key 没变就不重建子项，于是共享层已经 `Completed:100`，界面还写着
   `Queued · 0%`。hilog 里 `ForEachNode skip mark dirty. Ids.size[...]` 是同一个现象。
   改成 `${task.id}:${state}:${percent}:${outputs}` 的组合 key 后立刻正常。

第一条也说明为什么“JVM 全绿”不能代替真机：宿主边界（谁负责发哪条消息）不在共享层里。

#### 真机联调的坑

`hdc rport` 不是永久的。中途 hdc 服务重启后转发规则消失，设备侧直接
`ECONNREFUSED`，而应用只会显示“重连中 · 第 1 代”。`hdc fport ls` 为空即中招，
重新执行 `hdc rport tcp:18188 tcp:8188` 即可。hilog 里对应
`Lws client connection error conn fail: ECONNREFUSED`。

## 未验证

- ArkTS 侧 RDB 读写（共享侧的快照 schema 与恢复规则已有单测，数据库本身还没实现；
  真机重启后任务列表目前会清空）
- 前台 ↔ 背景切换时的冻结检测在真机上的表现（逻辑有 4 个单测）
- `generation` 在 WiFi↔蜂窝切换时递增（该机没有 SIM，`gsm.sim.state = ABSENT`）
- `silent` 场景里“静默预算翻倍到 240s”的现场观察（状态帧不暴露预算）
- 后台长任务 / 实况窗（`backgroundTaskManager`、通知权限）

这些在 `harmony/README.md` 的“尚未完成”里也列了，不能当成已完成。

## 端到端夹具（离线）

`shared/src/jvmTest/.../host/BridgeHarness.kt` 是一个**真的**宿主执行器：按 C ABI 的命令
出栈就执行，`http` 用 JDK `HttpClient` 真发，`ws.open` 真握手，收到的帧再按协议回投。
对端是 `tools/mock-comfyui`，端口现挑，测试之间会 `POST /mock/reset`。

它验证的是协议本身可执行：URL、方法、请求头、base64 请求体、WS 文本帧、`persist` 载荷。
不验证 ArkTS 对 `@ohos.net` 的用法——那部分仍然只有真机说话。

```powershell
$env:JAVA_HOME = "D:\Tools\Java\jdk-21.0.6"
.\gradlew.bat :shared:jvmTest --tests "com.comfypilot.core.host.BridgeEndToEndTest"
```

需要本机有 `node` 和 `tools/mock-comfyui/node_modules`；缺依赖时测试会 skip 而不是失败，
所以**看到 skip 不算验证过**。

踩到的一个坑：JDK 客户端默认会带 `Upgrade: h2c`，而 mock 的 `upgrade` 处理只认 `/ws`，
会直接把连接掐掉（`header parser received no bytes`）。夹具固定用 HTTP/1.1，和真实的
OkHttp、`@ohos.net` 一致。

## 共享层的三个关键设计（构建级已验证）

### 1. pump 模型

Kotlin 不跑自己的线程：宿主按自己的节奏调 `cp_session_pump(nowMs)`，Kotlin 把到期的
定时器与协程队列跑到该时刻，吐出一帧命令（`http` / `ws.open` / `ws.close` /
`persist` / `notice`），宿主执行完把结果用 `cp_session_send` 回投。

好处是可复现：整套超时、退避、看门狗都由传入时间驱动，JVM 单测能直接“快进”，
不需要 `Thread.sleep`，也不需要真机。

### 2. 后台冻结从逻辑时间里扣掉

产品承诺是“后台可能暂停监控，回来自动同步”。如果按墙上时间算，用户切出去十分钟，
回来时看门狗会立刻把我们自己冻结的十分钟当成“ComfyUI 静默”：

- 误报进度超时、触发不必要的重连；
- 更糟的是在 `Recovering` 阶段，对账预算被冻结耗光，任务被误判 `Unknown`。

`SessionHost.pump` 的做法是：两次 pump 间隔超过 `freezeGapMs`
（默认 `max(2s, 4×tickIntervalMs)`）就认为这段时间没在监控，把它从逻辑时钟里扣掉。
断言在 `SessionHostFreezeTest`：

| 断言 | 含义 |
|---|---|
| 冻结十分钟不报进度超时 | 冻结不算静默 |
| 正常监控下的静默仍然触发看门狗 | 不能把看门狗一起关掉 |
| 对账窗口内冻结一小时，任务不被判 `Unknown` | 预算按监控时间算 |
| 退避期间冻结半小时，不爆发多次重连 | 定时器按逻辑时间到期 |

顺便挡住系统时间被往回调的情况：负跳变同样不推进逻辑时间，避免定时器卡住。

### 3. 重启恢复：恢复历史，不恢复控制权

持久化快照的 schema 由 Kotlin 单方面拥有（`host/PersistedTask.kt`）；宿主只搬字节。
`cp_session_create` 的配置里可以带 `tasks` 数组，把上次落盘的任务送回来。

恢复规则（`task/Restore.kt`）要防的是"重启把任务偷偷跑两遍"：`Submitting` 没有 `promptId`，
直接记 `Unknown`；`Queued`/`Running` 保留状态但对账后才算数；核对不上依旧是 `Unknown`，
**永远不自动重投**。草稿编号接着恢复出来的 `draft-N` 往后排，避免覆盖旧任务。

这条链路不依赖鸿蒙 API，所以能纯 JVM 验证：`SessionHostRestoreTest` 断言"恢复的 Running
任务只发 `/history` + `/queue`，一个 `/prompt` 都不发"。

## 签名

签名材料由 DevEco Studio 自动生成（Project Structure → Signing Configs →
Automatically generate signature），落到 `~/.ohos/config/default_harmony_*.p7b|.cer|.p12`，
`harmony/build-profile.json5` 里写入 `signingConfigs`。

这些材料与**本机、本账号**绑定（路径是绝对路径，口令是 DevEco 本地加密串）。
仓库里目前提交了这份配置（`harmony/build-profile.json5`），只是为了让当前开发机
能直接复现构建；换机器或换华为账号时必须重新生成自动签名覆盖它，否则签名、
安装都会失败。

## 工具链约束（重要）

官方 Kotlin（仓库 Android 侧用 2.4.21）**没有 `ohosArm64` 目标**，所以鸿蒙构建是独立的一套：

| 组件 | 版本 |
|---|---|
| Kotlin | `2.0.21-KBA-010`（KuiklyBase） |
| Gradle | 8.5（KBA 插件不兼容 Gradle 9） |
| kotlinx-coroutines | `2.0.21-coroutines-KBA-001` |
| kotlinx-serialization | `1.7.1-KBA-003` |
| OpenHarmony SDK | DevEco Studio 26.0 自带 |

因此 `commonMain` 必须同时能被 Kotlin 2.0.21 编译——这是长期约束，新增代码时要注意。

链接 `.so` 时编译器读 `OHOS_SDK_HOME`（不是 `DEVECO_SDK_HOME`），且 Gradle/Kotlin
守护进程会缓存旧环境；`ohos/build.ps1` 在自动探测到 SDK 时会先 `--stop` 再构建。

## 复现步骤

```powershell
# 1. Android 单测（同一份 commonMain）
$env:JAVA_HOME = "D:\Tools\Java\jdk-21.0.6"
.\gradlew.bat :shared:jvmTest

# 2. 编译 + 签名 + 安装 + 拉起 + 抓日志（一条命令）
cd harmony
.\install.ps1 -Serial 33Z0224613077036
```

`install.ps1` 会调用 `build.ps1`（内含 `ohos/build.ps1`），然后：

1. 找最新的 `*-signed.hap`（找不到就提示去 DevEco 开自动签名，而不是白装一次）；
2. `hdc kill` / `hdc start` / `install -r`；
3. `aa start`，清 `hilog` 后抓本 bundle 的日志；
4. `snapshot_display` 截图到 `artifacts/`。

bundle 名从 `AppScope/app.json5` 读取，不写死。

## 下一步

四个场景已经在真机上过了，接下来按这个顺序：

1. ArkTS 侧 RDB 持久化：把 `persist` / `persist.remove` 命令真正写库，启动时用
   `restoredTasksFromJson` 送回去（共享层 schema 已经就绪，只差宿主这一半）；
2. 切前后台验证冻结检测：页面把逻辑时间与看门狗预算显示出来，切出去几分钟再回来，
   确认不误报超时、且能补对账；
3. 前台长任务 / 实况窗：需要 `backgroundTaskManager` 与通知权限，文案保持
   “后台可能暂停监控，回来自动同步”，不要说“实时监控”；
4. 拿到有 SIM 的设备后补 WiFi↔蜂窝的 `generation` 递增验证（当前只有单测）。

复现四个场景时注意：`hdc rport` 每次 hdc 服务重启都会丢，先 `hdc fport ls` 确认。
