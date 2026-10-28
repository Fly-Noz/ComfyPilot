# AI 循环迁移：交接与设计（2026-10-28）

> 这不是新功能文档，是给「下一段会话」用的上下文快照：目标、已做的事、踩过的坑、
> 下一步该改哪些文件。读完这一页就能继续，不需要翻旧对话。

## 一、目标

把 AI 助手的**工具分派与循环状态机**从 Android / 鸿蒙各一份，收进 `shared`。
现状：协议已统一（工具声明、无状态分派），**循环本身仍两份**。

## 二、已完成

| 项 | 位置 | 说明 |
|---|---|---|
| 工具声明唯一来源 | `shared/.../core/ai/AiToolSpec.kt` | 10 个工具的 JSON；Android 直接解析，鸿蒙经 `cp_ai_tools_json` → napi `nativeAiTools` |
| 无状态工具分派 | `shared/.../core/ai/AiToolRouter.kt` + `AiToolEnv` | submit / task_status / open_workflow / list_graphs / list_nodes / get_node / lookup_prompt_guide / fetch_doc |
| Android 接入 | `app/.../ai/AiRuntime.kt` | 实现 `AiToolEnv`；`executeTool` 先走 router；旧 8 个 `when` 分支已不可达，待删 |
| 鸿蒙接入 | — | **未接**。原因见下 |

测试：`AiToolSpecTest`、`AiToolRouterTest`；`:shared:jvmTest` 271 项通过。

## 三、关键结论：鸿蒙不能靠单次 ABI 调路由器

路由器分派时会**回调宿主**（open_workflow 先取 allowedPaths；list_nodes 要 view+state；
task_status 要等任务）。ArkTS 的能力无法在一次 C 调用里同步回填，napi 回调 Kotlin
再回 ArkTS 是跨语言死结。

**正确做法**：照抄会话层已验证的模式 `cp_session_pump` / `cp_session_send`：

```
cp_ai_step(stateJson) -> {"action":"http" | "call_host" | "done" | "ask_user", ...}
宿主执行后 cp_ai_send(resultJson) -> 下一个 action
```

循环、消息拼装、工具分派全在 Kotlin；鸿蒙只剩「执行 HTTP」和「执行宿主动作」两个执行器。

## 四、下一步（按顺序）

1. `shared/.../core/ai/AiLoopState.kt`
   - 状态：messages、round、pending、question、maxRounds
   - 方法：`nextRequest(...)` / `acceptReply(...)` / `acceptToolResult(...)` / `finish()`
   - 纯逻辑，先写单测（覆盖：无工具直接结束、工具往返、达到轮数上限、pending 后暂停）
2. `AiToolEnv` 改为**返回指令**（`Http` / `CallHost` / `Done`），不再直接调宿主
3. Android 先消费状态机（无 ABI），模拟器跑通（不提交任务也能验证：发一句会触发 list_graphs 的话）
4. 鸿蒙加 `cp_ai_step` / `cp_ai_send`（ABI + napi + ArkTS 绑定，参照 `cp_ai_tools_json` 的缓冲区约定）
5. 两端接完后删除：Android `executeTool` 的 8 个死分支、鸿蒙 `executeTool` 的 8 个 if、两端各自的 loop

## 五、踩过的坑（别再犯）

- **不要用 PowerShell `Add-Content` 改源码**：中文会按 GBK 写入，文件变非法 UTF-8。用 write/edit 工具。
- **不要按行号批量改 C++/大文件**：`napi_init.cpp` 曾因此结构损坏，只能 `git checkout`。
- **鸿蒙保障层不要用 `fieldValues` 回读**：真机上会误报「改动没有生效」；只信桥返回的 `editCount`。
- **ArkTS 严格模式**：对象字面量必须对应已声明 interface；`catch(err)` 里 `throw err` 要转成 `new Error(...)`。
- 鸿蒙 `parseInput` 会保留「空名但有连线」的输入（Reroute 依赖这一点），别改回去。

## 六、验证清单

```powershell
$env:JAVA_HOME='D:\Tools\Java\jdk-21.0.6'
.\gradlew.bat :shared:jvmTest :app:assembleDebug --console=plain
```

- 安卓模拟器：`D:\AndroidSDK\emulator\emulator.exe`，AVD `manjing`（需 `ANDROID_AVD_HOME=D:\AndroidAVD`）
- 真机服务端：`100.84.55.22:8188`（本机可达）；模拟器连不上 Tailscale 时用
  `node tools/comfy-proxy.mjs 8188 100.84.55.22 8188`（监听 127.0.0.1，模拟器用 `10.0.2.2:8188`）
- **测试时不要提交任务**（用户明确要求）；只读页面、开关节点、跑 AI 查询类工具。

## 七、未关闭项

- **BUG-04 辅助图组语义**：待用户定 A（整组开关，需新增 forcedBypassNodeIds）或 B（只改文案）。
- `docs/bug-report-2026-10-28.md`：只差 BUG-04，关闭后删除。
- Android `AiRuntime` 里 `TOOLS` 旧构造（buildJsonArray 那一大段）是死代码，可删。
