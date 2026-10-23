# 阶段 S7 · 任务顺序与工作流置顶

日期：2026-10-23

## 结论

| 项 | 结果 |
|---|---|
| 鸿蒙任务列表最新在上 | 共享桥按 `createdAtMs` 倒序输出；共享测试通过 |
| Android 任务列表最新在上 | 真机确认：最新完成的任务在最上，失败任务居中，旧任务在下 |
| Android 工作流置顶 | 真机确认：置顶后移到最前并自动滚回顶部；取消置顶恢复；重启 App 后仍保留 |
| 鸿蒙工作流置顶 | 已实现并构建通过；HAP 尚未安装（当前 `hdc list targets` 为空） |
| 共享层测试 | **204 / 0 / 0** |
| Android 单测 | **13 / 0 / 0** |

## 实现

- `BridgeProtocol.toBridgeJson()`：任务从新到旧输出；Android 仍按自己的时间排序，鸿蒙直接按数组渲染。
- 新增两端本机 `PinnedWorkflowStore`：
  - Android：`SharedPreferences("pinned_workflows")`，保存路径集合；
  - 鸿蒙：`Preferences("pinned_workflows")`，路径按换行保存；
  - 列表排序：置顶工作流在前，同组按路径排序；
  - 置顶只影响本机列表，不写回服务器，不进入任务/工作流协议。
- Android 置顶后自动滚到列表顶部；鸿蒙使用单独的 `catalogScroller` 做同样处理。

## 测试

```powershell
$env:Path='D:\Tools\Java\jdk-21.0.6\bin;C:\Windows\system32;C:\Windows'
$env:CLASSPATH=''
.\gradlew.bat :shared:jvmTest :app:testDebugUnitTest :app:assembleDebug --no-daemon

powershell -NoProfile -ExecutionPolicy Bypass -File .\harmony\build.ps1
```

Android 真机 `1575924193008T5` 已安装新 APK。鸿蒙产物：
`harmony\entry\build\default\outputs\default\entry-default-signed.hap`。
