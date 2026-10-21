# 阶段二 · 鸿蒙构建与真机自检

日期：2026-10-21（会话桥与后台冻结语义为同日追加）

## 结论

分两层说，不要混：

1. **构建级**：共享层 + 会话桥 + 宿主循环 + HAP 都能编出来，C ABI 导出正确，
   93 个 JVM 测试全绿（含桥协议、冻结语义）。
2. **真机级**：只验证过“共享层能在真机加载、纯逻辑自检能跑通”。
   会话联调（网络 / WebSocket / 任务）**尚未上真机**——设备在开发过程中掉线
   （`hdc list targets -v` 返回 `[Empty]`），不是“没时间跑”。

所以现在的准确说法是：**鸿蒙端骨架已通，网络与任务语义未在真机验证**。

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
| HAP | `entry-default-signed.hap`，约 6.6 MB（v1 自检版） |

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
| 桥协议与状态机正确 | `SessionHostTest`（7）+ `SessionHostFreezeTest`（4）+ `SessionHostConfigTest`（4）等，共 93 个 JVM 测试、0 失败 |
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

### 真机级（仅限 v1 自检版）

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

注意：这次真机运行的是 `cp_version() == 1` 的 HAP，**不含会话桥**。当前仓库里的
HAP 是 `cp_version() == 2`，只做过构建级验证。

## 未验证

- 会话桥在真机上跑通（`cp_session_pump` → NAPI → ArkTS → 回投）
- ArkTS `@ohos.net` 的 HTTP / WebSocket 实际收发（只在构建级确认能编译）
- 任务提交、`/history` 三分类、`/queue` 兜底
- 断线重连、退避、进度看门狗在真机上的行为
- 前台 ↔ 背景切换时的冻结检测（逻辑有单测，真机未跑）
- 阶段一的四个连接场景在鸿蒙上的复现
- `generation` 在真机网络切换时的行为（Android 侧也仅单测）

这些在 `harmony/README.md` 的“尚未完成”里也列了，不能当成已完成。

## 共享层的两个关键设计（构建级已验证）

### 1. pump 模型

Kotlin 不跑自己的线程：宿主按自己的节奏调 `cp_session_pump(nowMs)`，Kotlin 把到期的
定时器与协程队列跑到该时刻，吐出一帧命令（`http` / `ws.open` / `ws.send` /
`ws.close` / `persist` / `notice`），宿主执行完把结果用 `cp_session_send` 回投。

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

设备恢复后按这个顺序：

1. `hdc rport tcp:18188 tcp:8188`，让手机访问开发机的 mock ComfyUI；
2. `cd harmony; .\install.ps1`，页面填 `http://127.0.0.1:18188`；
3. 跑阶段一的四个场景：连接/提交/完成、`history_error` 立即失败、`silent` 看门狗、
   ComfyUI 重启后任务 `Unknown` 且不重投；
4. 切前后台，确认冻结检测在真机上按预期工作（不误报超时、回来能补对账）；
5. 以上都过了，再补 RDB 持久化与前台长任务/实况窗（需要 `backgroundTaskManager`
   与通知权限）。
