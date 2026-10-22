# 02 · 鸿蒙补齐 LoadImage 本机图片上传

Status: needs-triage
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
