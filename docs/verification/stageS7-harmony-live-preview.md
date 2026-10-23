# 阶段 S7 · 鸿蒙实时采样预览

日期：2026-10-23

## 结论

| 层级 | 结果 |
|---|---|
| 共享层测试 | **213 / 0 / 0（另有 4 个既有 e2e 用例在 JVM 跳过）** |
| Android 单测 | **13 / 0 / 0** |
| Android 构建 | `:app:assembleDebug` 成功 |
| 鸿蒙构建 | `harmony/build.ps1` 成功，HAP 已生成 |
| 鸿蒙真机 | **通过**：`33Z0224613077036`，详情/任务页进度条、百分比和采样图正常 |
| 真实 ComfyUI 预览帧 | **已收到**：服务端已用 `--preview-method auto` 启动，探针和鸿蒙真机都收到 type=1 JPEG 帧（约 30–37KB/帧） |
| 两端真机画面 | **鸿蒙通过**；Android 采样预览此前已通过，详情页导航仍待单独回归 |

## 实现

### 状态帧只带元数据

`SessionState.toBridgeJson()` 新增轻量 `preview`：

```json
{"promptId":"...","nodeId":"...","receivedAtMs":123,"bytes":4567}
```

图片字节不进入 500ms 状态帧。只有元数据变化时，ArkTS 才去取图。

### 采样进度（不依赖预览开关）

新版 ComfyUI 0.37.4 只发 `progress_state`，不再发旧版 `progress`。共享层从
`data.nodes` 取 `state=running` 的节点，转成现有 `Progress` 事件；没有运行节点时
用已完成的采样节点兜底。任务运行中显示 `#节点 · 当前步/总步数`，即使服务端
没有开启 `--preview-method` 也能看到采样进度。

### 按需取预览帧

- ABI 版本升到 **4**；新增 `cp_session_preview(buffer, capacity)`：返回 base64 图片和元数据；
- C++ NAPI 导出 `nativeSessionPreview()`，缓冲区不足时扩容重取；
- ArkTS `ComfyBridge.preview()` 只在 `preview.receivedAtMs` 变化时调用；
- ArkTS 用 `util.Base64Helper` 解码，再用 `@kit.ImageKit` 的
  `createImageSource(...).createPixelMap()` 显示，旧 `PixelMap` 会释放。

### 页面显示

- 鸿蒙“任务”页顶部新增 `采样预览` 卡片；工作流详情内的独立预览页签已在
  `07-harmony-detail-preview` 补齐：
  - 有活动任务且尚无帧时显示“等待采样画面…”；
  - 收到帧后显示图片，并显示字节数；
  - 解码失败显示文字，不崩溃；
  - 没有预览帧的工作流只显示等待提示，不报错；
  - 运行中显示 `#节点 · 当前步/总步数`，服务端未开预览时仍能看到采样进度。
- 只有存在 `Submitting / Queued / Running` 任务时显示预览卡片。

### 清理时机

- 任务进入完成/失败/取消/未知：共享层清掉对应 `preview`；
- 用户取消任务：立即清掉该 prompt 的预览；
- 断线、停止、重连、切后台：共享层和 ArkTS 都清理；
- ArkTS 用请求序号丢弃迟到的旧帧，避免新任务显示旧画面。

## 构建

```powershell
$env:Path='D:\Tools\Java\jdk-21.0.6\bin;C:\Windows\system32;C:\Windows'
$env:CLASSPATH=''
.\gradlew.bat :shared:jvmTest :app:testDebugUnitTest :app:assembleDebug --no-daemon

powershell -NoProfile -ExecutionPolicy Bypass -File .\harmony\build.ps1
```

HAP：`harmony/entry/build/default/outputs/default/entry-default-signed.hap`

## 真实帧格式与解码修复

服务端加上 `--preview-method auto` 后，独立 WebSocket 探针在 Qwen 文生图
KSampler 阶段收到了真实二进制帧：

```text
00000001 00000001 ffd8ffe0 ...
```

