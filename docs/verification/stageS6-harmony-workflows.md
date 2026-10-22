# 阶段 S6 · 鸿蒙工作流、置顶与产物对齐

日期：2026-10-22

## 结论

| 层级 | 结果 |
|---|---|
| 构建级 | `harmony/build.ps1` 成功；生成 `entry-default-signed.hap`（约 8.1 MB，2026-10-22 18:14） |
| JVM 测试级 | `:shared:jvmTest` **195 通过 / 0 跳过 / 0 失败**；`:app:testDebugUnitTest` **11 通过 / 0 失败** |
| 鸿蒙真机级 | **已执行**：用户真机连接真实 ComfyUI，完成提交、进度、完成和图片预览 |
| Android 真机级 | **已执行回归**：iQOO 7（V2049A）通过本机代理连接真实 ComfyUI，验证提交置灰与阻断原因 |
| 真实 ComfyUI | 鸿蒙通过 Tailscale 直连 `http://100.x.x.x:8188`；Android 通过 `adb reverse` + 本机 TCP 代理访问同一主机 |

结论边界：工作流提交、任务进度、图片预览和产物文件名唯一性已在真机验证；
系统下载目录、视频/音频播放、本机相册上传和多图编辑仍属于后续范围。
旧任务中已经被覆盖的产物无法恢复，只能保证新提交的产物不再互相覆盖。

## 1. 已实现

### 工作流目录与详情

- 连接成功后自动请求 `workflows`，也可手动刷新；
- 点开工作流后调用 ABI `cp_session_workflow`（`op=view`），一次性拿到：
  - 工作流格式、路径、名称；
  - 节点图及默认启用状态；
  - 按输入区规则排序的节点与字段；
  - 阻断问题、缺失节点类、等待中的节点定义；
  - 是否可提交；
- 工作流原文、节点定义和字段模型不进入 500ms 状态帧，避免大工作流拖慢宿主循环；
- 节点图启停会重新生成视图并滚回顶部；
- 提交前用 `op=prompt` 让共享层重新校验 overrides、节点图和缺失输入；
  只有返回 `ok=true` 且 `prompt` 非空时才发送 `submit`。

### 节点置顶

- ArkTS `PinnedNodeStore` 使用 Preferences，key 与 Android 一致：
  `workflow:<工作流路径>`；
- 只保存节点 ID，排序完全由共享层完成；
- 仅在节点 `reachable` 时提前，未启用/旁路图的置顶只保留状态；
- 置顶/取消置顶后滚回顶部；
- 置顶不写入工作流 JSON、任务快照或提交给 ComfyUI 的 prompt。

### 提交状态

- 提交按钮由共享层 `canSubmit` 驱动；不可提交时按钮置灰，并在按钮上方显示阻断原因；
- `submitWorkflow()` 只显示“正在提交，等待服务器接受…”，不会提前显示绿色“已提交”；
- 只有 ComfyUI 返回 `prompt_id` 后，`SubmitAccepted` 才变成绿色“已提交”；
- 400/校验失败仍显示明确的失败原因；
- 警告不再静默提交：先弹“提交前确认”，用户点“仍然提交”后才发送。

### 产物与文件名唯一性

- 状态帧中的每个任务新增 `outputRefs`，字段包括文件名、显示名、MIME、
  节点、类型、是否图片/视频/音频和相对 `/view` 路径；
- 图片使用 ArkUI `Image` 预览；
- 视频、音频和其他类型至少下载；
- 下载流程：
  1. 申请 `ohos.permission.READ_WRITE_DOWNLOAD_DIRECTORY`；
  2. 系统 `request.downloadFile` 下载到 app cache；
  3. 复制到 `Environment.getUserDownloadDir()`；
  4. 同名文件按 `name (1).ext` 避让；
  5. 清理临时文件。
- 文件名/MIME/媒体判断由共享层 `ArtifactNames` 提供，鸿蒙侧不重写规则。

### 真实工作流文件名覆盖修复

真机 `/history` 显示，多个成功任务都写到同一个文件：

```text
QW21-%date:yyyyMMddhhmmss%_00001_.png
```

原因是 ComfyUI `SaveImage` 只展开 `%year% / %month% / %day% / %hour% / %minute% / %second%`
等占位符，不识别 `%date:...%`；Windows 又会把冒号替换掉，导致计数匹配失败，
每次都被当成 `_00001_` 并覆盖上一张。

修复方式：每次提交生成一个唯一短 token，共享层把它追加到保存类节点的
`filename_prefix`。鸿蒙在 `op=prompt` 里带 `run` 字段；安卓在
`WorkflowDetailScreen.submit()` 里直接传 `runToken`。旧产物无法恢复，
但新提交的每次产物都会落到不同文件。

### 跨端收尾

