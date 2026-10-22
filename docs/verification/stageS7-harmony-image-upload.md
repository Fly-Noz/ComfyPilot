# 阶段 S7-02 · 鸿蒙 LoadImage 本机图片上传

日期：2026-10-22

## 结论

| 层级 | 结果 |
|---|---|
| 构建级 | `harmony/build.ps1` 成功；`entry-default-signed.hap` 约 8.1 MB（2026-10-22 18:55） |
| 鸿蒙真机级 | **通过**：系统相册选图、上传、字段回填、提交、服务端执行全部成功 |
| 真实 ComfyUI | **通过**：`/upload/image` 返回 200，`/history` 为 `success` |

## 已实现

- `LoadImage.image` 不再使用“输入服务端文件名 + 可选列表”的旧方式；
- 字段只显示当前服务器文件名和“从相册选择 / 重新选择图片”按钮；
- `PhotoViewPicker` 选择图片后读取媒体 URI 字节；
- 手写 multipart，POST `/upload/image`，字段名 `image`，类型 `input`；
- 服务端返回的 `name` / `subfolder` 写回 `LoadImage.image` override；
- 上传失败、取消、读取失败在字段旁显示文本；
- 上传上限 50 MB。

## 代码位置

- `harmony/.../workflow/ImageUploader.ets`：相册选择、读取、multipart、上传；
- `harmony/.../pages/Index.ets`：`ImageField` 渲染、上传状态、生命周期保护；
- `shared/.../api/ImageUpload.kt` / `ApiCall.kt`：Android 侧已有实现，保持同一
  `/upload/image` 契约。

## 真机证据

设备：HarmonyOS 手机 `33Z0224613077036`；主机：
`http://100.x.x.x:8188`（Tailscale 直连，无代理）。

选择图片：

```text
file://media/Photo/3403/IMG_1792391170_3163/screenshot_20261019_142430.png
size=174226
```

上传：

```text
POST http://100.x.x.x:8188/upload/image bytes=174523
HTTP 200 {"name":"screenshot_20261019_142430.png","subfolder":"","type":"input"}
```

提交 prompt `f5a58020-66a3-4225-806d-625cc2a225ef` 中：

```json
"475": {
  "class_type": "LoadImage",
  "inputs": { "image": "screenshot_20261019_142430.png" }
}
```

服务端 `/history`：

```text
status=success completed=true
487/images = QW21-%date:yyyyMMddhhmmss%_mvjfghdw0_00001_.png
```

## 生命周期修复

打开系统相册会让页面短暂 `aboutToDisappear`。原实现会在这个时机停止 bridge，
回来时重建会话，导致上传中断、节点图选择被清空。现在：

- `pickerActive` 为 true 时不停止 bridge；
- 相册返回时 `skipNextAppear` 跳过一次不必要的 bootstrap；
- 上传完成后保持当前工作流、图选择和连接状态。

## 未验证 / 限制

- 上传大于 50 MB 的图片（主动拒绝）；
- 上传字节级进度条（当前只有“上传中…”）；
- 相册取消、断网、HTTP 失败的真机分支；
- 多图输入（`image-2` 等辅助图组）仍是独立问题。
