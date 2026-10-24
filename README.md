# ComfyPilot

用 Kotlin Multiplatform 写核心、以原生 UI 分别实现 Android 与 HarmonyOS 的 ComfyUI 移动客户端。

连上 ComfyUI 之后可以浏览服务器上的工作流、调参数、按节点图启停、提交任务，并实时查看采样预览与产物（图片 / 视频 / 音频）。

## 功能

- **连接管理**：地址历史（本机保存最近 8 条）、自动重连、断线提示
- **工作流目录**：置顶、刷新、按目录浏览 `/user_data/workflows`
- **详情页**
  - 节点卡片按图分组，展开/收起，字段错峰入场
  - 每个节点可单独启用/关闭（旁路），关闭关键节点前会做构建试算并弹确认框
  - 字符串多行编辑、图片上传（`/upload/image`）、单节点备注
  - 性能监控条：内存 / 显存 / CPU / GPU / 温度 / 队列 / 版本，可收起
- **任务与产物**
  - 实时进度、取消任务、队列计数
  - 产物列表、下载保存、图片预览、视频首帧 + 内嵌播放
- **主题**：深色 / 浅色 / 跟随系统，光谱渐变背景与毛玻璃控件
- **双端一致**：Android 与 HarmonyOS 共用同一套共享逻辑与视觉规范

## 结构

```
shared/     Kotlin Multiplatform 核心：会话状态机、API、工作流解析与提示词构建
app/        Android 客户端（Jetpack Compose）
ohos/       Kotlin/Native 共享层编译工程，产出 HarmonyOS 用的 .so
harmony/    HarmonyOS 客户端（ArkTS + ArkUI），通过 C ABI 调共享层
docs/       设计资产、阶段性文档
tools/      本地开发工具（TCP 转发、mock ComfyUI）
```

## 构建

### Android

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### HarmonyOS

需要 DevEco Studio 的 SDK 与 hvigor：

```bash
# 1) 编译共享层 .so 并复制到 harmony/entry/libs/arm64-v8a/
./harmony/build.ps1

# 2) 打包 HAP
cd harmony
hvigorw assembleHap --mode module -p product=default -p buildMode=debug --no-daemon
```

首次在 DevEco Studio 打开 `harmony/` 时需配置签名（自动签名即可），构建脚本会把 `.so` 与头文件复制到对应目录。

### 本地联调

```bash
# 把本机端口转发到 ComfyUI，供设备通过 127.0.0.1 访问
node tools/comfy-proxy.mjs 18188 <comfyui-host> 8188
adb reverse tcp:18188 tcp:18188      # Android
hdc rport tcp:18188 tcp:18188        # HarmonyOS
```

`tools/mock-comfyui` 提供了一个可离线运行的假服务端，用于 UI 调试。

## 关于性能监控

内存与显存来自 ComfyUI 官方 `/system_stats`。CPU / GPU / 温度需要安装
[ComfyUI-Crystools](https://github.com/crystian/ComfyUI-Crystools)，它通过 WebSocket 推送
`crystools.monitor` 帧；未安装时客户端不会显示这几项，也不会伪造数值。

## 许可证

GPL-3.0，见 [LICENSE](LICENSE)。
