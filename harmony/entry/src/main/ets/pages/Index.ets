import { nativeVersion } from 'libentry.so';
import { common, ConfigurationConstant } from '@kit.AbilityKit';
import { image } from '@kit.ImageKit';
import { util } from '@kit.ArkTS';
import { pasteboard } from '@kit.BasicServicesKit';
import { AnimatorOptions, AnimatorResult, promptAction } from '@kit.ArkUI';
import { http } from '@kit.NetworkKit';
import {
  BridgeCatalog,
  BridgeMonitor,
  BridgeOutput,
  BridgePreview,
  BridgeState,
  BridgeTask,
  BridgeWorkflow,
  CatalogEntry,
  ComfyBridge,
  TaskRestoreResult,
} from '../bridge/ComfyBridge';
import { PinnedNodeStore } from '../bridge/PinnedNodeStore';
import { PinnedWorkflowStore } from '../bridge/PinnedWorkflowStore';
import { NodeNoteStore } from '../bridge/NodeNoteStore';
import { AddressEntry, AddressStore } from '../bridge/AddressStore';
import { DismissedErrorStore } from '../bridge/DismissedErrorStore';
import { emptyServerStats, fetchServerStats, sameServerStats, ServerStats } from '../bridge/ServerStats';
import { Palette, Spectrum } from '../theme/Spectrum';
import { ArtifactDownloader, ArtifactDownloadResult } from '../artifact/ArtifactDownloader';
import { VideoThumb } from '../artifact/VideoThumb';
import { ImageUploader, PickedImage } from '../workflow/ImageUploader';
import {
  BuildProblemView,
  DisableImpactResponse,
  PromptResponse,
  WorkflowFieldView,
  WorkflowGraphView,
  WorkflowNodeView,
  WorkflowViewResponse,
} from '../model/WorkflowTypes';

/** 服务器性能条的轮询间隔（毫秒）；页面不可见时会整个停掉。 */
const STATS_POLL_MS: number = 2000;

function emptyTask(): BridgeTask {
  return {
    id: '',
    promptId: null,
    name: null,
    workflowPath: null,
    state: '',
    percent: 0,
    progressValue: 0,
    progressMax: 0,
    progressNode: null,
    outputs: 0,
    outputRefs: [],
    error: null,
    updatedAtMs: 0,
  };
}

function emptyOutput(): BridgeOutput {
  return {
    nodeId: '',
    kind: '',
    filename: '',
    subfolder: '',
    type: '',
    displayName: '',
    mime: '',
    image: false,
    video: false,
    audio: false,
    path: '',
  };
}

function emptyCatalog(): BridgeCatalog {
  return { state: 'idle', entries: [] };
}

function emptyWorkflow(): BridgeWorkflow {
  return {
    loadingPath: null,
    path: null,
    name: null,
    format: '',
    error: null,
    nodeClasses: 0,
    nodeInfo: 0,
  };
}

function emptyView(): WorkflowViewResponse {
  return {
    ok: false,
    state: 'empty',
    message: '',
    path: '',
    name: '',
    format: '',
    defaultGraphIds: [],
    selectedGraphIds: [],
    bypassedNodeIds: [],
    enabledNodeIds: [],
    graphs: [],
    nodes: [],
    commonFields: [],
    problems: [],
    warnings: [],
    pendingNodeClasses: [],
    missingNodeClasses: [],
    canSubmit: false,
    blockReason: '不可提交：还没有打开工作流',
    prompt: null,
  };
}

/** 性能条百分比的补间显示值；-1 表示该指标缺失（整格省略，不补 0）。 */
interface ShownStats {
  ram: number;
  vram: number;
  cpu: number;
  gpu: number;
}

function emptyShownStats(): ShownStats {
  return { ram: -1, vram: -1, cpu: -1, gpu: -1 };
}

/** 缺失的监控指标按 -1 处理，性能条据此判断"这一格要不要画"。 */
function shownNumber(value: number | null): number {
  return value === null || !Number.isFinite(value) ? -1 : Math.round(value);
}

/**
 * 只比较性能条真正会显示的数字（四舍五入后）。 
 * 监控帧的 receivedAtMs 每秒都变，读数没变时就不该重绘整条性能条。 
 */
function sameMonitor(a: BridgeMonitor | null, b: BridgeMonitor | null): boolean {
  if (a === null || b === null) {
    return a === b;
  }
  return shownNumber(a.cpu) === shownNumber(b.cpu) &&
    shownNumber(a.ram) === shownNumber(b.ram) &&
    shownNumber(a.gpu) === shownNumber(b.gpu) &&
    shownNumber(a.vram) === shownNumber(b.vram) &&
    shownNumber(a.temperature) === shownNumber(b.temperature) &&
    a.gpuName === b.gpuName;
}

interface PreviewPayload {
  ok: boolean;
  promptId?: string | null;
  nodeId?: string | null;
  receivedAtMs?: number;
  bytes?: number;
  image?: string;
  message?: string;
}

/** bindPopup 的 onStateChange 参数；ArkTS 不允许用对象字面量直接当类型。 */
interface PopupStateEvent {
  isVisible: boolean;
}

@Entry
@Component
struct Index {
  @State baseUrl: string = 'http://127.0.0.1:8188';
  /** 最近连接过的地址（冷启动从这里恢复）与地址历史（最新在前）。 */
  @State addressHistory: AddressEntry[] = [];
  @State lastUsedUrl: string = '';
  @State addressMenuOpen: boolean = false;
  /** 已成功加载（或已失败）的图片 URL：控制骨架显隐与淡入。 */
  @State loadedImageUrls: string[] = [];
  /** 当前展开子图面板的父图 id（空串表示没有展开）。 */
  @State openChildGraphId: string = '';
  /** 地址输入框实测宽度（vp）：历史下拉按它对齐，不再用固定 300vp。 */
  @State addressFieldWidth: number = 0;
  /** 性能条的数据；拿不到的项保持 -1 / ''，页面直接跳过。 */
  @State serverStats: ServerStats = emptyServerStats();
  /**
   * 状态帧顶层的 Crystools 监控采样（CPU/GPU/温度/内存/显存）。 
   *
   * 它每秒都会变，所以单独存一份 @State 只给性能条读：summaryKey 里没有它， 
   * 不会触发 refreshView()，工作流视图不会被每秒重建。 
   */
  @State monitor: BridgeMonitor | null = null;
  /** 性能条展开/收起；收起时只剩一行标题，纯界面状态，不进 summaryKey。 */
  @State statsExpanded: boolean = false;
  /** 进度条轨道实测宽度（vp）；填充按它算绝对宽度，不用百分比。 */
  @State progressTrackWidth: number = 0;
  @State taskTrackWidth: number = 0;
  /** 页面切换方向：true=进入下一层（从右侧滑入），false=返回（退出到右侧）。 */
  @State navForward: boolean = true;
  /** 当前被按住的控件 key；同一时刻只可能有一个手指按下，单值就够。 */
  @State pressedKey: string = '';
  /** 骨架屏呼吸相位（0.35↔0.7）。 */
  @State skeletonPhase: number = 0.35;
  /** 空状态轻浮相位（0→1，2.6s 往返）。 */
  @State floatPhase: number = 0;
  /** 性能条百分比的补间显示值；-1 表示缺失。 */
  @State shownStats: ShownStats = emptyShownStats();
  /** 分段页签滑动底板的轨道宽度与自身宽度（vp，由 onAreaChange 量出）。 */
  @State tabTrackWidth: number = 0;
  @State tabIndicatorWidth: number = 0;
  /** 被用户关掉的错误提示 key：`taskId|errorText`，按工作流持久化。 */
  @State dismissedErrorKey: string = '';
  @State running: boolean = false;
  @State label: string = '未连接';
  @State generation: number = 0;
  @State network: string = '-';
  @State tasks: BridgeTask[] = [];
  /** 队列计数：共享层状态帧里的 queueRunning / queuePending，对应 Android state.queue。 */
  @State queueRunning: number = 0;
  @State queuePending: number = 0;
  @State notice: string = '';
  @State logs: string[] = [];
  @State storageReady: boolean = false;
  @State storageLabel: string = '存储初始化中…';

  @State tab: number = 0;
  @State detailTab: number = 0;
  @State catalog: BridgeCatalog = emptyCatalog();
  @State workflow: BridgeWorkflow = emptyWorkflow();
  @State selectedPath: string = '';
  @State view: WorkflowViewResponse = emptyView();
  @State viewLoading: boolean = false;
  @State viewError: string = '';
  @State graphIds: string[] = [];
  @State graphsInitialized: boolean = false;
  /**
   * 本次会话里被用户逐个关闭 / 重新启用的节点。 
   *
   * 和 `graphIds` 一样只存在于宿主内存，随每次视图/prompt 请求送给共享层；
   * 切换工作流时清空（Android 侧这两个集合在共享会话状态里，语义一致）。 
   * 两者互斥且关闭优先：同一个 id 不会同时出现在两个集合里。 
   */
  @State bypassedIds: number[] = [];
  @State enabledIds: number[] = [];
  /** 待确认的「关闭节点」：只有共享层判定会破坏工作流时才会 >= 0。 */
  @State bypassConfirmNodeId: number = -1;
  @State bypassConfirmReason: string = '';
  @State pinnedIds: number[] = [];
  @State pinnedWorkflowPaths: string[] = [];
  @State editTick: number = 0;
  @State submitting: boolean = false;
  /** 提交成功后按钮短暂显示「已提交 ✓」。 */
  @State submitFlash: boolean = false;
  /** 任务状态轨旧颜色的淡出进度（1→0）与完成脉冲的透明度（1→0）。 */
  @State railFade: number = 1;
  @State railTick: number = 0;
  @State railVisible: boolean = false;
  @State donePulseAlpha: number = 0;
  @State pulseTaskId: string = '';
  @State submitNotice: string = '';
  @State confirmOpen: boolean = false;
  @State confirmWarnings: string[] = [];
  @State confirmPrompt: string = '';

  @State uploadingField: string = '';
  @State uploadErrorKey: string = '';
  @State uploadError: string = '';
  @State uploadNoticeKey: string = '';
  @State uploadNotice: string = '';

  @State nodeNotesVersion: number = 0;
  @State previewVersion: number = 0;
  @State noteEditorId: number = -1;
  @StorageLink('appearanceMode') appearanceMode: string = 'system';
  @StorageProp('systemDark') @Watch('onSystemDarkChange') systemDark: boolean = true;
  /** 系统栏高度（px），由 EntryAbility 发布；页面据此给内容留安全边距。 */
  @StorageProp('statusBarPx') statusBarPx: number = 0;
  @StorageProp('navBarPx') navBarPx: number = 0;
  @State isDark: boolean = true;
  @State tasksOpen: boolean = false;
  @State connectView: boolean = false;
  @State settingsOpen: boolean = false;
  /** 关于页：从设置进入，返回时回到设置。 */
  @State aboutOpen: boolean = false;
  /** 关于页内的许可证说明浮层。 */
  @State licenseOpen: boolean = false;
  @State noteDraft: string = '';
  @State nodeQuery: string = '';
  @State traitFilter: number[] = [];
  /** 图选择器下拉面板的展开状态。 */
  @State graphMenuOpen: boolean = false;
  /** 「全部节点」面板的展开状态（对应 Android 的 ModalBottomSheet）。 */
  @State allNodesOpen: boolean = false;
  /** 节点卡片展开集合；expandedSeed 为 false 时用 autoExpandedIds() 的自动展开。 */
  @State expandedIds: number[] = [];
  @State expandedSeed: boolean = false;

  @State artifactOpen: boolean = false;
  @State artifactTask: BridgeTask = emptyTask();
  @State previewOpen: boolean = false;
  @State previewOutput: BridgeOutput = emptyOutput();
  @State previewError: boolean = false;
  @State downloadingKey: string = '';
  @State downloadNotice: string = '';
  /** 最近一次失败的产物 key：列表行据此显示 err 文案 + 单个「重试」。 */
  @State downloadFailedKey: string = '';

  @State livePreview: image.PixelMap | null = null;
  @State livePreviewMeta: BridgePreview | null = null;
  @State livePreviewFailed: boolean = false;

  private bridge: ComfyBridge = new ComfyBridge();
  private abi: number = 0;
  private pinnedStore: PinnedNodeStore = new PinnedNodeStore();
  private pinnedWorkflowStore: PinnedWorkflowStore = new PinnedWorkflowStore();
  private downloader: ArtifactDownloader = new ArtifactDownloader();
  private imageUploader: ImageUploader = new ImageUploader();
  private noteStore: NodeNoteStore = new NodeNoteStore();
  private addressStore: AddressStore = new AddressStore();
  private dismissedStore: DismissedErrorStore = new DismissedErrorStore();
  /** 性能条复用一个 HttpRequest 句柄，页面离场时 destroy()。 */
  private statsRequest: http.HttpRequest | null = null;
  private statsTimer: number = -1;
  private statsBusy: boolean = false;
  private statsPaused: boolean = false;
  private notes: Map<number, string> = new Map();
  private previewUris: Map<string, string> = new Map();
  private uiContext: common.UIAbilityContext | null = null;
  private edits: Map<string, string | number | boolean | null> = new Map();
  private editRefreshTimer: number = -1;
  private submitNonce: number = 0;
  private pickerActive: boolean = false;
  private skipNextAppear: boolean = false;
  private viewSeq: number = 0;
  private summaryKey: string = '';
  private skeletonStarted: boolean = false;
  private floatStarted: boolean = false;
  private submitFlashTimer: number = -1;
  /** 性能条百分比补间：目标变化时用 UIContext 的 Animator 逐帧推进。 */
  private statAnimator: AnimatorResult | null = null;
  private statFrom: ShownStats = emptyShownStats();
  private statTarget: ShownStats = emptyShownStats();
  /** 任务状态快照：只用于识别状态切换（轨道颜色/完成脉冲），不参与渲染。 */
  private taskStates: Map<string, string> = new Map();
  private prevTaskStates: Map<string, string> = new Map();
  private catalogRequested: boolean = false;
  private reopenRequested: boolean = false;
  private scroller: Scroller = new Scroller();
  private catalogScroller: Scroller = new Scroller();
  private previewRequestSeq: number = 0;
  private previewFrameKey: string = '';
  private previewBase64: util.Base64Helper = new util.Base64Helper();

  aboutToAppear(): void {
    // 从相册选择器或后台回来时页面重新可见：恢复性能轮询（此时连接可能还活着）。 
    this.statsPaused = false;
    if (this.running) {
      this.startStatsPolling();
    }
    PersistentStorage.persistProp('appearanceMode', 'system');
    this.applyAppearance();
    // 相册选择器会把页面短暂切到后台；回来时不要重建会话，否则正在进行的上传 
    // 会被停止，工作流和节点图选择也会被重置。 
    if (this.skipNextAppear) {
      this.skipNextAppear = false;
      if (this.running) {
        return;
      }
    }
    this.abi = nativeVersion();
    this.bridge.onState = (state: BridgeState) => this.applyState(state);
    this.bridge.onNotice = (text: string) => {
      this.notice = text;
      if (text.startsWith('提交被拒绝') || text.startsWith('已提交')) {
        this.submitNotice = text;
      }
      // 共享层确认服务器已接受后才算成功：只有这条路径点亮「已提交 ✓」。
      if (text.startsWith('已提交')) {
        this.flashSubmitSuccess();
      }
      this.log(text);
    };
    this.bridge.onLog = (line: string) => this.log(line);
    this.bootstrap();
  }

  aboutToDisappear(): void {
    if (this.submitFlashTimer !== -1) {
      clearTimeout(this.submitFlashTimer);
      this.submitFlashTimer = -1;
    }
    // 先停掉性能轮询和复用的 HTTP 句柄，再走原来的离场逻辑。 
    this.stopStatsPolling();
    if (this.pickerActive) {
      return;
    }
    this.cancelEditRefresh();
    this.clearLivePreview();
    this.bridge.stop();
    this.running = false;
  }

  /** 页面重新可见（含应用回前台）：恢复性能轮询。 */
  onPageShow(): void {
    this.statsPaused = false;
    if (this.running) {
      this.startStatsPolling();
    }
  }

  /** 页面不可见（切后台 / 跳走）：停掉轮询，回来再开。 */
  onPageHide(): void {
    this.statsPaused = true;
    this.stopStatsPolling();
  }

  /**
   * 页面切换：先定方向再在 animateTo 里翻页状态，页面容器的 transition 负责滑动+淡入淡出。
   * 只包裹导航本身，不改原来的 if/else 返回栈。
   */
  private goPage(forward: boolean, action: () => void): void {
    this.navForward = forward;
    animateTo({ duration: 260, curve: Curve.EaseOut }, action);
  }

  /**
   * 页面进出：进入方向跟随 navForward（前进从右 1/4 屏滑入，返回从左滑入），
   * 离开统一向右退出（返回方向）。方向在页面挂载时求值，避免退场页拿不到新方向。
   */
  private screenTransition(): TransitionEffect {
    const enterX: string = this.navForward ? '25%' : '-25%';
    return TransitionEffect.asymmetric(
      TransitionEffect.OPACITY
        .combine(TransitionEffect.translate({ x: enterX }))
        .animation({ duration: 260, curve: Curve.EaseOut }),
      TransitionEffect.OPACITY
        .combine(TransitionEffect.translate({ x: '25%' }))
        .animation({ duration: 220, curve: Curve.EaseOut }),
    );
  }

  /** 任务浮层：从下方 1/3 屏高度滑入并淡入，关闭时滑回下方。 */
  private openTasks(): void {
    animateTo({ duration: 240, curve: Curve.EaseOut }, () => {
      this.tasksOpen = true;
    });
  }

  private closeTasks(): void {
    animateTo({ duration: 200, curve: Curve.EaseOut }, () => {
      this.tasksOpen = false;
    });
  }

  /** 分段页签的单格宽度：两格 + 中间 3vp 间隔。 */
  private tabItemWidth(): number {
    return this.tabTrackWidth > 0 ? Math.max(0, (this.tabTrackWidth - 3) / 2) : 0;
  }

  private tabIndicatorOffset(): number {
    return this.detailTab * (this.tabItemWidth() + 3);
  }

  /** 按压反馈的缩放值；按下 120ms 收进去，抬起 160ms 弹回。 */
  private pressScale(key: string, pressed: number = 0.965): number {
    return this.pressedKey === key ? pressed : 1;
  }

  private pressScaleOptions(key: string, pressed: number = 0.965): ScaleOptions {
    const value: number = this.pressScale(key, pressed);
    return { x: value, y: value };
  }

