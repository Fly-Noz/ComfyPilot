# 阶段二 · 鸿蒙构建与真机自检

日期：2026-10-21

## 结论

共享层已经能在**真机**上加载并执行，链路是通的：

```
shared/src/commonMain (Kotlin)
  → KuiklyBase Kotlin/Native (ohosArm64)
  → libcomfypilot_core.so
  → C++ NAPI 模块 libentry.so
  → entry-default-signed.hap
  → 真机运行
```

但请注意范围：**只验证了“共享层能被加载、纯逻辑状态机自检能跑通”**。
网络、WebSocket、任务提交、前后台与长任务语义全部还没接，所以不能说“鸿蒙端可用”。

| 项 | 值 |
|---|---|
| 设备 | HUAWEI Pura 70 Pro+（HBN-AL80） |
| 系统 | HarmonyOS 7.0.0.109，API 26 |
| hdc 序列号 | `33Z0224613077036` |
| bundle 名 | `com.fly.comfypilot` |
| HAP | `entry-default-signed.hap`，约 6.6 MB |

bundle 名由 DevEco 自动签名流程确定（AGC 中的应用标识），不是最初计划的
`com.comfypilot.harmony`。签名 profile 里的 bundle 名与 `AppScope/app.json5`
必须一致，否则装机报 `9568320`/`10104001`。

## 已验证

### 构建级

| 项 | 证据 |
|---|---|
| 共享层能用 KBA 工具链编译 | `:shared-ohos:linkDebugSharedOhosArm64` BUILD SUCCESSFUL |
| 导出的 C ABI 正确 | `llvm-nm -D` 显示 `cp_version`、`cp_self_test_json` |
| HAP 包含 Kotlin 产物 | zip 内 `libs/arm64-v8a/libcomfypilot_core.so`（5.08 MB，已 strip） |
| NAPI 模块链接正确 | `libentry.so` 的 `NEEDED` 含 `libcomfypilot_core.so`，`RUNPATH` 含 `$ORIGIN` |
| 自检逻辑本身正确 | `SelfTestTest` 在 JVM 上通过；全部单测 73 个、0 失败 |

### 真机级

| 项 | 证据 |
|---|---|
| 已签名 HAP 能安装 | `hdc install -r` → `install bundle successfully` |
| Ability 能拉起 | `aa start -a EntryAbility -b com.fly.comfypilot` → `start ability successfully` |
| `.so` 在 HarmonyOS 上实际加载成功 | 页面显示 C ABI 版本 `1`（`cp_version()` 返回），未出现 `dlopen` 失败 |
| Kotlin 逻辑在真机跑通 | 页面显示“自检通过”，JSON 与 JVM 上一致 |

真机返回的自检 JSON：

```json
{"ok":true,"generationAfterStart":1,"generationAfterSwitch":2,"connectedLabel":"已连接","phaseAfterSwitch":"Connecting","openSocketEffects":1,"queueRunning":1,"message":"共享层自检通过"}
```

截图（本地生成，不入库）：`artifacts/ohos-selftest.jpeg`。

## 未验证

- 网络与 WebSocket（ArkTS `@ohos.net` ↔ Kotlin 的异步桥尚未实现）
- 任务提交、`/history` 三分类、`/queue` 兜底
- 前后台切换、长任务、后台暂停/回前台同步
- 阶段一的四个连接场景在鸿蒙上的复现
- `generation` 在真机网络切换时的行为（Android 侧也仅单测）
- 断线重连、watchdog、进度静默超时

这些在 `harmony/README.md` 的“尚未完成”里也列了，不能当成已完成。

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

1. 设计并实现 Kotlin ↔ ArkTS 的异步桥：HTTP / WebSocket 由 ArkTS `@ohos.net` 执行，
   通过 NAPI 线程安全回调把结果送回 Kotlin 的 `HttpTransport` / `WebSocketTransport`。
2. `ohosArm64Main` 补时钟、任务存储、网络监视器。
3. 在真机上跑通阶段一的四个场景，再谈“鸿蒙端可用”。
