# 阶段 B · 产物预览与下载到系统下载目录

日期：2026-10-22

## 结论

| 层级 | 结果 |
|---|---|
| 构建级 | `:app:assembleDebug` 成功 |
| JVM 测试级 | `:shared:jvmTest` **189 通过 / 0 跳过 / 0 失败**；`:app:testDebugUnitTest` **11 通过 / 0 失败**；合计 **200 / 0 / 0** |
| Android 模拟服务级 | **已执行**：iQOO 7（V2049A，Android 14）；通过 `adb reverse` 连接本机临时 mock，任务列表、全屏图片预览、视频预览、图片/视频下载均验证 |
| 真实 ComfyUI | **未验证**：没有用真实主机做提交或 `/view` 产物验证；配置 mock 时只读探测过一次 `/system_stats`，没有写入服务端 |

本轮把产物从“任务卡片里显示文件名”推进到可用状态：

- 任务列表和详情页共用同一个全屏产物对话框；
- 图片在应用内加载缩略图，并可打开全屏预览；
- 视频使用系统 `VideoView` 预览，支持播放/暂停/进度拖动，播放失败自动提示下载；
- 图片、视频、音频及其他文件都可以流式下载；
- 下载写入系统公共下载目录，同名文件自动生成 `name (1).ext`。

## 1. 实现位置

| 文件 | 作用 |
|---|---|
| `shared/.../core/api/ArtifactNames.kt` | 文件名清洗、MIME 映射、图片/视频/音频判断、同名避让；纯 Kotlin，可被 Android 与 HarmonyOS 复用 |
| `app/.../artifact/ArtifactDownloader.kt` | OkHttp 流式下载；Android 10+ 写 `MediaStore.Downloads`，Android 9 及以下写公共 Downloads |
| `app/.../ui/ArtifactDialog.kt` | 全屏产物列表、图片全屏预览、视频预览、下载状态与旧系统权限请求 |
| `app/.../ui/TaskList.kt` | 任务卡片“查看产物”入口 |
| `app/.../ui/WorkflowDetailScreen.kt` | 详情页预览区“查看产物”入口 |

下载使用 64 KB 缓冲区边读边写，不把视频等大文件整体读进内存。Android 10+ 的
MediaStore 路径用 `IS_PENDING` 标记写入中的文件，失败时删除半成品；旧系统路径在写完后
调用 `MediaScannerConnection.scanFile` 让下载文件出现在系统文件列表中。

视频预览使用 Android 框架自带的 `VideoView` + `MediaController`，没有新增播放器依赖。
进入预览后自动播放，带系统播放/暂停与进度条；关闭对话框时停止并释放，应用进入后台时
暂停。视频解码能力取决于设备，失败时保留下载入口。

## 2. 本机 mock 验证

本轮只验证 UI、图片/视频预览与下载链路，不验证 ComfyUI 服务端行为。做法：

1. 在电脑上启动临时 HTTP/WebSocket mock，端口 `18190`；
2. 执行 `adb reverse tcp:18190 tcp:18190`，让手机通过 loopback 访问 mock；
3. 通过 `adb` 指定调试地址并自动连接；
4. 写入一条临时完成态任务，产物为一张 960×640 PNG 和一个真实的 5 秒
   H.264/AAC MP4（960×540，约 1.1 MB）；mock 的 `/view` 对视频返回真实文件并支持
   HTTP Range。

验证结果：

- 任务卡片出现“查看产物”，打开后 PNG 与视频两行产物都显示；
- PNG 缩略图加载成功，点开后是全屏图片预览，顶部标题与“返回/下载”可读；
- 视频行显示“视频 · 点击预览”，点击后自动播放；
- `MediaController` 显示播放/暂停、进度和 `00:02 / 00:05`，说明解码与拖动控件工作；
- 把视频输出改成 mock 返回 404 的坏文件后，播放器不会永远转圈：20 秒兜底超时后
  显示“视频预览失败，可下载后用系统应用打开”，下载按钮仍在；
- 点击下载后文件出现在 `/sdcard/Download`：
  - `ComfyPilot-offline.png`，6175 字节，Pillow 确认为 960×640 PNG；
  - `ComfyPilot-video.mp4`，1110702 字节，SHA-256 与 mock 源文件一致；
  - 之前用同名 temp 产物验证过 `name (1).ext` 避让，仍保留该结论；
- 重新安装 APK 后功能仍在，下载状态在对话框内按产物即时更新。

截图：

- `artifacts/stageB-artifact-list.png`
- `artifacts/stageB-artifact-preview.png`
- `artifacts/stageB-artifact-video-list.png`
- `artifacts/stageB-artifact-video-preview.png`
- `artifacts/stageB-artifact-video-error.png`

（`artifacts/` 已 gitignore，不入库。）

## 3. 测试与构建

```powershell
$env:JAVA_HOME="D:\Tools\Java\jdk-21.0.6"
$env:PATH=(($env:PATH -split ';' | ? { $_ -and $_ -notmatch '"' -and $_ -notmatch 'iqoo 7' }) -join ';')
.\gradlew.bat :shared:jvmTest :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

结果：`BUILD SUCCESSFUL`；XML 测试报告合计 200 个测试，0 失败、0 错误、0 跳过。
其中共享层新增的 `ArtifactNamesTest` 覆盖文件名清洗、大小写 MIME、媒体判断和同名避让。

## 4. 明确未验证

以下路径本轮没有执行，不能据此认为已经覆盖：

- 真实 ComfyUI 主机的 `/view` URL、鉴权/反代、真实大图与真实视频；
- 真实工作流产出的 MOV/MKV/AVI、ProRes 等编码在 `VideoView` 上的兼容性；
- 视频播放到后台暂停/恢复的实际效果（代码已处理，但本轮没有听感验证）；
- Android 9 及以下的运行时存储权限分支和公共 Downloads 写入；
- 下载请求的 HTTP 非 2xx、网络中断、磁盘写满、写入中被杀进程等失败与恢复；
- 应用旋转、进程重启后对话框选中状态、视频进度与下载状态的恢复；
- 详情页“查看产物”入口的 UI 点击路径（仅通过编译，未在设备上点开）；
- 下载取消与 MediaStore 中 `IS_PENDING=1` 的残留清理；
- 鸿蒙侧的产物预览、下载与置顶节点对齐（仍在 S6）。

## 5. 测试数据说明

离线验证在真机上写入了临时 `files/tasks.json` 和下载文件；验证结束后已删除临时任务、
下载文件和 `adb reverse`，mock 进程也已停止。写临时任务时覆盖了验证机上原有的本地任务
快照，因此验证机任务列表不再保留之前的 Qwen 提交记录；服务端历史不受影响。