  private onPressTouch(event: TouchEvent, key: string): void {
    if (event.type === TouchType.Down) {
      animateTo({ duration: 120, curve: Curve.EaseOut }, () => {
        this.pressedKey = key;
      });
    } else if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
      animateTo({ duration: 160, curve: Curve.EaseOut }, () => {
        if (this.pressedKey === key) {
          this.pressedKey = '';
        }
      });
    }
  }

  /** 骨架屏呼吸：透明度 0.35↔0.7，900ms 一次往返；只启动一次。 */
  private startSkeletonPulse(): void {
    if (this.skeletonStarted) {
      return;
    }
    this.skeletonStarted = true;
    this.skeletonPhase = 0.35;
    animateTo({
      duration: 900,
      curve: Curve.EaseInOut,
      iterations: -1,
      playMode: PlayMode.Alternate,
    }, () => {
      this.skeletonPhase = 0.7;
    });
  }

  /** 空状态轻浮：上下 3vp、透明度 0.82↔1，2.6s 一次往返。 */
  private startGentleFloat(): void {
    if (this.floatStarted) {
      return;
    }
    this.floatStarted = true;
    this.floatPhase = 0;
    animateTo({
      duration: 2600,
      curve: Curve.EaseInOut,
      iterations: -1,
      playMode: PlayMode.Alternate,
    }, () => {
      this.floatPhase = 1;
    });
  }

  /** 提交成功：按钮标签切到「已提交 ✓」，1.2s 后由定时器恢复。 */
  private flashSubmitSuccess(): void {
    if (this.submitFlashTimer !== -1) {
      clearTimeout(this.submitFlashTimer);
    }
    this.submitFlash = true;
    this.submitFlashTimer = setTimeout(() => {
      this.submitFlashTimer = -1;
      this.submitFlash = false;
    }, 1200);
  }

  /** 任务状态轨：旧颜色叠层从 1 淡到 0，280ms 后清理旧状态。 */
  private startRailFade(): void {
    if (this.railFade < 0.99) {
      return;
    }
    animateTo({ duration: 280, curve: Curve.EaseOut }, () => {
      this.railFade = 0;
    });
  }

  /** 完成脉冲：绿色描边从 1 淡到 0（900ms），结束后卸载叠层。 */
  private startDonePulse(): void {
    if (this.donePulseAlpha < 0.99) {
      return;
    }
    const id: string = this.pulseTaskId;
    animateTo({ duration: 900, curve: Curve.EaseOut }, () => {
      this.donePulseAlpha = 0;
    });
    setTimeout(() => {
      if (this.pulseTaskId === id) {
        this.pulseTaskId = '';
      }
    }, 940);
  }

  /** 任务状态切换：记录旧状态给轨道颜色淡出用，并在进入「已完成」时触发一次脉冲。 */
  private syncTaskAnimations(next: BridgeTask[]): void {
    let railChanged: boolean = false;
    let completedId: string = '';
    const seen: Map<string, boolean> = new Map();
    next.forEach((task: BridgeTask) => {
      seen.set(task.id, true);
      const before: string | undefined = this.taskStates.get(task.id);
      if (before !== undefined && before !== task.state) {
        if (this.taskRailColor(before) !== this.taskRailColor(task.state)) {
          this.prevTaskStates.set(task.id, before);
          railChanged = true;
        }
        if (task.state === 'Completed') {
          completedId = task.id;
        }
      }
      this.taskStates.set(task.id, task.state);
    });
    const stale: string[] = [];
    this.taskStates.forEach((state: string, id: string) => {
      if (seen.get(id) === undefined) {
        stale.push(id);
      }
    });
    stale.forEach((id: string) => {
      this.taskStates.delete(id);
      this.prevTaskStates.delete(id);
    });
    if (railChanged) {
      // visible 控制叠层挂载；fade 只驱动 opacity，两者分开，淡出期间叠层不会被立刻卸载。
      this.railVisible = true;
      this.railFade = 1;
      this.railTick++;
      setTimeout(() => {
        this.prevTaskStates.clear();
        this.railVisible = false;
        this.railTick++;
      }, 320);
    }
    if (completedId.length > 0) {
      this.pulseTaskId = completedId;
      this.donePulseAlpha = 1;
    }
  }

  /** 状态轨的旧颜色；没有过渡需求时返回空串（不渲染叠层）。 */
  private taskRailPrevious(task: BridgeTask): string {
    if (this.railTick < 0 || !this.railVisible) {
      return '';
    }
    const before: string | undefined = this.prevTaskStates.get(task.id);
    return before === undefined ? '' : this.taskRailColor(before);
  }

  private async bootstrap(): Promise<void> {
    const host = this.getUIContext().getHostContext();
    if (host === undefined) {
      this.storageReady = true;
      this.storageLabel = '拿不到 Ability 上下文，任务只在内存里';
      return;
    }
    const context = host as common.Context;
    this.uiContext = host as common.UIAbilityContext;
    const result: TaskRestoreResult = await this.bridge.init(context);
    const pinnedReady: boolean = await this.pinnedStore.open(context);
    const notesReady: boolean = await this.noteStore.open(context);
    const workflowPinsReady: boolean = await this.pinnedWorkflowStore.open(context);
    // 地址历史和错误关闭状态都必须在第一次连接前就绪。 
    const addressReady: boolean = await this.addressStore.load(context);
    const dismissedReady: boolean = await this.dismissedStore.open(context);
    if (workflowPinsReady) {
      this.pinnedWorkflowPaths = this.pinnedWorkflowStore.load();
    }
    if (addressReady) {
      // 冷启动优先恢复上次连通过的地址；没有记录时保留默认的 127.0.0.1:8188。 
      this.lastUsedUrl = this.addressStore.lastUrl();
      this.addressHistory = this.addressStore.history();
      if (this.lastUsedUrl.length > 0) {
        this.baseUrl = this.lastUsedUrl;
      }
    }
    this.storageReady = true;
    if (!result.storage) {
      this.storageLabel = `本地存储不可用（${result.error}），任务只在内存里`;
    } else {
      this.storageLabel = result.restored > 0 ? `已恢复 ${result.restored} 个历史任务` : '本地存储就绪，没有历史任务';
    }
    if (!pinnedReady) {
      this.log(`置顶存储不可用：${this.pinnedStore.error}`);
    }
    if (!workflowPinsReady) {
      this.log(`工作流置顶存储不可用：${this.pinnedWorkflowStore.error}`);
    }
    if (!notesReady) {
      this.log(`备注存储不可用：${this.noteStore.error}`);
    }
    if (!addressReady) {
      this.log(`地址历史存储不可用：${this.addressStore.error}`);
    }
    if (!dismissedReady) {
      this.log(`错误关闭状态存储不可用：${this.dismissedStore.error}`);
    }
    this.bridge.prepare(this.baseUrl);
  }

  private log(line: string): void {
    const stamp = new Date().toTimeString().substring(0, 8);
    this.logs = [`${stamp} ${line}`, ...this.logs].slice(0, 60);
  }

  private applyState(state: BridgeState): void {
    this.label = `${state.label} · 第 ${state.generation} 代 · ${state.network}`;
    this.generation = state.generation;
    this.network = state.validated ? `${state.network}（已验证）` : state.network;
    this.tasks = state.tasks;
    this.syncTaskAnimations(state.tasks);
    this.queueRunning = state.queueRunning ?? 0;
    this.queuePending = state.queuePending ?? 0;
    // 监控采样只喂性能条：单独一份 @State，summaryKey 不读它，工作流视图不受影响。 
    const monitor: BridgeMonitor | null = state.monitor ?? null;
    this.monitor = sameMonitor(this.monitor, monitor) ? this.monitor : monitor;
    // 监控帧或 HTTP 轮询都可能更新百分比：每次状态帧后对齐一次补间目标。
    this.syncStatsTargets();
    this.catalog = state.catalog;
    this.workflow = state.workflow;
    this.livePreviewMeta = state.preview ?? null;
    this.updateLivePreview(this.livePreviewMeta);
    if (this.artifactOpen && this.artifactTask.id.length > 0) {
      const current = state.tasks.find((task: BridgeTask) => task.id === this.artifactTask.id);
      if (current !== undefined) {
        this.artifactTask = current;
      }
    }

    if (this.running && state.phase === 'Connected') {
      // 共享层确认连上（随后会拉工作流目录）才算“成功连接”：这时才记住地址。 
      this.rememberAddress(this.normalizedBaseUrl());
      this.startStatsPolling();
      if (!this.catalogRequested) {
        this.catalogRequested = true;
        this.bridge.refreshWorkflows();
      }
      if (this.selectedPath.length > 0 &&
        state.workflow.path === null &&
        state.workflow.loadingPath === null &&
        !this.reopenRequested) {
        this.reopenRequested = true;
        this.bridge.openWorkflow(this.selectedPath);
      }
    }

    if (this.selectedPath.length > 0 && state.workflow.path === this.selectedPath) {
      const key = `${state.workflow.path}|${state.workflow.format}|${state.workflow.nodeInfo}|${state.workflow.nodeClasses}`;
      if (key !== this.summaryKey) {
        this.summaryKey = key;
        this.refreshView();
      }
    }
  }

  private activeTask(): BridgeTask | null {
    let active: BridgeTask | null = null;
    for (let i = 0; i < this.tasks.length; i++) {
      const task = this.tasks[i];
      if (task.state === 'Submitting' || task.state === 'Queued' || task.state === 'Running') {
        if (active === null || task.updatedAtMs > active.updatedAtMs) {
          active = task;
        }
      }
    }
    return active;
  }

  private previewTask(): BridgeTask | null {
    const meta = this.livePreviewMeta;
    if (meta !== null && meta.promptId !== null) {
      const match = this.tasks.find((task: BridgeTask) => task.promptId === meta.promptId);
      if (match !== undefined &&
        (match.state === 'Submitting' || match.state === 'Queued' || match.state === 'Running')) {
        return match;
      }
    }
    return this.activeTask();
  }

  /** 当前工作流自己的任务；详情页不能显示别的工作流的采样图。 */
  private workflowTask(activeOnly: boolean): BridgeTask | null {
    let found: BridgeTask | null = null;
    for (let i = 0; i < this.tasks.length; i++) {
      const task = this.tasks[i];
      if (task.workflowPath !== this.selectedPath) {
        continue;
      }
      if (activeOnly &&
        task.state !== 'Submitting' && task.state !== 'Queued' && task.state !== 'Running') {
        continue;
      }
      if (found === null || task.updatedAtMs > found.updatedAtMs) {
        found = task;
      }
    }
    return found;
  }

  private activeWorkflowTask(): BridgeTask | null {
    return this.workflowTask(true);
  }

  private latestWorkflowTask(): BridgeTask | null {
    return this.workflowTask(false);
  }

  private openActiveWorkflowArtifacts(): void {
    const task = this.activeWorkflowTask();
    if (task !== null) {
      this.openArtifacts(task);
    }
  }

  private livePreviewMatches(task: BridgeTask | null): boolean {
    if (task === null) {
      return false;
    }
    const meta = this.livePreviewMeta;
    if (meta === null) {
      return false;
    }
    if (meta.promptId !== null) {
      return meta.promptId === task.promptId;
    }
    const active = this.previewTask();
    return active !== null && active.id === task.id;
  }

  private firstImageUrl(task: BridgeTask): string {
    for (let i = 0; i < task.outputRefs.length; i++) {
      const output = task.outputRefs[i];
      if (output.image) {
        return this.artifactUrl(output);
      }
    }
    return '';
  }

  private previewStepLabel(task: BridgeTask | null): string {
    if (task === null || task.progressMax <= 0) {
      return '';
    }
    const node = task.progressNode !== null && task.progressNode.length > 0
      ? `#${task.progressNode} · `
      : '';
    return `${node}${Math.round(task.progressValue)}/${Math.round(task.progressMax)}`;
  }

  private updateLivePreview(meta: BridgePreview | null): void {
    if (meta === null) {
      this.clearLivePreview();
      return;
    }
    const key = `${meta.promptId ?? ''}:${meta.receivedAtMs}`;
    if (key === this.previewFrameKey) {
      return;
    }
    this.previewFrameKey = key;
    this.loadLivePreview(++this.previewRequestSeq);
  }

  private async loadLivePreview(seq: number): Promise<void> {
    try {
      const raw = this.bridge.preview();
      const payload = JSON.parse(raw) as PreviewPayload;
      if (seq !== this.previewRequestSeq) {
        return;
      }
      if (!payload.ok || payload.image === undefined || payload.image.length === 0) {
        this.livePreviewFailed = true;
        return;
      }
      const bytes = this.previewBase64.decodeSync(payload.image);
      const source = image.createImageSource(bytes.buffer);
      try {
        const pixel = await source.createPixelMap();
        if (seq !== this.previewRequestSeq) {
          await pixel.release();
          return;
        }
        const old = this.livePreview;
        this.livePreview = pixel;
        this.livePreviewFailed = false;
        if (old !== null) {
          old.release().catch(() => {});
        }
      } finally {
        source.release();
      }
    } catch (err) {
      if (seq === this.previewRequestSeq) {
        this.livePreviewFailed = true;
        this.log(`预览帧解码失败：${this.errorText(err)}`);
      }
    }
  }

  private clearLivePreview(): void {
    this.previewRequestSeq++;
    this.previewFrameKey = '';
    this.livePreviewMeta = null;
    this.livePreviewFailed = false;
    const old = this.livePreview;
    this.livePreview = null;
    if (old !== null) {
      old.release().catch(() => {});
    }
  }

  private normalizedBaseUrl(): string {
    let url = this.baseUrl.trim();
    while (url.endsWith('/')) {
      url = url.substring(0, url.length - 1);
    }
    return url;
  }

  /** 输入框为空时用上次连通的地址当占位，避免冷启动后忘了上次连的是哪台机器。 */
  private addressPlaceholder(): string {
    return this.lastUsedUrl.length > 0 ? this.lastUsedUrl : 'http://192.168.1.10:8188';
  }

  /** 下拉面板里的「当前」标记：和输入框里规范化后的地址比较。 */
  private isCurrentAddress(entry: AddressEntry): boolean {
    return this.normalizedBaseUrl() === entry.url;
  }

  private addressAge(usedAt: number): string {
    if (usedAt <= 0) {
      return '';
    }
    const elapsed = Date.now() - usedAt;
    if (elapsed < 60 * 1000) {
      return '刚刚使用';
    }
    if (elapsed < 60 * 60 * 1000) {
      return `${Math.floor(elapsed / (60 * 1000))} 分钟前`;
    }
    if (elapsed < 24 * 60 * 60 * 1000) {
      return `${Math.floor(elapsed / (60 * 60 * 1000))} 小时前`;
    }
    const date = new Date(usedAt);
    return `${date.getMonth() + 1} 月 ${date.getDate()} 日`;
  }

  /** 只在连接成功路径上调用：去重、置顶、最多保留 8 条。 */
  private rememberAddress(url: string): void {
    if (url.length === 0) {
      return;
    }
    if (this.lastUsedUrl === url && this.addressHistory.length > 0 &&
      this.addressHistory[0].url === url) {
      return;
    }
    this.addressStore.remember(url).then(() => {
      this.addressHistory = this.addressStore.history();
      this.lastUsedUrl = this.addressStore.lastUrl();
    }).catch((err: Error) => this.log(`地址历史保存失败：${err.message}`));
  }

  /** ✕ 只从历史里删掉，不清空输入框（用户可能还想连它）。 */
  private forgetAddress(url: string): void {
    this.addressStore.forget(url).then(() => {
      this.addressHistory = this.addressStore.history();
      this.lastUsedUrl = this.addressStore.lastUrl();
    }).catch((err: Error) => this.log(`地址历史删除失败：${err.message}`));
  }

  /** 点历史里的地址：填回输入框并收起面板，连不连由「连接」按钮决定。 */
  private pickAddress(url: string): void {
    if (this.running) {
      // 连接期间输入框锁着，历史也不能偷偷换掉正在用的地址。 
      this.addressMenuOpen = false;
      return;
    }
    this.baseUrl = url;
    this.addressMenuOpen = false;
  }

  // ---- 服务器性能条：每 2 秒拉一次 /system_stats + /crystools/monitor ----

  private startStatsPolling(): void {
    if (!this.running || this.statsPaused) {
      return;
    }
    if (this.statsTimer !== -1) {
      return;
    }
    if (this.statsRequest === null) {
      try {
        this.statsRequest = http.createHttp();
      } catch (err) {
        // 创建句柄失败时静默放弃性能条，连接与其它功能不受影响。 
        this.statsRequest = null;
        return;
      }
    }
    this.statsTimer = setInterval(() => {
      this.pollServerStats();
    }, STATS_POLL_MS);
    // 首帧不等 2 秒，连上就显示。 
    this.pollServerStats();
  }

  private stopStatsPolling(): void {
    if (this.statsTimer !== -1) {
      clearInterval(this.statsTimer);
      this.statsTimer = -1;
    }
    const request = this.statsRequest;
    this.statsRequest = null;
    if (request !== null) {
      try {
        request.destroy();
      } catch (err) {
        // 句柄可能已随失败的请求销毁，这里忽略。 
      }
    }
    this.clearServerStats();
    // 页面离场/断开：停掉补间并清空显示值，不在后台继续跑动画。
    this.cancelStatAnimator();
    this.shownStats = emptyShownStats();
    this.statTarget = emptyShownStats();
    this.statFrom = emptyShownStats();
  }

  private clearServerStats(): void {
    if (this.hasServerStats()) {
      this.serverStats = emptyServerStats();
    }
  }

  private hasServerStats(): boolean {
    // 监控帧只要有一项读数（或显卡名）就算"有数据"：/system_stats 拿不到时性能条照样要出。 
    const live: BridgeMonitor | null = this.monitor;
    if (live !== null &&
      (this.monitorValue(live.cpu) >= 0 || this.monitorValue(live.ram) >= 0 ||
        this.monitorValue(live.gpu) >= 0 || this.monitorValue(live.vram) >= 0 ||
        this.monitorValue(live.temperature) >= 0 || this.gpuName().length > 0)) {
      return true;
    }
    const stats = this.serverStats;
    return stats.ramPercent >= 0 || stats.vramPercent >= 0 || stats.cpuPercent >= 0 ||
      stats.gpuPercent >= 0 || stats.temperature >= 0 || stats.version.length > 0;
  }

  /** 监控帧里的一个数；缺失或非法返回 -1，性能条据此省掉这一格（绝不补 0）。 */
  private monitorValue(value: number | null): number {
    if (value === null || !Number.isFinite(value)) {
      return -1;
    }
    return value;
  }

  /** 内存占用：监控帧优先，没有就退回 /system_stats 算出来的百分比；都没有 -1。 */
  private stripRam(): number {
    const live: BridgeMonitor | null = this.monitor;
    const value = live === null ? -1 : this.monitorValue(live.ram);
    return value >= 0 ? value : this.serverStats.ramPercent;
  }

  /** 显存占用：监控帧优先，/system_stats 兜底。 */
  private stripVram(): number {
    const live: BridgeMonitor | null = this.monitor;
    const value = live === null ? -1 : this.monitorValue(live.vram);
    return value >= 0 ? value : this.serverStats.vramPercent;
  }

  /** CPU 占用：监控帧优先，HTTP 轮询兜底。 */
  private stripCpu(): number {
    const live: BridgeMonitor | null = this.monitor;
    const value = live === null ? -1 : this.monitorValue(live.cpu);
    return value >= 0 ? value : this.serverStats.cpuPercent;
  }

  /** GPU 占用：监控帧优先，HTTP 轮询兜底。 */
  private stripGpu(): number {
    const live: BridgeMonitor | null = this.monitor;
    const value = live === null ? -1 : this.monitorValue(live.gpu);
    return value >= 0 ? value : this.serverStats.gpuPercent;
  }

  /** 温度（°C）：监控帧优先，HTTP 轮询兜底。 */
  private stripTemp(): number {
    const live: BridgeMonitor | null = this.monitor;
    const value = live === null ? -1 : this.monitorValue(live.temperature);
    return value >= 0 ? value : this.serverStats.temperature;
  }

  /** 显卡名：只有监控帧给，没给就空字符串（这一格不画）。 */
  private gpuName(): string {
    const live: BridgeMonitor | null = this.monitor;
    if (live === null) {
      return '';
    }
    const name: string | null = live.gpuName;
    if (name === null) {
      return '';
    }
    return name.trim();
  }

  /**
   * 收起时右侧的一行摘要：内存/显存优先，两者都没有才退回 CPU/温度。 
   * 一个数都没有就返回空字符串，标题行只留箭头（不写 --，也不补 0）。 
   */
  private statsSummary(): string {
    const parts: string[] = [];
    if (this.stripRam() >= 0) {
      parts.push(`内存 ${this.statPercentText(this.shownStats.ram)}`);
    }
    if (this.stripVram() >= 0) {
      parts.push(`显存 ${this.statPercentText(this.shownStats.vram)}`);
    }
    if (parts.length === 0) {
      if (this.stripCpu() >= 0) {
        parts.push(`CPU ${this.statPercentText(this.shownStats.cpu)}`);
      }
      const temperature = this.stripTemp();
      if (temperature >= 0) {
        parts.push(`温度 ${Math.round(temperature)}°C`);
      }
    }
    return parts.join(' · ');
  }

  private statPercentText(value: number): string {
    return value < 0 ? '' : `${Math.round(value)}%`;
  }

  private mixStat(from: number, to: number, progress: number): number {
    if (from < 0 || to < 0) {
      return to;
    }
    return from + (to - from) * progress;
  }

  private cancelStatAnimator(): void {
    const animator = this.statAnimator;
    this.statAnimator = null;
    if (animator !== null) {
      try {
        animator.cancel();
      } catch (err) {
        // 动画可能已经结束，句柄失效时忽略。
      }
    }
  }

  /**
   * 性能条百分比补间：目标变化后 320ms 逐帧推进显示值。
   *
   * ArkUI 的 animateTo 只补间组件属性，不逐帧改 @State 数值，因此这里用
   * UIContext 的 Animator 在 onFrame 里更新 shownStats 再渲染文本，数值是真实补间。
   */
  private syncStatsTargets(): void {
    const target: ShownStats = {
      ram: this.stripRam(),
      vram: this.stripVram(),
      cpu: this.stripCpu(),
      gpu: this.stripGpu(),
    };
    if (target.ram === this.statTarget.ram && target.vram === this.statTarget.vram &&
      target.cpu === this.statTarget.cpu && target.gpu === this.statTarget.gpu) {
      return;
    }
    this.statTarget = target;
    const from: ShownStats = {
      ram: this.shownStats.ram >= 0 && target.ram >= 0 ? this.shownStats.ram : target.ram,
      vram: this.shownStats.vram >= 0 && target.vram >= 0 ? this.shownStats.vram : target.vram,
      cpu: this.shownStats.cpu >= 0 && target.cpu >= 0 ? this.shownStats.cpu : target.cpu,
      gpu: this.shownStats.gpu >= 0 && target.gpu >= 0 ? this.shownStats.gpu : target.gpu,
    };
    this.cancelStatAnimator();
    this.statFrom = from;
    if (from.ram === target.ram && from.vram === target.vram &&
      from.cpu === target.cpu && from.gpu === target.gpu) {
      this.shownStats = target;
      return;
    }
    try {
      const options: AnimatorOptions = {
        duration: 320,
        easing: 'ease-out',
        delay: 0,
        fill: 'forwards',
        direction: 'normal',
        iterations: 1,
        begin: 0,
        end: 1,
      };
      const animator: AnimatorResult = this.getUIContext().createAnimator(options);
      animator.onFrame = (progress: number) => {
        const p: number = progress < 0 ? 0 : (progress > 1 ? 1 : progress);
        const stats: ShownStats = {
          ram: this.mixStat(from.ram, target.ram, p),
          vram: this.mixStat(from.vram, target.vram, p),
          cpu: this.mixStat(from.cpu, target.cpu, p),
          gpu: this.mixStat(from.gpu, target.gpu, p),
        };
        this.shownStats = stats;
      };
      animator.onFinish = () => {
        this.shownStats = target;
        if (this.statAnimator === animator) {
          this.statAnimator = null;
        }
      };
      this.statAnimator = animator;
      this.shownStats = from;
      animator.play();
    } catch (err) {
      // 极端情况下拿不到 UI 上下文：退回直接显示目标值，绝不卡在旧数字上。
      this.shownStats = target;
      this.log(`性能百分比补间不可用：${this.errorText(err)}`);
    }
  }

  private async pollServerStats(): Promise<void> {
    if (this.statsBusy) {
      // 上一次请求还没回来（服务器慢），跳过这一拍，别把请求堆起来。 
      return;
    }
    const request = this.statsRequest;
    const url = this.normalizedBaseUrl();
    if (request === null || url.length === 0) {
      return;
    }
    this.statsBusy = true;
    try {
      const stats: ServerStats | null = await fetchServerStats(request, url);
      // 轮询期间可能已经断开或换了地址：旧句柄的结果直接丢弃。 
      if (this.statsRequest !== request) {
        return;
      }
      if (stats === null) {
        this.clearServerStats();
        this.syncStatsTargets();
        return;
      }
      if (!sameServerStats(this.serverStats, stats)) {
        this.serverStats = stats;
        this.syncStatsTargets();
      }
    } catch (err) {
      if (this.statsRequest === request) {
        this.clearServerStats();
      }
    } finally {
      this.statsBusy = false;
    }
  }

  private connect(): void {
    const url = this.normalizedBaseUrl();
    if (url.length === 0) {
      this.notice = '地址不能为空';
      return;
    }
    this.baseUrl = url;
    if (!this.storageReady) {
      this.notice = '本地存储还在初始化，请稍等';
      return;
    }
    this.catalogRequested = false;
    this.reopenRequested = false;
    this.summaryKey = '';
    this.clearLivePreview();
    // 旧地址的性能数据不能留在界面上；连上后 applyState 会重新开轮询。 
    this.stopStatsPolling();
    this.log(`开始连接 ${url}`);
    this.running = true;
    const started = this.bridge.start(url);
    if (!started) {
      this.running = false;
      this.label = '未连接';
      this.notice = '无法创建会话，请查看日志';
    }
  }

  private disconnect(): void {
    this.cancelEditRefresh();
    this.clearLivePreview();
    this.stopStatsPolling();
    this.bridge.stop();
    this.running = false;
    this.label = '未连接';
    this.tasks = [];
    this.catalog = emptyCatalog();
    this.selectedPath = '';
    this.view = emptyView();
    // 断开后没有“当前工作流”，单节点开关和待确认的关闭框一起作废。 
    this.bypassedIds = [];
    this.enabledIds = [];
    this.bypassConfirmNodeId = -1;
    this.bypassConfirmReason = '';
    this.catalogRequested = false;
    this.reopenRequested = false;
    this.resetUploadState();
    this.log('已断开');
  }

  private openWorkflow(path: string, name: string): void {
    if (!this.running) {
      this.notice = '请先连接 ComfyUI';
      return;
    }
    this.cancelEditRefresh();
    this.selectedPath = path;
    this.summaryKey = '';
    this.detailTab = 0;
    this.graphsInitialized = false;
    this.graphIds = [];
    // 单节点开关只属于当前打开的工作流：换文档就清空，避免把旧 id 套到新图上。 
    this.bypassedIds = [];
    this.enabledIds = [];
    this.bypassConfirmNodeId = -1;
    this.bypassConfirmReason = '';
    this.edits.clear();
    this.editTick++;
    this.view = emptyView();
    this.viewError = '';
    this.submitNotice = '';
    // 上一次提交的拒绝通知还留在顶部，打开新工作流时不应该继续误导。 
    this.notice = '';
    this.resetUploadState();
    this.pinnedIds = this.pinnedStore.load(path);
    this.notes = this.noteStore.load(path);
    // 同一个工作流上次关掉的错误块重新打开时保持关闭（key 由 taskId|errorText 组成， 
    // 换了错误文案就不再匹配，会自动重新显示）。 
    this.dismissedErrorKey = this.dismissedStore.load(path);
    this.nodeNotesVersion++;
    this.previewUris.clear();
    this.previewVersion++;
    this.nodeQuery = '';
    this.traitFilter = [];
    this.noteEditorId = -1;
    this.noteDraft = '';
    this.graphMenuOpen = false;
    this.allNodesOpen = false;
    this.expandedIds = [];
    this.expandedSeed = false;
    this.log(`打开工作流：${name || path}`);
    this.bridge.openWorkflow(path);
    setTimeout(() => {
      this.scroller.scrollEdge(Edge.Top);
    }, 0);
  }

  private closeWorkflow(): void {
    this.cancelEditRefresh();
    this.notes.clear();
    this.nodeNotesVersion++;
    this.previewUris.clear();
    this.previewVersion++;
    this.nodeQuery = '';
    this.traitFilter = [];
    this.noteEditorId = -1;
    this.graphMenuOpen = false;
    this.allNodesOpen = false;
    this.expandedIds = [];
    this.expandedSeed = false;
    this.resetUploadState();
    this.selectedPath = '';
    this.summaryKey = '';
    this.detailTab = 0;
    this.graphsInitialized = false;
    this.graphIds = [];
    // 单节点开关只属于当前打开的工作流：换文档就清空，避免把旧 id 套到新图上。 
    this.bypassedIds = [];
    this.enabledIds = [];
    this.bypassConfirmNodeId = -1;
    this.bypassConfirmReason = '';
    this.edits.clear();
    this.editTick++;
    this.view = emptyView();
    this.viewError = '';
    this.submitNotice = '';
    this.dismissedErrorKey = '';
  }

  private refreshView(scrollTop: boolean = false): void {
    if (this.selectedPath.length === 0) {
      return;
    }
    const seq = ++this.viewSeq;
    this.viewLoading = true;
    try {
      const response = JSON.parse(this.bridge.workflow(this.requestJson('view'))) as WorkflowViewResponse;
      if (seq !== this.viewSeq) {
        return;
      }
      this.viewLoading = false;
      if (!response.ok) {
        this.viewError = response.message !== undefined && response.message.length > 0
          ? response.message : '工作流还没有加载好';
        return;
      }
      this.viewError = '';
      // 节点定义还没到时，共享层会返回空图列表；这时不能把“空选择”记成用户显式 
      // 关闭了所有图，否则定义加载完后会把所有节点都判成不可达。 
      if (!this.graphsInitialized && response.graphs.length > 0) {
        this.graphIds = response.selectedGraphIds;
        this.graphsInitialized = true;
      }
      this.view = response;
      // 共享层是开关状态的裁判：响应里带回的就是这次实际生效的覆盖集合， 
      // 宿主的本地副本跟着对齐，不会出现“界面关了但提交没关”的分叉。 
      this.bypassedIds = response.bypassedNodeIds ?? [];
      this.enabledIds = response.enabledNodeIds ?? [];
      this.pruneEdits();
      if (scrollTop) {
        // 等一帧让新的节点顺序完成布局，否则滚动仍会停在旧位置。 
        // 预览标签页下节点列表没有挂载（Scroller 未绑定），此时不滚动。 
        setTimeout(() => {
          if (this.detailTab === 0) {
            this.scroller.scrollEdge(Edge.Top);
          }
        }, 0);
      }
    } catch (err) {
      if (seq !== this.viewSeq) {
        return;
      }
      this.viewLoading = false;
      this.viewError = `读取工作流视图失败：${(err as Error).message}`;
    }
  }

  /**
   * 节点定义还没到齐、且连节点图都还识别不出来。此时共享层只能给出空图列表， 
   * 所有节点都会被标成 missing，图名也只会退化成“其他节点”；先显示加载提示，
   * 等定义（至少是输出节点的定义）到齐后再渲染真正的节点图和字段。 
   */
  private definitionsPending(): boolean {
    return this.view.ok &&
      this.view.pendingNodeClasses.length > 0 &&
      this.view.graphs.length === 0;
  }

  /** 这个节点类还在等 `/object_info`：missing 只代表“定义没到”，不是服务端缺类。 */
  private classPending(nodeType: string): boolean {
    return this.view.pendingNodeClasses.includes(nodeType);
  }

  private requestJson(op: string, runToken: string = '', nodeId: number = -1): string {
    const graphs = this.graphsInitialized
      ? `[${this.graphIds.map((id: string) => JSON.stringify(id)).join(',')}]`
      : 'null';
    const pinned = `[${this.pinnedIds.join(',')}]`;
    const run = runToken.length > 0 ? `,"run":${JSON.stringify(runToken)}` : '';
    // 节点级开关和节点图选择走同一条路：覆盖集合随请求送，共享层回填生效状态。 
    const bypass = this.bridge.nodeBypassJson(this.bypassedIds, this.enabledIds);
    // `op=disable_impact` 只关心要试关的那一个节点；其余请求不带这个字段。 
    const target = nodeId >= 0 ? `,"nodeId":${nodeId}` : '';
    return `{"op":${JSON.stringify(op)},"graphs":${graphs},"pinned":${pinned}${run},` +
      `"overrides":${this.overridesJson()},${bypass}${target}}`;
  }

  private overridesJson(): string {
    const fields = new Map<string, WorkflowFieldView>();
    this.view.nodes.forEach((node: WorkflowNodeView) => {
      node.fields.forEach((field: WorkflowFieldView) => {
        fields.set(this.editKey(field.nodeId, field.inputName), field);
      });
    });
    const nodes: Map<string, string[]> = new Map();
    this.edits.forEach((raw: string | number | boolean | null, key: string) => {
      const split = key.split('\u0000');
      if (split.length !== 2) {
        return;
      }
      const nodeId = split[0];
      const inputName = split[1];
      const field = fields.get(key);
      const value = field !== undefined ? this.overrideValue(field, raw) : raw;
      const parts = nodes.get(nodeId);
      const json = `${JSON.stringify(inputName)}:${JSON.stringify(value)}`;
      if (parts === undefined) {
        nodes.set(nodeId, [json]);
      } else {
        parts.push(json);
      }
    });
    const result: string[] = [];
    nodes.forEach((parts: string[], nodeId: string) => {
      result.push(`${JSON.stringify(nodeId)}:{${parts.join(',')}}`);
    });
    return `{${result.join(',')}}`;
  }

  /** 输入框里保存原文，提交前再按字段类型还原成 JSON 数字/布尔/null。 */
  private overrideValue(
    field: WorkflowFieldView,
    raw: string | number | boolean | null,
  ): string | number | boolean | null {
    if (field.type !== 'INT' && field.type !== 'FLOAT') {
      if (field.type === 'BOOLEAN' && typeof raw !== 'boolean') {
        return raw === 'true';
      }
      return raw;
    }
    if (typeof raw === 'boolean') {
      return raw;
    }
    const text = raw === null ? '' : `${raw}`.trim();
    if (text.length === 0) {
      return null;
    }
    const numberValue = Number(text);
    return Number.isNaN(numberValue) ? text : numberValue;
  }

  private editKey(nodeId: number, inputName: string): string {
    return `${nodeId}\u0000${inputName}`;
  }

  private isImageField(field: WorkflowFieldView): boolean {
    return field.nodeType === 'LoadImage' && field.inputName === 'image';
  }

  private selectOptions(field: WorkflowFieldView): SelectOption[] {
    return field.options.map((option: string) => {
      const item: SelectOption = { value: option };
      return item;
    });
  }

  private async pickAndUpload(field: WorkflowFieldView): Promise<void> {
    if (this.uploadingField.length > 0) {
      return;
    }
    const base = this.normalizedBaseUrl();
    if (!this.running || base.length === 0) {
      this.setUploadError(field, '未连接 ComfyUI');
      return;
    }

    const key = this.editKey(field.nodeId, field.inputName);
    this.uploadingField = key;
    this.uploadErrorKey = '';
    this.uploadError = '';
    this.uploadNoticeKey = '';
    this.uploadNotice = '';
    this.pickerActive = true;
    this.skipNextAppear = true;
    try {
      const picked: PickedImage | null = await this.imageUploader.pick();
      if (picked === null) {
        return;
      }
      const result = await this.imageUploader.upload(base, picked);
      if (!result.ok) {
        this.setUploadError(field, result.message);
        return;
      }
      this.setFieldText(field, result.reference);
      this.previewUris.set(key, picked.uri);
      this.previewVersion++;
      this.uploadNoticeKey = key;
      this.uploadNotice = result.message;
    } catch (err) {
      this.setUploadError(field, this.errorText(err));
    } finally {
      this.pickerActive = false;
      this.uploadingField = '';
    }
  }

  private setUploadError(field: WorkflowFieldView, message: string): void {
    this.uploadErrorKey = this.editKey(field.nodeId, field.inputName);
    this.uploadError = message;
    this.uploadNoticeKey = '';
    this.uploadNotice = '';
  }

  private errorText(err: object): string {
    const message = (err as Error).message;
    return message !== undefined && message.length > 0 ? message : '操作失败';
  }

  private resetUploadState(): void {
    this.uploadingField = '';
    this.uploadErrorKey = '';
    this.uploadError = '';
    this.uploadNoticeKey = '';
    this.uploadNotice = '';
  }

  private fieldText(field: WorkflowFieldView): string {
    const version = this.editTick;
    if (version < 0) {
      return '';
    }
    const edited = this.edits.get(this.editKey(field.nodeId, field.inputName));
    if (edited !== undefined) {
      return edited === null ? '' : `${edited}`;
    }
    return field.valueText;
  }

  private fieldBool(field: WorkflowFieldView): boolean {
    const version = this.editTick;
    if (version < 0) {
      return false;
    }
    const edited = this.edits.get(this.editKey(field.nodeId, field.inputName));
    if (typeof edited === 'boolean') {
      return edited;
    }
    return field.value === true;
  }

  private setFieldText(field: WorkflowFieldView, text: string): void {
    // TextInput 首次渲染也会触发 onChange；只有值真的变了才算用户编辑，
    // 否则每个字段都会被记成 override，切到会把它变成连线的节点图时就会挡住提交。 
    const key = this.editKey(field.nodeId, field.inputName);
    const current = this.fieldText(field);
    if (text === current) {
      return;
    }
    // 保存原文而不是每个按键都 Number()，否则输入 "0." 会被回写成 "0"， 
    // 小数点根本打不出来。提交前由 overrideValue() 按字段类型转换。 
    this.edits.set(key, text);
    this.editTick++;
    this.scheduleEditRefresh();
  }

  private setFieldBool(field: WorkflowFieldView, value: boolean): void {
    const key = this.editKey(field.nodeId, field.inputName);
    if (value === this.fieldBool(field)) {
      return;
    }
    this.edits.set(key, value);
    this.editTick++;
    this.scheduleEditRefresh();
  }

  /**
   * 节点图切换或节点定义补齐后，字段的可编辑性会变：
   * - 字段现在由连线驱动，之前的值不能再当 override； 
   * - 字段已经不存在（节点定义缺失等），override 没有意义。 
   * 不清理的话，一次无意的首次渲染编辑会永久挡住提交。 
   */
  private pruneEdits(): void {
    const fields = new Map<string, WorkflowFieldView>();
    this.view.nodes.forEach((node: WorkflowNodeView) => {
      node.fields.forEach((field: WorkflowFieldView) => {
        fields.set(this.editKey(field.nodeId, field.inputName), field);
      });
    });
    const stale: string[] = [];
    this.edits.forEach((value: string | number | boolean | null, key: string) => {
      const field = fields.get(key);
      if (field === undefined || field.linked || !field.widget) {
        stale.push(key);
      }
    });
    if (stale.length === 0) {
      return;
    }
    stale.forEach((key: string) => this.edits.delete(key));
    this.editTick++;
  }

  /** 编辑后稍等一会儿再重算 canSubmit/problems，避免每个按键都重建整张字段视图。 */
  private scheduleEditRefresh(): void {
    if (this.editRefreshTimer !== -1) {
      clearTimeout(this.editRefreshTimer);
    }
    this.editRefreshTimer = setTimeout(() => {
      this.editRefreshTimer = -1;
      this.refreshView();
    }, 250);
  }

  private cancelEditRefresh(): void {
    if (this.editRefreshTimer !== -1) {
      clearTimeout(this.editRefreshTimer);
      this.editRefreshTimer = -1;
    }
  }

  private togglePin(node: WorkflowNodeView): void {
    const index = this.pinnedIds.indexOf(node.id);
    if (index >= 0) {
      this.pinnedIds = this.pinnedIds.filter((id: number) => id !== node.id);
    } else {
      this.pinnedIds = [...this.pinnedIds, node.id];
      // 新置顶的节点默认展开，和 Android 的 togglePin 行为一致。 
      if (node.reachable) {
        if (!this.expandedSeed) {
          this.expandedIds = this.autoExpandedIds();
          this.expandedSeed = true;
        }
        if (!this.expandedIds.includes(node.id)) {
          this.expandedIds = [...this.expandedIds, node.id];
        }
      }
    }
    this.pinnedStore.save(this.selectedPath, this.pinnedIds);
    this.refreshView(true);
  }

  private isWorkflowPinned(path: string): boolean {
    return this.pinnedWorkflowPaths.includes(path);
  }

  private orderedCatalogEntries(): CatalogEntry[] {
    const entries: CatalogEntry[] = (this.catalog.entries ?? []).slice();
    entries.sort((a: CatalogEntry, b: CatalogEntry) => {
      const aPinned = this.isWorkflowPinned(a.path);
      const bPinned = this.isWorkflowPinned(b.path);
      if (aPinned !== bPinned) {
        return aPinned ? -1 : 1;
      }
      return a.path.toLowerCase().localeCompare(b.path.toLowerCase());
    });
    return entries;
  }

  private toggleWorkflowPin(path: string): void {
    const wasPinned = this.isWorkflowPinned(path);
    const next = this.pinnedWorkflowPaths.filter((item: string) => item !== path);
    if (!wasPinned) {
      next.push(path);
    }
    this.pinnedWorkflowPaths = next;
    this.pinnedWorkflowStore.save(next).catch((err: Error) => {
      this.log(`工作流置顶保存失败：${err.message}`);
    });
    if (!wasPinned) {
      setTimeout((): void => {
        this.catalogScroller.scrollEdge(Edge.Top);
      }, 0);
    }
  }

  /** 启用中的节点图排在前面，未启用的整组后移；组内顺序仍跟主机一致。 */
  private parentGraphs(): WorkflowGraphView[] {
    const parents = this.view.graphs.filter((graph: WorkflowGraphView) => graph.outputNodeIds.length > 0);
    return parents.filter((graph: WorkflowGraphView) => graph.selected)
      .concat(parents.filter((graph: WorkflowGraphView) => !graph.selected));
  }

  private childGraphs(parentId: string): WorkflowGraphView[] {
    return this.view.graphs.filter((graph: WorkflowGraphView) => graph.parentId === parentId);
  }

  private toggleGraph(graph: WorkflowGraphView): void {
    const index = this.graphIds.indexOf(graph.id);
    if (index >= 0) {
      let next = this.graphIds.filter((id: string) => id !== graph.id);
      const childIds = this.childGraphs(graph.id).map((child: WorkflowGraphView) => child.id);
      next = next.filter((id: string) => !childIds.includes(id));
      this.graphIds = next;
    } else {
      this.graphIds = [...this.graphIds, graph.id];
    }
    this.graphsInitialized = true;
    // 切换节点图后回到自动展开，和 Android toggleGraph 里 expandedOverride = null 一致。 
    this.expandedSeed = false;
    this.expandedIds = [];
    this.refreshView(true);
  }

  /**
   * 节点开关，对应 Android 的 toggleBypass。 
   *
   * 已关闭（文件里是静音/旁路，或本次会话关过）→ 立刻启用，不需要探测：
   * 打开一个节点不会让工作流更坏。还开着 → 先让共享层试关一次，只有它判定 
   * 会破坏工作流时才弹确认框，普通节点直接关。 
   */
  private toggleNodeBypass(node: WorkflowNodeView): void {
    // 开关在展开区里：关闭后节点会变成不可达，自动展开会把它收起来， 
    // 所以先把手动展开固定住，保证用户还能原地再点回来。 
    if (!this.expandedSeed) {
      this.expandedIds = this.autoExpandedIds();
      this.expandedSeed = true;
    }
    if (!this.expandedIds.includes(node.id)) {
      this.expandedIds = [...this.expandedIds, node.id];
    }
    if (!node.enabled) {
      this.applyNodeBypass(node.id, false);
      return;
    }
    const impact = this.probeNodeDisable(node.id);
    if (impact === null) {
      return;
    }
    if (!impact.ok) {
      const message = impact.message !== undefined && impact.message.length > 0
        ? impact.message : '无法确认关闭该节点的影响';
      promptAction.showToast({ message: message, duration: 1600 });
      return;
    }
    if (impact.breaking) {
      this.bypassConfirmNodeId = node.id;
      this.bypassConfirmReason = impact.reason;
      return;
    }
    this.applyNodeBypass(node.id, true);
  }

  /** 同步问一次共享层：关掉这个节点会不会让工作流构建失败或没有可执行输出。 */
  private probeNodeDisable(nodeId: number): DisableImpactResponse | null {
    try {
      return JSON.parse(
        this.bridge.workflow(this.requestJson('disable_impact', '', nodeId)),
      ) as DisableImpactResponse;
    } catch (err) {
      this.log(`关闭影响探测失败：${(err as Error).message}`);
      return null;
    }
  }

  /**
   * 落地一次开关：两份集合互斥且关闭优先，然后只重取视图。 
   *
   * 这里不排序、不滚动：节点顺序由共享层按工作流原文给出，关掉一个节点 
   * 不能让它跳到列表底部（`orderReachable` 在共享层已经处理好了）。 
   */
  private applyNodeBypass(nodeId: number, bypassed: boolean): void {
    if (bypassed) {
      if (!this.bypassedIds.includes(nodeId)) {
        this.bypassedIds = [...this.bypassedIds, nodeId];
      }
      this.enabledIds = this.enabledIds.filter((id: number) => id !== nodeId);
    } else {
      if (!this.enabledIds.includes(nodeId)) {
        this.enabledIds = [...this.enabledIds, nodeId];
      }
      this.bypassedIds = this.bypassedIds.filter((id: number) => id !== nodeId);
    }
    this.bypassConfirmNodeId = -1;
    this.bypassConfirmReason = '';
    this.refreshView();
  }

  /** 确认框里的「仍要关闭」：共享层已经给过原因，直接关。 */
  private confirmNodeBypass(): void {
    const nodeId = this.bypassConfirmNodeId;
    if (nodeId < 0) {
      return;
    }
    this.applyNodeBypass(nodeId, true);
  }

  /** 确认框里的「取消」：什么都不改，节点仍然是启用状态。 */
  private cancelNodeBypass(): void {
    this.bypassConfirmNodeId = -1;
    this.bypassConfirmReason = '';
  }

  /** 节点被关闭后，卡片里的字段只读：控件还在，但不再接受输入。 */
  private nodeFieldEnabled(nodeId: number): boolean {
    for (let i = 0; i < this.view.nodes.length; i++) {
      if (this.view.nodes[i].id === nodeId) {
        return this.view.nodes[i].enabled;
      }
    }
    return true;
  }

  /** 图选择器药丸上的「已启用 / 全部输出图」计数。 */
  private enabledGraphCount(): number {
    let count = 0;
    this.parentGraphs().forEach((graph: WorkflowGraphView) => {
      if (graph.selected) {
        count++;
      }
    });
    return count;
  }

  /** 输入页标题上的字段总数，对应 Android 的 fields.nodes.sumOf { it.fields.size }。 */
  private fieldCount(): number {
    let total = 0;
    this.view.nodes.forEach((node: WorkflowNodeView) => {
      total += node.fields.length;
    });
    return total;
  }

  private reachableNodeCount(): number {
    let total = 0;
    this.view.nodes.forEach((node: WorkflowNodeView) => {
      if (node.reachable) {
        total++;
      }
    });
    return total;
  }

  /** 卡片副标题：有备注时保留主机原名，否则显示输入数量（同 Android NodeInputCard）。 */
  private nodeSubtitle(node: WorkflowNodeView): string {
    if (this.noteOf(node.id).length > 0) {
      return `${node.type} · ${node.displayName}`;
    }
    return `${node.type} · ${node.fields.length} 个输入`;
  }

  /** 自动展开：可达且（置顶或带公共可编辑字段），对应 Android 的 autoExpanded。 */
  private autoExpandedIds(): number[] {
    const result: number[] = [];
    this.view.nodes.forEach((node: WorkflowNodeView) => {
      if (!node.reachable) {
        return;
      }
      if (node.pinned) {
        result.push(node.id);
        return;
      }
      const editable = node.fields.some((field: WorkflowFieldView) =>
        field.common && field.widget && !field.linked);
      if (editable) {
        result.push(node.id);
      }
    });
    return result;
  }

  private isNodeExpanded(node: WorkflowNodeView): boolean {
    if (this.expandedSeed) {
      return this.expandedIds.includes(node.id);
    }
    return this.autoExpandedIds().includes(node.id);
  }

  private toggleNodeExpanded(node: WorkflowNodeView): void {
    if (!this.expandedSeed) {
      this.expandedIds = this.autoExpandedIds();
      this.expandedSeed = true;
    }
    if (this.expandedIds.includes(node.id)) {
      this.expandedIds = this.expandedIds.filter((id: number) => id !== node.id);
    } else {
      this.expandedIds = [...this.expandedIds, node.id];
    }
  }

  /** 节点所属节点图；不属于任何图（rest）时返回 null。 */
  private graphOfNode(node: WorkflowNodeView): WorkflowGraphView | null {
    for (let i = 0; i < this.view.graphs.length; i++) {
      const graph = this.view.graphs[i];
      if (graph.nodeIds.includes(node.id)) {
        return graph;
      }
    }
    return null;
  }

  private graphKeyOfNode(node: WorkflowNodeView): string {
    const graph = this.graphOfNode(node);
    return graph === null ? 'rest' : graph.id;
  }

  /** 列表分组标题：每个节点图前插一行，切换图组时才显示。 */
  private isSectionStart(index: number): boolean {
    const nodes = this.view.nodes;
    if (index <= 0) {
      return true;
    }
    return this.graphKeyOfNode(nodes[index]) !== this.graphKeyOfNode(nodes[index - 1]);
  }

  private graphTitleOfNode(node: WorkflowNodeView): string {
    const graph = this.graphOfNode(node);
    if (graph === null) {
      return '其他节点';
    }
    if (graph.parentId.length > 0) {
      return `└ ${graph.title}`;
    }
    return graph.title;
  }

  private graphMetaOfNode(node: WorkflowNodeView): string {
    const graph = this.graphOfNode(node);
    if (graph === null) {
      let count = 0;
      this.view.nodes.forEach((item: WorkflowNodeView) => {
        if (this.graphOfNode(item) === null) {
          count++;
        }
      });
      return `${count} 个节点 · 未归属节点图`;
    }
    let submit = 0;
    this.view.nodes.forEach((item: WorkflowNodeView) => {
      if (graph.nodeIds.includes(item.id) && item.reachable) {
        submit++;
      }
    });
    if (graph.selected) {
      return `${graph.nodeIds.length} 个节点 · ${submit} 个会提交`;
    }
    return `${graph.nodeIds.length} 个节点 · 未启用`;
  }

  /** 任务状态文案，和 Android taskStateLabel 一一对应。 */
  private taskStateName(state: string): string {
    if (state === 'Submitting') {
      return '提交中';
    }
    if (state === 'Queued') {
      return '排队中';
    }
    if (state === 'Running') {
      return '执行中';
    }
    if (state === 'Completed') {
      return '已完成';
    }
    if (state === 'Failed') {
      return '失败';
    }
    if (state === 'Cancelled') {
      return '已取消';
    }
    return '状态未知';
  }

  /** 「全部节点」里每个节点的状态标签，同 Android 底部弹层。 */
  private nodeStateLabel(node: WorkflowNodeView): string {
    if (node.missing) {
      return '缺少定义';
    }
    // 生效状态优先于可达性：被关掉的节点即使原本会提交，也应该显示“已关闭”。 
    if (!node.enabled) {
      return '已关闭';
    }
    if (!node.reachable) {
      return '不会提交';
    }
    return `${node.fields.length} 个输入`;
  }

  /** 节点备注覆盖显示名；副标题仍保留主机原名，避免认错节点。 */
  private noteOf(nodeId: number): string {
    const version = this.nodeNotesVersion;
    if (version < 0) {
      return '';
    }
    return this.notes.get(nodeId) ?? '';
  }

  private nodeTitle(node: WorkflowNodeView): string {
    const note = this.noteOf(node.id);
    return note.length > 0 ? note : node.displayName;
  }

  private openNoteEditor(node: WorkflowNodeView): void {
    this.noteEditorId = node.id;
    this.noteDraft = this.noteOf(node.id);
  }

  private persistNotes(): void {
    this.nodeNotesVersion++;
    this.noteStore.save(this.selectedPath, this.notes)
      .catch((err: Error) => this.log(`保存备注失败：${err.message}`));
  }

  private previewUri(field: WorkflowFieldView): string {
    const version = this.previewVersion;
    if (version < 0) {
      return '';
    }
    return this.previewUris.get(this.editKey(field.nodeId, field.inputName)) ?? '';
  }

  private saveNote(): void {
    const id = this.noteEditorId;
    if (id < 0) {
      return;
    }
    const text = this.noteDraft.replace(/[\t\n\r]+/g, ' ').trim();
    if (text.length === 0) {
      this.notes.delete(id);
    } else {
      this.notes.set(id, text);
    }
    this.persistNotes();
    this.noteEditorId = -1;
    this.noteDraft = '';
  }

  private clearNote(): void {
    const id = this.noteEditorId;
    if (id < 0) {
      return;
    }
    this.notes.delete(id);
    this.persistNotes();
    this.noteEditorId = -1;
    this.noteDraft = '';
  }

  private matchesNode(node: WorkflowNodeView, query: string): boolean {
    const q = query.trim().toLowerCase();
    if (q.length === 0) {
      return true;
    }
    const hay: string[] = [`#${node.id}`, node.displayName, node.type, this.noteOf(node.id)];
    node.fields.forEach((field: WorkflowFieldView) => hay.push(field.type));
    return hay.some((part: string) => part.toLowerCase().indexOf(q) >= 0);
  }

  private nodeHasTrait(node: WorkflowNodeView, trait: number): boolean {
    if (trait === 3) {
      return node.needsInput;
    }
    return node.fields.some((field: WorkflowFieldView) => {
      const type = field.type.toUpperCase();
      if (trait === 0) {
        return node.type === 'LoadImage' || type.indexOf('IMAGE') >= 0;
      }
      if (trait === 1) {
        return type === 'STRING';
      }
      return type === 'INT' || type === 'FLOAT';
    });
  }

  private filteredNodes(): WorkflowNodeView[] {
    const version = this.nodeNotesVersion;
    if (version < 0) {
      return [];
    }
    const traits = this.traitFilter;
    return this.view.nodes.filter((node: WorkflowNodeView) => {
      if (!this.matchesNode(node, this.nodeQuery)) {
        return false;
      }
      if (traits.length === 0) {
        return true;
      }
      return traits.some((trait: number) => this.nodeHasTrait(node, trait));
    });
  }

  private toggleTrait(trait: number): void {
    if (this.traitFilter.includes(trait)) {
      this.traitFilter = this.traitFilter.filter((item: number) => item !== trait);
    } else {
      this.traitFilter = [...this.traitFilter, trait];
    }
  }

  private submitWorkflow(): void {
    if (!this.running) {
      this.submitNotice = '请先连接 ComfyUI';
      return;
    }
    this.submitting = true;
    this.submitNotice = '';
    this.notice = '';
    try {
      // 每次提交一个唯一短 token，共享层会把它追加到 SaveImage 的 filename_prefix， 
      // 避免工作流前缀（如 %date:...%）不被 ComfyUI 识别时反复覆盖同一份产物。 
      const runToken = `${Date.now().toString(36)}${(this.submitNonce++).toString(36)}`;
      const response = JSON.parse(this.bridge.workflow(this.requestJson('prompt', runToken))) as PromptResponse;
      if (!response.ok || response.prompt === null) {
        const problems = response.problems !== undefined ? response.problems : [];
        const lines = problems.map((problem: BuildProblemView) => problem.display);
        this.submitNotice = lines.length > 0 ? lines.join('\n') : (response.message ?? '不能提交');
        return;
      }
      // 这里只说明“请求发出去了”。服务器接受后会回 submit_accepted， 
      // 校验失败会回 submit_rejected；不能在这一步就显示“已提交”。 
      const warnings = response.warnings !== undefined ? response.warnings : [];
      if (warnings.length > 0) {
        // Same as Android: warnings do not block, but ask before submitting.
        this.confirmPrompt = JSON.stringify(response.prompt);
        this.confirmWarnings = warnings;
        this.confirmOpen = true;
        this.submitNotice = '';
        return;
      }
      this.submitNotice = warnings.length > 0
        ? `正在提交，等待服务器接受…（${warnings.join('；')}）`
        : '正在提交，等待服务器接受…';
      this.bridge.submit(JSON.stringify(response.prompt), this.view.name, this.selectedPath);
      this.detailTab = 1;
    } catch (err) {
      this.submitNotice = `提交失败：${(err as Error).message}`;
    } finally {
      this.submitting = false;
    }
  }

  private confirmSubmit(): void {
    const prompt = this.confirmPrompt;
    this.confirmOpen = false;
    this.confirmWarnings = [];
    this.confirmPrompt = '';
    if (prompt.length === 0) {
      return;
    }
    this.submitNotice = '正在提交，等待服务器接受…';
    this.bridge.submit(prompt, this.view.name, this.selectedPath);
    this.detailTab = 1;
  }

  private cancelSubmitConfirm(): void {
    this.confirmOpen = false;
    this.confirmWarnings = [];
    this.confirmPrompt = '';
    this.submitNotice = '';
  }

  private noticeColor(): string {
    if (this.notice.startsWith('提交被拒绝')) {
      return this.c().err;
    }
    if (this.notice.startsWith('已提交')) {
      return this.c().ok;
    }
    return this.c().warn;
  }

  /** 按钮禁用时，把“为什么不能提交”放在按钮正上方，避免只看到灰按钮没有提示。 */
  private blockReason(): string {
    // 原因由共享决策层生成，ArkTS 只负责渲染。 
    return this.view.blockReason.length > 0
      ? this.view.blockReason
      : '不可提交：工作流还没有就绪';
  }

  // ---- 可关闭的错误提示 ----

  /** 错误块的 key：`taskId|errorText`；工作流级错误用 `selectedPath#来源` 当作用域 id。 */
  private errorSurfaceKey(scopeId: string, text: string): string {
    return `${scopeId}|${text}`;
  }

  private workflowScopeId(surface: string): string {
    // 工作流级错误没有 taskId，用「工作流 + 错误来源」当作用域，避免不同错误文案
    // 恰好相同时互相顶掉对方的关闭状态。 
    return `${this.selectedPath}#${surface}`;
  }

  /** 构建失败的完整文案（problems 每条一行），key 跟着内容变。 */
  private buildErrorText(): string {
    return this.view.problems.map((problem: BuildProblemView) => problem.display).join('\n');
  }

  private isErrorDismissed(scopeId: string, text: string): boolean {
    return text.length > 0 && this.dismissedErrorKey === this.errorSurfaceKey(scopeId, text);
  }

  /**
   * 关掉一条错误提示：只影响展示，不改错误来源。key 里带错误原文， 
   * 下一条不同的错误依然会显示；按工作流持久化，重开工作流仍然保持关闭。 
   */
  private dismissError(scopeId: string, text: string): void {
    if (text.length === 0) {
      return;
    }
    const key = this.errorSurfaceKey(scopeId, text);
    this.dismissedErrorKey = key;
    if (this.selectedPath.length === 0) {
      return;
    }
    this.dismissedStore.save(this.selectedPath, key)
      .catch((err: Error) => this.log(`错误关闭状态保存失败：${err.message}`));
  }

  private openArtifacts(task: BridgeTask): void {
    this.artifactTask = task;
    this.artifactOpen = true;
    this.previewOpen = false;
    this.previewError = false;
    this.downloadNotice = '';
    this.downloadFailedKey = '';
  }

  private closeArtifacts(): void {
    this.artifactOpen = false;
    this.previewOpen = false;
    // 下载可能还在后台跑；保留 downloadingKey，重新打开时不会重复点同一份。 
    this.downloadNotice = '';
    this.downloadFailedKey = '';
  }

  private artifactUrl(output: BridgeOutput): string {
    const base = this.normalizedBaseUrl();
    if (base.length === 0 || output.path.length === 0) {
      return '';
    }
    return base + output.path;
  }

  private artifactKey(output: BridgeOutput): string {
    return `${output.type}\u0000${output.subfolder}\u0000${output.filename}`;
  }

  private download(output: BridgeOutput): void {
    const context = this.uiContext;
    if (context === null) {
      this.downloadNotice = '没有 Ability 上下文，不能保存产物';
      return;
    }
    const key: string = this.artifactKey(output);
    this.downloadingKey = key;
    this.downloadNotice = '';
    this.downloadFailedKey = '';
    this.downloader.download(context, this.normalizedBaseUrl(), output).then((result: ArtifactDownloadResult) => {
      this.downloadingKey = '';
      if (result.ok || result.cancelled === true) {
        this.downloadNotice = result.message;
      } else {
        this.downloadNotice = `保存失败：${result.message}`;
        // 行内用 err 文案 + 一个 ghost 重试，和 Android DownloadStatusLine 一致。 
        this.downloadFailedKey = key;
      }
      this.log(result.message);
    }).catch((err: Error) => {
      this.downloadingKey = '';
      this.downloadNotice = `保存失败：${err.message}`;
      this.downloadFailedKey = key;
    });
  }

  private isGalleryOutput(output: BridgeOutput): boolean {
    return output.image || output.video;
  }

  private artifactActionLabel(output: BridgeOutput): string {
    return this.isGalleryOutput(output) ? '保存到相册' : '下载';
  }

  private artifactActionBusyLabel(output: BridgeOutput): string {
    return this.isGalleryOutput(output) ? '保存中…' : '下载中…';
  }

  private downloadNoticeColor(): string {
    if (this.downloadNotice.startsWith('已保存')) {
      return this.c().ok;
    }
    if (this.downloadNotice.startsWith('已取消')) {
      return this.c().textMuted;
    }
    return this.c().err;
  }

  private catalogError(): string {
    return this.catalog.error !== undefined ? this.catalog.error : '';
  }

  private taskLabel(task: BridgeTask): string {
    return task.name !== null && task.name.length > 0 ? task.name : (task.promptId ?? task.id);
  }

  // ---- 任务页：分组、配色与卡片文案（对应 Android TaskList.kt） ----

  /**
   * 任务按最近更新时间倒序，和 Android `sortedByDescending { it.createdAtMs }` 一致。 
   * 鸿蒙侧桥接只给 updatedAtMs（没有创建时间），用更新时间排同一目的。 
   */
  private tasksNewestFirst(list: BridgeTask[]): BridgeTask[] {
    const sorted: BridgeTask[] = list.slice();
    sorted.sort((a: BridgeTask, b: BridgeTask) => b.updatedAtMs - a.updatedAtMs);
    return sorted;
  }

  /** 进行中：提交中 + 执行中，对应 Android TaskState.Submitting / Running。 */
  private inProgressTasks(): BridgeTask[] {
    return this.tasksNewestFirst(this.tasks.filter((task: BridgeTask) =>
      task.state === 'Submitting' || task.state === 'Running'));
  }

  /** 排队中：对应 Android TaskState.Queued。 */
  private queuedTasks(): BridgeTask[] {
    return this.tasksNewestFirst(this.tasks.filter((task: BridgeTask) => task.state === 'Queued'));
  }

  /** 已结束：完成 / 失败 / 取消 / 未知；状态胶囊仍分别显示具体状态。 */
  private finishedTasks(): BridgeTask[] {
    return this.tasksNewestFirst(this.tasks.filter((task: BridgeTask) =>
      task.state !== 'Submitting' && task.state !== 'Running' && task.state !== 'Queued'));
  }

  /** 状态色：Android PillTone（Ok / Warn / Err / Neutral）到调色板的映射。 */
  private taskToneColor(state: string): string {
    if (state === 'Running') {
      return this.c().ok;
    }
    if (state === 'Submitting' || state === 'Queued') {
      return this.c().warn;
    }
    if (state === 'Failed') {
      return this.c().err;
    }
    return this.c().textMuted;
  }

  private taskToneBackground(state: string): string {
    if (state === 'Running') {
      return this.c().okSoft;
    }
    if (state === 'Submitting' || state === 'Queued') {
      return this.c().warnSoft;
    }
    if (state === 'Failed') {
      return this.c().errSoft;
    }
    return this.c().cardStrong;
  }

  /** 卡片左侧 3vp 状态色轨，同 Android TaskCard 的 rail。 */
  private taskRailColor(state: string): string {
    if (state === 'Running') {
      return this.c().ok;
    }
    if (state === 'Submitting' || state === 'Queued') {
      return this.c().warn;
    }
    if (state === 'Failed') {
      return this.c().err;
    }
    return this.c().textDim;
  }

  /** Android 只在有进度快照时画进度条；这里用状态 + percent 判断。 */
  private taskShowsProgress(task: BridgeTask): boolean {
    return task.state === 'Running' || task.state === 'Submitting' || task.percent > 0;
  }

  /** 卡片上的产物摘要行，文案同 Android：「输出：a、b」。 */
  private taskOutputLine(task: BridgeTask): string {
    const names: string[] = [];
    task.outputRefs.forEach((output: BridgeOutput) => {
      names.push(output.displayName.length > 0 ? output.displayName : output.filename);
    });
    return `输出：${names.join('、')}`;
  }

  /** 卡片缩略图：最多 3 张可访问的图片产物，复用 artifactUrl 拼装。 */
  private taskThumbnails(task: BridgeTask): BridgeOutput[] {
    const images: BridgeOutput[] = [];
    task.outputRefs.forEach((output: BridgeOutput) => {
      if (images.length < 3 && output.image && this.artifactUrl(output).length > 0) {
        images.push(output);
      }
    });
    return images;
  }

  /** ForEach key：状态、进度、产物和错误变化时重建卡片。 */
  private taskCardKey(task: BridgeTask): string {
    const error: string = task.error !== null ? task.error : '';
    return `${task.id}:${task.state}:${task.percent}:${task.outputRefs.length}:${error}`;
  }

  /** 任务页「刷新」：让共享层重新对账一次，不重建会话。 */
  private refreshTaskState(): void {
    if (!this.running) {
      return;
    }
    this.bridge.reconnect();
  }

  /** 关于页：点「项目作者」时复制作者名，并弹一个短暂提示。 */
  private copyAuthor(): void {
    try {
      const data: pasteboard.PasteData =
        pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, 'Fly-Noz');
      pasteboard.getSystemPasteboard().setDataSync(data);
      promptAction.showToast({ message: '已复制作者：Fly-Noz', duration: 1600 });
    } catch (err) {
      promptAction.showToast({ message: this.errorText(err), duration: 1600 });
    }
  }

  /** 方形小图标按钮：备注 / 置顶 / 展开，对应 Android 的 NodeIconButton。 */
  @Builder
  NodeIconButton(glyph: string, active: boolean, pressKey: string, action: () => void) {
    Row() {
      Text(glyph)
        .fontSize(13)
        .fontColor(active ? this.c().accent : this.c().textMuted)
    }
    .width(30)
    .height(30)
    .justifyContent(FlexAlign.Center)
    .borderRadius(10)
    .backgroundColor(active ? this.c().accentSoft : Color.Transparent)
    .border({ width: 1, color: active ? this.c().accentBorder : this.c().border })
    .scale(this.pressScaleOptions(pressKey, 0.9))
    .onTouch((event: TouchEvent) => this.onPressTouch(event, pressKey))
    .onClick(() => action())
  }

  /** 圆角状态胶囊，对应 Android SpectrumPill。 */
  @Builder
  StatusTag(label: string, color: string, background: string) {
    Row({ space: 5 }) {
      Row()
        .width(5)
        .height(5)
        .borderRadius(3)
        .backgroundColor(color)
      Text(label)
        .fontSize(10)
        .fontColor(color)
        .maxLines(1)
    }
    .padding({ left: 8, right: 8, top: 3, bottom: 3 })
    .borderRadius(999)
    .backgroundColor(background)
  }

  /** 筛选 chip，对应 Android SpectrumChip。 */
  @Builder
  Chip(label: string, selected: boolean, action: () => void) {
    Text(label)
      .fontSize(11.5)
      .fontWeight(selected ? FontWeight.Medium : FontWeight.Normal)
      .fontColor(selected ? this.c().accentInk : this.c().textMuted)
      .padding({ left: 12, right: 12, top: 7, bottom: 7 })
      .borderRadius(999)
      .backgroundColor(selected ? this.c().accent : this.c().card)
      .border({ width: 1, color: selected ? this.c().accent : this.c().border })
      .scale(this.pressScaleOptions(`chip-${label}`))
      .onTouch((event: TouchEvent) => this.onPressTouch(event, `chip-${label}`))
      .onClick(() => action())
  }

  /** 节点卡片，对应 Android NodeInputCard：标题行 + 方形按钮 + 展开的字段区。 */
  @Builder
  NodeCard(node: WorkflowNodeView) {
    Column() {
      Row({ space: 6 }) {
        Column({ space: 2 }) {
          Row() {
            Text(`#${node.id}`)
              .fontSize(11)
              .fontFamily('monospace')
              .fontColor(this.c().textDim)
              .padding({ right: 6 })
            Text(this.nodeTitle(node))
              .fontSize(13.5)
              .fontWeight(FontWeight.Bold)
              .fontColor(node.enabled ? this.c().text : this.c().textDim)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .layoutWeight(1)
          }
          .width('100%')
          .alignItems(VerticalAlign.Bottom)
          Text(this.nodeSubtitle(node))
            .fontSize(9.5)
            .fontFamily('monospace')
            .fontColor(this.c().textDim)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .onClick(() => this.toggleNodeExpanded(node))

        if (node.needsInput) {
          this.StatusTag('待填写', this.c().err, this.c().errSoft)
        }
        this.NodeIconButton('✎', this.noteOf(node.id).length > 0, `note-${node.id}`,
          () => this.openNoteEditor(node))
        this.NodeIconButton('⇧', node.pinned, `pin-${node.id}`, () => this.togglePin(node))
        this.NodeIconButton(this.isNodeExpanded(node) ? '▴' : '▾', false, `expand-${node.id}`,
          () => this.toggleNodeExpanded(node))
      }
      .width('100%')
      .padding({ left: 13, right: 9, top: 10, bottom: 10 })
      .alignItems(VerticalAlign.Center)

      if (node.missing) {
        if (this.classPending(node.type)) {
          // 定义还在路上：不要说成“服务端没有”，否则首帧会误报整个工作流缺节点。 
          Text('正在加载该节点的定义…')
            .fontSize(11)
            .lineHeight(16)
            .fontColor(this.c().textDim)
            .width('100%')
            .padding({ left: 13, right: 13, top: 2, bottom: 2 })
        } else {
          Text('服务端没有该节点的定义，不能调参；提交会被拒绝。')
            .fontSize(11)
            .lineHeight(16)
            .fontColor(this.c().err)
            .width('100%')
            .padding({ left: 13, right: 13, top: 2, bottom: 2 })
        }
      } else if (!node.enabled) {
        // 关闭状态优先解释：即使节点本身不可达，用户先要知道“是我关的”。 
        Text('已关闭，提交时会跳过该节点。')
          .fontSize(11)
          .lineHeight(16)
          .fontColor(this.c().textMuted)
          .width('100%')
          .padding({ left: 13, right: 13, top: 2, bottom: 2 })
      } else if (!node.reachable) {
        Text('未连接到输出节点，不会被提交。')
          .fontSize(11)
          .lineHeight(16)
          .fontColor(this.c().textDim)
          .width('100%')
          .padding({ left: 13, right: 13, top: 2, bottom: 2 })
      }

      if (this.isNodeExpanded(node)) {
        Column({ space: 10 }) {
          // 关闭的节点整块字段降透明度，控件同时 enabled(false)：看得见、改不了。 
          Column({ space: 10 }) {
            ForEach(node.fields, (field: WorkflowFieldView, index: number) => {
              this.FieldRow(field, index)
            }, (field: WorkflowFieldView) =>
              `${node.id}:${field.inputName}:${field.linked}:${field.widget}:${field.needsInput}:${field.type}`)
            if (node.fields.length === 0) {
              Text('没有可调参数。')
                .fontSize(11)
                .fontColor(this.c().textDim)
            }
          }
          .alignItems(HorizontalAlign.Start)
          .width('100%')
          .opacity(node.enabled ? 1 : 0.55)

          // 开关放在展开内容最底部、右对齐：收起时整行不渲染，不会误触。 
          this.NodeBypassRow(node)
        }
        .alignItems(HorizontalAlign.Start)
        .width('100%')
        .padding({ left: 13, right: 13, top: 2, bottom: 13 })
        .transition(TransitionEffect.OPACITY
          .combine(TransitionEffect.translate({ y: 6 }))
          .animation({ duration: 220, curve: Curve.EaseOut }))
      }
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
    // 关闭的卡片不再用卡片底色，退回到页面底色：整张卡“沉下去”，但仍保留边框可辨。 
    .backgroundColor(node.enabled ? this.c().card : this.c().background)
    .borderRadius(18)
    .border({ width: 1, color: node.pinned ? this.c().accentBorder : this.c().border })
    .clip(true)
  }

  /**
   * 节点开关行，对应 Android 的 BypassIconButton：左边一行说明，右边 36vp 图标按钮。 
   *
   * 只在展开内容的最底部出现；收起时整行不渲染，滚动列表里不会误触。 
   */
  @Builder
  NodeBypassRow(node: WorkflowNodeView) {
    Row({ space: 8 }) {
      Blank()
      Text(node.enabled ? '关闭该节点' : '启用该节点')
        .fontSize(11)
        .fontColor(node.enabled ? this.c().textDim : this.c().textMuted)
      this.NodeBypassButton(node.enabled, `bypass-${node.id}`, () => this.toggleNodeBypass(node))
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
  }

  /**
   * 开关本体：36vp 点击区 + 纯图元画的电源图标（不引图标库）。 
   *
   * 启用 = 强调色描边 + accentSoft 底色；关闭 = 暗色描边并压到 55% 透明度。 
   * 图标只由「圆角边框的容器 + 一根竖杆」叠出来：全是通用属性（border/
   * borderRadius/backgroundColor），不依赖 Shape 的 fill/stroke（那组 API
   * 在当前兼容的 SDK 12 上会报版本告警）。 
   */
  @Builder
  NodeBypassButton(enabled: boolean, pressKey: string, action: () => void) {
    Stack({ alignContent: Alignment.Center }) {
      Stack({ alignContent: Alignment.Top }) {
        // 圆环：透明填充 + 圆角边框，就是一个空心的电源圆环。 
        Row()
          .width(12)
          .height(12)
          .borderRadius(6)
          .border({ width: 1.8, color: enabled ? this.c().accent : this.c().textDim })
          .margin({ top: 3 })
        // 竖杆从圆环顶部穿出：电源符号的标志，和圆环叠成完整图标。 
        Row()
          .width(1.8)
          .height(9)
          .borderRadius(1)
          .backgroundColor(enabled ? this.c().accent : this.c().textDim)
      }
      .width(16)
      .height(16)
      .opacity(enabled ? 1 : 0.55)
    }
    .width(36)
    .height(36)
    .borderRadius(10)
    .backgroundColor(enabled ? this.c().accentSoft : '#00000000')
    .border({ width: 1, color: enabled ? this.c().accentBorder : this.c().border })
    .scale(this.pressScaleOptions(pressKey, 0.9))
    .onTouch((event: TouchEvent) => this.onPressTouch(event, pressKey))
    .onClick(() => action())
  }

  /** 字段行，对应 Android FieldEditor：标签 + 类型 + 控件 + 必填提示。 */
  @Builder
  FieldRow(field: WorkflowFieldView, index: number) {
    Column({ space: 4 }) {
      Row() {
        Text(field.label.length > 0 ? field.label : field.inputName)
          .fontSize(11)
          .fontColor(this.c().textMuted)
          .layoutWeight(1)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(field.type)
          .fontSize(8.5)
          .fontFamily('monospace')
          .letterSpacing(0.6)
          .fontColor(this.c().textDim)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)

      if (field.linked) {
        Text('由连线提供')
          .fontSize(11)
          .fontColor(this.c().textDim)
      } else if (!field.widget) {
        Text(field.required ? '需要连线；当前未连接，提交会被拒绝。' : '可选连线；当前未连接。')
          .fontSize(11)
          .fontColor(field.required ? this.c().err : this.c().textDim)
      } else if (this.isImageField(field)) {
        this.ImageField(field)
      } else if (field.options.length > 0) {
        this.OptionField(field)
      } else if (field.type === 'BOOLEAN') {
        Toggle({ type: ToggleType.Switch, isOn: this.fieldBool(field) })
          // 节点被关闭后字段只读：外观保留，但不再接受点击。 
          .enabled(this.nodeFieldEnabled(field.nodeId))
          .selectedColor(this.c().accent)
          .switchPointColor(this.c().accentInk)
          .onChange((value: boolean) => this.setFieldBool(field, value))
      } else if (field.type === 'STRING') {
        // 提示词这类长文本用 TextArea：自动换行，超出高度内部滚动。 
        TextArea({ text: this.fieldText(field), placeholder: '' })
          .enabled(this.nodeFieldEnabled(field.nodeId))
          .fontSize(12.5)
          .fontColor(this.c().text)
          .placeholderColor(this.c().textDim)
          .caretColor(this.c().accent)
          .backgroundColor(this.c().card)
          .borderRadius(12)
          .border({ width: 1, color: this.c().border })
          .height(96)
          .width('100%')
          .padding({ left: 12, right: 12, top: 8, bottom: 8 })
          .onChange((value: string) => this.setFieldText(field, value))
      } else {
        TextInput({ text: this.fieldText(field), placeholder: '' })
          .enabled(this.nodeFieldEnabled(field.nodeId))
          .fontSize(12.5)
          .fontFamily('monospace')
          .fontColor(this.c().text)
          .placeholderColor(this.c().textDim)
          .caretColor(this.c().accent)
          .backgroundColor(this.c().card)
          .borderRadius(12)
          .border({ width: 1, color: this.c().border })
          .height(38)
          .width('100%')
          .padding({ left: 12, right: 12 })
          .onChange((value: string) => this.setFieldText(field, value))
      }

      if (field.required && field.widget && !field.linked &&
        field.value === null && this.fieldText(field).length === 0) {
        Text('必填')
          .fontSize(9.5)
          .fontFamily('monospace')
          .fontColor(this.c().err)
      }
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
    .transition(TransitionEffect.OPACITY
      .combine(TransitionEffect.translate({ y: 8 }))
      .animation({ duration: 200, curve: Curve.EaseOut, delay: Math.min(index, 10) * 22 }))
  }

  /** LoadImage 的 image 控件：缩略图 + 上传按钮 + 上传结果提示。 */
  @Builder
  ImageField(field: WorkflowFieldView) {
    Column({ space: 6 }) {
      Row({ space: 10 }) {
        if (this.previewUri(field).length > 0) {
          Image(this.previewUri(field))
            .width(56)
            .height(56)
            .borderRadius(10)
            .objectFit(ImageFit.Cover)
            .border({ width: 1, color: this.c().border })
        }
        this.GhostAction(
          this.uploadingField === this.editKey(field.nodeId, field.inputName)
            ? '上传中…'
            : (this.fieldText(field).length > 0 ? '更换图片' : '从相册选择'),
          this.running && this.uploadingField.length === 0 && this.nodeFieldEnabled(field.nodeId),
          () => {
            this.pickAndUpload(field);
          },
        )
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)

      Text(this.fieldText(field).length > 0 ? `当前：${this.fieldText(field)}` : '尚未选择图片')
        .fontSize(10.5)
        .fontFamily('monospace')
        .fontColor(this.fieldText(field).length > 0 ? this.c().textMuted : this.c().textDim)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
      if (this.uploadNoticeKey === this.editKey(field.nodeId, field.inputName)) {
        Text(this.uploadNotice)
          .fontSize(10.5)
          .fontColor(this.c().ok)
          .width('100%')
      }
      if (this.uploadErrorKey === this.editKey(field.nodeId, field.inputName)) {
        Text(this.uploadError)
          .fontSize(10.5)
          .fontColor(this.c().err)
          .width('100%')
      }
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
  }

  /** 下拉选项字段，对应 Android OptionPicker 的触发器外观。 */
  @Builder
  OptionField(field: WorkflowFieldView) {
    Select(this.selectOptions(field))
      .enabled(this.nodeFieldEnabled(field.nodeId))
      .selected(field.options.indexOf(this.fieldText(field)))
      .value(this.fieldText(field).length > 0 ? this.fieldText(field) : '选择…')
      .font({ size: 11.5, family: 'monospace' })
      .fontColor(this.c().text)
      .backgroundColor(this.c().card)
      .borderRadius(12)
      .border({ width: 1, color: this.c().border })
      .width('100%')
      .height(38)
      .onSelect((index: number, value: string) => this.setFieldText(field, value))
  }

  @Builder
  NoteEditor() {
    Column({ space: 10 }) {
      Row({ space: 8 }) {
        Text('节点备注')
          .fontSize(15)
          .fontWeight(FontWeight.Bold)
          .fontColor(this.c().text)
          .layoutWeight(1)
        Text('关闭')
          .fontSize(12)
          .fontColor(this.c().textMuted)
          .padding({ left: 7, right: 7, top: 7, bottom: 7 })
          .borderRadius(8)
          .onClick(() => {
            this.noteEditorId = -1;
            this.noteDraft = '';
          })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)

      Text(`#${this.noteEditorId} · 只保存在本机，用于卡片和「全部节点」里的显示名。`)
        .fontSize(11)
        .fontColor(this.c().textDim)
        .width('100%')

      TextInput({ text: this.noteDraft, placeholder: '给这个节点起个好认的名字' })
        .fontSize(12.5)
        .fontColor(this.c().text)
        .placeholderColor(this.c().textDim)
        .caretColor(this.c().accent)
        .backgroundColor(this.c().card)
        .borderRadius(12)
        .border({ width: 1, color: this.c().border })
        .height(40)
        .width('100%')
        .padding({ left: 12, right: 12 })
        .onChange((value: string) => {
          this.noteDraft = value;
        })

      Row({ space: 8 }) {
        if (this.noteOf(this.noteEditorId).length > 0) {
          Button('清除备注')
            .fontSize(12.5)
            .fontColor(this.c().textMuted)
            .height(38)
            .padding({ left: 16, right: 16 })
            .borderRadius(12)
            .backgroundColor(this.c().card)
            .border({ width: 1, color: this.c().borderStrong })
            .onClick(() => this.clearNote())
        }
        Blank()
        Button('取消')
          .fontSize(12.5)
          .fontColor(this.c().textMuted)
          .height(38)
          .padding({ left: 16, right: 16 })
          .borderRadius(12)
          .backgroundColor(this.c().card)
          .border({ width: 1, color: this.c().borderStrong })
          .onClick(() => {
            this.noteEditorId = -1;
            this.noteDraft = '';
          })
        this.GradientAction('保存', true, () => {
          this.saveNote();
        })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
    .padding(14)
    .backgroundColor(this.c().dialog)
    .borderRadius(18)
    .border({ width: 1, color: this.c().border })
  }

  @Builder
  WorkflowCatalog() {
    // 与 Android WorkflowCatalogScreen 的 when(catalog) 一一对应：Idle / Loading / Failed / Ready。 
    if (this.catalog.state === 'loading') {
      // 骨架屏：和真实条目卡一样的形状，数据到达时整页不跳动；透明度呼吸代替转圈。
      Column({ space: 10 }) {
        ForEach([0, 1, 2, 3], (index: number) => {
          Column({ space: 8 }) {
            Row()
              .width(`${Math.round((0.62 - index * 0.07) * 100)}%`)
              .height(13)
              .borderRadius(6)
              .backgroundColor(this.c().cardStrong)
            Row()
              .width('34%')
              .height(10)
              .borderRadius(5)
              .backgroundColor(this.c().cardStrong)
          }
          .alignItems(HorizontalAlign.Start)
          .width('100%')
          .padding(14)
          .backgroundColor(this.c().card)
          .borderRadius(18)
          .border({ width: 1, color: this.c().border })
          .opacity(this.skeletonPhase)
          .onAppear(() => this.startSkeletonPulse())
        }, (index: number) => `skeleton-${index}`)
      }
      .width('100%')
      .transition(TransitionEffect.OPACITY.animation({ duration: 260, curve: Curve.EaseOut }))
    } else if (this.catalog.state === 'failed') {
      Column() {
        Text(`读取失败：${this.catalogError()}`)
          .fontSize(12.5)
          .lineHeight(18)
          .fontColor(this.c().err)
          .width('100%')
        // 对应 Kotlin 的 GhostButton("重试", width = 96.dp)。 
        Button('重试')
          .fontSize(12.5)
          .fontColor(this.c().textMuted)
          .height(42)
          .width(96)
          .padding({ left: 16, right: 16 })
          .borderRadius(14)
          .backgroundColor(this.c().card)
          .border({ width: 1, color: this.c().borderStrong })
          .enabled(this.running)
          .opacity(this.running ? 1 : 0.45)
          .margin({ top: 10 })
          .scale(this.pressScaleOptions('catalog-retry'))
          .onTouch((event: TouchEvent) => this.onPressTouch(event, 'catalog-retry'))
          .onClick(() => {
            this.catalogRequested = true;
            this.bridge.refreshWorkflows();
          })
      }
      .alignItems(HorizontalAlign.Start)
      .width('100%')
      .padding(14)
      .backgroundColor(this.c().card)
      .borderRadius(18)
      .border({ width: 1, color: this.c().border })
    } else if (this.catalog.state === 'ready' && (this.catalog.entries ?? []).length === 0) {
      Column() {
        Text('服务器 workflows 目录里没有 .json 工作流。')
          .fontSize(12.5)
          .fontColor(this.c().textMuted)
          .width('100%')
          .translate({ y: -3 * this.floatPhase })
          .opacity(0.82 + 0.18 * this.floatPhase)
          .onAppear(() => this.startGentleFloat())
        // 对应 Kotlin 的 GhostButton("刷新", width = 96.dp)。 
        Button('刷新')
          .fontSize(12.5)
          .fontColor(this.c().textMuted)
          .height(42)
          .width(96)
          .padding({ left: 16, right: 16 })
          .borderRadius(14)
          .backgroundColor(this.c().card)
          .border({ width: 1, color: this.c().borderStrong })
          .margin({ top: 10 })
          .scale(this.pressScaleOptions('catalog-refresh'))
          .onTouch((event: TouchEvent) => this.onPressTouch(event, 'catalog-refresh'))
          .onClick(() => {
            this.catalogRequested = true;
            this.bridge.refreshWorkflows();
          })
      }
      .alignItems(HorizontalAlign.Start)
      .width('100%')
      .padding(14)
      .backgroundColor(this.c().card)
      .borderRadius(18)
      .border({ width: 1, color: this.c().border })
    } else if (this.catalog.state === 'ready') {
      Text(`共 ${(this.catalog.entries ?? []).length} 个，点一个打开。`)
        .fontSize(10.5)
        .fontColor(this.c().textDim)
        .fontFamily('monospace')
        .width('100%')
        .transition(TransitionEffect.OPACITY.animation({ duration: 260, curve: Curve.EaseOut }))

      Scroll(this.catalogScroller) {
        Column({ space: 9 }) {
          ForEach(this.orderedCatalogEntries(), (entry: CatalogEntry) => {
            // 对应 Android EntryCard：置顶时左侧 3vp 强调色条 + accentBorder 描边。 
            Column() {
              Row({ space: 8 }) {
                Column({ space: 4 }) {
                  Row({ space: 8 }) {
                    Text(entry.name)
                      .fontSize(13.5)
                      .fontWeight(600)
                      .fontColor(this.c().text)
                      .maxLines(1)
                      .textOverflow({ overflow: TextOverflow.Ellipsis })
                      .layoutWeight(1)
                    if (entry.size > 0) {
                      Text(entry.size < 1024
                        ? `${entry.size} B`
                        : (entry.size < 1024 * 1024
                          ? `${Math.floor(entry.size / 1024)} KB`
                          : `${Math.floor(entry.size / (1024 * 1024))} MB`))
                        .fontSize(9.5)
                        .fontColor(this.c().textDim)
                        .fontFamily('monospace')
                    }
                  }
                  .width('100%')
                  .alignItems(VerticalAlign.Center)

                  Row({ space: 8 }) {
                    Text(entry.path)
                      .fontSize(10)
                      .fontColor(this.c().textDim)
                      .fontFamily('monospace')
                      .maxLines(1)
                      .textOverflow({ overflow: TextOverflow.Ellipsis })
                      .layoutWeight(1)
                    if (this.isWorkflowPinned(entry.path)) {
                      Text('已置顶')
                        .fontSize(9.5)
                        .fontColor(this.c().accent)
                        .fontFamily('monospace')
                    }
                    // 对应 Kotlin 带描边的置顶切换文本；点它只切换置顶，不打开工作流。 
                    Text(this.isWorkflowPinned(entry.path) ? '取消置顶' : '置顶')
                      .fontSize(10)
                      .fontColor(this.c().textMuted)
                      .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                      .borderRadius(7)
                      .border({ width: 1, color: this.c().borderStrong })
                      .onClick(() => {
                        this.toggleWorkflowPin(entry.path);
                      })
                  }
                  .width('100%')
                  .alignItems(VerticalAlign.Center)
                }
                .layoutWeight(1)
                .alignItems(HorizontalAlign.Start)
                .padding({ left: 14, right: 14, top: 12, bottom: 12 })
              }
              .width('100%')
              .alignItems(VerticalAlign.Center)
            }
            .width('100%')
            .backgroundColor(this.c().card)
            .borderRadius(18)
            .border({
              width: { left: this.isWorkflowPinned(entry.path) ? 3 : 1, top: 1, right: 1, bottom: 1 },
              color: this.isWorkflowPinned(entry.path) ? this.c().accentBorder : this.c().border,
              radius: 18
            })
            .onClick(() => this.goPage(true, () => this.openWorkflow(entry.path, entry.name)))
          }, (entry: CatalogEntry) =>
            `${entry.path}:${this.isWorkflowPinned(entry.path)}:${entry.size}`)
        }
        .width('100%')
        .padding({ bottom: 16 })
      }
      .layoutWeight(1)
      .width('100%')
      .scrollBar(BarState.Off)
      .transition(TransitionEffect.OPACITY.animation({ duration: 260, curve: Curve.EaseOut }))
    } else {
      // Idle（以及未知状态）：对应 Kotlin 的 HintCard。 
      Column() {
        Text(this.running ? '准备读取服务器工作流…' : '尚未连接 ComfyUI。')
          .fontSize(12.5)
          .fontColor(this.c().textMuted)
          .width('100%')
          .translate({ y: -3 * this.floatPhase })
          .opacity(0.82 + 0.18 * this.floatPhase)
          .onAppear(() => this.startGentleFloat())
      }
      .width('100%')
      .padding(14)
      .backgroundColor(this.c().card)
      .borderRadius(18)
      .border({ width: 1, color: this.c().border })
    }
  }

  @Builder
  WorkflowDetail() {
    Column() {
      // ---- 顶栏：返回 / 标题 + 元信息 / 刷新 + 任务，对应 Android SpectrumTopBar ----
      Row({ space: 2 }) {
        Text('返回')
          .fontSize(12)
          .fontColor(this.c().textMuted)
          .padding({ left: 7, right: 7, top: 7, bottom: 7 })
          .borderRadius(8)
          .onClick(() => this.goPage(false, () => this.closeWorkflow()))
        Column({ space: 2 }) {
          Text(this.view.name.length > 0 ? this.view.name : (this.workflow.name ?? this.selectedPath))
            .fontSize(15)
            .fontWeight(FontWeight.Bold)
            .letterSpacing(-0.2)
            .fontColor(this.c().text)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(`${this.view.format} · ${this.view.nodes.length} 个节点 · ${this.pinnedIds.length} 个置顶`)
            .fontSize(9.5)
            .fontFamily('monospace')
            .letterSpacing(0.4)
            .fontColor(this.c().textDim)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        Text('刷新')
          .fontSize(12)
          .fontColor(this.running ? this.c().textMuted : this.c().textDim)
          .padding({ left: 7, right: 7, top: 7, bottom: 7 })
          .borderRadius(8)
          .enabled(this.running)
          .onClick(() => {
            this.summaryKey = '';
            this.refreshView();
          })
        Text('任务')
          .fontSize(12)
          .fontColor(this.c().textMuted)
          .padding({ left: 7, right: 7, top: 7, bottom: 7 })
          .borderRadius(8)
          .onClick(() => {
            this.openTasks();
          })
      }
      .width('100%')
      .padding({ left: 4, right: 4 })
      .constraintSize({ minHeight: 46 })
      .alignItems(VerticalAlign.Center)

      // ---- 服务器性能条：对应 Android 详情页标题下方的 ServerStatsStrip ----
      this.ServerStatsStrip()

      // ---- 读取状态：对应 Android LoadingBody / ErrorBody 的内联提示 ----
      if (this.workflow.loadingPath !== null) {
        this.InlineNote(`正在读取 ${this.workflow.loadingPath}`)
      }
      if (this.workflow.error !== null && this.workflow.error.length > 0 &&
        !this.isErrorDismissed(this.workflowScopeId('load'), `读取失败：${this.workflow.error}`)) {
        Row({ space: 8 }) {
          Text(`读取失败：${this.workflow.error}`)
            .fontSize(11)
            .lineHeight(16)
            .fontColor(this.c().err)
            .layoutWeight(1)
          this.DismissButton(() => {
            if (this.workflow.error !== null) {
              this.dismissError(this.workflowScopeId('load'), `读取失败：${this.workflow.error}`);
            }
          })
        }
        .width('100%')
        .padding({ left: 4, right: 4 })
        .alignItems(VerticalAlign.Top)
      }
      if (this.viewError.length > 0 && !this.isErrorDismissed(this.workflowScopeId('view'), this.viewError)) {
        Row({ space: 8 }) {
          Text(this.viewError)
            .fontSize(11)
            .lineHeight(16)
            .fontColor(this.c().err)
            .layoutWeight(1)
          this.DismissButton(() => this.dismissError(this.workflowScopeId('view'), this.viewError))
        }
        .width('100%')
        .padding({ left: 4, right: 4 })
        .alignItems(VerticalAlign.Top)
      }
      if (this.viewLoading) {
        this.InlineNote('正在生成字段视图…')
      }

      // ---- 图选择器：每个父图一个药丸，点药丸切换是否纳入提交；
      //      有子图的药丸右侧分出一个 ▾，展开子图勾选面板。与 Android GraphSelector 一致。 ----
      Scroll() {
        Row({ space: 6 }) {
          if (this.parentGraphs().length > 0) {
            ForEach(this.parentGraphs(), (graph: WorkflowGraphView) => {
              this.GraphChip(graph)
            }, (graph: WorkflowGraphView) => `${graph.id}:${graph.selected}:${this.childGraphs(graph.id).length}`)
          } else if (this.definitionsPending()) {
            // 定义还没到齐时图列表一定是空的，不能显示成“没有可切换的节点图”。
            Text('正在识别节点图…')
              .fontSize(11)
              .fontColor(this.c().textDim)
              .padding({ left: 13, right: 13, top: 7, bottom: 7 })
              .borderRadius(999)
              .backgroundColor(this.c().card)
              .border({ width: 1, color: this.c().border })
          } else {
            Text('没有可切换的节点图')
              .fontSize(11)
              .fontColor(this.c().textDim)
              .padding({ left: 13, right: 13, top: 7, bottom: 7 })
              .borderRadius(999)
              .backgroundColor(this.c().card)
              .border({ width: 1, color: this.c().border })
          }
        }
        .padding({ left: 4, right: 4, top: 2 })
        .constraintSize({ minHeight: 34 })
        .alignItems(VerticalAlign.Center)
      }
      .scrollable(ScrollDirection.Horizontal)
      .scrollBar(BarState.Off)
      .width('100%')
      .constraintSize({ minHeight: 34 })
      // ---- 输入 / 预览 分段 tabs，对应 Android SpectrumTabs ----
      this.SegmentedTabs()

      // ---- 待加载 / 缺失 / 提交问题 ----
      if (this.view.pendingNodeClasses.length > 0) {
        this.InlineNote(`正在加载节点定义：还差 ${this.view.pendingNodeClasses.length} 个类`)
      }
      if (this.uploadingField.length > 0) {
        this.InlineNote('正在上传图片…')
      }

      if (this.view.problems.length > 0 &&
        !this.isErrorDismissed(this.workflowScopeId('build'), this.buildErrorText())) {
        Column() {
          Column() {
            Row({ space: 8 }) {
              Text('构建失败')
                .fontSize(10)
                .fontFamily('monospace')
                .letterSpacing(0.6)
                .fontColor(this.c().err)
                .layoutWeight(1)
              this.DismissButton(() => this.dismissError(this.workflowScopeId('build'), this.buildErrorText()))
            }
            .width('100%')
            .padding({ left: 11, right: 5, top: 6, bottom: 2 })
            .alignItems(VerticalAlign.Center)

            Column({ space: 2 }) {
              ForEach(this.view.problems, (problem: BuildProblemView) => {
                Text(problem.display)
                  .fontSize(11)
                  .lineHeight(16)
                  .fontColor(this.c().err)
              }, (problem: BuildProblemView) => `${problem.nodeId}:${problem.message}`)
            }
            .alignItems(HorizontalAlign.Start)
            .width('100%')
            .padding({ left: 11, right: 11, bottom: 11 })
          }
          .alignItems(HorizontalAlign.Start)
          .width('100%')
          .backgroundColor(this.c().card)
          .borderRadius(16)
          .border({ width: 1, color: this.c().border })
        }
        .width('100%')
        .padding({ left: 4, right: 4, top: 2, bottom: 2 })
      }


      // 节点备注面板（Android 是 SpectrumDialog，这里作为内容面板嵌在详情里）。 
      if (this.noteEditorId >= 0) {
        this.NoteEditor()
      }

      // ---- 内容：节点列表 / 预览 ----
      // 内容与底栏放在同一个 Stack 里：胶囊浮在列表之上，列表能从玻璃后面透出来。
      Stack({ alignContent: Alignment.Bottom }) {
        Column() {
      if (this.detailTab === 0) {
        Scroll(this.scroller) {
          Column({ space: 9 }) {
            if (!this.definitionsPending() && this.view.ok && this.view.nodes.length === 0) {
              Text('这是 API 格式工作流，没有网页字段可编辑；可以直接提交。')
                .fontSize(12.5)
                .fontColor(this.c().textMuted)
                .width('100%')
            }
            if (this.definitionsPending()) {
              // 定义没到齐时每行都会被标成“缺少定义”，图名也会退化成“其他节点”；
              // 先显示加载提示，等共享层给出完整视图后再一次性渲染。 
              this.InlineNote('正在加载节点定义，字段和节点图稍后显示…')
            } else {
              // missing/fields/节点图归属都会在节点定义落地后变化；键里必须带上这些， 
              // 否则 ForEach 会复用旧组件，界面一直停在“服务端没有该节点的定义”。 
              ForEach(this.view.nodes, (node: WorkflowNodeView, index: number) => {
                if (this.isSectionStart(index)) {
                  this.SectionHeader(node)
                }
                this.NodeCard(node)
              }, (node: WorkflowNodeView) =>
                `${node.id}:${this.graphKeyOfNode(node)}:${node.missing}:${node.fields.length}:` +
                  `${node.pinned}:${node.reachable}:${node.needsInput}:${node.enabled}:${node.bypassed}`)
            }
          }
          .width('100%')
          .alignItems(HorizontalAlign.Start)
          .padding({ left: 4, right: 4, top: 6, bottom: 84 })
        }
        .layoutWeight(1)
        .width('100%')
        .scrollBar(BarState.Off)
        .align(Alignment.Top)
      } else {
        Scroll() {
          Column({ space: 10 }) {
            this.DetailPreview()
          }
          .width('100%')
          .padding({ left: 4, right: 4, top: 6, bottom: 84 })
        }
        .layoutWeight(1)
        .width('100%')
        .scrollBar(BarState.Off)
        .align(Alignment.Top)
      }

        }
        .width('100%')
        .height('100%')

        // ---- 底部提交条：悬浮玻璃胶囊，浮在列表之上 ----
        Row() {
        Column({ space: 8 }) {
          if (this.submitNotice.length > 0 &&
            !this.isErrorDismissed(this.workflowScopeId('submit'), this.submitNotice)) {
            Row({ space: 8 }) {
              Text(this.submitNotice)
                .fontSize(10.5)
                .lineHeight(15)
                .fontColor(this.submitNotice.startsWith('已提交') ? this.c().ok : this.c().err)
                .layoutWeight(1)
              this.DismissButton(() => this.dismissError(this.workflowScopeId('submit'), this.submitNotice))
            }
            .width('100%')
            .alignItems(VerticalAlign.Top)
          }
          if (!this.view.canSubmit) {
            Text(this.blockReason())
              .fontSize(10.5)
              .lineHeight(15)
              .fontColor(this.c().err)
              .width('100%')
          }
          Row({ space: 10 }) {
            Button('全部节点')
              .fontSize(12.5)
              .fontColor(this.c().textMuted)
              .height(36)
              .padding({ left: 16, right: 16 })
              .borderRadius(14)
              .backgroundColor(this.c().card)
              .border({ width: 1, color: this.c().borderStrong })
              .scale(this.pressScaleOptions('all-nodes'))
              .onTouch((event: TouchEvent) => this.onPressTouch(event, 'all-nodes'))
              .onClick(() => {
                this.allNodesOpen = true;
              })
            Blank()
            Text(`${this.reachableNodeCount()} 个会提交`)
              .fontSize(9.5)
              .fontFamily('monospace')
              .fontColor(this.c().textDim)
              .textAlign(TextAlign.End)
            Button(this.submitFlash ? '已提交 ✓' : (this.submitting ? '提交中…' : '提交任务'))
              .fontSize(14)
              .fontWeight(FontWeight.Bold)
              .fontColor(this.c().accentInk)
              .height(38)
              .padding({ left: 16, right: 16 })
              .borderRadius(12)
              .backgroundColor(Color.Transparent)
              .linearGradient({ angle: 120, colors: Spectrum.gradientColors(this.c()) })
              .enabled(this.running && !this.submitting && this.view.canSubmit)
              .opacity(this.running && !this.submitting && this.view.canSubmit ? 1 : 0.42)
              .scale(this.pressScaleOptions('submit'))
              .onTouch((event: TouchEvent) => this.onPressTouch(event, 'submit'))
              .onClick(() => this.submitWorkflow())
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: 7, bottom: 7 })
        .borderRadius(22)
        // 真·毛玻璃：backdropBlur 直接模糊组件背后的内容（官方「高效使用模糊」推荐做法），
        // 再叠一层低不透明度底色与高光边框；backgroundBlurStyle 的系统材质会带灰调。
        .backgroundColor(this.c().isDark ? 'rgba(28,30,40,0.34)' : 'rgba(255,255,255,0.38)')
        .backdropBlur(32)
        .border({ width: 1, color: this.c().isDark ? 'rgba(255,255,255,0.16)' : 'rgba(255,255,255,0.72)' })
        .shadow({ radius: 16, color: this.c().isDark ? 'rgba(0,0,0,0.32)' : 'rgba(31,41,55,0.14)', offsetX: 0, offsetY: 5 })
      }
      .width('100%')
      .padding({ left: 14, right: 14, bottom: px2vp(this.navBarPx) })
      }
      .layoutWeight(1)
      .width('100%')
      // 内容与胶囊一起向下溢出 MainPanel 的底部安全区，列表就能铺到屏幕最底。
      .margin({ bottom: -(14 + px2vp(this.navBarPx)) })
    }
    .width('100%')
    .height('100%')
  }

  /** 输入 / 预览 分段控件，对应 Android SpectrumTabs。 */
  @Builder
  SegmentedTabs() {
    Column() {
      Column() {
        Stack({ alignContent: Alignment.TopStart }) {
          // 一块共享底板跟着选中项滑动，而不是每个页签自己换底色。
          Row()
            .width(this.tabIndicatorWidth)
            .height(36)
            .borderRadius(11)
            .backgroundColor(this.c().cardStrong)
            .translate({ x: this.tabIndicatorOffset() })
          Row({ space: 3 }) {
            Row() {
              Text(`输入 (${this.fieldCount()})`)
                .fontSize(12.5)
                .fontWeight(this.detailTab === 0 ? FontWeight.Bold : FontWeight.Normal)
                .fontColor(this.detailTab === 0 ? this.c().text : this.c().textMuted)
                .maxLines(1)
            }
            .layoutWeight(1)
            .height(36)
            .justifyContent(FlexAlign.Center)
            .scale(this.pressScaleOptions('tab-input', 0.94))
            .onTouch((event: TouchEvent) => this.onPressTouch(event, 'tab-input'))
            .onClick(() => {
              animateTo({ duration: 220, curve: Curve.Friction }, () => {
                this.detailTab = 0;
              });
            })
            Row() {
              Text('预览')
                .fontSize(12.5)
                .fontWeight(this.detailTab === 1 ? FontWeight.Bold : FontWeight.Normal)
                .fontColor(this.detailTab === 1 ? this.c().text : this.c().textMuted)
                .maxLines(1)
            }
            .layoutWeight(1)
            .height(36)
            .justifyContent(FlexAlign.Center)
            .scale(this.pressScaleOptions('tab-preview', 0.94))
            .onTouch((event: TouchEvent) => this.onPressTouch(event, 'tab-preview'))
            .onClick(() => {
              animateTo({ duration: 220, curve: Curve.Friction }, () => {
                this.detailTab = 1;
              });
            })
          }
          .width('100%')
        }
        .width('100%')
        .height(36)
        .onAreaChange((oldValue: Area, newValue: Area) => {
          const width: number = newValue.width as number;
          if (!Number.isFinite(width) || width <= 0 || Math.abs(width - this.tabTrackWidth) <= 0.5) {
            return;
          }
          if (this.tabTrackWidth <= 0) {
            this.tabTrackWidth = width;
            this.tabIndicatorWidth = Math.max(0, (width - 3) / 2);
          } else {
            animateTo({ duration: 220, curve: Curve.Friction }, () => {
              this.tabTrackWidth = width;
              this.tabIndicatorWidth = Math.max(0, (width - 3) / 2);
            });
          }
        })
      }
      .width('100%')
      .padding(3)
      .borderRadius(14)
      .backgroundColor(this.c().card)
      .border({ width: 1, color: this.c().border })
      .clip(true)
    }
    .width('100%')
    .padding({ left: 4, right: 4, top: 6, bottom: 2 })
  }

  /** 带转圈的内联提示，对应 Android InlineNote。 */
  @Builder
  InlineNote(text: string) {
    Row({ space: 8 }) {
      LoadingProgress()
        .width(13)
        .height(13)
        .color(this.c().accent)
      Text(text)
        .fontSize(10.5)
        .fontColor(this.c().textDim)
        .layoutWeight(1)
    }
    .width('100%')
    .padding({ left: 4, right: 4, top: 4, bottom: 2 })
    .alignItems(VerticalAlign.Center)
  }

  /** 错误块右上角的小 ✕：只隐藏这一条提示，不改变错误来源。 */
  @Builder
  DismissButton(action: () => void) {
    Text('✕')
      .fontSize(11)
      .fontColor(this.c().textDim)
      .padding({ left: 7, right: 7, top: 4, bottom: 4 })
      .borderRadius(8)
      .backgroundColor(this.c().card)
      .border({ width: 1, color: this.c().border })
      .onClick(() => action())
  }

  /** 节点图分组标题，对应 Android SectionHeader。 */
  @Builder
  SectionHeader(node: WorkflowNodeView) {
    Row({ space: 8 }) {
      Text(this.graphTitleOfNode(node))
        .fontSize(11)
        .fontWeight(FontWeight.Bold)
        .letterSpacing(0.4)
        .fontColor(this.c().textMuted)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .layoutWeight(1)
      Text(this.graphMetaOfNode(node))
        .fontSize(9)
        .fontFamily('monospace')
        .fontColor(this.c().textDim)
    }
    .width('100%')
    .padding({ left: 2, top: 4, bottom: 1 })
    .alignItems(VerticalAlign.Center)
  }

  /** 图选择器下拉面板：父图 + 子图开关，深色面板 + 描边 + 阴影。 */
  @Builder
  GraphMenuPanel() {
    Column() {
      Text('节点图 · 子图开关')
        .fontSize(9)
        .fontFamily('monospace')
        .letterSpacing(0.8)
        .fontColor(this.c().textDim)
        .width('100%')
        .padding({ left: 16, right: 16, top: 4, bottom: 6 })

      if (this.parentGraphs().length === 0) {
        Text('这个工作流没有可切换的节点图。')
          .fontSize(11)
          .fontColor(this.c().textMuted)
          .width('100%')
          .padding({ left: 16, right: 16, top: 6, bottom: 10 })
      } else {
        ForEach(this.parentGraphs(), (graph: WorkflowGraphView, index: number) => {
          if (index > 0) {
            Column() {
              Row()
                .width('100%')
                .height(1)
                .backgroundColor(this.c().border)
            }
            .width('100%')
            .padding({ left: 8, right: 8, top: 2, bottom: 2 })
          }
          this.GraphMenuRow(graph.title,
            `${graph.nodeIds.length} 个节点 · ${graph.selected ? '会提交' : '不会提交'}`,
            graph.selected, false, () => {
              this.toggleGraph(graph);
            })
          ForEach(this.childGraphs(graph.id), (child: WorkflowGraphView) => {
            this.GraphMenuRow(child.title,
              `${child.nodeIds.length} 个节点 · 参考图槽位`,
              child.selected, true, () => {
                this.toggleGraph(child);
              })
          }, (child: WorkflowGraphView) => `${child.id}:${child.selected}`)
        }, (graph: WorkflowGraphView) => `${graph.id}:${graph.selected}`)
      }
    }
    .width(this.addressFieldWidth > 0 ? this.addressFieldWidth : 300)
    .padding({ top: 6, bottom: 6, left: 6, right: 6 })
    .backgroundColor(this.c().dialog)
    .borderRadius(18)
    .border({ width: 1, color: this.c().border })
    .shadow({ radius: 22, color: 'rgba(0,0,0,0.45)', offsetX: 0, offsetY: 10 })
  }

  /** 单个父图药丸：左边点标题切换启用，右边（仅有子图时）点 ▾ 展开子图勾选面板。 */
  @Builder
  GraphChip(graphItem: WorkflowGraphView) {
    Stack({ alignContent: Alignment.Center }) {
      Row() {
        Text(graphItem.title)
          .fontSize(11.5)
          .fontWeight(graphItem.selected ? FontWeight.Medium : FontWeight.Normal)
          .fontColor(graphItem.selected ? this.c().accentInk : this.c().textMuted)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .constraintSize({ maxWidth: 190 })
          .padding({
            left: 13,
            right: this.childGraphs(graphItem.id).length > 0 ? 9 : 13,
            top: 7,
            bottom: 7,
          })
          .onClick(() => {
            this.toggleGraph(graphItem);
          })
        if (this.childGraphs(graphItem.id).length > 0) {
          Row()
            .width(1)
            .height(16)
            .backgroundColor(graphItem.selected ? this.c().accentInk : this.c().border)
            .opacity(graphItem.selected ? 0.3 : 1)
          Row() {
            Text(this.openChildGraphId === graphItem.id ? '▴' : '▾')
              .fontSize(9)
              .fontColor(graphItem.selected ? this.c().accentInk : this.c().textDim)
          }
          .width(28)
          .height(32)
          .justifyContent(FlexAlign.Center)
          .onClick(() => {
            this.openChildGraphId = this.openChildGraphId === graphItem.id ? '' : graphItem.id;
          })
          .bindPopup(this.openChildGraphId === graphItem.id, {
            builder: () => {
              this.ChildGraphPanel(graphItem)
            },
            placement: Placement.Bottom,
            popupColor: this.c().dialog,
            enableArrow: false,
            radius: 18,
            onStateChange: (event: PopupStateEvent) => {
              if (!event.isVisible) {
                this.openChildGraphId = '';
              }
            },
          })
        }
      }
      .height(32)
    }
    .width('auto')
    .height(32)
    .borderRadius(999)
    .backgroundColor(this.c().card)
    // 选中时铺渐变（与安卓一致），未选中用全透明色停，避免额外图层把药丸撑满整行。
    .linearGradient({
      angle: 120,
      colors: graphItem.selected
        ? Spectrum.gradientColors(this.c())
        : this.transparentGradient(),
    })
    .border({ width: 1, color: graphItem.selected ? this.c().accentBorder : this.c().border })
    .clip(true)
    .animation({ duration: 160, curve: Curve.EaseOut })
  }

  /** 全透明渐变：linearGradient 的颜色必须是非空数组，用它表达"不铺渐变"。 */
  private transparentGradient(): Array<[string, number]> {
    return [['#00000000', 0.0], ['#00000000', 1.0]];
  }
  /** 子图勾选面板：只列这个父图下的辅助图组，标题与 Android DropdownPanel 一致。 */
  @Builder
  ChildGraphPanel(parentGraph: WorkflowGraphView) {
    Column() {
      Text(`子节点图 · ${parentGraph.title}`)
        .fontSize(9)
        .fontFamily('monospace')
        .letterSpacing(0.8)
        .fontColor(this.c().textDim)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
        .padding({ left: 16, right: 16, top: 4, bottom: 6 })

      if (this.childGraphs(parentGraph.id).length === 0) {
        Text('这个节点图没有子图。')
          .fontSize(11)
          .fontColor(this.c().textMuted)
          .width('100%')
          .padding({ left: 16, right: 16, top: 6, bottom: 10 })
      } else {
        ForEach(this.childGraphs(parentGraph.id), (child: WorkflowGraphView, index: number) => {
          if (index > 0) {
            Column() {
              Row()
                .width('100%')
                .height(1)
                .backgroundColor(this.c().border)
            }
            .width('100%')
            .padding({ left: 8, right: 8, top: 2, bottom: 2 })
          }
          this.GraphMenuRow(child.title,
            `${child.nodeIds.length} 个节点 · 参考图槽位`,
            child.selected, false, () => {
              this.toggleGraph(child);
            })
        }, (child: WorkflowGraphView) => `${child.id}:${child.selected}`)
      }
    }
    .width(256)
    .padding({ top: 6, bottom: 6, left: 6, right: 6 })
    .backgroundColor(this.c().dialog)
    .borderRadius(18)
    .border({ width: 1, color: this.c().border })
    .shadow({ radius: 22, color: 'rgba(0,0,0,0.45)', offsetX: 0, offsetY: 10 })
  }
  /** 下拉面板里的一行：勾选态 + 名称/副标题 + 启用标签。 */
  @Builder
  GraphMenuRow(title: string, subtitle: string, checked: boolean, indent: boolean, action: () => void) {
    Row({ space: 8 }) {
      Row() {
        if (checked) {
          Text('✓')
            .fontSize(10)
            .fontColor(this.c().ok)
        }
      }
      .width(18)
      .height(18)
      .justifyContent(FlexAlign.Center)

      Column({ space: 2 }) {
        Text(title)
          .fontSize(12.5)
          .fontWeight(FontWeight.Medium)
          .fontColor(checked ? this.c().text : this.c().textMuted)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(subtitle)
          .fontSize(9.5)
          .fontFamily('monospace')
          .fontColor(this.c().textDim)
          .maxLines(1)
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Text(checked ? '已启用' : '未启用')
        .fontSize(9.5)
        .fontFamily('monospace')
        .fontColor(checked ? this.c().ok : this.c().textDim)
        .padding({ left: 7, right: 7, top: 3, bottom: 3 })
        .borderRadius(7)
        .backgroundColor(checked ? this.c().okSoft : Color.Transparent)
        .border({ width: 1, color: checked ? this.c().ok : this.c().border })
    }
    .width('100%')
    .padding({ left: indent ? 24 : 10, right: 10, top: 8, bottom: 8 })
    .borderRadius(12)
    .backgroundColor(checked ? this.c().accentSoft : Color.Transparent)
    .onClick(() => action())
  }

  /** 地址历史下拉：最近连通的地址（最新在前）+ 删除；样式与图选择器一致。 */
  @Builder
  AddressMenuPanel() {
    Column() {
      Text('最近的地址')
        .fontSize(9)
        .fontFamily('monospace')
        .letterSpacing(0.8)
        .fontColor(this.c().textDim)
        .width('100%')
        .padding({ left: 16, right: 16, top: 4, bottom: 6 })

      if (this.addressHistory.length === 0) {
        Text('还没有保存过的地址')
          .fontSize(11)
          .fontColor(this.c().textMuted)
          .width('100%')
          .padding({ left: 16, right: 16, top: 6, bottom: 10 })
      } else {
        ForEach(this.addressHistory, (entry: AddressEntry, index: number) => {
          if (index > 0) {
            Column() {
              Row()
                .width('100%')
                .height(1)
                .backgroundColor(this.c().border)
            }
            .width('100%')
            .padding({ left: 8, right: 8, top: 2, bottom: 2 })
          }
          this.AddressMenuRow(entry)
        }, (entry: AddressEntry) => `${entry.url}:${entry.usedAt}`)
      }
    }
    .width(this.addressFieldWidth > 0 ? this.addressFieldWidth : 300)
    .padding({ top: 6, bottom: 6, left: 6, right: 6 })
    .backgroundColor(this.c().dialog)
    .borderRadius(18)
    .border({ width: 1, color: this.c().border })
    .shadow({ radius: 22, color: 'rgba(0,0,0,0.45)', offsetX: 0, offsetY: 10 })
  }

  /** 下拉里的一行：地址 + 使用时间 + 「当前」标记 + ✕。 */
  @Builder
  AddressMenuRow(entry: AddressEntry) {
    Row({ space: 6 }) {
      Row({ space: 6 }) {
        Column({ space: 2 }) {
          Text(entry.url)
            .fontSize(11.5)
            .fontFamily('monospace')
            .fontColor(this.isCurrentAddress(entry) ? this.c().text : this.c().textMuted)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(this.addressAge(entry.usedAt))
            .fontSize(9)
            .fontFamily('monospace')
            .fontColor(this.c().textDim)
            .maxLines(1)
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)

        if (this.isCurrentAddress(entry)) {
          Text('当前')
            .fontSize(9.5)
            .fontFamily('monospace')
            .fontColor(this.c().ok)
            .padding({ left: 7, right: 7, top: 3, bottom: 3 })
            .borderRadius(7)
            .backgroundColor(this.c().okSoft)
            .border({ width: 1, color: this.c().ok })
        }
      }
      .layoutWeight(1)
      .padding({ top: 6, bottom: 6 })
      .alignItems(VerticalAlign.Center)
      .onClick(() => this.pickAddress(entry.url))

      Text('✕')
        .fontSize(11)
        .fontColor(this.c().textDim)
        .padding({ left: 8, right: 8, top: 7, bottom: 7 })
        .borderRadius(8)
        .onClick(() => this.forgetAddress(entry.url))
    }
    .width('100%')
    .padding({ left: 10, right: 6 })
    .borderRadius(12)
    .backgroundColor(this.isCurrentAddress(entry) ? this.c().accentSoft : Color.Transparent)
    .alignItems(VerticalAlign.Center)
  }

  /**
   * 服务器性能条（对应 Android ServerStatsStrip）。 
   *
   * 默认收成一行标题：左边「主机状态」，右边 ▾/▸ 加两个最有用的读数 
   * （内存·显存，两者都没有才退回 CPU·温度），整条 38vp 左右，不再占掉半屏；
   * 整行标题就是展开/收起的热区。展开后是两列网格：每格宽 50% 自动换行， 
   * 标签在上、数值在下，Crystools 没给的指标不画格子、不写 `--`。 
   * 监控帧（CPU/GPU/温度/内存/显存/显卡名）优先，/system_stats 的百分比兜底； 
   * 一个数都没有时整块不渲染（离线时页面不会多一条空卡片）。 
   */
  @Builder
  ServerStatsStrip() {
    if (this.hasServerStats()) {
      Column() {
        Row({ space: 6 }) {
          Text('主机状态')
            .fontSize(12)
            .fontColor(this.c().textMuted)
          Blank()
          if (this.statsSummary().length > 0) {
            Text(this.statsSummary())
              .fontSize(10.5)
              .fontFamily('monospace')
              .fontColor(this.c().textDim)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .constraintSize({ maxWidth: '72%' })
          }
          Text('▸')
            .fontSize(12)
            .fontColor(this.c().textDim)
            .rotate({ angle: this.statsExpanded ? 90 : 0 })
        }
        .width('100%')
        .height(36)
        .padding({ left: 10, right: 10 })
        .alignItems(VerticalAlign.Center)
        .onClick(() => {
          animateTo({ duration: 200, curve: Curve.EaseOut }, () => {
            this.statsExpanded = !this.statsExpanded;
          });
        })

        if (this.statsExpanded) {
          Flex({ direction: FlexDirection.Row, wrap: FlexWrap.Wrap, alignItems: ItemAlign.Start }) {
            if (this.stripRam() >= 0) {
              this.ServerStatCell('内存', this.statPercentText(this.shownStats.ram))
            }
            if (this.stripVram() >= 0) {
              this.ServerStatCell('显存', this.statPercentText(this.shownStats.vram))
            }
            if (this.stripCpu() >= 0) {
              this.ServerStatCell('CPU', this.statPercentText(this.shownStats.cpu))
            }
            if (this.stripGpu() >= 0) {
              this.ServerStatCell('GPU', this.statPercentText(this.shownStats.gpu))
            }
            if (this.stripTemp() >= 0) {
              this.ServerStatCell('温度', `${Math.round(this.stripTemp())}°C`)
            }
            if (this.gpuName().length > 0) {
              this.ServerStatCell('显卡名', this.gpuName())
            }
            this.ServerStatCell('队列', `${this.queueRunning} / ${this.queuePending}`)
            if (this.serverStats.version.length > 0) {
              this.ServerStatCell('版本', this.serverStats.version)
            }
          }
          .width('100%')
          .padding({ left: 2, right: 2, bottom: 4 })
          .transition(TransitionEffect.OPACITY
            .combine(TransitionEffect.translate({ y: -6 }))
            .animation({ duration: 200, curve: Curve.EaseOut }))
        }
      }
      .width('100%')
      .backgroundColor(this.c().card)
      .borderRadius(14)
      .border({ width: 1, color: this.c().border })
    }
  }

  /**
   * 性能条里的一格：9sp 等宽小标签在上，12sp 等宽数值在下。 
   * 宽度固定半行、高度由内容撑开；数值不设 maxLines/textOverflow， 
   * 放不下就换行，绝不裁字。 
   */
  @Builder
  ServerStatCell(label: string, value: string) {
    Column({ space: 3 }) {
      Text(label)
        .fontSize(9)
        .fontFamily('monospace')
        .letterSpacing(0.9)
        .fontColor(this.c().textDim)
      Text(value)
        .fontSize(12)
        .fontFamily('monospace')
        .fontColor(this.c().text)
    }
    .width('50%')
    .alignItems(HorizontalAlign.Start)
    .padding({ left: 10, right: 10, top: 6, bottom: 6 })
  }

  @Builder
  DetailPreview() {
    Column({ space: 10 }) {
      if (this.liveCardTask() !== null) {
        this.DetailLivePreview()
        Row({ space: 9 }) {
          Blank()
          this.GhostAction('查看产物',
            this.activeWorkflowTask() !== null &&
              (this.activeWorkflowTask() as BridgeTask).outputRefs.length > 0,
            () => {
              this.openActiveWorkflowArtifacts();
            })
          this.GhostAction('查看全部任务', true, () => {
            this.tab = 1;
            this.openTasks();
          })
        }
        .width('100%')
      } else {
        this.LatestTaskPreview(this.latestWorkflowTask())
      }
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }

  /** 进行中任务的预览：Hero 面板 + 状态/进度 + 渐变进度条（对应 Android ActivePreview）。 */
  @Builder
  DetailLivePreview() {
    Column({ space: 10 }) {
      Stack() {
        this.HeroBackdrop()
        Row({ space: 5 }) {
          if (this.liveCardState() === 'Running') {
            Row()
              .width(5)
              .height(5)
              .borderRadius(3)
              .backgroundColor(this.c().ok)
          }
          Text(this.liveCardState() === 'Running' ? 'LIVE' : 'QUEUED')
            .fontSize(9)
            .fontFamily('monospace')
            .letterSpacing(0.8)
            .fontColor(this.liveCardState() === 'Running' ? this.c().ok : 'rgba(242,244,247,0.85)')
        }
        .padding({ left: 7, right: 7, top: 4, bottom: 4 })
        .backgroundColor('rgba(10,11,16,0.55)')
        .borderRadius(8)
        .position({ x: 10, y: 10 })

        if (this.livePreview !== null && this.livePreviewMatches(this.liveCardTask())) {
          Image(this.livePreview as image.PixelMap)
            .width('100%')
            .height('100%')
            .objectFit(ImageFit.Contain)
        } else {
          Column({ space: 6 }) {
            Text(this.liveCardState() === 'Queued' ? '排队中' : '等待采样画面…')
              .fontSize(12.5)
              .fontColor('rgba(242,244,247,0.85)')
            if (this.liveCardState() === 'Running') {
              Text('若一直不出现，确认 ComfyUI 以 --preview-method auto 启动')
                .fontSize(9.5)
                .fontColor('rgba(242,244,247,0.55)')
                .textAlign(TextAlign.Center)
                .maxLines(2)
            }
          }
          .width('100%')
          .height('100%')
          .padding({ left: 24, right: 24 })
          .justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
        }
      }
      .width('100%')
      .height(330)
      .borderRadius(22)
      .clip(true)
      .backgroundColor(this.c().dialog)
      .border({ width: 1, color: this.c().borderStrong })

      Row() {
        Column({ space: 2 }) {
          Text(this.taskStateName(this.liveCardState()))
            .fontSize(13)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.c().text)
          if (this.liveCardStep().length > 0) {
            Text(this.liveCardStep())
              .fontSize(10)
              .fontFamily('monospace')
              .fontColor(this.c().textDim)
          }
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        Text(`${this.liveCardPercent()}`)
          .fontSize(30)
          .fontWeight(FontWeight.Bold)
          .letterSpacing(-0.8)
          .fontColor(this.c().accent)
        Text('%')
          .fontSize(10)
          .fontColor(this.c().textDim)
          .padding({ left: 2, bottom: 5 })
      }
      .width('100%')
      .alignItems(VerticalAlign.Bottom)

      // 进度条直接内联：@Builder 的入参在 ArkUI 里不是响应式的，任务进度首次是 0，
      // 之后即使百分比文字更新，带参 builder 也不会重绘，于是填充永远停在 0。
      Row() {
        Row()
          .layoutWeight(this.progressWeight(this.liveCardPercent() / 100))
          .height(7)
          .linearGradient({ angle: 90, colors: Spectrum.gradientColors(this.c()) })
        Row()
          .layoutWeight(1000 - this.progressWeight(this.liveCardPercent() / 100))
          .height(7)
      }
      .width('100%')
      .height(7)
      .borderRadius(999)
      .backgroundColor(this.c().cardStrong)
      .clip(true)
      .animation({ duration: 320, curve: Curve.EaseOut })    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  LatestTaskPreview(task: BridgeTask | null) {
    if (task === null) {
      Column({ space: 10 }) {
        Stack() {
          this.HeroBackdrop()
          Row({ space: 5 }) {
            Text('IDLE')
              .fontSize(9)
              .fontFamily('monospace')
              .letterSpacing(0.8)
              .fontColor('rgba(242,244,247,0.85)')
          }
          .padding({ left: 7, right: 7, top: 4, bottom: 4 })
          .backgroundColor('rgba(10,11,16,0.55)')
          .borderRadius(8)
          .position({ x: 10, y: 10 })

          Column({ space: 6 }) {
            Text('还没有任务')
              .fontSize(13)
              .fontWeight(FontWeight.Bold)
              .fontColor('rgba(242,244,247,0.85)')
            Text('提交后在这里显示采样画面和结果')
              .fontSize(10.5)
              .fontColor('rgba(242,244,247,0.55)')
          }
          .width('100%')
          .height('100%')
          .justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
          .translate({ y: -3 * this.floatPhase })
          .opacity(0.82 + 0.18 * this.floatPhase)
          .onAppear(() => this.startGentleFloat())
        }
        .width('100%')
        .height(300)
        .borderRadius(22)
        .clip(true)
        .backgroundColor(this.c().dialog)
        .border({ width: 1, color: this.c().borderStrong })

        if (this.submitNotice.length > 0 &&
          !this.isErrorDismissed(this.workflowScopeId('submit'), this.submitNotice)) {
          Row({ space: 8 }) {
            Text(this.submitNotice)
              .fontSize(11)
              .fontColor(this.submitNotice.startsWith('已提交') ? this.c().ok : this.c().err)
              .layoutWeight(1)
            this.DismissButton(() => this.dismissError(this.workflowScopeId('submit'), this.submitNotice))
          }
          .width('100%')
          .alignItems(VerticalAlign.Top)
        } else {
          Text('这个工作流还没有任务。')
            .fontSize(11)
            .fontColor(this.c().textDim)
            .width('100%')
        }
        Row() {
          Blank()
          this.GhostAction('查看全部任务', true, () => {
            this.tab = 1;
            this.openTasks();
          })
        }
        .width('100%')
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
    } else {
      Column({ space: 10 }) {
        Stack() {
          this.HeroBackdrop()
          Row({ space: 5 }) {
            Text('RESULT')
              .fontSize(9)
              .fontFamily('monospace')
              .letterSpacing(0.8)
              .fontColor('rgba(242,244,247,0.85)')
          }
          .padding({ left: 7, right: 7, top: 4, bottom: 4 })
          .backgroundColor('rgba(10,11,16,0.55)')
          .borderRadius(8)
          .position({ x: 10, y: 10 })

          if (this.firstImageUrl(task).length > 0) {
            this.RemoteImage(this.firstImageUrl(task), ImageFit.Contain)

          } else {
            Column() {
              Text(task.state === 'Failed' ? '任务失败' : '没有可预览的图片')
                .fontSize(12)
                .fontColor('rgba(242,244,247,0.7)')
            }
            .width('100%')
            .height('100%')
            .justifyContent(FlexAlign.Center)
            .alignItems(HorizontalAlign.Center)
          }
        }
        .width('100%')
        .height(330)
        .borderRadius(22)
        .clip(true)
        .backgroundColor(this.c().dialog)
        .border({ width: 1, color: this.c().borderStrong })

        Row() {
          Column({ space: 2 }) {
            Text(this.taskStateName(task.state))
              .fontSize(13)
              .fontWeight(FontWeight.Bold)
              .fontColor(this.c().text)
            Text(this.taskLabel(task))
              .fontSize(9.5)
              .fontFamily('monospace')
              .fontColor(this.c().textDim)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
          }
          .alignItems(HorizontalAlign.Start)
          .layoutWeight(1)
          Text(`${task.percent}`)
            .fontSize(30)
            .fontWeight(FontWeight.Bold)
            .letterSpacing(-0.8)
            .fontColor(this.c().accent)
          Text('%')
            .fontSize(10)
            .fontColor(this.c().textDim)
            .padding({ left: 2, bottom: 5 })
        }
        .width('100%')
        .alignItems(VerticalAlign.Bottom)

        if (task.error !== null && task.error.length > 0 &&
          !this.isErrorDismissed(task.id, task.error)) {
          Row({ space: 8 }) {
            Text(`任务失败：${task.error}`)
              .fontSize(11)
              .lineHeight(16)
              .fontColor(this.c().err)
              .layoutWeight(1)
            this.DismissButton(() => {
              if (task.error !== null) {
                this.dismissError(task.id, task.error);
              }
            })
          }
          .width('100%')
          .alignItems(VerticalAlign.Top)
        } else if (task.outputRefs.length > 0 && this.firstImageUrl(task).length === 0) {
          Text(`产物 ${task.outputRefs.length} 个；该类型暂不内置预览。`)
            .fontSize(10.5)
            .fontFamily('monospace')
            .fontColor(this.c().textDim)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .width('100%')
        }

        if (task.outputRefs.length > 0) {
          Row({ space: 9 }) {
            this.GhostAction('查看产物', task.outputRefs.length > 0, () => {
              this.openArtifacts(task);
            })
            this.GhostAction('查看全部任务', true, () => {
              this.tab = 1;
              this.openTasks();
            })
          }
          .width('100%')
        }
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
    }
  }

  /** 预览 Hero 的底：固定深色 + 四团彩光，和 Android SpectrumArt 一致（不跟随深浅色）。 */
  @Builder
  HeroBackdrop() {
    Stack() {
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor('#12161C')
      Column()
        .width(380)
        .height(380)
        .borderRadius(190)
        .radialGradient({
          center: [190, 190],
          radius: 190,
          colors: [['#FF9A63', 0.0], ['rgba(255,154,99,0)', 1.0]],
        })
        .position({ x: -93, y: -117 })
      Column()
        .width(300)
        .height(300)
        .borderRadius(150)
        .radialGradient({
          center: [150, 150],
          radius: 150,
          colors: [['#7B5CFF', 0.0], ['rgba(123,92,255,0)', 1.0]],
        })
        .position({ x: 96, y: -58 })
      Column()
        .width(430)
        .height(430)
        .borderRadius(215)
        .radialGradient({
          center: [215, 215],
          radius: 215,
          colors: [['#24C79A', 0.0], ['rgba(36,199,154,0)', 1.0]],
        })
        .position({ x: -40, y: 62 })
      Column()
        .width(260)
        .height(260)
        .borderRadius(130)
        .radialGradient({
          center: [130, 130],
          radius: 130,
          colors: [['#FFD166', 0.0], ['rgba(255,209,102,0)', 1.0]],
        })
        .position({ x: -72, y: 127 })
    }
    .width('100%')
    .height('100%')
  }

  /**
   * 渐变进度条，对应 Android SpectrumProgress。
   * 填充不能用百分比宽度：Stack 子元素的 '%' 依赖父容器已确定的尺寸，
   * 在按内容撑开的布局里会解析成 0——之前"轨道在、填充永远空白"就是这个原因。
   * 现在量出轨道实际宽度（vp），按比例换算成绝对宽度。
   */
  /**
   * 进度条：直接用系统 Progress，不再手写"轨道 + 百分比宽度填充"。
   * 之前手写两个原因都不成立：Stack 子元素的 % 宽度会解析成 0；onAreaChange 在
   * 这个位置也不保证回调，于是填充永远是空的。Progress 的 value/total 是确定值，必定渲染。
   */
  @Builder
  ProgressBar(fraction: number) {
    Row() {
      // 用 layoutWeight 分配宽度：不依赖百分比解析、也不需要测量回调，布局引擎必定给出可见宽度。
      Row()
        .layoutWeight(this.progressWeight(fraction))
        .height(7)
        .backgroundColor(this.c().accent)
      Row()
        .layoutWeight(1000 - this.progressWeight(fraction))
        .height(7)
    }
    .width('100%')
    .height(7)
    .borderRadius(999)
    .backgroundColor(this.c().cardStrong)
    .clip(true)
    .animation({ duration: 320, curve: Curve.EaseOut })
  }

  /** 任务卡里的细进度条（5vp），同 Android TaskCard 的 SpectrumProgress(height = 5.dp)。 */
  @Builder
  TaskProgressBar(fraction: number) {
    Row() {
      Row()
        .layoutWeight(this.progressWeight(fraction))
        .height(5)
        .backgroundColor(this.c().accent)
      Row()
        .layoutWeight(1000 - this.progressWeight(fraction))
        .height(5)
    }
    .width('100%')
    .height(5)
    .borderRadius(999)
    .backgroundColor(this.c().cardStrong)
    .clip(true)
    .animation({ duration: 320, curve: Curve.EaseOut })
  }

  /**
   * 填充权重：ArkUI 的 layoutWeight 按整数参与分配，直接传 0.45 这类小数会被截断成 0，
   * 表现为"轨道在、百分比正常、填充永远为空"。这里放大 1000 倍后取整，两端都 ≥1，
   * 既保留 0.1% 的精度，也不会出现 0 权重。
   */
  private progressWeight(fraction: number): number {
    if (!(fraction > 0)) {
      return 0;
    }
    const clamped: number = fraction > 1 ? 1 : fraction;
    return Math.round(clamped * 1000);
  }

  /** 全部节点底部面板：搜索 + 特征筛选 + 节点列表，对应 Android ModalBottomSheet。 */
  @Builder
  AllNodesSheet() {
    Stack({ alignContent: Alignment.Bottom }) {
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor('rgba(0,0,0,0.55)')
        .onClick(() => {
          this.allNodesOpen = false;
        })

      Column() {
        Row({ space: 8 }) {
          Text('全部节点')
            .fontSize(15)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.c().text)
          Text(`${this.filteredNodes().length} / ${this.view.nodes.length}`)
            .fontSize(10)
            .fontFamily('monospace')
            .fontColor(this.c().textDim)
          Blank()
          Text('关闭')
            .fontSize(12)
            .fontColor(this.c().textMuted)
            .padding({ left: 7, right: 7, top: 7, bottom: 7 })
            .borderRadius(8)
            .onClick(() => {
              this.allNodesOpen = false;
            })
        }
        .width('100%')
        .padding({ left: 18, right: 18, top: 4, bottom: 8 })
        .alignItems(VerticalAlign.Center)

        Row() {
          TextInput({ text: this.nodeQuery, placeholder: '搜索名称、类型、#id、备注' })
            .fontSize(12.5)
            .fontColor(this.c().text)
            .placeholderColor(this.c().textDim)
            .caretColor(this.c().accent)
            .backgroundColor(this.c().card)
            .borderRadius(12)
            .border({ width: 1, color: this.c().border })
            .height(38)
            .width('100%')
            .padding({ left: 12, right: 12 })
            .onChange((value: string) => {
              this.nodeQuery = value;
            })
        }
        .width('100%')
        .padding({ left: 18, right: 18 })

        Scroll() {
          Row({ space: 6 }) {
            this.Chip('图像输入', this.traitFilter.includes(0), () => this.toggleTrait(0))
            this.Chip('文本输入', this.traitFilter.includes(1), () => this.toggleTrait(1))
            this.Chip('数值输入', this.traitFilter.includes(2), () => this.toggleTrait(2))
            this.Chip('待填写', this.traitFilter.includes(3), () => this.toggleTrait(3))
          }
          .padding({ left: 18, right: 18 })
        }
        .scrollable(ScrollDirection.Horizontal)
        .scrollBar(BarState.Off)
        .width('100%')
        .height(36)
        .align(Alignment.Start)

        Scroll() {
          Column() {
            if (this.filteredNodes().length === 0) {
              Text(this.nodeQuery.length > 0 || this.traitFilter.length > 0
                ? '没有匹配的节点。'
                : '这个工作流没有节点。')
                .fontSize(11.5)
                .fontColor(this.c().textDim)
                .width('100%')
                .padding({ left: 18, right: 18, top: 18, bottom: 18 })
            }
            ForEach(this.filteredNodes(), (node: WorkflowNodeView) => {
              Row() {
                Column({ space: 2 }) {
                  Text(`#${node.id} ${this.nodeTitle(node)}`)
                    .fontSize(12.5)
                    .fontColor(this.c().text)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text(this.noteOf(node.id).length > 0
                    ? `${node.type} · ${node.displayName}`
                    : node.type)
                    .fontSize(9.5)
                    .fontFamily('monospace')
                    .fontColor(this.c().textDim)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                }
                .alignItems(HorizontalAlign.Start)
                .layoutWeight(1)
                Text(this.nodeStateLabel(node))
                  .fontSize(10)
                  .fontFamily('monospace')
                  .fontColor(this.c().textDim)
              }
              .width('100%')
              .padding({ left: 18, right: 18, top: 11, bottom: 11 })
              .alignItems(VerticalAlign.Center)
              .onClick(() => {
                if (!this.expandedSeed) {
                  this.expandedIds = this.autoExpandedIds();
                  this.expandedSeed = true;
                }
                if (!this.expandedIds.includes(node.id)) {
                  this.expandedIds = [...this.expandedIds, node.id];
                }
                this.detailTab = 0;
                this.allNodesOpen = false;
              })
              Row()
                .width('100%')
                .height(1)
                .backgroundColor(this.c().border)
            }, (node: WorkflowNodeView) =>
              `${node.id}:${node.missing}:${node.reachable}:${node.fields.length}:${this.nodeNotesVersion}`)
          }
          .width('100%')
        }
        .layoutWeight(1)
        .width('100%')
        .scrollBar(BarState.Off)
        .align(Alignment.Top)
      }
      .width('100%')
      .height('78%')
      // 面板贴底，最后一行不能被导航条盖住。 
      .padding({ bottom: px2vp(this.navBarPx) })
      .backgroundColor(this.c().dialog)
      .borderRadius({ topLeft: 22, topRight: 22 })
      .border({ width: 1, color: this.c().border })
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  WorkflowsTab() {
    if (this.selectedPath.length === 0) {
      this.WorkflowCatalog()
    } else {
      Scroll(this.scroller) {
        Column({ space: 8 }) {
          this.WorkflowDetail()
        }
        .width('100%')
      }
      .layoutWeight(1)
      .width('100%')
    }
  }

  /**
   * 任务卡：对应 Android TaskCard —— 左侧 3vp 状态色轨、等宽任务名 + 状态胶囊、 
   * 工作流路径、产物缩略图与「输出：…」摘要、细进度条 + 百分比、可关闭的错误行， 
   * 以及底部「查看产物」（产物面板入口，行为与改造前一致）。 
   */
  @Builder
  TaskCard(task: BridgeTask) {
    Stack({ alignContent: Alignment.TopStart }) {
      Column({ space: 7 }) {
      Row({ space: 8 }) {
        Text(this.taskLabel(task))
          .fontSize(11.5)
          .fontWeight(FontWeight.Medium)
          .fontFamily('monospace')
          .fontColor(this.c().text)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
        this.StatusTag(this.taskStateName(task.state),
          this.taskToneColor(task.state), this.taskToneBackground(task.state))
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)

      if (task.workflowPath !== null && task.workflowPath.length > 0) {
        Text(task.workflowPath)
          .fontSize(9.5)
          .fontFamily('monospace')
          .fontColor(this.c().textDim)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
      }

      if (this.taskShowsProgress(task)) {
        Row({ space: 8 }) {
          Column() {
            Row() {
          Row()
            .layoutWeight(this.progressWeight(task.percent / 100))
            .height(5)
            .linearGradient({ angle: 90, colors: Spectrum.gradientColors(this.c()) })
          Row()
            .layoutWeight(1000 - this.progressWeight(task.percent / 100))
            .height(5)
        }
        .width('100%')
        .height(5)
        .borderRadius(999)
        .backgroundColor(this.c().cardStrong)
        .clip(true)
        .animation({ duration: 320, curve: Curve.EaseOut })          }
          .layoutWeight(1)
          Text(`${task.percent}%`)
            .fontSize(9.5)
            .fontFamily('monospace')
            .fontColor(this.c().textDim)
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
      }

      if (this.taskThumbnails(task).length > 0) {
        Row({ space: 6 }) {
          ForEach(this.taskThumbnails(task), (output: BridgeOutput) => {
            Image(this.artifactUrl(output))
              .width(46)
              .height(46)
              .borderRadius(9)
              .objectFit(ImageFit.Cover)
              .backgroundColor(this.c().cardStrong)
              .onClick(() => this.openArtifacts(task))
          }, (output: BridgeOutput) => this.artifactKey(output))
        }
        .width('100%')
      }

      if (task.outputRefs.length > 0) {
        Text(this.taskOutputLine(task))
          .fontSize(10)
          .fontFamily('monospace')
          .fontColor(this.c().textDim)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
      }

      if (task.error !== null && task.error.length > 0 &&
        !this.isErrorDismissed(task.id, task.error)) {
        Row({ space: 8 }) {
          Text(task.error)
            .fontSize(10.5)
            .lineHeight(15)
            .fontColor(this.c().err)
            .maxLines(3)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
          this.DismissButton(() => {
            if (task.error !== null) {
              this.dismissError(task.id, task.error);
            }
          })
        }
        .width('100%')
        .alignItems(VerticalAlign.Top)
      }

      // 运行中/提交中的卡片可以取消；还没拿到 promptId 的草稿任务只显示禁用态。 
      if (task.outputRefs.length > 0 || this.taskCancelVisible(task)) {
        Row({ space: 8 }) {
          if (this.taskCancelVisible(task)) {
            this.GhostAction('取消', this.taskCancellable(task), () => this.cancelTask(task),
              `cancel-${task.id}`)
          }
          if (task.outputRefs.length > 0) {
            this.GhostAction('查看产物', true, () => this.openArtifacts(task), `outputs-${task.id}`)
          }
        }
        .width('100%')
      }
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
    .padding({ left: 12, right: 12, top: 11, bottom: 11 })
    .backgroundColor(this.c().card)
    .borderRadius(16)
    .border({
      width: { left: 3, top: 1, right: 1, bottom: 1 },
      color: {
        left: this.taskRailColor(task.state),
        top: this.c().border,
        right: this.c().border,
        bottom: this.c().border,
      },
    })
    .onClick(() => {
      if (task.outputRefs.length > 0) {
        this.openArtifacts(task);
      }
    })

      // 状态轨颜色过渡：旧颜色叠在左轨上淡出，露出下面的新状态色。
      if (this.taskRailPrevious(task) !== '') {
        Row()
          .width(3)
          .height('100%')
          .backgroundColor(this.taskRailPrevious(task))
          .borderRadius({ topLeft: 16, bottomLeft: 16 })
          .opacity(this.railFade)
          .hitTestBehavior(HitTestMode.None)
          .onAppear(() => this.startRailFade())
      }
      // 完成脉冲：一圈绿色描边从 0.75 透明度淡出，只叠一层，不改内部布局。
      if (this.pulseTaskId === task.id) {
        Column()
          .width('100%')
          .height('100%')
          .borderRadius(16)
          .border({ width: 1.5, color: this.c().ok })
          .opacity(this.donePulseAlpha * 0.75)
          .hitTestBehavior(HitTestMode.None)
          .onAppear(() => this.startDonePulse())
      }
    }
    .width('100%')
  }

  /** 预览卡当前使用的任务：任务页看全局，工作流详情看当前工作流。 */
  private liveCardTask(): BridgeTask | null {
    return this.tab === 1 ? this.previewTask() : this.activeWorkflowTask();
  }

  private liveCardPercent(): number {
    const task = this.liveCardTask();
    return task !== null ? task.percent : 0;
  }

  private liveCardState(): string {
    const task = this.liveCardTask();
    return task !== null ? task.state : '';
  }

  private liveCardStep(): string {
    return this.previewStepLabel(this.liveCardTask());
  }

  /**
   * 预览卡不接收 task 对象参数：@Builder 的对象参数在 ArkUI 里不会随任务快照刷新， 
   * 进度和采样图会停在首次渲染的值。这里每次都直接读 @State tasks。 
   */
  @Builder
  LivePreviewCard() {
    Column({ space: 6 }) {
      Row({ space: 8 }) {
        Text('采样预览')
          .fontSize(13)
          .fontWeight(FontWeight.Medium)
          .layoutWeight(1)
        if (this.livePreviewMeta !== null && this.livePreviewMatches(this.liveCardTask())) {
          Text(`${this.livePreviewMeta.bytes} B`)
            .fontSize(10)
            .fontColor(this.c().textDim)
        }
      }
      .width('100%')

      if (this.liveCardTask() !== null) {
        Row({ space: 8 }) {
          Text(`${this.liveCardPercent()}%`)
            .fontSize(10)
            .fontColor(this.c().textDim)
          Text(this.liveCardState())
            .fontSize(10)
            .fontColor(this.c().textDim)
            .layoutWeight(1)
        }
        .width('100%')
        Row() {
          Row()
            .layoutWeight(this.progressWeight(this.liveCardPercent() / 100))
            .height(7)
            .linearGradient({ angle: 90, colors: Spectrum.gradientColors(this.c()) })
          Row()
            .layoutWeight(1000 - this.progressWeight(this.liveCardPercent() / 100))
            .height(7)
        }
        .width('100%')
        .height(7)
        .borderRadius(999)
        .backgroundColor(this.c().cardStrong)
        .clip(true)
        .animation({ duration: 320, curve: Curve.EaseOut })
      }

      if (this.liveCardStep().length > 0) {
        Text(this.liveCardStep())
          .fontSize(10)
          .fontColor(this.c().textDim)
          .width('100%')
      }

      if (this.livePreview !== null && this.livePreviewMatches(this.liveCardTask())) {
        Image(this.livePreview as image.PixelMap)
          .width('100%')
          .height(180)
          .objectFit(ImageFit.Contain)
          .backgroundColor('#0E0F14')
          .borderRadius(14)
      } else if (this.livePreviewFailed && this.livePreviewMatches(this.liveCardTask())) {
        Text('预览帧解码失败')
          .fontSize(11)
          .fontColor(this.c().err)
      } else {
        Column({ space: 2 }) {
          Row({ space: 8 }) {
            LoadingProgress().width(18).height(18)
            Text('等待采样画面…')
              .fontSize(11)
              .fontColor(this.c().textMuted)
          }
          Text('若持续没有画面，确认 ComfyUI 启动参数包含 --preview-method auto')
            .fontSize(10)
            .fontColor(this.c().textDim)
        }
        .alignItems(HorizontalAlign.Start)
      }
    }
    .width('100%')
    .padding(10)
    .backgroundColor(this.c().card)
    .borderRadius(14)
  }

  /** 任务分组标题：11sp 粗体 + 计数胶囊，对应 Android SectionHeader 的排版。 */
  @Builder
  TasksSectionHeader(title: string, count: number) {
    Row({ space: 8 }) {
      Text(title)
        .fontSize(11)
        .fontWeight(FontWeight.Bold)
        .letterSpacing(0.4)
        .fontColor(this.c().textMuted)
      Text(`${count}`)
        .fontSize(9)
        .fontFamily('monospace')
        .fontColor(this.c().textDim)
        .padding({ left: 6, right: 6, top: 1, bottom: 1 })
        .borderRadius(999)
        .backgroundColor(this.c().cardStrong)
      Blank()
    }
    .width('100%')
    .padding({ left: 2, top: 4, bottom: 1 })
    .alignItems(VerticalAlign.Center)
  }

  /** 空状态：对应 Android TaskScreen 没有任务时的 GlassCard，文案原样搬过来。 */
  @Builder
  TasksEmptyState() {
    Column({ space: 4 }) {
      Text('还没有提交过任务。')
        .fontSize(12.5)
        .fontColor(this.c().textMuted)
        .width('100%')
      Text('在详情页调好参数后点「提交任务」，进度和产物会出现在这里。')
        .fontSize(11)
        .lineHeight(16)
        .fontColor(this.c().textDim)
        .width('100%')
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .padding(16)
    .margin({ top: 24 })
    .backgroundColor(this.c().card)
    .borderRadius(18)
    .border({ width: 1, color: this.c().border })
    .translate({ y: -3 * this.floatPhase })
    .opacity(0.82 + 0.18 * this.floatPhase)
    .onAppear(() => this.startGentleFloat())
  }

  /**
   * 任务页正文：连接信息行 + 最近事件 + 进行中卡片，然后按 
   * 进行中 / 排队中 / 已结束 分组列出任务卡；没有任务时给 Android 同款空状态。 
   */
  @Builder
  TasksTab() {
    Column({ space: 10 }) {
      // Dot + 连接标签 + 「· transport · 第 N 代」，同 Android TaskScreen 顶部那行。 
      Row({ space: 7 }) {
        Column()
          .width(6)
          .height(6)
          .borderRadius(3)
          .backgroundColor(this.running ? this.c().ok : this.c().textDim)
        Text(this.label.split(' · ')[0])
          .fontSize(11.5)
          .fontColor(this.c().textMuted)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(`· ${this.network} · 第 ${this.generation} 代`)
          .fontSize(10)
          .fontFamily('monospace')
          .fontColor(this.c().textDim)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)

      if (this.notice.length > 0) {
        Row({ space: 6 }) {
          Text('最近事件')
            .fontSize(10)
            .fontFamily('monospace')
            .fontColor(this.c().textDim)
          Text(this.notice)
            .fontSize(11)
            .fontColor(this.c().textMuted)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
        }
        .width('100%')
        .alignItems(VerticalAlign.Top)
      }

      // 进行中任务保留采样预览卡（Harmony 既有实现，Android 放在详情页）。 
      if (this.liveCardTask() !== null) {
        this.LivePreviewCard()
      }

      if (this.tasks.length === 0) {
        this.TasksEmptyState()
      } else {
        Scroll() {
          Column({ space: 9 }) {
            if (this.inProgressTasks().length > 0) {
              this.TasksSectionHeader('进行中', this.inProgressTasks().length)
              ForEach(this.inProgressTasks(), (task: BridgeTask) => {
                this.TaskCard(task)
              }, (task: BridgeTask) => this.taskCardKey(task))
            }
            if (this.queuedTasks().length > 0) {
              this.TasksSectionHeader('排队中', this.queuedTasks().length)
              ForEach(this.queuedTasks(), (task: BridgeTask) => {
                this.TaskCard(task)
              }, (task: BridgeTask) => this.taskCardKey(task))
            }
            if (this.finishedTasks().length > 0) {
              this.TasksSectionHeader('已完成', this.finishedTasks().length)
              ForEach(this.finishedTasks(), (task: BridgeTask) => {
                this.TaskCard(task)
              }, (task: BridgeTask) => this.taskCardKey(task))
            }
          }
          .width('100%')
        }
        .layoutWeight(1)
        .width('100%')
        .scrollBar(BarState.Off)
      }
    }
    .width('100%')
    .layoutWeight(1)
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  LogsTab() {
    Scroll() {
      Column({ space: 2 }) {
        ForEach(this.logs, (line: string) => {
          Text(line)
          .fontColor(this.c().textMuted)
            .fontSize(11)
            .fontFamily('monospace')
            .width('100%')
        }, (line: string, index: number) => `${index}-${line}`)
      }
      .alignItems(HorizontalAlign.Start)
    }
    .layoutWeight(1)
    .width('100%')
    .backgroundColor('rgba(255,255,255,0.08)')
    .borderRadius(14)
    .padding(8)
  }

  /**
   * 面板玻璃底：`dialog` token 叠在页面同一层光谱光晕上。 
   * 深色 0.88 / 浅色 0.84，同 Android `c.dialog.copy(alpha = ...)`；不是一块实心底板。 
   */
  private glassPanelColor(): string {
    const base: string = this.c().dialog;
    const alpha: number = this.c().isDark ? 0.88 : 0.84;
    if (base.startsWith('#') && base.length === 7) {
      const value: number = Math.round(alpha * 255);
      const hex: string = value < 16 ? `0${value.toString(16)}` : value.toString(16);
      // ArkUI 的 8 位十六进制颜色是 #AARRGGBB，Alpha 放最前面。 
      return `#${hex.toUpperCase()}${base.substring(1)}`;
    }
    return base;
  }

  /** 产物类型文案，同 Android artifactMeta 的 kind 段。 */
  private artifactKindLabel(output: BridgeOutput): string {
    if (output.image) {
      return '图片';
    }
    if (output.video) {
      return '视频';
    }
    if (output.audio) {
      return '音频';
    }
    return '文件';
  }

  /** 格式段：优先文件名扩展名，没有扩展名时退回共享层给的 kind。 */
  private artifactFormatLabel(output: BridgeOutput): string {
    const name: string = output.displayName.length > 0 ? output.displayName : output.filename;
    const dot: number = name.lastIndexOf('.');
    if (dot <= 0 || dot === name.length - 1) {
      return output.kind.toUpperCase();
    }
    return name.substring(dot + 1).toUpperCase();
  }

  /** 列表副标题与预览 meta：`类型 · 格式`，同 Android artifactMeta。 */
  private artifactMetaLine(output: BridgeOutput): string {
    return `${this.artifactKindLabel(output)} · ${this.artifactFormatLabel(output)}`;
  }

  /** 失败行只显示错误本身；`保存失败：` 前缀由行内补，避免重复。 */
  private downloadErrorLine(): string {
    const prefix: string = '保存失败：';
    return this.downloadNotice.startsWith(prefix)
      ? this.downloadNotice.substring(prefix.length)
      : this.downloadNotice;
  }

  private openPreview(output: BridgeOutput): void {
    this.previewOutput = output;
    animateTo({ duration: 220, curve: Curve.EaseOut }, () => {
      this.previewOpen = true;
    });
    this.previewError = false;
  }

  /** 分享：项目已引入剪贴板能力，复制产物直链（系统分享面板未接入）。 */
  private shareArtifact(output: BridgeOutput): void {
    const url: string = this.artifactUrl(output);
    if (url.length === 0) {
      return;
    }
    try {
      const data: pasteboard.PasteData = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, url);
      pasteboard.getSystemPasteboard().setDataSync(data);
      promptAction.showToast({ message: '已复制产物链接', duration: 1600 });
    } catch (err) {
      promptAction.showToast({ message: this.errorText(err), duration: 1600 });
    }
  }

  /** 取消按钮只在执行中/提交中的卡片上出现，同 Android TaskCard 的 isActive 判断。 */
  private taskCancelVisible(task: BridgeTask): boolean {
    return task.state === 'Running' || task.state === 'Submitting';
  }

  /** 共享层按 promptId 找任务取消；草稿任务（还没拿到 promptId）只能禁用。 */
  private taskCancellable(task: BridgeTask): boolean {
    return this.taskCancelVisible(task) && task.promptId !== null && task.promptId.length > 0;
  }

  /** 取消：envelope 与 BridgeProtocol.kt 的 `{ "t": "cancel", "promptId": ... }` 一致。 */
  private cancelTask(task: BridgeTask): void {
    const promptId: string | null = task.promptId;
    if (promptId === null || promptId.length === 0 || !this.taskCancelVisible(task)) {
      return;
    }
    this.bridge.cancelTask(promptId);
    this.log(`取消任务：${promptId}`);
  }

  /** 顶栏文字动作，对应 Android TopBarAction（关闭 / 分享）。 */
  @Builder
  PanelTextAction(label: string, emphasized: boolean, action: () => void) {
    Text(label)
      .fontSize(12)
      .fontWeight(emphasized ? FontWeight.Medium : FontWeight.Normal)
      .fontColor(emphasized ? this.c().accent : this.c().textMuted)
      .padding({ left: 7, right: 7, top: 7, bottom: 7 })
      .borderRadius(8)
      .onClick(() => action())
  }

  /** 36 高的渐变动作按钮，同 Android GradientButton(height = 36.dp)（产物行的「下载」）。 */
  @Builder
  ArtifactActionButton(label: string, enabled: boolean, action: () => void, pressKey: string = '') {
    Button(label)
      .fontSize(12)
      .fontWeight(FontWeight.Medium)
      .fontColor(this.c().accentInk)
      .height(36)
      .padding({ left: 16, right: 16 })
      .borderRadius(12)
      .backgroundColor(Color.Transparent)
      .linearGradient({ angle: 120, colors: Spectrum.gradientColors(this.c()) })
      .enabled(enabled)
      .scale(this.pressScaleOptions(pressKey.length > 0 ? pressKey : `artifact-${label}`))
      .onTouch((event: TouchEvent) =>
        this.onPressTouch(event, pressKey.length > 0 ? pressKey : `artifact-${label}`))
      .onClick(() => action())
  }

  /**
   * 视频首帧占位：鸿蒙侧没有 MediaMetadataRetriever，`/view` 对视频只回视频本体，
   * 没有可用的抽帧通道；这里按 Android 抽帧失败时的退路画哑光底 + 播放键 + 「视频」胶囊。 
   * 颜色都用调色板里深浅两套都够亮的 token（gradientStart / accentSoft），
   * 因为这块底板永远是近黑色，不能用会随主题翻转的正文色。 
   */
  @Builder
  VideoArtifactPlaceholder(label: string) {
    Stack({ alignContent: Alignment.TopStart }) {
      Column({ space: 8 }) {
        Column() {
          Polygon()
            .width(15)
            .height(18)
            .points([[0, 0], [15, 9], [0, 18]])
            .fill(this.c().gradientStart)
        }
        .width(48)
        .height(48)
        .borderRadius(24)
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
        .backgroundColor(this.c().accentSoft)
        .border({ width: 1, color: this.c().accentBorder })

        Text(label)
          .fontSize(11)
          .fontColor(this.c().gradientStart)
      }
      .width('100%')
      .height('100%')
      .justifyContent(FlexAlign.Center)
      .alignItems(HorizontalAlign.Center)

      Row() {
        Text('视频')
          .fontSize(9)
          .fontColor(this.c().gradientStart)
          .padding({ left: 8, right: 8, top: 3, bottom: 3 })
          .borderRadius(999)
          .backgroundColor(this.c().accentSoft)
          .border({ width: 1, color: this.c().accentBorder })
      }
      .padding(8)
    }
    .width('100%')
    .height('100%')
  }

  /** 网络图片：未加载完成时铺一层脉冲骨架，完成后 220ms 淡入（对应 Android 的 ShimmerBox + 淡入）。 */
  @Builder
  RemoteImage(url: string, fit: ImageFit) {
    Stack({ alignContent: Alignment.Center }) {
      if (!this.imageReady(url)) {
        Row()
          .width('100%')
          .height('100%')
          .backgroundColor(this.c().cardStrong)
          .opacity(this.skeletonPhase)
      }
      Image(url)
        .width('100%')
        .height('100%')
        .objectFit(fit)
        .opacity(this.imageReady(url) ? 1 : 0)
        .animation({ duration: 220, curve: Curve.EaseOut })
        .onComplete(() => this.markImageReady(url))
        .onError(() => this.markImageReady(url))
    }
    .width('100%')
    .height('100%')
    .onAppear(() => this.startSkeletonPulse())
  }

  private imageReady(url: string): boolean {
    return this.loadedImageUrls.indexOf(url) >= 0;
  }

  private markImageReady(url: string): void {
    if (url.length === 0 || this.loadedImageUrls.indexOf(url) >= 0) {
      return;
    }
    this.loadedImageUrls = this.loadedImageUrls.concat([url]);
  }
  /** 列表行的媒体位：16 圆角、近黑哑光、1px 边框，图片 Cover，视频走首帧占位。 */
  @Builder
  ArtifactMedia(output: BridgeOutput, mediaHeight: number, contain: boolean) {
    Stack({ alignContent: Alignment.Center }) {
      if (output.image && this.artifactUrl(output).length > 0) {
        this.RemoteImage(this.artifactUrl(output), contain ? ImageFit.Contain : ImageFit.Cover)

      } else if (output.video && this.artifactUrl(output).length > 0) {
        // 视频首帧：抽到帧就画 PixelMap，抽不到自动退回哑光占位（点击行为在外层 Stack）。 
        VideoThumb({ url: this.artifactUrl(output), isDark: this.isDark, hint: '点击播放' })
      } else {
        Text('该类型暂不支持内置预览，可下载后用系统应用打开。')
          .fontSize(12)
          .fontColor(this.c().textMuted)
          .padding(16)
      }
    }
    .width('100%')
    .height(mediaHeight)
    .borderRadius(16)
    .clip(true)
    .backgroundColor('#0E0F14')
    .border({ width: 1, color: this.c().border })
    .onClick(() => {
      if (this.artifactUrl(output).length > 0 && (output.image || output.video)) {
        this.openPreview(output);
      }
    })
  }

  /** 产物行：同 Android ArtifactRow —— 文件名、`类型 · 格式`、190 高媒体位、36 高下载按钮。 */
  @Builder
  ArtifactCard(output: BridgeOutput) {
    Column({ space: 8 }) {
      Text(output.displayName.length > 0 ? output.displayName : output.filename)
        .fontSize(15)
        .fontWeight(600)
        .fontColor(this.c().text)
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')

      Text(this.artifactMetaLine(output))
        .fontSize(10)
        .fontFamily('monospace')
        .letterSpacing(0.5)
        .fontColor(this.c().textDim)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')

      if (this.artifactUrl(output).length === 0) {
        Text('未连接 ComfyUI，无法预览或下载。')
          .fontSize(12)
          .fontColor(this.c().textMuted)
          .width('100%')
      } else if (output.image || output.video) {
        this.ArtifactMedia(output, 190, false)
      } else if (output.audio) {
        Text('音频产物：下载后可用系统播放器打开。')
          .fontSize(12)
          .fontColor(this.c().textMuted)
          .width('100%')
      } else {
        Text('该类型暂不支持内置预览，可下载后用系统应用打开。')
          .fontSize(12)
          .fontColor(this.c().textMuted)
          .width('100%')
      }

      // 失败时只留一个「重试」（ghost），不再同时出现第二个重试按钮。 
      if (this.downloadFailedKey === this.artifactKey(output)) {
        Row({ space: 8 }) {
          Text(`下载失败：${this.downloadErrorLine()}`)
            .fontSize(12)
            .lineHeight(17)
            .fontColor(this.c().err)
            .layoutWeight(1)
          this.GhostAction('重试', this.downloadingKey.length === 0, () => this.download(output))
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
      } else {
        Row() {
          this.ArtifactActionButton(
            this.downloadingKey === this.artifactKey(output) ? '下载中…' : this.artifactActionLabel(output),
            this.downloadingKey.length === 0 && this.artifactUrl(output).length > 0,
            () => this.download(output),
          )
        }
        .width('100%')
        .justifyContent(FlexAlign.End)
      }
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
    .padding(12)
    .backgroundColor(this.c().card)
    .borderRadius(16)
    .border({ width: 1, color: this.c().border })
  }

  /** 预览态：返回 + 文件名/ meta + 分享 + 关闭，媒体区 16 圆角裁剪。 */
  @Builder
  ArtifactPreview() {
    Row({ space: 4 }) {
      this.GhostAction('返回', true, () => {
        this.previewOpen = false;
        this.previewError = false;
      })
      Column({ space: 2 }) {
        Text(this.previewOutput.displayName.length > 0
          ? this.previewOutput.displayName : this.previewOutput.filename)
          .fontSize(15)
          .fontWeight(600)
          .fontColor(this.c().text)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
        Text(this.artifactMetaLine(this.previewOutput))
          .fontSize(10)
          .fontFamily('monospace')
          .fontColor(this.c().textDim)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)
      .padding({ left: 4, right: 4 })

      if (this.artifactUrl(this.previewOutput).length > 0) {
        this.PanelTextAction('分享', false, () => this.shareArtifact(this.previewOutput))
      }
      this.PanelTextAction('关闭', true, () => this.closeArtifacts())
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)

    Column() {
      if (this.artifactUrl(this.previewOutput).length === 0) {
        Column() {
          Text('未连接 ComfyUI，无法预览。')
            .fontSize(12.5)
            .fontColor(this.c().textMuted)
        }
        .padding(16)
        .backgroundColor(this.c().card)
        .borderRadius(16)
        .border({ width: 1, color: this.c().border })
      } else if (this.previewOutput.image) {
        if (this.previewError) {
          Text('图片预览失败，可能是网络中断或服务端返回了错误。')
            .fontSize(12.5)
            .fontColor(this.c().textMuted)
            .padding(24)
        } else {
          this.RemoteImage(this.artifactUrl(this.previewOutput), ImageFit.Contain)

        }
      } else if (this.previewOutput.video) {
        // 预览区同列表行：首帧抽到就 Cover 铺满，抽不到退回原占位。 
        VideoThumb({ url: this.artifactUrl(this.previewOutput), isDark: this.isDark, hint: '点击播放' })
      } else {
        Text('该类型暂不支持内置预览，请下载后打开。')
          .fontSize(12)
          .fontColor(this.c().textMuted)
          .padding(24)
      }
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
    .backgroundColor('#0E0F14')
    .borderRadius(16)
    .clip(true)
    .border({ width: 1, color: this.c().border })

    // 没有播放器接入，文案放在哑光区外，普通背景上才看得清。 
    if (this.previewOutput.video && this.artifactUrl(this.previewOutput).length > 0) {
      Text('鸿蒙暂未接入内置播放器，可先保存到相册后用系统应用播放。')
        .fontSize(10.5)
        .fontColor(this.c().textMuted)
        .width('100%')
        .textAlign(TextAlign.Center)
    }

    if (this.downloadNotice.length > 0 &&
      this.downloadFailedKey !== this.artifactKey(this.previewOutput)) {
      Text(this.downloadNotice)
        .fontSize(11)
        .fontColor(this.downloadNoticeColor())
        .width('100%')
    }

    // 和列表行一致：失败时只留一个 ghost「重试」，不再叠一个渐变下载按钮。 
    if (this.downloadFailedKey === this.artifactKey(this.previewOutput)) {
      Row({ space: 8 }) {
        Text(`下载失败：${this.downloadErrorLine()}`)
          .fontSize(12)
          .lineHeight(17)
          .fontColor(this.c().err)
          .layoutWeight(1)
        this.GhostAction('重试', this.downloadingKey.length === 0, () => this.download(this.previewOutput))
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
    } else {
      Row() {
        this.ArtifactActionButton(
          this.downloadingKey === this.artifactKey(this.previewOutput)
            ? '下载中…' : this.artifactActionLabel(this.previewOutput),
          this.downloadingKey.length === 0 && this.artifactUrl(this.previewOutput).length > 0,
          () => this.download(this.previewOutput),
        )
      }
      .width('100%')
      .justifyContent(FlexAlign.End)
    }
  }

  /**
   * 产物面板：玻璃底（dialog 0.88/0.84 + 22 圆角 + 1px borderStrong）浮在页面同一层光晕上， 
   * 列表 / 预览 / 空态三态同 Android ArtifactDialog。 
   */
  @Builder
  ArtifactPanel() {
    Column() {
      Column({ space: 8 }) {
        if (this.previewOpen) {
          // 单张产物预览：0.92→1 的缩放 + 淡入，和面板列表区分开。
          Column() {
            this.ArtifactPreview()
          }
          .width('100%')
          .layoutWeight(1)
          .alignItems(HorizontalAlign.Start)
          .transition(TransitionEffect.OPACITY
            .combine(TransitionEffect.scale({ x: 0.92, y: 0.92 }))
            .animation({ duration: 220, curve: Curve.EaseOut }))
        } else {
          Row({ space: 8 }) {
            Column({ space: 2 }) {
              Text('产物')
                .fontSize(15)
                .fontWeight(600)
                .letterSpacing(-0.2)
                .fontColor(this.c().text)
                .maxLines(1)
              Text(this.taskLabel(this.artifactTask))
                .fontSize(10)
                .fontFamily('monospace')
                .fontColor(this.c().textDim)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
            }
            .layoutWeight(1)
            .alignItems(HorizontalAlign.Start)

            this.PanelTextAction('关闭', true, () => this.closeArtifacts())
          }
          .width('100%')
          .padding({ left: 2, right: 0, top: 2, bottom: 4 })
          .alignItems(VerticalAlign.Center)

          if (this.downloadNotice.length > 0 && this.downloadFailedKey.length === 0) {
            Text(this.downloadNotice)
              .fontSize(11)
              .fontColor(this.downloadNoticeColor())
              .width('100%')
          }

          if (this.artifactTask.outputRefs.length === 0) {
            Column() {
              Text('该任务没有产物。')
                .fontSize(12.5)
                .fontColor(this.c().textMuted)
            }
            .width('100%')
            .alignItems(HorizontalAlign.Start)
            .padding(16)
            .backgroundColor(this.c().card)
            .borderRadius(16)
            .border({ width: 1, color: this.c().border })
          } else {
            Scroll() {
              Column({ space: 10 }) {
                ForEach(this.artifactTask.outputRefs, (output: BridgeOutput) => {
                  this.ArtifactCard(output)
                }, (output: BridgeOutput) => this.artifactKey(output))
              }
              .width('100%')
            }
            .layoutWeight(1)
            .width('100%')
            .align(Alignment.Top)
            .scrollBar(BarState.Off)
          }
        }
      }
      .width('100%')
      .layoutWeight(1)
      .padding({ left: 14, right: 14, top: 12, bottom: 12 })
      .backgroundColor(this.glassPanelColor())
      .borderRadius(22)
      .border({ width: 1, color: this.c().borderStrong })
    }
    .width('100%')
    .height('100%')
    .padding({
      left: 8,
      right: 8,
      top: 8 + px2vp(this.statusBarPx),
      bottom: 8 + px2vp(this.navBarPx),
    })
  }

  @Builder
  SubmitConfirmDialog() {
    Column() {
      Column({ space: 10 }) {
        Text('提交前确认')
          .fontSize(17)
          .fontWeight(FontWeight.Bold)
        Text('以下提示不会阻止提交：')
          .fontSize(12)
          .fontColor(this.c().textMuted)
        ForEach(this.confirmWarnings, (warning: string, index: number) => {
          Text(`· ${warning}`)
            .fontSize(12)
            .fontColor(this.c().warn)
        }, (warning: string, index: number) => `${index}:${warning}`)
        Row({ space: 8 }) {
          Button('取消')
            .onClick(() => this.cancelSubmitConfirm())
          Button('仍然提交')
            .onClick(() => this.confirmSubmit())
        }
        .width('100%')
        .justifyContent(FlexAlign.End)
      }
      .padding(16)
      .width('88%')
      .backgroundColor(Color.White)
      .borderRadius(12)
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .backgroundColor('#66000000')
  }

  /**
   * 关闭关键节点前的确认框，对应 Android 的 BypassConfirm + SpectrumDialog。 
   *
   * 只有共享层判定 `breaking` 才会走到这里：提示语固定，原因逐字显示共享层给的 
   * `reason`（宿主不自己编原因）。取消 = 什么都不改，节点仍然启用。 
   */
  @Builder
  BypassConfirmDialog() {
    Column() {
      Column({ space: 10 }) {
        Text('关闭节点')
          .fontSize(16)
          .fontWeight(FontWeight.Bold)
          .fontColor(this.c().text)
        Text('关闭该节点可能导致工作流无法运行，是否确认关闭')
          .fontSize(12)
          .lineHeight(18)
          .fontColor(this.c().textMuted)
          .width('100%')
        if (this.bypassConfirmReason.length > 0) {
          Text(this.bypassConfirmReason)
            .fontSize(11)
            .lineHeight(16)
            .fontColor(this.c().err)
            .width('100%')
        }
        Row({ space: 8 }) {
          Button('取消')
            .fontSize(12.5)
            .fontColor(this.c().textMuted)
            .height(38)
            .padding({ left: 16, right: 16 })
            .borderRadius(12)
            .backgroundColor(this.c().card)
            .border({ width: 1, color: this.c().borderStrong })
            .onClick(() => this.cancelNodeBypass())
          Button('仍要关闭')
            .fontSize(12.5)
            .fontColor(this.c().err)
            .height(38)
            .padding({ left: 16, right: 16 })
            .borderRadius(12)
            .backgroundColor(this.c().errSoft)
            .border({ width: 1, color: this.c().err })
            .onClick(() => this.confirmNodeBypass())
        }
        .width('100%')
        .justifyContent(FlexAlign.End)
      }
      .padding(18)
      .width('84%')
      .alignItems(HorizontalAlign.Start)
      .backgroundColor(this.c().dialog)
      .borderRadius(18)
      .border({ width: 1, color: this.c().borderStrong })
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .backgroundColor('#66000000')
  }

  c(): Palette {
    return Spectrum.palette(this.isDark);
  }

  private systemIsDark(): boolean {
    return this.systemDark;
  }

  onSystemDarkChange(): void {
    if (this.appearanceMode === 'system') {
      this.applyAppearance();
    }
  }

  private applyAppearance(): void {
    this.isDark = this.appearanceMode === 'dark' ||
      (this.appearanceMode === 'system' && this.systemIsDark());
  }

  private setAppearance(mode: string): void {
    animateTo({ duration: 200, curve: Curve.EaseOut }, () => {
      this.appearanceMode = mode;
      this.applyAppearance();
    });
  }
  @Builder
  GradientAction(label: string, enabled: boolean, action: () => void, pressKey: string = '') {
    Button(label)
      .fontSize(13)
      .fontWeight(FontWeight.Medium)
      .fontColor(this.c().accentInk)
      .height(44)
      .padding({ left: 18, right: 18 })
      .borderRadius(14)
      .backgroundColor(Color.Transparent)
      .linearGradient({ angle: 120, colors: Spectrum.gradientColors(this.c()) })
      .enabled(enabled)
      .scale(this.pressScaleOptions(pressKey.length > 0 ? pressKey : `grad-${label}`))
      .onTouch((event: TouchEvent) =>
        this.onPressTouch(event, pressKey.length > 0 ? pressKey : `grad-${label}`))
      .onClick(() => action())
  }

  @Builder
  GhostAction(label: string, enabled: boolean, action: () => void, pressKey: string = '') {
    Button(label)
      .fontSize(12)
      .fontColor(this.c().textMuted)
      .height(36)
      .padding({ left: 14, right: 14 })
      .borderRadius(12)
      .backgroundColor(this.c().card)
      .border({ width: 1, color: this.c().border })
      .enabled(enabled)
      .scale(this.pressScaleOptions(pressKey.length > 0 ? pressKey : `ghost-${label}`))
      .onTouch((event: TouchEvent) =>
        this.onPressTouch(event, pressKey.length > 0 ? pressKey : `ghost-${label}`))
      .onClick(() => action())
  }

  @Builder
  StatusPill() {
    Text(this.running ? '已连接' : '未连接')
      .fontSize(10)
      .fontColor(this.running ? this.c().ok : this.c().textDim)
      .padding({ left: 10, right: 10, top: 4, bottom: 4 })
      .backgroundColor(this.running ? this.c().okSoft : this.c().card)
      .borderRadius(999)
      .border({ width: 1, color: this.running ? this.c().ok : this.c().border })
  }

  @Builder
  HeroArt() {
    Stack() {
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor(this.c().isDark ? '#12161C' : '#E8E4F5')
      Column()
        .width(220)
        .height(220)
        .borderRadius(110)
        .radialGradient({
          center: [110, 110],
          radius: 110,
          colors: [[this.c().isDark ? 'rgba(255,154,99,0.85)' : 'rgba(255,154,99,0.55)', 0.0],
            ['rgba(255,154,99,0)', 1.0]],
        })
        .position({ x: -30, y: -40 })
      Column()
        .width(190)
        .height(190)
        .borderRadius(95)
        .radialGradient({
          center: [95, 95],
          radius: 95,
          colors: [[this.c().isDark ? 'rgba(123,92,255,0.85)' : 'rgba(123,92,255,0.55)', 0.0],
            ['rgba(123,92,255,0)', 1.0]],
        })
        .position({ x: 190, y: -20 })
      Column()
        .width(230)
        .height(230)
        .borderRadius(115)
        .radialGradient({
          center: [115, 115],
          radius: 115,
          colors: [[this.c().isDark ? 'rgba(36,199,154,0.8)' : 'rgba(36,199,154,0.5)', 0.0],
            ['rgba(36,199,154,0)', 1.0]],
        })
        .position({ x: 110, y: 60 })
      Column()
        .width(170)
        .height(170)
        .borderRadius(85)
        .radialGradient({
          center: [85, 85],
          radius: 85,
          colors: [[this.c().isDark ? 'rgba(255,209,102,0.7)' : 'rgba(255,209,102,0.45)', 0.0],
            ['rgba(255,209,102,0)', 1.0]],
        })
        .position({ x: -10, y: 70 })
      Text('ComfyPilot')
        .fontSize(13)
        .fontColor('#F2F4F7')
        .opacity(0.9)
        .position({ x: 18, y: 96 })
    }
    .width('100%')
    .height(150)
    .borderRadius(20)
    .clip(true)
    .border({ width: 1, color: this.c().border })
  }

  @Builder
  ConnectPanel() {
    Column() {
      // 顶栏：对应 Android SpectrumTopBar(titleOverride = Wordmark, actions = 设置 / 诊断)。 
      // Android 的「诊断」是独立 DebugScreen；鸿蒙把诊断日志并进了设置页，这里两个入口都进设置。 
      Row({ space: 2 }) {
        Row() {
          Text('Comfy')
            .fontSize(16)
            .fontWeight(FontWeight.Bold)
            .letterSpacing(-0.3)
            .fontColor(this.c().text)
          // Wordmark 在 Android 上是渐变字；ArkUI 文本不支持渐变画刷，用强调色近似。 
          Text('Pilot')
            .fontSize(16)
            .fontWeight(FontWeight.Bold)
            .letterSpacing(-0.3)
            .fontColor(this.c().accent)
        }
        .layoutWeight(1)
        .alignItems(VerticalAlign.Bottom)

        Text('设置')
          .fontSize(12)
          .fontColor(this.c().textMuted)
          .padding({ left: 7, right: 7, top: 7, bottom: 7 })
          .borderRadius(8)
          .onClick(() => {
            this.goPage(true, () => {
              this.settingsOpen = true;
            });
          })
        Text('诊断')
          .fontSize(12)
          .fontColor(this.c().textMuted)
          .padding({ left: 7, right: 7, top: 7, bottom: 7 })
          .borderRadius(8)
          .onClick(() => {
            this.goPage(true, () => {
              this.settingsOpen = true;
            });
          })
      }
      .width('100%')
      .constraintSize({ minHeight: 46 })
      .alignItems(VerticalAlign.Center)

      Scroll() {
        Column({ space: 14 }) {
          Text('连接 ComfyUI')
            .fontSize(27)
            .fontWeight(FontWeight.Bold)
            .letterSpacing(-0.6)
            .fontColor(this.c().text)
            .width('100%')
          Text('连上后选择服务器上已有的工作流，调参数、提交任务、看采样。')
            .fontSize(12.5)
            .lineHeight(18)
            .fontColor(this.c().textMuted)
            .width('100%')

          // 地址卡：GlassCard(padding = 14.dp)。 
          Column() {
            Text('COMFYUI 地址')
              .fontSize(9.5)
              .fontColor(this.c().textDim)
              .fontFamily('monospace')
              .letterSpacing(1.1)
              .width('100%')

            // 对应 AddressField：输入框 + 右侧 ▾ 历史下拉。空输入时占位显示上次连通的地址。 
            Row() {
              TextInput({ text: this.baseUrl, placeholder: this.addressPlaceholder() })
                .fontSize(12.5)
                .fontFamily('monospace')
                .fontColor(this.c().text)
                .placeholderColor(this.c().textDim)
                .caretColor(this.c().accent)
                .backgroundColor(Color.Transparent)
                .borderRadius(0)
                .padding({ left: 0, right: 0, top: 8, bottom: 8 })
                .height(34)
                .layoutWeight(1)
                .enabled(!this.running)
                .onChange((value: string) => {
                  this.baseUrl = value;
                })
              Text('▾')
                .fontSize(11)
                .fontColor(this.addressHistory.length > 0 ? this.c().textMuted : this.c().textDim)
                .width(28)
                .height(28)
                .textAlign(TextAlign.Center)
                .borderRadius(9)
                .backgroundColor(this.addressMenuOpen ? this.c().accentSoft : this.c().card)
                .border({
                  width: 1,
                  color: this.addressMenuOpen ? this.c().accentBorder : this.c().border,
                })
                .margin({ left: 6 })
                // 输入框在连接期间是锁住的，历史下拉也不该能改地址。 
                .enabled(!this.running)
                .opacity(this.running ? 0.45 : 1)
                .onClick(() => {
                  this.addressMenuOpen = !this.addressMenuOpen;
                })
            }
            .width('100%')
            .padding({ left: 12, right: 12, top: 4, bottom: 4 })
            // 弹层挂在整张地址框上而不是右下角的 ▾，这样下拉的左边缘/宽度与输入框一致；
            // 面板宽度用实测的输入框宽度，避免固定 300vp 与窄屏/宽屏对不齐。
            .onAreaChange((_old: Area, area: Area) => {
              const width: number = Number(area.width);
              if (width > 0 && Math.abs(width - this.addressFieldWidth) > 0.5) {
                this.addressFieldWidth = width;
              }
            })
            .bindPopup(this.addressMenuOpen, {
              builder: this.AddressMenuPanel,
              placement: Placement.Bottom,
              popupColor: this.c().dialog,
              enableArrow: false,
              radius: 18,
              onStateChange: (event: PopupStateEvent) => {
                if (!event.isVisible) {
                  this.addressMenuOpen = false;
                }
              },
            })
            .backgroundColor(this.c().card)
            .borderRadius(12)
            .border({ width: 1, color: this.c().border })
            .margin({ top: 7 })
            .alignItems(VerticalAlign.Center)

            Row({ space: 9 }) {
              // GradientButton(weight = 1f, height = 46.dp)。 
              Button(this.running ? '重新连接' : '连接')
                .fontSize(14)
                .fontWeight(FontWeight.Bold)
                .fontColor(this.c().accentInk)
                .height(46)
                .padding({ left: 18, right: 18 })
                .borderRadius(14)
                .backgroundColor(Color.Transparent)
                .linearGradient({ angle: 120, colors: Spectrum.gradientColors(this.c()) })
                .layoutWeight(1)
                .scale(this.pressScaleOptions('connect'))
                .onTouch((event: TouchEvent) => this.onPressTouch(event, 'connect'))
                .onClick(() => {
                  this.goPage(true, () => {
                    this.connect();
                    this.connectView = false;
                  });
                })
              // GhostButton("断开", width = 84.dp, enabled = hasLease)。 
              Button('断开')
                .fontSize(12.5)
                .fontColor(this.c().textMuted)
                .height(42)
                .width(84)
                .padding({ left: 16, right: 16 })
                .borderRadius(14)
                .backgroundColor(this.c().card)
                .border({ width: 1, color: this.c().borderStrong })
                .enabled(this.running)
                .opacity(this.running ? 1 : 0.45)
                .scale(this.pressScaleOptions('disconnect'))
                .onTouch((event: TouchEvent) => this.onPressTouch(event, 'disconnect'))
                .onClick(() => this.disconnect())
            }
            .width('100%')
            .alignItems(VerticalAlign.Top)
            .margin({ top: 12 })

            if (this.running) {
              // 对应 Kotlin 里有租约时通栏的「选择工作流」。 
              Button('选择工作流')
                .fontSize(12.5)
                .fontColor(this.c().textMuted)
                .height(42)
                .width('100%')
                .padding({ left: 16, right: 16 })
                .borderRadius(14)
                .backgroundColor(this.c().card)
                .border({ width: 1, color: this.c().borderStrong })
                .margin({ top: 9 })
                .scale(this.pressScaleOptions('select-workflow'))
                .onTouch((event: TouchEvent) => this.onPressTouch(event, 'select-workflow'))
                .onClick(() => {
                  this.goPage(true, () => {
                    this.selectedPath = '';
                    this.connectView = false;
                  });
                })
            }
          }
          .width('100%')
          .padding(14)
          .backgroundColor(this.c().card)
          .borderRadius(18)
          .border({ width: 1, color: this.c().border })

          // SessionStatusCard：玻璃卡 / 状态点 / 连接标签 / GEN / 等宽信息行。 
          Column() {
            Row({ space: 7 }) {
              Column()
                .width(6)
                .height(6)
                .borderRadius(3)
                .backgroundColor(this.running ? this.c().ok : this.c().textDim)
              Text(this.label.split(' · ')[0])
                .fontSize(13.5)
                .fontWeight(600)
                .fontColor(this.c().text)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .layoutWeight(1)
              Text(`GEN ${this.generation}`)
                .fontSize(9.5)
                .fontColor(this.c().textDim)
                .fontFamily('monospace')
                .letterSpacing(0.6)
            }
            .width('100%')
            .alignItems(VerticalAlign.Center)

            Row() {
              Text('网络')
                .fontSize(9.5)
                .fontColor(this.c().textDim)
                .fontFamily('monospace')
                .letterSpacing(0.5)
                .width(64)
              Text(this.network)
                .fontSize(11.5)
                .fontColor(this.c().textMuted)
                .fontFamily('monospace')
                .layoutWeight(1)
            }
            .width('100%')
            .padding({ top: 17 })
            .alignItems(VerticalAlign.Top)

            // Android 的 clientId / 队列两行需要租约与服务器队列快照；鸿蒙状态里没有， 
            // 这里保留 clientId 占位（未连接时为 —），队列行暂不渲染。 
            Row() {
              Text('clientId')
                .fontSize(9.5)
                .fontColor(this.c().textDim)
                .fontFamily('monospace')
                .letterSpacing(0.5)
                .width(64)
              Text('—')
                .fontSize(11.5)
                .fontColor(this.c().textMuted)
                .fontFamily('monospace')
                .layoutWeight(1)
            }
            .width('100%')
            .padding({ top: 7 })
            .alignItems(VerticalAlign.Top)
          }
          .width('100%')
          .padding(14)
          .backgroundColor(this.c().card)
          .borderRadius(18)
          .border({ width: 1, color: this.c().border })

          // 已连接时顺手显示服务器性能；拿不到就整块不渲染。 
          if (this.running) {
            this.ServerStatsStrip()
          }

          if (this.notice.length > 0) {
            // notice?.let { 最近事件 … }：等宽小标签 + 正文。 
            Row({ space: 6 }) {
              Text('最近事件')
                .fontSize(10)
                .fontColor(this.c().textDim)
                .fontFamily('monospace')
              Text(this.notice)
                .fontSize(11)
                .fontColor(this.c().textMuted)
                .layoutWeight(1)
            }
            .width('100%')
            .alignItems(VerticalAlign.Top)
          }

          if (this.running) {
            Text('已连接，正在进入工作流列表；也可以点上面的「选择工作流」。')
              .fontSize(11)
              .fontColor(this.c().textDim)
              .width('100%')
          }

          Text('后台可能暂停监控，回来自动同步。')
            .fontSize(11)
            .fontColor(this.c().textDim)
            .width('100%')
            .margin({ top: 2, bottom: 24 })
        }
        .width('100%')
        // MainPanel 已给 14vp 外边距，这里补 4vp，对齐 Android 内容区的 18vp。 
        // 顶部 20vp = Kotlin 的 Spacer(6) + spacedBy(14)。 
        .padding({ left: 4, right: 4, top: 20 })
      }
      .layoutWeight(1)
      .width('100%')
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  CatalogPanel() {
    Column() {
      // 顶栏：对应 Android SpectrumTopBar(返回 + 工作流 + 设置 / 刷新 / 任务 / 断开)。 
      Row({ space: 2 }) {
        Text('返回')
          .fontSize(12)
          .fontColor(this.c().textMuted)
          .padding({ left: 7, right: 7, top: 7, bottom: 7 })
          .borderRadius(8)
          .margin({ right: 2 })
          .onClick(() => {
            this.goPage(false, () => {
              this.connectView = true;
            });
          })
        Text('工作流')
          .fontSize(15)
          .fontWeight(FontWeight.Bold)
          .letterSpacing(-0.2)
          .fontColor(this.c().text)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
        Text('设置')
          .fontSize(12)
          .fontColor(this.c().textMuted)
          .padding({ left: 7, right: 7, top: 7, bottom: 7 })
          .borderRadius(8)
          .onClick(() => {
            this.goPage(true, () => {
              this.settingsOpen = true;
            });
          })
        Text('刷新')
          .fontSize(12)
          .fontColor(this.running ? this.c().textMuted : this.c().textDim)
          .padding({ left: 7, right: 7, top: 7, bottom: 7 })
          .borderRadius(8)
          .enabled(this.running)
          .onClick(() => {
            this.catalogRequested = true;
            this.bridge.refreshWorkflows();
          })
        Text('任务')
          .fontSize(12)
          .fontColor(this.c().textMuted)
          .padding({ left: 7, right: 7, top: 7, bottom: 7 })
          .borderRadius(8)
          .onClick(() => {
            this.openTasks();
          })
        Text('断开')
          .fontSize(12)
          .fontColor(this.running ? this.c().textMuted : this.c().textDim)
          .padding({ left: 7, right: 7, top: 7, bottom: 7 })
          .borderRadius(8)
          .enabled(this.running)
          .onClick(() => {
            this.goPage(false, () => {
              this.disconnect();
            });
          })
      }
      .width('100%')
      .constraintSize({ minHeight: 46 })
      .alignItems(VerticalAlign.Center)

      Column({ space: 10 }) {
        // Dot + 连接标签 + 「· transport · 第 N 代」。 
        Row({ space: 7 }) {
          Column()
            .width(6)
            .height(6)
            .borderRadius(3)
            .backgroundColor(this.running ? this.c().ok : this.c().textDim)
          Text(this.label.split(' · ')[0])
            .fontSize(11.5)
            .fontColor(this.c().textMuted)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(`· ${this.network} · 第 ${this.generation} 代`)
            .fontSize(10)
            .fontColor(this.c().textDim)
            .fontFamily('monospace')
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)

        this.WorkflowCatalog()
      }
      .width('100%')
      .layoutWeight(1)
      // 同 ConnectPanel：补 4vp 对齐 Android 的 18vp 内容边距。 
      .padding({ left: 4, right: 4 })
    }
    .width('100%')
    .height('100%')
  }

  /** 全局光谱光晕：根页面与整屏覆盖页共用同一份定义，保证底色完全一致。 */
  @Builder
  AppGlow() {
    Column()
      .width(460)
      .height(460)
      .borderRadius(230)
      .radialGradient({
        center: [230, 230],
        radius: 230,
        colors: [['rgba(139,92,246,0.38)', 0.0], ['rgba(139,92,246,0.12)', 0.55],
          ['rgba(139,92,246,0)', 1.0]],
      })
      .position({ x: -150, y: -130 })
    Column()
      .width(400)
      .height(400)
      .borderRadius(200)
      .radialGradient({
        center: [200, 200],
        radius: 200,
        colors: [['rgba(94,234,212,0.26)', 0.0], ['rgba(94,234,212,0.08)', 0.55],
          ['rgba(94,234,212,0)', 1.0]],
      })
      .position({ x: 190, y: 60 })
    Column()
      .width(380)
      .height(380)
      .borderRadius(190)
      .radialGradient({
        center: [190, 190],
        radius: 190,
        colors: [['rgba(244,114,182,0.24)', 0.0], ['rgba(244,114,182,0.07)', 0.55],
          ['rgba(244,114,182,0)', 1.0]],
      })
      .position({ x: 20, y: 560 })
  }
  /** 任务页：顶栏（关闭 / 标题 + 队列计数 / 连接状态 / 刷新）+ 正文，对应 Android TaskScreen。 */
  /** 整屏覆盖页的底：纯色 + 与根页面完全相同的光晕（复用 AppGlow）。 */
  @Builder
  OverlayBackdrop() {
    Stack() {
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor(this.c().background)
      this.AppGlow()
    }
    .width('100%')
    .height('100%')
  }
  @Builder
  TasksPanel() {
    Stack() {
      this.OverlayBackdrop()
      Column({ space: 8 }) {
      Row({ space: 2 }) {
        Text('关闭')
          .fontSize(12)
          .fontColor(this.c().textMuted)
          .padding({ left: 7, right: 7, top: 7, bottom: 7 })
          .borderRadius(8)
          .margin({ right: 2 })
          .onClick(() => {
            this.closeTasks();
          })
        Column({ space: 2 }) {
          Text('任务')
            .fontSize(15)
            .fontWeight(FontWeight.Bold)
            .letterSpacing(-0.2)
            .fontColor(this.c().text)
            .maxLines(1)
          // 队列计数只在连接时可信（Android 同理：queue 为 null 时不显示队列行）。 
          Text(this.running
            ? `队列：运行 ${this.queueRunning} / 等待 ${this.queuePending}`
            : `历史任务 ${this.tasks.length} 个`)
            .fontSize(9.5)
            .fontFamily('monospace')
            .letterSpacing(0.4)
            .fontColor(this.c().textDim)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        this.StatusPill()
        Text('刷新')
          .fontSize(12)
          .fontColor(this.running ? this.c().textMuted : this.c().textDim)
          .padding({ left: 7, right: 7, top: 7, bottom: 7 })
          .borderRadius(8)
          .enabled(this.running)
          .onClick(() => {
            this.refreshTaskState();
          })
      }
      .width('100%')
      .padding({ left: 4, right: 4 })
      .constraintSize({ minHeight: 46 })
      .alignItems(VerticalAlign.Center)

      this.TasksTab()
    }
    // 任务面板是整屏覆盖层，不在 MainPanel 的内边距里，需要自己避开系统栏。 
    .padding({
      left: 14,
      right: 14,
      top: 14 + px2vp(this.statusBarPx),
      bottom: 14 + px2vp(this.navBarPx),
    })
      .width('100%')
      .height('100%')
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.c().background)
    .transition(TransitionEffect.OPACITY
      .combine(TransitionEffect.translate({ y: 120 }))
      .animation({ duration: 240, curve: Curve.EaseOut }))
  }

  @Builder
  SettingsPanel() {
    Row({ space: 8 }) {
      Text('设置')
        .fontSize(20)
        .fontWeight(FontWeight.Bold)
        .layoutWeight(1)
      this.GhostAction('返回', true, () => {
        this.goPage(false, () => {
          this.settingsOpen = false;
        });
      })
    }
    .width('100%')

    Text('外观').fontSize(11).fontColor(this.c().textDim)
    Row({ space: 6 }) {
      Button('跟随系统')
        .layoutWeight(1)
        .height(36)
        .fontSize(12)
        .borderRadius(11)
        .backgroundColor(this.appearanceMode === 'system' ? this.c().accentSoft : this.c().card)
        .fontColor(this.appearanceMode === 'system' ? this.c().text : this.c().textDim)
        .border({ width: 1, color: this.appearanceMode === 'system' ? this.c().accentBorder : this.c().border })
        .scale(this.pressScaleOptions('appearance-system'))
        .onTouch((event: TouchEvent) => this.onPressTouch(event, 'appearance-system'))
        .onClick(() => this.setAppearance('system'))
      Button('深色')
        .layoutWeight(1)
        .height(36)
        .fontSize(12)
        .borderRadius(11)
        .backgroundColor(this.appearanceMode === 'dark' ? this.c().accentSoft : this.c().card)
        .fontColor(this.appearanceMode === 'dark' ? this.c().text : this.c().textDim)
        .border({ width: 1, color: this.appearanceMode === 'dark' ? this.c().accentBorder : this.c().border })
        .scale(this.pressScaleOptions('appearance-dark'))
        .onTouch((event: TouchEvent) => this.onPressTouch(event, 'appearance-dark'))
        .onClick(() => this.setAppearance('dark'))
      Button('浅色')
        .layoutWeight(1)
        .height(36)
        .fontSize(12)
        .borderRadius(11)
        .backgroundColor(this.appearanceMode === 'light' ? this.c().accentSoft : this.c().card)
        .fontColor(this.appearanceMode === 'light' ? this.c().text : this.c().textDim)
        .border({ width: 1, color: this.appearanceMode === 'light' ? this.c().accentBorder : this.c().border })
        .scale(this.pressScaleOptions('appearance-light'))
        .onTouch((event: TouchEvent) => this.onPressTouch(event, 'appearance-light'))
        .onClick(() => this.setAppearance('light'))
    }
    .width('100%')

    Column({ space: 6 }) {
      Text('服务器').fontSize(11).fontColor(this.c().textDim)
      Text(this.baseUrl).fontSize(13).fontColor(this.c().text)
      Text(`ABI ${this.abi} · ${this.label}`).fontSize(10).fontColor(this.c().textDim)
      Text(this.storageLabel).fontSize(10).fontColor(this.c().textDim)
    }
    .alignItems(HorizontalAlign.Start)
    .padding(14)
    .backgroundColor(this.c().card)
    .borderRadius(16)
    .border({ width: 1, color: this.c().border })
    .width('100%')

    Text('诊断日志').fontSize(11).fontColor(this.c().textDim)
    this.LogsTab()

    // 设置页底部的「关于」入口；点进去后 aboutOpen 打开关于页，返回仍回到设置。 
    Row({ space: 8 }) {
      Text('关于')
        .fontSize(14)
        .fontColor(this.c().text)
        .layoutWeight(1)
      Text('版本 0.1.0')
        .fontSize(11)
        .fontColor(this.c().textDim)
      Text('›')
        .fontSize(16)
        .fontColor(this.c().textDim)
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
    .alignItems(VerticalAlign.Center)
    .backgroundColor(this.c().card)
    .borderRadius(16)
    .border({ width: 1, color: this.c().border })
    .onClick(() => {
      this.goPage(true, () => {
        this.aboutOpen = true;
      });
    })
  }

  /** 关于页的一行：左侧标签、右侧取值、末尾 chevron，整行可点。 */
  @Builder
  AboutRow(label: string, value: string, action: () => void) {
    Row({ space: 8 }) {
      Text(label)
        .fontSize(15)
        .fontColor(this.c().text)
        .layoutWeight(1)
      Text(value)
        .fontSize(12)
        .fontColor(this.c().textDim)
      Text('›')
        .fontSize(16)
        .fontColor(this.c().textDim)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .alignItems(VerticalAlign.Center)
    .onClick(action)
  }

  /** 许可证说明浮层：居中卡片，点「关闭」收起。 */
  @Builder
  LicensePanel() {
    Column({ space: 12 }) {
      Text('开源许可证')
        .fontSize(17)
        .fontWeight(FontWeight.Bold)
        .fontColor(this.c().text)
      Text('本项目以 GNU General Public License v3.0 发布；你可以自由使用、修改与分发，衍生作品需同样以 GPL-3.0 开源；完整协议见仓库根目录 LICENSE。')
        .fontSize(12)
        .lineHeight(19)
        .fontColor(this.c().textMuted)
        .width('100%')
      Row() {
        Button('关闭')
          .fontSize(12)
          .height(34)
          .fontColor(this.c().accentInk)
          .backgroundColor(this.c().accent)
          .borderRadius(10)
          .onClick(() => {
            this.licenseOpen = false;
          })
      }
      .width('100%')
      .justifyContent(FlexAlign.End)
    }
    .padding(18)
    .width('84%')
    .alignItems(HorizontalAlign.Start)
    .backgroundColor(this.c().dialog)
    .borderRadius(18)
    .border({ width: 1, color: this.c().borderStrong })
  }

  /** 关于页：图标 + 版本 + 简介 + 两行信息卡 + 脚注。 */
  @Builder
  AboutPanel() {
    Stack() {
      Column() {
        Row({ space: 2 }) {
          Text('‹ 返回')
            .fontSize(13)
            .fontColor(this.c().textDim)
            .padding({ left: 7, right: 7, top: 7, bottom: 7 })
            .borderRadius(8)
            .onClick(() => {
              this.goPage(false, () => {
                this.aboutOpen = false;
              });
            })
          Text('关于')
            .fontSize(15)
            .fontWeight(FontWeight.Bold)
            .letterSpacing(-0.2)
            .fontColor(this.c().text)
            .layoutWeight(1)
        }
        .width('100%')
        .constraintSize({ minHeight: 46 })
        .alignItems(VerticalAlign.Center)

        Scroll() {
          Column({ space: 18 }) {
            Column({ space: 10 }) {
              Column() {
                Image($r('app.media.startIcon'))
                  .width(96)
                  .height(96)
                  .objectFit(ImageFit.Cover)
              }
              .borderRadius(24)
              .clip(true)
              .backgroundColor(this.c().card)
              .border({ width: 1, color: this.c().border })

              Text('ComfyPilot')
                .fontSize(26)
                .fontWeight(FontWeight.Bold)
                .fontColor(this.c().text)
              Text('版本 0.1.0 · 鸿蒙版')
                .fontSize(10)
                .fontFamily('monospace')
                .fontColor(this.c().textDim)
            }
            .width('100%')
            .alignItems(HorizontalAlign.Center)
            .padding({ top: 10 })

            Text('一个 ComfyUI 远程客户端，支持工作流浏览、参数调节、任务队列与产物预览。共享核心由 Kotlin Multiplatform 实现，界面为 ArkTS 原生。')
              .fontSize(13)
              .lineHeight(20)
              .fontColor(this.c().textMuted)
              .textAlign(TextAlign.Center)
              .width('100%')
              .padding({ left: 8, right: 8 })

            Column() {
              this.AboutRow('项目作者', 'Fly-Noz', () => {
                this.copyAuthor();
              })
              Divider()
                .strokeWidth(1)
                .color(this.c().border)
                .margin({ left: 16, right: 16 })
              this.AboutRow('开源许可证', 'GPL-3.0', () => {
                this.licenseOpen = true;
              })
            }
            .width('100%')
            .backgroundColor(this.c().card)
            .borderRadius(18)
            .border({ width: 1, color: this.c().border })
            .clip(true)

            Text('本项目的部分界面、文案与图标由 AI 辅助生成。')
              .fontSize(10.5)
              .fontColor(this.c().textDim)
              .textAlign(TextAlign.Center)
              .width('100%')
              .padding({ left: 12, right: 12 })
          }
          .width('100%')
          .padding({ left: 4, right: 4, top: 4, bottom: 16 })
        }
        .layoutWeight(1)
        .width('100%')
        .scrollBar(BarState.Off)
      }
      .width('100%')
      .height('100%')

      if (this.licenseOpen) {
        // 遮罩用背景色加透明度，不写死颜色，深/浅色下都能压暗底层。 
        Column()
          .width('100%')
          .height('100%')
          .backgroundColor(this.c().background)
          .opacity(0.72)
        this.LicensePanel()
      }
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  MainPanel() {
    Stack() {
      // 页面容器放在 Stack 里：切换时新旧两页重叠做滑动/淡出，而不是在 Column 里上下排开。
      Stack({ alignContent: Alignment.TopStart }) {
        if (this.aboutOpen) {
          Column() {
            this.AboutPanel()
          }
          .width('100%')
          .height('100%')
          .transition(this.screenTransition())
        } else if (this.settingsOpen) {
          // 设置页是多段内容，保留原来的 10vp 段间距。
          Column({ space: 10 }) {
            this.SettingsPanel()
          }
          .width('100%')
          .height('100%')
          .transition(this.screenTransition())
        } else if (!this.running || this.connectView) {
          Column() {
            this.ConnectPanel()
          }
          .width('100%')
          .height('100%')
          .transition(this.screenTransition())
        } else if (this.selectedPath.length > 0) {
          // 详情页自带滚动区与底部提交条，不能再套一层 Scroll。
          Column() {
            this.WorkflowDetail()
          }
          .width('100%')
          .height('100%')
          .transition(this.screenTransition())
        } else {
          Column() {
            this.CatalogPanel()
          }
          .width('100%')
          .height('100%')
          .transition(this.screenTransition())
        }
      }
      .width('100%')
      .height('100%')
      .padding({
        left: 14,
        right: 14,
        top: 14 + px2vp(this.statusBarPx),
        bottom: 14 + px2vp(this.navBarPx),
      })

      if (this.tasksOpen) {
        this.TasksPanel()
      }
    }
    .width('100%')
    .height('100%')
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      this.AppGlow()
      if (this.artifactOpen) {
        this.ArtifactPanel()
      } else {
        this.MainPanel()
      }
      if (this.allNodesOpen) {
        this.AllNodesSheet()
      }
      if (this.confirmOpen) {
        this.SubmitConfirmDialog()
      }
      if (this.bypassConfirmNodeId >= 0) {
        this.BypassConfirmDialog()
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.c().background)
    // 背景与光晕画到状态栏/导航条下面；文字内容由 MainPanel 的内边距避开系统栏。 
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
