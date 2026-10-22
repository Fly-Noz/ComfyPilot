# 阶段 B · Android 任务标识、本地快照与输入排序

日期：2026-10-22

## 结论

| 层级 | 结果 |
|---|---|
| 构建级 | `:app:assembleDebug` 成功 |
| JVM 测试级 | `:shared:jvmTest` **184 通过 / 0 跳过 / 0 失败**；`:app:testDebugUnitTest` **7 通过 / 0 失败** |
| Android 真机级 | **未执行**：iQOO 7 未连接，`adb devices` 为空 |
| 真实 ComfyUI | **未执行**：`100.x.x.x:8188` 超时，Tailscale 节点 offline |

本文只声称构建级与 JVM 测试级结论；进程被杀后能否从文件恢复、真实提交与
错误展示，仍需真机验证。

## 1. 输入排序：未启用节点不再标“待填写”

**问题**：`WorkflowNodeView.needsInput` 只看字段是否缺值，没有看节点是否
`reachable`；详情页又按 `needsInput` 排序。多图工作流里，未启用/被旁路的
分支会顶到输入区最前面，并被标成“待填写”。

用真实工作流 + 真实 `/object_info` 复算：

| 工作流 | 待填写节点 | 其中不可达 |
|---|---:|---:|
| MiniMaxH3-加速视频流整合 | 38 | **38** |
| Qwen-2512-千问生图流整合 | 8 | **8** |
| Qwen-image21-图像编辑+生图流 | 13 | **13** |

**修正**：

- `WorkflowNodeView.needsInput` 改为 `reachable && fields.any { it.needsInput }`；
- 详情页排序键改为 `(!reachable, !needsInput)`：可达节点在前，可达且缺值的最前，
  未启用/旁路节点统一放到最后；
- 新增 `WorkflowFieldOrderTest` 用例：不可达节点即使字段缺值也不标“待填写”。

## 2. 任务带工作流归属

`PromptTask` 新增可选字段 `workflowPath`：

- 详情页提交时同时传 `document.name` 与 `document.path`；
- 草稿转正式任务后，名称与路径保留；
- 详情页“ComfyUI 报错”卡片只显示 `workflowPath == 当前 document.path` 的失败，
  旧快照没有该字段时不在详情页提示，避免把别的工作流的错误当成当前工作流的；
- 任务列表继续用 `displayName` 显示工作流名；没有名称的旧任务回落显示 promptId；
- 宿主桥协议 `submit` 增加可选的 `workflowName` / `workflowPath`，旧宿主不传仍可解析；
- 持久化快照写入 `workflowPath`，旧快照读取为 `null`，schema 向后兼容。

## 3. Android 本地任务快照

新增 `app/.../task/JsonFileTaskStore.kt`：

- 文件位置：`filesDir/tasks.json`；
- 写盘顺序：写 `tasks.json.tmp` → `fd.sync()` → rename 替换；rename 失败时退化为
  覆盖写，避免留下半个 JSON；
- 只保留最近 50 条，旧任务按 `updatedAtMs` 淘汰；
- 旧快照不能覆盖新状态（`updatedAtMs` 比较）；
- `remove` 会记录 tombstone，晚到的 `Persist(draft)` 不会把已删除草稿写回来；
- 文件损坏或 JSON 不合法时按“没有历史”启动，下一次保存会重建文件。

恢复时复用共享层 `restoreTasks`：

- `Submitting` 且没有 promptId 的任务标记为 `Unknown("应用重启，提交结果未知")`；
- `Queued` / `Running` 保留状态，等待下一次连接按 `/history`、`/queue` 对账；
- **不会自动重投**；对账不上的结果是 `Unknown`，不是重新提交。

## 4. 新增测试

- `WorkflowFieldOrderTest`：不可达节点不标待填写；
- `PersistedTaskTest`：`workflowPath` 往返，以及旧快照没有该字段时可读；
- `TaskLifecycleTest`：提交身份在草稿转正式任务后保留；
- `BridgeProtocolTest`：新 `submit` 字段解析与旧消息兼容；
- `JsonFileTaskStoreTest`：落盘/重载、旧快照不覆盖新状态、删除持久化、
  删除后晚到保存不能复活、坏文件降级、50 条截断。

## 未验证

- 真机上杀进程后重新打开，任务是否从 `filesDir/tasks.json` 恢复；
- 真机上提交失败/执行错误时，错误卡是否只显示当前工作流；
- 真实 ComfyUI 的提交、`/history` 对账与产物；
- 鸿蒙侧本轮未改动；其 `submit` 仍未发送工作流名/路径，后续 S6 对齐时补。

## 证据

```powershell
$env:JAVA_HOME="D:\Tools\Java\jdk-21.0.6"
.\gradlew.bat :shared:jvmTest --rerun-tasks
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

截图/真机证据待设备上线后补入 `artifacts/`（已 gitignore，不入库）。
