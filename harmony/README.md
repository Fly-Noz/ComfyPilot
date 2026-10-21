# harmony/ —— HarmonyOS 应用（阶段二）

这是鸿蒙侧的 DevEco 工程：一个 ArkTS 页面 + 一个 C++ NAPI 模块，调用
`ohos/` 编出来的 Kotlin 共享层 `.so`。

当前只验证**共享层能在真机加载、状态机自检能跑**（见
`../docs/verification/stage2-harmony-build.md`）。网络、任务提交、后台任务都还没有接。

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

## 签名

`build-profile.json5` 里的 `signingConfigs` 由 DevEco Studio 自动生成
（Project Structure → Signing Configs → Automatically generate signature），
材料在 `~/.ohos/config/default_harmony_*`，**与机器和华为账号绑定**。

仓库里提交了当前开发机的这份配置（内容只是绝对路径 + DevEco 本地加密口令，
不含可用私钥），目的是让本机构建可复现；换机器/换账号请重新生成自动签名覆盖它。

注意事项：

- bundle 名（`AppScope/app.json5`）必须和 profile 里的 bundle 名一致，
  当前是 `com.fly.comfypilot`；改名要重新生成自动签名。
- profile 只对生成时选的 bundle 生效，抄别人的 `.p7b` 一定会失败。
- Debug profile 有有效期，过期后重新生成即可。

## 目录

```
entry/src/main/
  ets/pages/Index.ets          # 唯一页面：版本 + 自检结果
  cpp/napi_init.cpp            # NAPI 模块，转发到 Kotlin C ABI
  cpp/CMakeLists.txt           # 链接 libcomfypilot_core.so
  cpp/types/libentry/          # ArkTS 侧类型声明
  cpp/include/                 # 生成的头文件（不提交）
  libs/arm64-v8a/              # 生成的 .so（不提交）
```

## Kotlin 与 ArkTS 的边界

Kotlin 侧导出的是 C ABI，不是 NAPI：

| C ABI | 说明 |
|---|---|
| `cp_version()` | 共享层 ABI 版本 |
| `cp_self_test_json(buffer, capacity)` | 跑纯逻辑自检，UTF-8 JSON 写入调用方缓冲区，返回字节数 |

字符串由调用方提供缓冲区，Kotlin 只写入并返回长度，**跨语言边界没有内存所有权转移**。
NAPI 层（`napi_init.cpp`）负责把 C 字符串转成 JS 字符串。

## 尚未完成

- HTTP / WebSocket 传输（ArkTS `@ohos.net` 通过 NAPI 回调喂给 Kotlin）
- 时钟、任务存储、网络监视器的 `ohosArm64Main` 实现
- 前台长任务 / 后台暂停语义（文案须为“后台可能暂停监控，回来自动同步”）
- 阶段一四个场景在鸿蒙上的真机复现
