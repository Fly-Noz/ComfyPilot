# 阶段 B · Android 节点置顶

日期：2026-10-22

## 结论

| 层级 | 结果 |
|---|---|
| 构建级 | `:app:assembleDebug` 成功 |
| JVM 测试级 | `:shared:jvmTest` **184 通过 / 0 跳过 / 0 失败**；`:app:testDebugUnitTest` **11 通过 / 0 失败** |
| Android 真机级 | **已执行**：iQOO 7（V2049A，Android 14，序列号 `1575924193008T5`）；验证了置顶、展开、排序、切图、取消置顶、进程重启恢复 |
| 真实 ComfyUI | **已连接**：经 `127.0.0.1:18188 → 100.x.x.x:8188` 读取 `/system_stats`、`/object_info` 与 21 个工作流；**未提交生成任务** |

结论边界：

- 本轮真机证据覆盖“连接、工作流目录、打开 Qwen 工作流、节点置顶排序、节点图启停、取消置顶、杀进程重启恢复”；
- 尚未在真机提交一次真实 prompt，因此不声称产物返回、`/history` 与 WebSocket 闭环已验证；
- 鸿蒙侧仍未实现置顶，留待 S6 对齐。

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
- 取消置顶一个可达节点时，同样滚回列表顶部，避免视口停留在该节点的新位置；
- 切换启用的节点图时，输入区滚回顶部，避免 LazyList 跟随旧顶部节点落到列表末尾；
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
  - `LaunchedEffect(selectedGraphIds)`：节点图启停变化后回到输入区顶部；
  - `togglePin`：置顶/取消置顶后，可达节点都会滚回顶部；
- `PinnedNodeStore.kt`：本地读写、ID 编解码、纯排序函数 `orderInputNodes`；
- `InputOrderTest.kt`：置顶可达节点在最前、置顶但图未启用的节点仍在最后；
- `PinnedNodeStoreTest.kt`：ID 往返、空值、非法项容错。

## 4. 真机验证

### 环境

- 设备：iQOO 7 `V2049A`，Android 14，序列号 `1575924193008T5`；
- 代理：`node tools\comfy-proxy.mjs`，监听 `127.0.0.1:18188`，转发到 `100.x.x.x:8188`；
- 端口：`adb reverse tcp:18188 tcp:18188`；
- 服务端：ComfyUI 0.37.4，RTX 4060；工作流目录显示 21 个真实工作流。

启动命令：

```powershell
adb shell am start -W -n com.comfypilot.app/.MainActivity `
  --es baseUrl "http://127.0.0.1:18188" --ez autoConnect true
```

### 验证步骤与结果

1. 打开 `▶▷Qwen-image21-图像编辑+生图流（整合）`。
   - 初始仅启用“图像编辑”图，底部显示 `17 个会提交`；
   - `#13 Resolution Selector` 在顶部，`#475 Load Image` 在它之后。
2. 点击 `#475` 的“置顶”。
   - `#475` 立即移到输入区顶部，自动展开并显示“取消置顶”；
   - 截图：`artifacts/android-pin-04-pinned.png`。
3. 读取应用私有偏好。
   - 文件：`shared_prefs/pinned_nodes.xml`；
   - key：`workflow:▶▷Qwen-image21-图像编辑+生图流（整合）.json`；
   - value：`475`。
4. 节点图切换。
   - 点“生图”后两图同时启用，底部为 `28 个会提交`；
   - 再点“图像编辑”只保留“生图”，底部为 `11 个会提交`；
   - `#475` 只属于“图像编辑”图；仅启用“生图”时它不可达，不应被置顶排序提前。
5. 首轮切图发现真实 UI 问题：
   - 切到仅“生图”后，LazyList 跟随原来的顶部 `#475` 滚到列表末尾，视觉上仍像 `#475` 被置顶；实际排序已经把它放到不可达组。
   - 该现象截图：`artifacts/android-pin-06-edit-disabled.png`（修复前）。
6. 加入切图回到顶部的修复后重测。
   - 仅“生图”时顶部变为 `#522 Text Encode Qwen Image 2.1`，`#475` 不再优先；
   - 截图：`artifacts/android-pin-09-graph2-fixed.png`。
7. 重新启用“图像编辑”图。
   - 两图同时启用，`28 个会提交`；
   - `#475` 回到顶部，说明置顶状态未被切图清掉；
   - 截图：`artifacts/android-pin-10-pin-restored.png`。
8. 取消置顶。
   - 首轮发现取消置顶后视口仍跟随 `#475`，看起来仍像置顶；截图：`artifacts/android-pin-12-unpinned.png`（修复前）。
   - 修复 `togglePin` 后，取消置顶会滚回顶部，顶部恢复为 `#13 Resolution Selector`，`#475` 回到普通位置；
   - 截图：`artifacts/android-pin-14-unpin-top.png`。
9. 重新置顶 `#475`，然后强制停止并重启应用。
   - `adb shell am force-stop com.comfypilot.app`；
   - 重新启动并打开同一个 Qwen 工作流；
   - `#475` 仍在顶部，按钮为“取消置顶”，`pinned_nodes.xml` 仍保存 `475`；
   - 最终截图：`artifacts/android-pin-15-restart-final.png`。

## 5. 发现并修复的问题

### 5.1 切图后列表跟随旧置顶节点

- 现象：仅启用“生图”后，可达节点已经重新排序，但列表视口仍停在 `#475` 的新位置，用户会误以为置顶仍然生效。
- 原因：LazyListState 在数据重排后继续保留原可见项的位置。
- 修复：在 `WorkflowDetailScreen.kt` 增加
  `LaunchedEffect(selectedGraphIds) { listState.scrollToItem(0) }`，切图后回到输入区顶部。
- 复测：顶部为 `#522`，`#475` 不再出现在顶部。

### 5.2 取消置顶后列表跟随该节点

- 现象：取消 `#475` 后它仍在视口顶部，实际排序已经恢复。
- 原因：`togglePin` 只在“新置顶”时滚动，取消置顶没有复位。
- 修复：对可达节点，置顶和取消置顶都执行 `animateScrollToItem(0)`；仅在新置顶时额外展开节点。
- 复测：取消置顶后顶部为 `#13`，`#475` 回到普通位置。

## 6. 未验证

- 真机提交真实 prompt 后的 `/history`、WebSocket 提示、产物展示与失败分支；
- 大工作流下置顶与输入校验同时发生时的滚动稳定性；
- 鸿蒙侧置顶与本地持久化；
- 真实 ComfyUI 的完整生成闭环。

## 证据

JVM 测试与构建：

```powershell
$env:JAVA_HOME="D:\Tools\Java\jdk-21.0.6"
$env:PATH=(($env:PATH -split ';' | ? { $_ -and $_ -notmatch '"' -and $_ -notmatch 'iqoo 7' }) -join ';')
.\gradlew.bat :shared:jvmTest --rerun-tasks --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleDebug --no-daemon
```

真机偏好读取：

```powershell
adb shell run-as com.comfypilot.app cat shared_prefs/pinned_nodes.xml
```

截图与 UI dump 位于 `artifacts/`，该目录已 gitignore，不入库。
