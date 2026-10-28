# AI 循环迁移：交接与设计（2026-10-28）

> 这不是新功能文档，是给「下一段会话」用的上下文快照：目标、已做的事、踩过的坑、
> 下一步该改哪些文件。读完这一页就能继续，不需要翻旧对话。

## 一、目标

把 AI 助手的**工具分派与循环状态机**从 Android / 鸿蒙各一份，收进 `shared`。
现状：协议已统一（工具声明、无状态分派），**循环本身仍两份**。

## 二、已完成

| 项 | 位置 | 说明 |
|---|---|---|
| 工具声明唯一来源 | `shared/.../core/ai/AiToolSpec.kt` | 10 个工具的 JSON；两端都读这一份 |
| 无状态工具分派 | `shared/.../core/ai/AiToolRouter.kt` + `AiToolEnv` | 参数校验 + 白名单；宿主能力以 `AiToolOutcome` 返回 |
| 循环状态机 | `shared/.../core/ai/AiLoopState.kt` | 会话拼装、工具往返、轮数上限、暂停与恢复 |
| Android 接入 | `app/.../ai/AiRuntime.kt` | `runLoop` 只发请求/执行工具/回填；消息拼装在共享层 |
| 鸿蒙接入 | — | **未接**。原因见下 |

测试：`AiToolSpecTest`、`AiToolRouterTest`（8 项）、`AiLoopStateTest`（14 项）；
`:shared:jvmTest` 288 项通过；`:app:testDebugUnitTest` 22 项通过。

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

落地方式：`AiToolEnv` 的每个方法都返回 `AiToolOutcome`——`Value`（现在就拿到）、
`CallHost(method,args)`（宿主做）、`Fetch(url)`（宿主代发 GET）。措辞统一由
`AiToolRouter.formatResult(name, raw)` 收尾，鸿蒙不需要在 ArkTS 里抄文案。

## 四、下一步（按顺序）

1. ✅ `AiLoopState.kt`（已完成）
   - 状态：messages（发给模型的会话，不含 system）、round、pending、question、maxRounds
   - 返回 `AiLoopStep`：`Http(body)` / `CallTool(callId,name,args)`（即 ABI 的 `call_host`）/ `Done(reason,text)`
   - 方法：`start(userTurn)` / `nextRequest(ctx)` / `acceptReply(message)` /
     `acceptToolResult(callId,result,ctx)` / `markPending()` / `markQuestion(text)` /
     `resume(turn?)` / `replaceUserTurn(turn)` / `finish()`
   - `AiLoopContext(model, system, temperature)`：system 行由宿主每轮给（切工作流后 digest 要变）
   - 暂停语义同旧实现：一批 tool_calls 全部拿到结果后才检查暂停，否则下一轮请求非法
2. ✅ `AiToolEnv` 改为返回指令（已完成）
   - `AiToolOutcome.Value / CallHost / Fetch`；`execute()` 返回 `AiToolOutcome?`
   - **遗留**：`allowedPaths()` 仍是同步 `List<String>`（纯数据，鸿蒙可从泵帧给）；
     `submit` / `open_workflow` 已经过 `env.submit()` / `env.openWorkflow()` 返回 outcome，
     但 Android 版仍是同步调宿主。鸿蒙接 ABI 时这两个方法要改成 `CallHost`，
     结果由 `formatResult("submit", raw)` 统一措辞
3. ✅ Android 消费状态机（已完成，模拟器跑通）
   - `runLoop` 变成 `while (true) { when (step) { Http / CallTool / Done } }`
   - `callModel(digest, conversation)` → `postModel(body)`：请求体由状态机拼好
   - `contextFor(host)` 每步重算摘要（旧实现只在开头算一次，切工作流后会拿到旧值）
   - 顺带删掉 `TOOLS` 死代码（约 290 行）和 `executeTool` 的 8 个不可达分支
   - 修回一处回归：`submit` / `open_workflow` 走路由器后，操作记录不再进 `actions`，
     现在写在 `toolEnv` 的成功分支里
4. 鸿蒙加 `cp_ai_step` / `cp_ai_send`（ABI + napi + ArkTS 绑定，参照 `cp_ai_tools_json` 的缓冲区约定）
5. 两端接完后删除：鸿蒙 `executeTool` 的 8 个 if、鸿蒙的 loop

## 五、踩过的坑（别再犯）

- **不要用 PowerShell `Add-Content` 改源码**：中文会按 GBK 写入，文件变非法 UTF-8。用 write/edit 工具。
- **不要按行号批量改 C++/大文件**：`napi_init.cpp` 曾因此结构损坏，只能 `git checkout`。
- **鸿蒙保障层不要用 `fieldValues` 回读**：真机上会误报「改动没有生效」；只信桥返回的 `editCount`。
- **ArkTS 严格模式**：对象字面量必须对应已声明 interface；`catch(err)` 里 `throw err` 要转成 `new Error(...)`。
- 鸿蒙 `parseInput` 会保留「空名但有连线」的输入（Reroute 依赖这一点），别改回去。

## 六、验证清单

```powershell
$env:JAVA_HOME='D:\Tools\Java\jdk-21.0.6'
.\gradlew.bat :shared:jvmTest :app:testDebugUnitTest :app:assembleDebug --console=plain
```

- `:app:testDebugUnitTest` 若报 `ClassNotFoundException: ...vivo办公套件...`，是本机
  `CLASSPATH` 被污染，不是代码问题：`.\gradlew.bat --stop` 后清掉 `CLASSPATH` 再跑
  （daemon 会继承启动时的环境）。
- 没有真 Key 也能验循环：`node tools/mock-openai/server.mjs`，App 的接口地址填
  `http://10.0.2.2:8790/v1`、Key 随便填；回复里会带真实的 `list_graphs` 结果。
  验完把接口地址改回去、Key 清空。

- 安卓模拟器：`D:\AndroidSDK\emulator\emulator.exe`，AVD `manjing`（需 `ANDROID_AVD_HOME=D:\AndroidAVD`）
- 真机服务端：`100.84.55.22:8188`（本机可达）；模拟器连不上 Tailscale 时用
  `node tools/comfy-proxy.mjs 8188 100.84.55.22 8188`（监听 127.0.0.1，模拟器用 `10.0.2.2:8188`）
- **测试时不要提交任务**（用户明确要求）；只读页面、开关节点、跑 AI 查询类工具。

## 七、未关闭项

- **BUG-04 辅助图组语义**：待用户定 A（整组开关，需新增 forcedBypassNodeIds）或 B（只改文案）。
- `docs/bug-report-2026-10-28.md`：只差 BUG-04，关闭后删除。
- 鸿蒙 `AiAssistant.ets` 的 `executeTool` 与循环仍在，等第 4 步接 ABI 后再删。
- 鸿蒙接 ABI 时，`submit` / `open_workflow` 要从同步布尔改成 `CallHost`，
  结果由 `AiToolRouter.formatResult` 收尾（见第四节第 2 条）。
