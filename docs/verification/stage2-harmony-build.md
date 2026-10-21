# 阶段二 · 鸿蒙构建链路验证

日期：2026-10-21

## 结论

共享层已经能以**官方 API 之外的可行方式**编进鸿蒙 HAP：

```
shared/src/commonMain (Kotlin)
  → KuiklyBase Kotlin/Native (ohosArm64)
  → libcomfypilot_core.so
  → C++ NAPI 模块 libentry.so
  → entry-default-unsigned.hap
```

**没有真机验证。** 本机 `hdc list targets` 为空，所以下面区分“已验证”和“未验证”。

## 已验证（构建级）

| 项 | 证据 |
|---|---|
| 共享层能用 KBA 工具链编译 | `:shared-ohos:linkDebugSharedOhosArm64` BUILD SUCCESSFUL |
| 导出的 C ABI 正确 | `llvm-nm -D` 显示 `cp_version`、`cp_self_test_json` |
| HAP 构建成功 | `entry-default-unsigned.hap`，约 6.5 MB |
| HAP 包含 Kotlin 产物 | zip 内 `libs/arm64-v8a/libcomfypilot_core.so`（5.08 MB，已 strip） |
| NAPI 模块链接正确 | `libentry.so` 的 `NEEDED` 含 `libcomfypilot_core.so`，`RUNPATH` 含 `$ORIGIN` |
| 自检逻辑本身正确 | `SelfTestTest` 在 JVM 上通过；全部单测 73 个、0 失败 |

构建命令：

```powershell
cd harmony
.\build.ps1
```

## 未验证（必须有鸿蒙设备才能做）

- `.so` 在 HarmonyOS 上的**实际加载**（`dlopen`/重定位/`libc++_shared` 版本匹配）
- NAPI 调用返回的 JSON 在真机上的内容
- HAP 安装（当前 unsigned，需 DevEco 配置签名）
- 网络、WebSocket、前后台切换、长任务
- 任何连接场景

这些在 `harmony/README.md` 的“尚未完成”里也列了，不能当成已完成。

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
# 1. 共享层 + 鸿蒙 .so
cd ohos
.\build.ps1 :shared-ohos:linkDebugSharedOhosArm64

# 2. Android 单测（同一份 commonMain）
cd ..
$env:JAVA_HOME = "D:\Tools\Java\jdk-21.0.6"
.\gradlew.bat :shared:jvmTest

# 3. HAP
cd harmony
.\build.ps1
```

## 下一步

1. 设计并实现 Kotlin ↔ ArkTS 的异步桥：HTTP / WebSocket 由 ArkTS `@ohos.net` 执行，
   通过 NAPI 线程安全回调把结果送回 Kotlin 的 `HttpTransport` / `WebSocketTransport`。
2. `ohosArm64Main` 补时钟、任务存储、网络监视器。
3. 真机（或模拟器）跑通阶段一的四个场景，再谈“鸿蒙端可用”。
