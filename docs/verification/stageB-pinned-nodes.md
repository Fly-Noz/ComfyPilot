# 阶段 B · Android 节点置顶

日期：2026-10-22

## 结论

| 层级 | 结果 |
|---|---|
| 构建级 | `:app:assembleDebug` 成功 |
| JVM 测试级 | `:shared:jvmTest` **184 通过 / 0 跳过 / 0 失败**；`:app:testDebugUnitTest` **11 通过 / 0 失败** |
| Android 真机级 | **未执行**：iQOO 7 未连接，`adb devices` 为空 |
| 真实 ComfyUI | **未执行**：`100.x.x.x:8188` 超时，Tailscale 节点 offline |

本文只声称构建级与 JVM 测试级结论；置顶按钮的点击、排序、展开、切图与
进程重启后的恢复仍需真机确认。

## 1. 行为

- 输入区每个节点卡片标题行新增“置顶 / 取消置顶”按钮；
- 置顶仅影响当前工作流的输入区，**按工作流路径隔离**保存到本机；
- 排序优先级：
  1. 置顶且 `reachable`（会参与本次提交）的节点；
  2. 其余 `reachable` 且缺值的节点；
  3. 其余 `reachable` 节点；
  4. 未启用/旁路的节点；
- 节点所在节点图未启用时，置顶状态仍保留，但**不会**被提前到前面；
  重新启用该图后，下一次输入区排序会把它放回顶部；
- 新置顶一个可达节点时，输入区会展开该节点并滚到列表顶部；
- 置顶的节点如果可达，打开详情页时默认展开。

置顶是本机 UI 偏好，不进入工作流 JSON、任务快照或提交给 ComfyUI 的 prompt。

## 2. 本地存储

新增 `app/.../ui/PinnedNodeStore.kt`：

- 使用应用私有 `SharedPreferences`（文件 `pinned_nodes`）；
- key 为 `workflow:<工作流路径>`，隔离不同工作流的置顶；
- value 为排序后的节点 ID，例如 `7,19,42`；
- 空集合删除 key，避免留下空值；
- 解码时忽略空项、非数字项与重复项；坏数据退化为“没有置顶”，不会阻止输入区显示。

当前没有把置顶数据迁移到鸿蒙；S6 对齐时由 ArkTS 侧按同样语义持久化。

## 3. 代码位置

- `WorkflowDetailScreen.kt`：按钮、状态、展开、滚动与排序调用；
- `PinnedNodeStore.kt`：本地读写、ID 编解码、纯排序函数 `orderInputNodes`；
- `InputOrderTest.kt`：置顶可达节点在最前、置顶但图未启用的节点仍在最后；
- `PinnedNodeStoreTest.kt`：ID 往返、空值、非法项容错。

## 4. 未验证

- 真机点击“置顶”后是否立即移到顶部并展开；
- 多图工作流中禁用/重新启用置顶节点所在图后的排序变化；
- 杀进程后重新打开，置顶是否从 `SharedPreferences` 恢复；
- 置顶按钮与卡片展开点击是否互相误触；
- 鸿蒙侧尚未实现置顶；
- 真实 ComfyUI 的提交与产物链路未执行。

## 证据

```powershell
$env:JAVA_HOME="D:\Tools\Java\jdk-21.0.6"
$env:PATH=(($env:PATH -split ';' | ? { $_ -and $_ -notmatch '"' -and $_ -notmatch 'iqoo 7' }) -join ';')
.\gradlew.bat :shared:jvmTest --rerun-tasks --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleDebug --no-daemon
```

截图/真机证据待设备上线后补入 `artifacts/`（已 gitignore，不入库）。
