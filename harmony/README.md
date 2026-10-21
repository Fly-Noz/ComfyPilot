# harmony/ —— HarmonyOS 应用（阶段二）

这是鸿蒙侧的 DevEco 工程：一个 ArkTS 页面 + 一个 C++ NAPI 模块，调用
`ohos/` 编出来的 Kotlin 共享层 `.so`。

当前状态分两层，别混着说：

| 层级 | 状态 |
|---|---|
| 构建级 | 通过：共享层自检、会话桥、HAP 都能编出来，115 个 JVM 测试全绿 |
| 真机级 | 通过：自检、ArkTS 宿主循环、HTTP/WebSocket、四个连接场景（`normal` / `history_error` / `silent` / `lost`） |

四个场景的真机证据（hilog + mock 计数器）见 `../docs/verification/stage2-harmony-build.md`。
仍未真机验证的是：RDB 持久化、切前后台的冻结检测、WiFi↔蜂窝切换的 `generation` 递增。

## 构建与安装

```powershell
cd harmony
.\install.ps1              # 构建 + 安装 + 拉起 + 抓日志 + 截图
.\install.ps1 -SkipBuild   # 只重装已有的 HAP
.\build.ps1                # 只构建
```

`build.ps1` 会依次：

1. 调 `..\ohos\build.ps1` 编出 `libcomfypilot_core.so`（debug）；
2. 把 `.so` 复制到 `entry\libs\arm64-v8a\`，头文件复制到 `entry\src\main\cpp\include\`；
3. 用 DevEco 自带的 hvigor 跑 `assembleHap`。

产物：`entry\build\default\outputs\default\entry-default-signed.hap`
（没有签名配置时只有 `-unsigned.hap`，装不上，`install.ps1` 会直接提示）。

> PowerShell 5.1 下这些脚本必须是纯 ASCII：无 BOM 的 UTF-8 会被当成 ANSI 读，
> 中文注释会破坏变量（曾经把 `OHOS_SDK_HOME` 设成空串）。

## 真机联调

联调页默认连 `http://127.0.0.1:18188`，这个端口由 `hdc rport` 转到开发机的
`tools/mock-comfyui`（8188）：

```powershell
hdc rport tcp:18188 tcp:8188    # hdc 服务重启后必须重做
hdc fport ls                    # 空列表 = 转发已经丢了
```

转发丢了的表现是设备侧 `ECONNREFUSED`，页面只有"重连中 · 第 N 代"，
hilog 里是 `Lws client connection error conn fail: ECONNREFUSED`。

mock 的场景与计数器：

```powershell
$m = "http://127.0.0.1:8188"
Invoke-RestMethod "$m/mock/reset" -Method Post -Body "{}" -ContentType "application/json"
Invoke-RestMethod "$m/mock/scenario" -Method Post -Body '{"scenario":"silent"}' -ContentType "application/json"
Invoke-RestMethod "$m/mock/state"       # sockets / hits / pending / running / history
Invoke-RestMethod "$m/mock/drop-sockets" -Method Post -Body "{}" -ContentType "application/json"
```

`/mock/drop-sockets` 只掐断 WebSocket，不清 jobs/history/hits，用来模拟
"ComfyUI 进程重启"——这样对账前后的 `hits.prompt` 才能在同一个进程里对比。

`ComfyBridge.ets` 里的 `CP_TRACE` 打开时，每帧状态、每条命令、每次 WS/HTTP 往返
都会打一行 `CP>` 到 hilog：

```powershell
hdc shell "hilog -x" | Select-String "CP>"
```

真机上有用的几条：

```powershell
hdc shell uitest dumpLayout -p /data/local/tmp/layout.json   # 拿控件坐标
hdc shell uitest uiInput click 237 619                       # 按坐标点
hdc shell snapshot_display -f /data/local/tmp/shot.jpeg      # 截图
hdc shell power-shell wakeup                                 # 等 120s 看门狗时别让屏幕睡
```

## 签名

`build-profile.json5` 里的 `signingConfigs` 由 DevEco Studio 自动生成
（Project Structure → Signing Configs → Automatically generate signature），
材料在 `~/.ohos/config/default_harmony_*`，**与机器和华为账号绑定**。

仓库里提交了当前开发机的这份配置（内容只是绝对路径 + DevEco 本地加密口令，
不含可用私钥），目的是让本机构建可复现；换机器/换账号请重新生成自动签名覆盖它。

注意事项：

- bundle 名（`AppScope/app.json5`）必须和 profile 里的 bundle 名一致，
  当前是 `com.fly.comfypilot`；改名要重新生成自动签名。
- profile 只对生成时选的那个 bundle 生效，抄别人的 `.p7b` 一定会失败。
- Debug profile 有有效期，过期后重新生成即可。

## 目录

```
entry/src/main/
  ets/bridge/ComfyBridge.ets   # 宿主循环：pump + 执行 http/ws/persist/notice
  ets/pages/Index.ets          # 联调页：地址、连接、提交、任务列表、日志
  cpp/napi_init.cpp            # NAPI 模块，转发到 Kotlin C ABI
  cpp/CMakeLists.txt           # 链接 libcomfypilot_core.so
  cpp/types/libentry/          # ArkTS 侧类型声明
  cpp/include/                 # 生成的头文件（不提交）
  libs/arm64-v8a/              # 生成的 .so（不提交）
```