- 安卓提交按钮改为和鸿蒙一样：不可提交时置灰，并在按钮上方显示原因；
- 鸿蒙补上警告提交前的“仍然提交”确认，与安卓一致；
- 两端都保留警告不阻止提交的语义；
- 详细的后端/UI 差异和后续项见 `.scratch/s7-parity/`。

## 2. 代码位置

- `shared/.../host/WorkflowUi.kt`：按需视图、prompt、排序、节点图选择；
- `shared/.../host/BridgeProtocol.kt`：`outputRefs` 契约和 `SubmitAccepted` 通知；
- `shared/.../workflow/PromptBuilder.kt`：唯一文件名后缀、prompt 转换；
- `shared/.../ohos/SessionAbi.kt`：`cp_session_workflow`，ABI 版本 3；
- `harmony/.../cpp/napi_init.cpp`：`nativeSessionWorkflow`；
- `harmony/.../bridge/PinnedNodeStore.ets`：Preferences 置顶；
- `harmony/.../artifact/ArtifactDownloader.ets`：下载与下载目录；
- `harmony/.../pages/Index.ets`：工作流/任务/产物页面、提交确认；
- `app/.../ui/WorkflowDetailScreen.kt`：安卓实时 `canSubmit`、阻断原因、唯一 token；
- `shared/.../commonTest/.../host/WorkflowUiTest.kt`：置顶排序、图选择、prompt、
  等待节点定义的回归测试；
- `shared/.../commonTest/.../workflow/PromptBuilderTest.kt`：唯一文件名后缀测试。

## 3. 真机验证记录

环境：

- 设备：HarmonyOS 手机，序列号 `33Z0224613077036`；
- 主机：ComfyUI `http://100.x.x.x:8188`，通过手机 Tailscale 直连；
- 不使用 `hdc rport`，不使用代理。

已验证：

1. 文生图工作流 `Qwen-image21-生图`：
   - 只启用生图图，未出现 `#514 EmptyLatentImage` 的 width/height 误 override；
   - POST `/prompt` 返回 200，prompt_id 为 `48426a21-8c9e-450d-84b3-12d24ccde0eb`；
   - 服务端接受后界面才出现绿色“已提交”。
2. 图像编辑工作流：
   - prompt_id 为 `898c798e-ee35-4b23-943e-e7d1c28d1761`；
   - 进度从 `Running 3%` 走到 `100%`；
   - 服务端 `/history` 为 `success`，任务显示 `Completed`；
   - 图片产物可在 App 内预览。
3. 产物唯一性：
   - 修复前多个成功任务都写同一个 `QW21-%date:yyyyMMddhhmmss%_00001_.png`；
   - 修复后用户真机确认新任务产物不再互相覆盖；
   - 旧产物已被覆盖，无法恢复。
4. 跨端提交规则（2026-10-22 18:30 回归）：
   - 鸿蒙：关闭两个节点图后，页面显示“不可提交：没有启用任何节点图，不能提交”，
     提交按钮变为不可用；重新启用后恢复。
   - 鸿蒙：Qwen 工作流存在“已忽略 1 个未连接到输出节点的节点”警告时，
     点“提交工作流”会先出现“提交前确认”弹窗，列出警告，提供“取消 / 仍然提交”；
     点“取消”回到详情页，没有提交任务。
   - 安卓：关闭节点图后，底部显示“不可提交：没有启用任何节点图，不能提交”，
     “0 个会提交”，提交按钮置灰；重新启用后恢复。
   - Android 连接方式：`adb reverse tcp:18188 tcp:18188`；
     本机 `tools/comfy-proxy.mjs` 监听 `127.0.0.1:18188` 并转发到
     `100.x.x.x:8188`；App 使用 `http://127.0.0.1:18188`。
     鸿蒙不需要代理，直接使用 `http://100.x.x.x:8188`。

## 4. 构建产物

```powershell
cd harmony
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

产物：`harmony\entry\build\default\outputs\default\entry-default-signed.hap`。

ArkTS 编译只剩已知告警：`getUserDownloadDir` / `request` 不是所有设备都支持；
代码已捕获异常并在界面显示失败原因，不会让页面崩溃。

## 5. 明确未验证 / 后续

- 系统下载目录和同名文件避让的真机结果；
- 本机相册上传 `LoadImage`（鸿蒙未实现，见 S7 `02-harmony-image-upload`）；
- 实时采样预览（鸿蒙未实现，见 S7 `03-harmony-live-preview`）；
- 视频/音频内置播放（鸿蒙当前只下载，见 S7 `04-harmony-media-playback`）；
- 多图编辑：`#505 LoadImage`（image-2）仍是旁路状态，提示词引用“图二”不会生效；
- 长任务后台保活；App 被杀或长时间后台后，任务状态可能依赖重新连接后的对账；
- 旧任务产物已经被覆盖，无法恢复。
