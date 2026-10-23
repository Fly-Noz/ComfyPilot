# 03 · 鸿蒙实时采样预览

Status: resolved
Type: task

## 问题

安卓详情页的“预览”页签消费 `SessionState.preview`（`PreviewFrame`），在任务执行时显示采样画面。鸿蒙当前只有任务进度和完成后的产物，没有实时预览。

## 范围

- 确认 ComfyUI WebSocket 预览帧在鸿蒙桥接层的传输方式，避免把二进制帧塞进 JSON 状态帧；
- ArkUI 侧的图片解码与显示；
- 任务完成、失败、取消、切后台时清理预览；
- 只对当前 prompt 显示预览，避免旧任务的帧串到新任务。

## 验收

- 真实 ComfyUI 跑一次带预览的采样，任务执行中能看到至少一帧；
- 完成后预览停止，产物仍可正常预览/下载；
- 断线、切后台、取消任务不会留下黑屏或旧帧；
- 没有预览帧的工作流不报错。

## Comments

- 2026-10-23：实现完成，等待鸿蒙真机验证。状态帧只带 `preview` 元数据；
  图片字节走按需 ABI `cp_session_preview`，base64 传给 ArkTS，再解码成 `PixelMap`。
  鸿蒙“任务”页顶部新增采样预览卡片；完成/失败/取消/断线/切后台都会清理。
- 验证：`:shared:jvmTest` 207 / 0 / 0；`:app:testDebugUnitTest` 13 / 0 / 0；
  `harmony/build.ps1` 成功。当前无鸿蒙设备连接，未安装。
- 记录：`docs/verification/stageS7-harmony-live-preview.md`。
- 2026-10-23：服务端已加 `--preview-method auto`，探针收到真实 type=1 JPEG 帧。
  发现 type=1 帧在图片前还有 4 字节图片格式头，旧解码器从 offset 4 取图导致
  `BitmapFactory` 失败。已修复并补 JPEG/PNG/旧 mock 测试；`:shared:jvmTest`
  212 / 0 / 0（4 skipped），`:app:testDebugUnitTest` 13 / 0 / 0，Android APK
  已重新安装。等待两端真机复测后关闭。
- 2026-10-23：真机复测反馈鸿蒙仍收不到采样画面，也没有进度条。根因是
  `ComfyBridge.openSocket()` 的 `socket.on('message')` 把二进制帧直接丢弃并打
  “忽略二进制 WebSocket 帧”；共享层根本没拿到 type=1/4 帧。
  现已改为把二进制帧 base64 成 `{"t":"ws.binary","id":...,"base64":"..."}` 回投
  共享层，`HostMessage.SocketBinary` 解码后交给 `WebSocketListener.onBinary()`。
  详情预览卡和任务页预览卡也补了 `Progress` 进度条与百分比。
  验证：`:shared:jvmTest` 213 / 0 / 0（4 skipped），`:app:testDebugUnitTest`
  13 / 0 / 0，`:app:assembleDebug` 与 `harmony/build.ps1` 均成功。
  最新 HAP（含 `ws.binary` 回投、进度条、`selectedPath` 提交身份）已安装到
  `33Z0224613077036` 并启动；待真机跑一次采样确认。
- 2026-10-23：真机验证通过。复查发现二进制帧和共享进度都已到达，界面仍停在
  `0% Submitting`，根因是 `LivePreviewCard(task)` 的 `@Builder` 对象参数不随
  任务快照刷新。已改为无参数 builder，每次直接读 `this.tasks`；用户真机确认
  进度条、百分比和采样图正常。HAP 构建时间 `18:03:18`。状态改为 `resolved`。