## Kotlin 与 ArkTS 的边界

**Kotlin 拥有策略与调度，ArkTS 拥有系统调用。** 具体到这一版：

- Kotlin 决定"要做什么"：发哪个 HTTP、开哪个 WebSocket、什么时候重连、任务算不算完成；
- ArkTS 只负责"怎么做"：`@ohos.net` 的 `http` / `webSocket` / `connection`；
- 两边只通过 JSON 帧通信，没有共享对象。

C ABI（`ohosArm64Main/.../SessionAbi.kt`）：

| C ABI | 说明 |
|---|---|
| `cp_version()` | ABI 版本，2 起有会话接口 |
| `cp_self_test_json(buffer, capacity)` | 纯逻辑自检，UTF-8 JSON 写入调用方缓冲区 |
| `cp_session_create(configJson)` | 建会话（配置：`baseUrl` 等；`tasks` 数组可带上次落盘的任务），1 成功 |
| `cp_session_pump(nowMs, buffer, capacity)` | **推进到 nowMs**，返回一帧 JSON |
| `cp_session_send(json)` | 回投宿主消息（IO 结果、网络变化、提交请求） |
| `cp_session_destroy()` | 关会话 |

字符串由调用方提供缓冲区，Kotlin 只写入并返回长度，**跨语言边界没有内存所有权转移**。
NAPI 层负责 C 字符串 ↔ JS 字符串，并在帧超出初始缓冲区时扩容重取；Kotlin 侧对
同一个 `nowMs` 有帧缓存，所以重取不会重复推进状态机。

`cp_session_*` 会兜住 Kotlin 异常并返回 `{"revision":-1,...,"error":"..."}`：
异常穿过 NAPI 边界会直接 abort 进程，宁可让上层看到错误。

### 为什么是 pump，不是回调

Kotlin 不在自己的线程里跑：宿主按自己的节奏 `pump(nowMs)`，Kotlin 把到期的定时器、
协程队列一口气跑完，然后交出一帧命令。好处是：

- 没有跨线程回调，状态机是单线程序列化的，好复现；
- 所有超时/退避都由宿主喂进来的时间决定，单测里可以直接"快进"；
- **宿主不 pump，会话就静止**——系统冻结应用时，语义天然就是"暂停监控"。

### 后台冻结

产品承诺是"后台可能暂停监控，回来自动同步"，这句话在 `SessionHost.pump` 里落地：
如果两次 pump 的间隔超过 `freezeGapMs`（默认 `max(2s, 4×tick)`），这段空档不算
"在监控"，会从逻辑时间里扣掉。于是回到前台时：

- 进度看门狗不会把我们自己冻结的时间算成 ComfyUI 静默；
- 对账预算（`reconcileWindowMs`）不会被冻结耗光，任务不会被误判成 `Unknown`；
- 退避不会被跳过，也不会因为"欠了很多定时器"一次爆发多次重连。

对应的断言在 `shared/src/commonTest/.../host/SessionHostFreezeTest.kt`。

### 持久化与重启

快照的 schema 由 Kotlin 单方面拥有（`host/PersistedTask.kt`）：`persist` 命令里带的就是它，
宿主只负责原样存、原样取，不要自己解析状态。启动时把上次的行拼成
`cp_session_create` 的 `tasks` 数组塞回去即可。

恢复规则（`task/Restore.kt`）只有一句话：**恢复历史，不恢复控制权**。

- `Submitting` 的任务没有 `promptId`，无从对账，直接变 `Unknown`；
- `Queued` / `Running` 保留原状态，等连上 WebSocket 后按 `/history`、`/queue` 核对，
  核对不上就是 `Unknown`，**永不自动重投**；
- 已结束的任务原样保留，作为历史；
- 草稿编号会接着恢复出来的 `draft-N` 往后排，不会撞车。

ArkTS 侧的 RDB 还没写（见下面的“尚未完成”），但恢复语义本身已经在 JVM 上锁住了：
`RestoreTest`、`PersistedTaskTest`、`SessionHostRestoreTest`。

## 尚未完成

- ArkTS 侧 RDB：共享侧的快照 schema 与恢复规则已完成并有单测，但数据库读写还没实现
  （现在只有内存存储 + `persist` 命令日志，重启即丢）
- 切前后台的冻结检测在真机上的表现（逻辑有 `SessionHostFreezeTest` 的 4 个断言）
- WiFi↔蜂窝切换时 `generation` 递增（测试机没有 SIM，只能单测）
- 前台长任务 / 实况窗（需要 `backgroundTaskManager` 与通知权限）
- 后台保活的电量策略与用户可见开关
- 重装/杀进程后的任务恢复（等 RDB 接上）

真机已经跑过的东西见 `docs/verification/stage2-harmony-build.md`：
`cp_version() == 2` 的 HAP 上，四个场景（`normal` / `history_error` / `silent` / `lost`）
逐条有 hilog 与 mock 计数器证据。