- 前 4 字节：`type=1`；
- 接下来 4 字节：图片格式（`1=JPEG`、`2=PNG`）；
- 之后才是图片字节。

旧 `PreviewDecoder` 从 offset 4 取图，JPEG 前会多出 4 字节格式头，
`BitmapFactory` 解码失败，所以两端都看不到画面。现已改为：

- 有格式头时从 offset 8 取图，并校验 JPEG/PNG 魔数；
- 没有格式头的旧 mock 帧仍从 offset 4 取图；
- 只有格式头、后面没有有效图片的帧直接丢弃。

`PreviewDecoderTest` 增加了真实 JPEG 头、PNG 头和旧 mock 无头三类用例。

## 服务端前提（已满足）

当前真实 ComfyUI 的启动参数已包含：

```text
ComfyUI\main.py --windows-standalone-build --listen --preview-method auto
```

探针已确认能收到 type=1 JPEG 帧。标准 KSampler 工作流用 `auto` 或
`latent2rgb` 即可；某些自定义模型/采样器可能仍不产生预览，需要换
`latent2rgb` / `taesd` 再试。

## 二进制预览帧回投修复（2026-10-23 复测）

真机复测反馈：鸿蒙始终没有采样画面，也没有进度条。排查发现
`harmony/entry/src/main/ets/bridge/ComfyBridge.ets` 的 WebSocket `message`
回调只处理字符串帧，二进制帧被直接丢弃：

```ts
} else {
  this.onLog('忽略二进制 WebSocket 帧');
}
```

因此共享层从未收到 type=1/4 预览帧，`SessionState.preview` 一直是空的。

修复链路：

1. ArkTS 收到 `ArrayBuffer` 后用 `Base64Helper.encodeToStringSync()` 转成字符串；
2. 回投 `{"t":"ws.binary","id":...,"base64":"..."}`；
3. `HostMessage.parse()` 新增 `ws.binary`，用 `kotlin.io.encoding.Base64` 解码；
4. `SessionHost` 把字节交给 `WebSocketListener.onBinary()`，后续复用共享层
   `PreviewDecoder` 和按需 `cp_session_preview` 的既有路径；
5. 鸿蒙详情预览卡和任务页预览卡增加 `Progress` 进度条与百分比。

新增测试：`BridgeProtocolTest` 覆盖 `ws.binary` 的 base64 解析。

## 真机验证通过（2026-10-23）

第二次复测日志显示：

- `ws binary len=30675 base64=40900`：二进制帧已从 ArkTS 回投共享层；
- `Running:40`、`Running:50`…：共享层进度持续更新；
- `preview=abab...:30667`：预览元数据与当前任务匹配；
- 但界面仍显示 `0% Submitting`，说明剩余问题在 ArkUI 刷新。

根因是 `LivePreviewCard(task)` 的 `@Builder` 对象参数不会随任务快照刷新。
已把预览卡改成**无参数 builder**，每次直接读取 `this.tasks` 中的当前任务；
`DetailPreview` 和 `TasksTab` 共用同一张卡，按顶部页签自动选择
`activeWorkflowTask()` 或 `previewTask()`。

用户真机确认：提交后留在详情“预览”，进度条/百分比推进，采样图出现，
完成后产物入口正常。对应 issue 03/07 已标记 `resolved`。

## 请用户真机验证

1. Android 运行一个标准 KSampler 工作流，切到任务详情的“预览”页签，确认出现采样画面；
2. 同时确认进度显示为 `#节点 · 当前步/总步数`，且百分比在推进；
3. 任务完成后预览卡片消失，产物仍可正常预览/下载；
4. 运行一个没有预览帧的工作流，确认只显示等待提示，不报错；
5. 执行中取消任务、断开连接或切后台，再回来确认没有黑屏或旧帧；
6. 鸿蒙 HAP 已安装到 `33Z0224613077036`；分别检查“任务”页和“工作流详情 → 预览”：
   采样图、进度条和百分比都应出现；完成后能从详情直接打开产物。
