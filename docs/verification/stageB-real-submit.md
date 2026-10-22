# 阶段 B · 真机真实提交闭环

日期：2026-10-22

## 结论

| 层级 | 结果 |
|---|---|
| 构建级 | `:app:assembleDebug` 成功 |
| JVM 测试级 | `:shared:jvmTest` **184 通过 / 0 跳过 / 0 失败**；`:app:testDebugUnitTest` **11 通过 / 0 失败** |
| Android 真机级 | **已执行**：iQOO 7（V2049A，Android 14）；提交、进度、任务列表、产物预览均已验证 |
| 真实 ComfyUI | **已执行**：Qwen-image21 图像编辑一次成功；`/history` 返回 `success` 与 3 个产物 |

本轮完成了一次真实、非模拟的 ComfyUI 提交闭环：

- 手机提交 prompt；
- 服务端排队并执行；
- 手机任务进度到 100%；
- `/history` 返回成功与产物；
- 手机任务列表与“查看结果”能显示产物图片。

## 1. 首轮失败：连线源节点 ID 类型错误

第一次真机提交返回：

```text
prompt_outputs_failed_validation
Prompt outputs failed validation
Exception when validating node: 480
#487 SaveImage：Exception when validating node / 480
#530 Image Comparer (rgthree)：Exception when validating node / 480
```

直接向 ComfyUI `/prompt` 重放同一份图，得到更具体的服务端异常：

```text
KeyError
execution.py, validate_inputs:
    o_class_type = prompt[o_id]['class_type']
```

**原因**：`PromptBuilder` 把连线写成数字节点 ID：

```json
"inputs": { "samples": [481, 0], "vae": [478, 0] }
```

ComfyUI 的 API 图要求连线源节点 ID 是字符串：

```json
"inputs": { "samples": ["481", 0], "vae": ["478", 0] }
```

数字 ID 在 `validate_inputs` 里用作 Python dict key 时触发 `KeyError`，表现为下游节点（#487/#530）校验失败。

**修复**：`PromptBuilder.kt` 输出 `JsonPrimitive(resolved.nodeId.toString())`；并在
`PromptBuilderTest` 增加断言，确认连线源节点 ID 的 JSON 类型是字符串，而不是只比较数值。

## 2. 第二轮提交

第一次失败还暴露出工作流引用了服务端不存在的输入图：

```text
LoadImage / image - Invalid image file: 测试图 (17).png
```

这不是转换器的问题；该文件不在服务端 `LoadImage` 的可选列表中。真机上把
`#475 Load Image` 的图像改为服务端已有的 `11 (6).jpg` 后重新提交。

结果：

- 队列：`running=1`；
- 手机进度：`执行中 0%` → `完成 100%`；
- 服务端 `/queue` 最终为空；
- `/history` 中对应 prompt 状态为 `success`，`completed=True`。

产物：

| 节点 | 产物 |
|---|---|
| `#487 SaveImage` | `QW21-%date:yyyyMMddhhmmss%_00001_.png` |
| `#530 Image Comparer` | `rgthree.compare._temp_xmojp_00001_.png` |
| `#530 Image Comparer` | `rgthree.compare._temp_xmojp_00002_.png` |

手机任务列表显示该工作流“完成 / 100%”，产物列表与上述文件一致；
“查看结果”能加载并显示三张图片。

`filename_prefix` 里的 `%date:yyyyMMddhhmmss%` 被原样保留，说明该占位符不是
ComfyUI 服务端 `SaveImage` 自动展开的；这是工作流/服务端行为，转换器按声明值原样提交，没有擅自改写。

## 3. 观察到的 UX 问题

详情页仍显示上一次同工作流失败的“ComfyUI 报错”卡片，即使之后已有新的成功任务。
它不影响本次成功任务的执行与产物，但可能让用户误以为当前仍失败。
该卡片可手动关闭；任务列表能明确看到最新成功记录。本轮没有改动这一行为。

## 4. 未验证

- 完成后的任务在杀进程重启后是否从本地快照恢复；
- 取消运行中任务、服务端执行报错、产物下载失败等分支；
- WebSocket 提示与 `/history` 对账的独立时序断言；
- 鸿蒙侧真实提交。

## 证据

```powershell
# 真机
adb shell am start -W -n com.comfypilot.app/.MainActivity `
  --es baseUrl "http://127.0.0.1:18188" --ez autoConnect true

# 测试与构建
$env:JAVA_HOME="D:\Tools\Java\jdk-21.0.6"
$env:PATH=(($env:PATH -split ';' | ? { $_ -and $_ -notmatch '"' -and $_ -notmatch 'iqoo 7' }) -join ';')
.\gradlew.bat :shared:jvmTest :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

真机截图：`artifacts/android-pin-18-real-submit.png`、
`artifacts/android-pin-19-task-complete.png`、`artifacts/android-pin-20-result.png`
（`artifacts/` 已 gitignore，不入库）。
