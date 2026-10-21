# ohos/ —— 鸿蒙侧共享层构建

这个目录是**独立的 Gradle 构建**，只做一件事：把 `shared/src/commonMain` 编成
鸿蒙可用的 `libcomfypilot_core.so`。它不构建 HAP，也不包含 ArkTS 工程。

## 为什么不是主 Gradle 构建

官方 Kotlin（本仓库 Android 侧用的是 2.4.21）**没有 `ohosArm64` 目标**，写了会直接
`Unresolved reference 'ohosArm64'`。目前能编鸿蒙的只有社区定制工具链，本仓库采用
腾讯 KuiklyBase：

| 组件 | 版本 | 原因 |
|---|---|---|
| Kotlin | `2.0.21-KBA-010` | 唯一提供 `ohosArm64` 的公开发行版 |
| Gradle | `8.5` | KBA 的 Kotlin 插件在 Gradle 9 上会因 API 移除而失败 |
| JDK | 21 | 与主工程一致 |
| kotlinx-coroutines | `2.0.21-coroutines-KBA-001` | 官方制品没有 ohosArm64 |
| kotlinx-serialization | `1.7.1-KBA-003` | 同上 |
| OpenHarmony SDK | DevEco Studio 自带 `sdk` | 链接 `.so` 需要 sysroot |

代价必须说清楚：**共享代码必须同时能被 Kotlin 2.0.21 编译**。这限制了语言特性和
标准库 API，但不影响 `commonMain` 现有代码（已经验证可编译）。

## 构建

```powershell
# 需要先有 DevEco Studio（含 OpenHarmony SDK）；脚本会自动找常见安装路径
cd ohos
.\build.ps1 :shared-ohos:linkDebugSharedOhosArm64
```

产物：

```
ohos\shared-ohos\build\bin\ohosArm64\debugShared\libcomfypilot_core.so
ohos\shared-ohos\build\bin\ohosArm64\debugShared\libcomfypilot_core_api.h
```

`DEVECO_SDK_HOME` 需指向 DevEco Studio 的 `sdk` 目录（例如
`D:\Huawei\DevEco Studio\sdk`）。`build.ps1` 会在未设置时自动探测并设置。

## 与 Android 构建的关系

- Android / 单测：仓库根目录的 `gradlew.bat`（Gradle 9.8.2 + Kotlin 2.4.21）。
- 鸿蒙：本目录的 `build.ps1`（Gradle 8.5 + Kotlin 2.0.21-KBA-010）。
- 两边**共用同一份源码**（`kotlin.srcDir("../../shared/src/commonMain/kotlin")`），
  不复制、不同步。

## 尚未完成

- C ABI / NAPI 封装（ArkTS 侧如何调用 Kotlin）
- `ohosArm64Main` 的平台实现（时钟、存储、网络监视器）
- HAP 集成与真机验证
