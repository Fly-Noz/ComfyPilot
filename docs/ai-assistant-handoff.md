# AI 助手交接文档

更新：2026-10-28 · 对应提交 `d3b8da4`

## 一、当前状态

AI 助手已可用：悬浮球 → 对话 → 查询工作流 → 改参数/开关节点/选图/切工作流 → 校验 → diff 确认或自动提交 → 操作记录。

两端构建通过，HAP/APK 已装机，但**尚未真机联调**。

已完成的提交：

```
d3b8da4 两端 ComfyPilotAI 系统日志
898def3 编辑按补丁合并，保留用户手动修改
25cadc5 操作记录（改参数/开关/图/工作流/提交）
dee81a0 停止按钮、host 解绑保护、Android 红点
21f82cd 连线来源 fromNode/fromSlot
431485b 节点→节点图归属、list_graphs nodeCount
6001540 鸿蒙摘要 digest + 查询工具
44f8b62 共享摘要 digest、AiQuery（Android）
1ed6728 重写系统提示词
383b1cb ask_user 问答卡、当前工作流按需注入
```

## 二、代码结构

共享层 `shared/src/commonMain/kotlin/com/comfypilot/core/ai/`

- `AiWorkflow.kt`：`AiField`（含 options/fromNode/fromSlot）、`AiNode`（含 graphId）、`AiEditState`、`AiDigest.summary()`、`AiQuery`（graphs/nodes/node）
- `AiEdit.kt`：op 解析、类型校验、整批应用
- `AiBridge.kt`：JSON 进出的 digest/apply，供鸿蒙 ABI 使用

Android `app/src/main/kotlin/com/comfypilot/app/`

- `ai/AiRuntime.kt`：配置（Key 走 AndroidKeyStore AES-GCM）、OpenAI 兼容请求、工具循环、停止、日志
- `ui/AiOverlay.kt`：悬浮球、聊天面板、工作区选择、问答卡
- `ui/SettingsScreen.kt`：设置分组；AI 独立页
- `ui/WorkflowDetailScreen.kt`：`AiWorkflowHost` 注册、digest 构建、applyEdit 合并

鸿蒙 `harmony/entry/src/main/ets/`

- `ai/AiAssistant.ets`：配置、请求、工具循环、停止、日志
- `ai/AiSecretStore.ets`：Asset Store 存 Key
- `pages/Index.ets`：悬浮球、AiPanel、AiScopePanel、设置页、AI/高级整屏页、host 实现
- 原生桥：`cp_ai_digest` / `cp_ai_apply`、`nativeAiDigest` / `nativeAiApply`

## 三、工具协议

`propose_edits` / `submit` / `open_workflow` / `ask_user` / `list_graphs` / `list_nodes` / `get_node`

op 支持 `set_input`、`toggle_node`、`select_graphs`、`open_workflow`。

系统提示词位置：

- Android：`AiRuntime.kt` 的 `SYSTEM_PROMPT`
- 鸿蒙：`AiAssistant.ets` 的 `SYSTEM_PROMPT`

## 四、未完成（按顺序）

1. **AI 日志进诊断页**
   当前只写 logcat/hilog（tag `ComfyPilotAI`）。需把同一批事件写入现有诊断缓冲区，让 App 内「诊断日志」可见。

2. **宿主 snapshot / restore**
   Android `WorkflowDetailScreen`、鸿蒙 `Index` 各加一份编辑状态快照与恢复方法。

3. **同键冲突提示**
   proposal 生成时记录涉及键的旧值；确认时若当前值已变，提示「你期间改过，覆盖/跳过」。

4. **状态过期检测**
   proposal 时记录 overrides/bypass/graphs 指纹，确认时比对。

5. **撤销本次 AI 修改**
   应用前存快照，聊天面板加「撤销这次修改」。

## 五、已知问题

- 工具循环在两端各写一份，协议已开始漂移（鸿蒙工具结果仍用文本回填，Android 用标准 `tool_call_id`）。
- 悬浮球位置只在本次会话记忆，重启回默认。
- 鸿蒙悬浮球没有待处理红点（Android 有）。
- 对话与操作记录不持久化，进程被杀即丢。
- 未做提交后验证循环（不等待任务结果、不自动重试）。
- 工作流关闭后受 host 解绑保护，但已生成的 pending 仍可被确认。

## 六、明天测试方法

构建：

```powershell
$env:JAVA_HOME='D:\Tools\Java\jdk-21.0.6'
.\gradlew.bat :app:assembleRelease --console=plain
cd harmony
$env:DEVECO_SDK_HOME='D:\Huawei\DevEco Studio\sdk'
$env:NODE_HOME='D:\Huawei\DevEco Studio\tools\node'
& 'D:\Huawei\DevEco Studio\tools\hvigor\bin\hvigorw.bat' assembleHap --mode module -p product=default -p buildMode=debug --no-daemon
```

安装：

```powershell
hdc install -r harmony\entry\build\default\outputs\default\entry-default-signed.hap
hdc file send app\build\outputs\apk\release\app-release.apk "/storage/media/100/local/files/Docs/Download/ComfyPilot-0.2.0.apk"
```

抓日志：

```powershell
hdc shell "hilog -x" | Select-String ComfyPilotAI
adb logcat -s ComfyPilotAI
```

测试路径：

1. 连接 → 打开多图工作流 → 问「有几张图，分别是什么」；
2. 选「仅当前」→ 让它改一个节点参数；
3. 选「全部」→ 让它切工作流；
4. 说「生成一张大肥鱼」→ 观察是否查询节点、提问、改提示词、提交；
5. 执行中点「停止」；
6. 关掉聊天框再打开，检查操作记录与待处理状态。

## 七、注意事项

- 日志不含 API Key，但会含用户消息与工具参数，测试勿输入隐私。
- `harmony/build-profile.json5`、`keystore.properties` 不入库。
- 鸿蒙 Key 存 Asset Store；Android 走 Keystore 加密。
- 发布前仍需：冲突提示、撤销、提交后验证循环。

## 八、2026-10-28 晚状态

第四节「未完成」的 1-5 已全部完成：

1. AI 日志进诊断页：Android 写 `DiagnosticsLog`，鸿蒙挂 `logSink`；
2. 宿主快照/恢复：Android 用 `AiEditState` 快照，鸿蒙用反向补丁；
3. 同键冲突提示：提案记旧值，确认时跳过用户手改过的字段并在聊天里列出；
4. 状态过期检测：工作流路径变化即作废提案；
5. 撤销本次修改：聊天框「撤销这次修改」。

另外补齐：`task_status` 提交后验证（含 `wait_seconds`）、多模态图片输入、按生成模型给提示词规范、`lookup_prompt_guide` / `fetch_doc`、权限开关、历史会话、对话落盘。

工具声明已统一到共享层 `AiToolSpec` + `cp_ai_tools_json`（鸿蒙 native 绑定 `nativeAiTools`），两端不再各写一份。

已知取舍：保障层只信桥返回的 `editCount`，不再用 `fieldValues` 回读（真机上会把成功误报成失败）。

剩余：工具循环状态机仍在两端各一份；下一步迁到 shared（需 `AiToolHost` 回调接口 + ABI）。

