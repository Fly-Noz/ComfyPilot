# 第三方组件与许可

ComfyPilot 自身以 **GPL-3.0** 发布（见 [LICENSE](LICENSE)）。以下是随应用分发的第三方组件及其许可。

## 运行时依赖

| 组件 | 用途 | 许可证 |
|---|---|---|
| Kotlin 标准库 | 语言运行时 | Apache-2.0 |
| kotlinx.coroutines | 协程与调度 | Apache-2.0 |
| kotlinx.serialization | JSON 编解码 | Apache-2.0 |
| AndroidX（core-ktx、activity-compose、lifecycle） | Android 基础库 | Apache-2.0 |
| Jetpack Compose（BOM、ui、foundation、material3） | Android UI | Apache-2.0 |
| OkHttp / Okio | Android 的 HTTP 与 WebSocket | Apache-2.0 |

这些组件以库的形式被链接进 APK，其版权归各自作者所有，许可全文见：

- Apache-2.0：<https://www.apache.org/licenses/LICENSE-2.0>

## 构建期工具（不随应用分发）

| 组件 | 用途 | 许可证 |
|---|---|---|
| Gradle / Gradle Wrapper | 构建 | Apache-2.0 |
| Kotlin/Native | 编译鸿蒙共享库 | Apache-2.0 |
| DevEco Studio / HarmonyOS SDK | 编译 HAP | 华为开发者协议（仅构建期使用，不随仓库分发） |

## 开发工具（不进入发布产物）

| 组件 | 用途 | 许可证 |
|---|---|---|
| Node.js `ws`（tools/mock-comfyui） | 本地 mock WebSocket 服务 | MIT |

MIT 许可全文见 <https://opensource.org/license/mit>。

## 说明

- 本项目**不包含** ComfyUI 的代码，也不分发任何模型文件；运行时只通过公开的 HTTP / WebSocket 协议连接用户自建的 ComfyUI。
- ComfyUI、VideoHelperSuite、ComfyUI-Crystools 等名称归各自作者所有，本项目与它们没有隶属关系。
