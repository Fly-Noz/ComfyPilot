# 阶段 S7 · 辅助图组启停与多图输入

日期：2026-10-23

## 结论

| 项 | 结果 |
|---|---|
| 辅助图组识别 | 通过：真实 Qwen 工作流识别出 `image-2`..`image-8` |
| Android 组级启停 | 通过：Qwen 工作流选中 `image-2` 后，提交节点 `17 → 19`，`#505 LoadImage` 从“未启用”恢复 |
| 子级 UI 层级 | 已改为挂在“图像编辑”下面；按用户要求只构建，不做真机测试 |
| 共享层测试 | **205 / 0 / 0** |
| Android 单测 | **13 / 0 / 0** |
| Android 构建 | 成功，APK 已安装 |
| 鸿蒙构建 | 成功，HAP 已生成；当前无设备连接，未安装 |
| 真实多图提交 | **交给用户**：上传第二张图并提交，确认 prompt 的 `image_2` 生效 |

## 实现

- `WorkflowGraphs.analyze` 在原有“有视觉输出的连通图”之外，追加连接到输出分支的 LoadImage 图组：
  - 只取含 `LoadImage`、不属于主输出分组、且与输出连通分量相连的组；
  - 这类图组 `outputNodeIds` 为空，选中时只把组内节点加入
    `forcedNormalNodeIds`，不额外增加输出节点；
  - 默认 `enabled` 要求组内节点全部正常；被旁路的 `image-2` 默认关闭。
- `WorkflowGraph` 增加 `parentId`：辅助图组记录所属输出图；输出图本身为 `null`。
- 两端原有图选择 UI 自动显示这些组，并按父子层级渲染：
  - Android：父图筛选条下方显示带缩进的 `└ image-2` 子图；
  - 鸿蒙：父图按钮下方显示缩进的 `└ image-2` 子图按钮；
  - 父图关闭时隐藏并清空子图选择，避免隐藏状态继续影响提交。
- 本轮只做组级启停，未增加单节点启停。

## Android 真机确认

工作流：`Qwen-image21-图像编辑+生图流（整合）`

1. 初始只启用“图像编辑”，显示 `17 个会提交`；
2. 图组条向右滚动，出现 `image-2`、`image-3`、`image-4` 等；
3. 点选 `image-2` 后显示 `19 个会提交`；
4. 打开“全部节点”：
   - `#505 Load Image` 不再显示“未启用”；
   - `#506`、`#507` 仍显示“未启用”。
5. 说明组内 `LoadImage + ImageScaleToTotalPixels` 已被共享层强制回正常执行。

## 请用户测试

### Android

1. 打开上述 Qwen 工作流，保持“图像编辑”启用；
2. 图组条滑到 `image-2` 并点选；
3. 在 `#505 Load Image` 点“从相册选择”，上传第二张图；
4. 提交，确认任务成功；
5. 在 `/history` 或产物/提示词中确认 `#497` 的 `images.image_2` 使用新图。

再验一次反例：取消 `image-2`，确认回到 `17 个会提交`，`#505` 恢复“未启用”。

### 鸿蒙

安装新 HAP 后重复上述步骤，重点确认：

- 节点图列表出现 `image-2` 等辅助组；
- 选中后 `#505` 可上传、可提交；
- 取消后恢复为未启用。

## 构建

- `:shared:jvmTest` 205 / 0 / 0
- `:app:testDebugUnitTest` 13 / 0 / 0
- `:app:assembleDebug` 成功，APK 已安装
- `harmony/build.ps1` 成功

子级层级改动只做了编译和安装，没有再做真机 UI 测试。

HAP：`harmony/entry/build/default/outputs/default/entry-default-signed.hap`
