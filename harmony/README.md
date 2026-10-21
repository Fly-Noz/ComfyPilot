# harmony/ —— HarmonyOS 应用（阶段二）

这是鸿蒙侧的 DevEco 工程：一个 ArkTS 页面 + 一个 C++ NAPI 模块，调用
`ohos/` 编出来的 Kotlin 共享层 `.so`。

当前只验证**共享层能加载、状态机自检能跑**。网络、任务提交、后台任务都还没有接。

## 构建

```powershell
cd harmony
.\build.ps1
```

脚本会依次：

1. 调 `..\ohos\build.ps1` 编出 `libcomfypilot_core.so`（debug）；
2. 把 `.so` 复制到 `entry\libs\arm64-v8a\`，头文件复制到 `entry\src\main\cpp\include\`；
3. 用 DevEco 自带的 hvigor 跑 `assembleHap`。

产物：`entry\build\default\outputs\default\entry-default-unsigned.hap`。

没有签名配置，所以 HAP 是 unsigned；装机需要先在 DevEco 里配置签名。

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
- 前台长任务 / 后台暂停语义
- 真机验证（当前没有鸿蒙设备）
