# 02 · 鸿蒙补齐 LoadImage 本机图片上传

Status: resolved
Type: task

## 问题

安卓用系统 `GetContent` 选择图片，调用 ComfyUI `/upload/image`，再把返回的文件名写回 `LoadImage.image`。鸿蒙目前只有服务端已有图片名的文本输入，不能从手机相册选图。

## 范围

- 鸿蒙图片选择器（系统 PhotoPicker 或文件选择器）；
- 读取图片并上传到 `/upload/image`；
- 上传成功后用返回文件名更新 `LoadImage.image` override；
- 上传失败、超时、文件过大时在字段旁显示错误；
- 权限/系统版本不支持的降级路径。

## 验收

- 真机从相册选一张图，上传后字段显示服务器返回的文件名；
- 提交后 ComfyUI 的 prompt 使用新文件名；
- 取消选择、断网、HTTP 错误都不会留下半截状态；
- 不把本地路径或临时文件名直接提交给 ComfyUI。

## Comments

- 2026-10-22：已实现 `harmony/entry/src/main/ets/workflow/ImageUploader.ets`：
  `PhotoViewPicker` 选图，读取媒体 URI 字节，手写 multipart 调用 `/upload/image`。
- `LoadImage.image` 不再显示服务端文件名的 TextInput 和“可选”列表；字段只显示当前文件名与
  “从相册选择 / 重新选择图片”。
- 打开相册会短暂触发页面 `aboutToDisappear`；用 `pickerActive` / `skipNextAppear`
  避免误停会话和重置节点图选择。
- 真机验证：选择 `screenshot_20261019_142430.png`（174,226 字节），上传返回 200；
  prompt `f5a58020-66a3-4225-806d-625cc2a225ef` 中 `475.LoadImage.image` 为该文件名；
  ComfyUI `/history` 为 `success`，产物为
  `QW21-%date:yyyyMMddhhmmss%_mvjfghdw0_00001_.png`。
- 已知限制：上传最多 50 MB；未显示字节级进度；相册取消/断网/HTTP 错误只在字段旁显示文本。
