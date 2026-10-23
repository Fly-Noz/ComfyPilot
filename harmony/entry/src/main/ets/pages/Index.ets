import { nativeVersion } from 'libentry.so';
import { common, ConfigurationConstant } from '@kit.AbilityKit';
import { image } from '@kit.ImageKit';
import { util } from '@kit.ArkTS';
import {
  BridgeCatalog,
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
import { Palette, Spectrum } from '../theme/Spectrum';
import { ArtifactDownloader, ArtifactDownloadResult } from '../artifact/ArtifactDownloader';
import { ImageUploader, PickedImage } from '../workflow/ImageUploader';
import {
  BuildProblemView,
  PromptResponse,
  WorkflowFieldView,
  WorkflowGraphView,
  WorkflowNodeView,
  WorkflowViewResponse,
} from '../model/WorkflowTypes';

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
  @State baseUrl: string = 'http://127.0.0.1:18188';
  @State running: boolean = false;
  @State label: string = '未连接';
  @State generation: number = 0;
  @State network: string = '-';
  @State tasks: BridgeTask[] = [];
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
  @State pinnedIds: number[] = [];
  @State pinnedWorkflowPaths: string[] = [];
  @State editTick: number = 0;
  @State submitting: boolean = false;
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
  private catalogRequested: boolean = false;
  private reopenRequested: boolean = false;
  private scroller: Scroller = new Scroller();
  private catalogScroller: Scroller = new Scroller();
  private previewRequestSeq: number = 0;
  private previewFrameKey: string = '';
  private previewBase64: util.Base64Helper = new util.Base64Helper();

  aboutToAppear(): void {
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
      this.log(text);
    };
    this.bridge.onLog = (line: string) => this.log(line);
    this.bootstrap();
  }

  aboutToDisappear(): void {
    if (this.pickerActive) {
      return;
    }
    this.cancelEditRefresh();
    this.clearLivePreview();
    this.bridge.stop();
    this.running = false;
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
    if (workflowPinsReady) {
      this.pinnedWorkflowPaths = this.pinnedWorkflowStore.load();
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
    this.bridge.stop();
    this.running = false;
    this.label = '未连接';
    this.tasks = [];
    this.catalog = emptyCatalog();
    this.selectedPath = '';
    this.view = emptyView();
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
    this.edits.clear();
    this.editTick++;
    this.view = emptyView();
    this.viewError = '';
    this.submitNotice = '';
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

  private requestJson(op: string, runToken: string = ''): string {
    const graphs = this.graphsInitialized
      ? `[${this.graphIds.map((id: string) => JSON.stringify(id)).join(',')}]`
      : 'null';
    const pinned = `[${this.pinnedIds.join(',')}]`;
    const run = runToken.length > 0 ? `,"run":${JSON.stringify(runToken)}` : '';
    return `{"op":${JSON.stringify(op)},"graphs":${graphs},"pinned":${pinned}${run},"overrides":${this.overridesJson()}}`;
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

  private openArtifacts(task: BridgeTask): void {
    this.artifactTask = task;
    this.artifactOpen = true;
    this.previewOpen = false;
    this.previewError = false;
    this.downloadNotice = '';
  }

  private closeArtifacts(): void {
    this.artifactOpen = false;
    this.previewOpen = false;
    // 下载可能还在后台跑；保留 downloadingKey，重新打开时不会重复点同一份。
    this.downloadNotice = '';
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
    this.downloadingKey = this.artifactKey(output);
    this.downloadNotice = '';
    this.downloader.download(context, this.normalizedBaseUrl(), output).then((result: ArtifactDownloadResult) => {
      this.downloadingKey = '';
      if (result.ok || result.cancelled === true) {
        this.downloadNotice = result.message;
      } else {
        this.downloadNotice = `保存失败：${result.message}`;
      }
      this.log(result.message);
    }).catch((err: Error) => {
      this.downloadingKey = '';
      this.downloadNotice = `保存失败：${err.message}`;
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

  /** 方形小图标按钮：备注 / 置顶 / 展开，对应 Android 的 NodeIconButton。 */
  @Builder
  NodeIconButton(glyph: string, active: boolean, action: () => void) {
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
              .fontColor(this.c().text)
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
        this.NodeIconButton('✎', this.noteOf(node.id).length > 0, () => this.openNoteEditor(node))
        this.NodeIconButton('⇧', node.pinned, () => this.togglePin(node))
        this.NodeIconButton(this.isNodeExpanded(node) ? '▴' : '▾', false,
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
          ForEach(node.fields, (field: WorkflowFieldView) => {
            this.FieldRow(field)
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
        .padding({ left: 13, right: 13, top: 2, bottom: 13 })
      }
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
    .backgroundColor(this.c().card)
    .borderRadius(18)
    .border({ width: 1, color: node.pinned ? this.c().accentBorder : this.c().border })
    .clip(true)
  }

  /** 字段行，对应 Android FieldEditor：标签 + 类型 + 控件 + 必填提示。 */
  @Builder
  FieldRow(field: WorkflowFieldView) {
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
          .selectedColor(this.c().accent)
          .switchPointColor(this.c().accentInk)
          .onChange((value: boolean) => this.setFieldBool(field, value))
      } else if (field.type === 'STRING') {
        // 提示词这类长文本用 TextArea：自动换行，超出高度内部滚动。
        TextArea({ text: this.fieldText(field), placeholder: '' })
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
          this.running && this.uploadingField.length === 0,
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
      Row({ space: 10 }) {
        LoadingProgress()
          .width(18)
          .height(18)
          .color(this.c().accent)
        Text('正在读取服务器 workflows 目录…')
          .fontSize(12)
          .fontColor(this.c().textMuted)
      }
      .width('100%')
      .padding(14)
      .backgroundColor(this.c().card)
      .borderRadius(18)
      .border({ width: 1, color: this.c().border })
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
            .onClick(() => this.openWorkflow(entry.path, entry.name))
          }, (entry: CatalogEntry) =>
            `${entry.path}:${this.isWorkflowPinned(entry.path)}:${entry.size}`)
        }
        .width('100%')
        .padding({ bottom: 16 })
      }
      .layoutWeight(1)
      .width('100%')
      .scrollBar(BarState.Off)
    } else {
      // Idle（以及未知状态）：对应 Kotlin 的 HintCard。
      Column() {
        Text(this.running ? '准备读取服务器工作流…' : '尚未连接 ComfyUI。')
          .fontSize(12.5)
          .fontColor(this.c().textMuted)
          .width('100%')
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
          .onClick(() => this.closeWorkflow())
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
            this.tasksOpen = true;
          })
      }
      .width('100%')
      .padding({ left: 4, right: 4 })
      .constraintSize({ minHeight: 46 })
      .alignItems(VerticalAlign.Center)

      // ---- 读取状态：对应 Android LoadingBody / ErrorBody 的内联提示 ----
      if (this.workflow.loadingPath !== null) {
        this.InlineNote(`正在读取 ${this.workflow.loadingPath}`)
      }
      if (this.workflow.error !== null && this.workflow.error.length > 0) {
        Text(`读取失败：${this.workflow.error}`)
          .fontSize(11)
          .lineHeight(16)
          .fontColor(this.c().err)
          .width('100%')
          .padding({ left: 4, right: 4 })
      }
      if (this.viewError.length > 0) {
        Text(this.viewError)
          .fontSize(11)
          .lineHeight(16)
          .fontColor(this.c().err)
          .width('100%')
          .padding({ left: 4, right: 4 })
      }
      if (this.viewLoading) {
        this.InlineNote('正在生成字段视图…')
      }

      // ---- 图选择器：药丸按钮（已启用计数 + chevron）+ 下拉面板 ----
      Row() {
        if (this.parentGraphs().length > 0) {
          Row({ space: 6 }) {
            Text('节点图')
              .fontSize(9)
              .fontFamily('monospace')
              .letterSpacing(0.6)
              .fontColor(this.c().textDim)
            Text(`${this.enabledGraphCount()}/${this.parentGraphs().length}`)
              .fontSize(11.5)
              .fontWeight(FontWeight.Medium)
              .fontColor(this.enabledGraphCount() > 0 ? this.c().text : this.c().textMuted)
            Text(this.graphMenuOpen ? '▴' : '▾')
              .fontSize(9)
              .fontColor(this.enabledGraphCount() > 0 ? this.c().textMuted : this.c().textDim)
          }
          .height(32)
          .padding({ left: 13, right: 11 })
          .borderRadius(999)
          .backgroundColor(this.enabledGraphCount() > 0 ? this.c().accentSoft : this.c().card)
          .border({
            width: 1,
            color: this.enabledGraphCount() > 0 ? this.c().accentBorder : this.c().border
          })
          .onClick(() => {
            this.graphMenuOpen = !this.graphMenuOpen;
          })
          .bindPopup(this.graphMenuOpen, {
            builder: this.GraphMenuPanel,
            placement: Placement.Bottom,
            popupColor: this.c().dialog,
            enableArrow: false,
            radius: 18,
            onStateChange: (event: PopupStateEvent) => {
              if (!event.isVisible) {
                this.graphMenuOpen = false;
              }
            },
          })
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
      .width('100%')
      .padding({ left: 4, right: 4, top: 2 })
      .constraintSize({ minHeight: 34 })
      .alignItems(VerticalAlign.Center)

      // ---- 输入 / 预览 分段 tabs，对应 Android SpectrumTabs ----
      this.SegmentedTabs()

      // ---- 待加载 / 缺失 / 提交问题 ----
      if (this.view.pendingNodeClasses.length > 0) {
        this.InlineNote(`正在加载节点定义：还差 ${this.view.pendingNodeClasses.length} 个类`)
      }
      if (this.uploadingField.length > 0) {
        this.InlineNote('正在上传图片…')
      }
      if (this.view.missingNodeClasses.length > 0) {
        Text(`服务端缺少节点：${this.view.missingNodeClasses.join('、')}`)
          .fontSize(10.5)
          .fontColor(this.c().err)
          .width('100%')
          .padding({ left: 4, right: 4 })
      }
      if (this.view.problems.length > 0) {
        Column() {
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
          .padding(11)
          .backgroundColor(this.c().card)
          .borderRadius(16)
          .border({ width: 1, color: this.c().border })
        }
        .width('100%')
        .padding({ left: 4, right: 4, top: 2, bottom: 2 })
      }
      if (this.view.warnings.length > 0) {
        Column({ space: 2 }) {
          Text('提交提示')
            .fontSize(10)
            .fontFamily('monospace')
            .fontColor(this.c().warn)
          ForEach(this.view.warnings, (warning: string, index: number) => {
            Text(`· ${warning}`)
              .fontSize(11)
              .fontColor(this.c().warn)
          }, (warning: string, index: number) => `${index}:${warning}`)
        }
        .alignItems(HorizontalAlign.Start)
        .width('100%')
        .padding({ left: 4, right: 4 })
      }

      // 节点备注面板（Android 是 SpectrumDialog，这里作为内容面板嵌在详情里）。
      if (this.noteEditorId >= 0) {
        this.NoteEditor()
      }

      // ---- 内容：节点列表 / 预览 ----
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
                  `${node.pinned}:${node.reachable}:${node.needsInput}`)
            }
          }
          .width('100%')
          .alignItems(HorizontalAlign.Start)
          .padding({ left: 4, right: 4, top: 6, bottom: 12 })
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
          .padding({ left: 4, right: 4, top: 6, bottom: 12 })
        }
        .layoutWeight(1)
        .width('100%')
        .scrollBar(BarState.Off)
        .align(Alignment.Top)
      }

      // ---- 底部提交条：玻璃底 + 阻断原因 + 全部节点 + 渐变提交按钮 ----
      Column() {
        Row()
          .width('100%')
          .height(1)
          .backgroundColor(this.c().border)
        Column({ space: 8 }) {
          if (this.submitNotice.length > 0) {
            Text(this.submitNotice)
              .fontSize(10.5)
              .lineHeight(15)
              .fontColor(this.submitNotice.startsWith('已提交') ? this.c().ok : this.c().err)
              .width('100%')
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
              .height(42)
              .padding({ left: 16, right: 16 })
              .borderRadius(14)
              .backgroundColor(this.c().card)
              .border({ width: 1, color: this.c().borderStrong })
              .onClick(() => {
                this.allNodesOpen = true;
              })
            Blank()
            Text(`${this.reachableNodeCount()} 个会提交`)
              .fontSize(9.5)
              .fontFamily('monospace')
              .fontColor(this.c().textDim)
              .textAlign(TextAlign.End)
            Button(this.submitting ? '提交中…' : '提交任务')
              .fontSize(14)
              .fontWeight(FontWeight.Bold)
              .fontColor(this.c().accentInk)
              .height(44)
              .padding({ left: 18, right: 18 })
              .borderRadius(14)
              .backgroundColor(Color.Transparent)
              .linearGradient({ angle: 120, colors: Spectrum.gradientColors(this.c()) })
              .enabled(this.running && !this.submitting && this.view.canSubmit)
              .opacity(this.running && !this.submitting && this.view.canSubmit ? 1 : 0.42)
              .onClick(() => this.submitWorkflow())
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
        }
        .width('100%')
        .padding({ left: 18, right: 18, top: 10, bottom: 10 })
      }
      .width('100%')
      .backgroundColor(this.c().isDark ? 'rgba(10,11,16,0.86)' : 'rgba(245,244,248,0.86)')
    }
    .width('100%')
    .height('100%')
  }

  /** 输入 / 预览 分段控件，对应 Android SpectrumTabs。 */
  @Builder
  SegmentedTabs() {
    Column() {
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
        .borderRadius(11)
        .backgroundColor(this.detailTab === 0 ? this.c().cardStrong : Color.Transparent)
        .onClick(() => {
          this.detailTab = 0;
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
        .borderRadius(11)
        .backgroundColor(this.detailTab === 1 ? this.c().cardStrong : Color.Transparent)
        .onClick(() => {
          this.detailTab = 1;
        })
      }
      .width('100%')
      .padding(3)
      .borderRadius(14)
      .backgroundColor(this.c().card)
      .border({ width: 1, color: this.c().border })
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
    .width(300)
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
            this.tasksOpen = true;
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

      this.ProgressBar(this.liveCardPercent() / 100)
    }
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
        }
        .width('100%')
        .height(300)
        .borderRadius(22)
        .clip(true)
        .backgroundColor(this.c().dialog)
        .border({ width: 1, color: this.c().borderStrong })

        if (this.submitNotice.length > 0) {
          Text(this.submitNotice)
            .fontSize(11)
            .fontColor(this.submitNotice.startsWith('已提交') ? this.c().ok : this.c().err)
            .width('100%')
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
            this.tasksOpen = true;
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
            Image(this.firstImageUrl(task))
              .width('100%')
              .height('100%')
              .objectFit(ImageFit.Contain)
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

        if (task.error !== null && task.error.length > 0) {
          Text(`任务失败：${task.error}`)
            .fontSize(11)
            .lineHeight(16)
            .fontColor(this.c().err)
            .width('100%')
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
              this.tasksOpen = true;
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

  /** 渐变进度条，对应 Android SpectrumProgress。 */
  @Builder
  ProgressBar(fraction: number) {
    Stack({ alignContent: Alignment.Start }) {
      Row()
        .width('100%')
        .height(7)
        .borderRadius(999)
        .backgroundColor(this.c().cardStrong)
      Row()
        .width(`${Math.max(0, Math.min(100, fraction * 100))}%`)
        .height(7)
        .borderRadius(999)
        .linearGradient({ angle: 90, colors: Spectrum.gradientColors(this.c()) })
    }
    .width('100%')
    .height(7)
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

  @Builder
  TaskCard(task: BridgeTask) {
    Column({ space: 4 }) {
      Row({ space: 8 }) {
        Text(task.state === 'Failed' ? 'Failed · 未执行' : `${task.state} · ${task.percent}%`)
          .fontSize(13)
          .fontWeight(FontWeight.Medium)
          .layoutWeight(1)
        Button(`产物 ${task.outputRefs.length}`)
          .fontSize(12)
          .height(30)
          .enabled(task.outputRefs.length > 0)
          .onClick(() => this.openArtifacts(task))
      }
      .width('100%')
      Text(this.taskLabel(task))
        .fontSize(11)
        .fontColor(this.c().textMuted)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
      if (task.workflowPath !== null && task.workflowPath.length > 0) {
        Text(task.workflowPath)
          .fontSize(10)
          .fontColor(this.c().textDim)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      Progress({ value: task.percent, total: 100, type: ProgressType.Linear })
        .width('100%')
      if (task.error !== null && task.error.length > 0) {
        Text(task.error)
          .fontSize(11)
          .fontColor(this.c().err)
      }
    }
    .alignItems(HorizontalAlign.Start)
    .padding(10)
    .backgroundColor(this.c().card)
    .borderRadius(14)
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
        Progress({ value: this.liveCardPercent(), total: 100, type: ProgressType.Linear })
          .width('100%')
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

  @Builder
  TasksTab() {
    if (this.liveCardTask() !== null) {
      this.LivePreviewCard()
    }
    if (this.tasks.length === 0) {
      Text('还没有任务。').fontSize(12).fontColor(this.c().textMuted)
    } else {
      Scroll() {
        Column({ space: 6 }) {
          ForEach(this.tasks, (task: BridgeTask) => {
            this.TaskCard(task)
          }, (task: BridgeTask) => `${task.id}:${task.state}:${task.percent}:${task.outputRefs.length}`)
        }
        .width('100%')
      }
      .layoutWeight(1)
      .width('100%')
    }
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

  @Builder
  ArtifactCard(output: BridgeOutput) {
    Column({ space: 6 }) {
      Text(output.displayName.length > 0 ? output.displayName : output.filename)
        .fontSize(14)
        .fontWeight(FontWeight.Medium)
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
      Text(`${output.kind} · ${output.type} · ${output.mime}`)
        .fontSize(10)
        .fontColor(this.c().textDim)

      if (output.image && this.artifactUrl(output).length > 0) {
        Image(this.artifactUrl(output))
          .width('100%')
          .height(180)
          .objectFit(ImageFit.Cover)
          .borderRadius(6)
          .onClick(() => {
            this.previewOutput = output;
            this.previewOpen = true;
            this.previewError = false;
          })
      } else if (output.video) {
        Text('视频产物：点击“保存到相册”，系统确认后会写入相册。')
          .fontSize(11)
          .fontColor(this.c().textMuted)
      } else if (output.audio) {
        Text('音频产物：下载后可用系统播放器打开。')
          .fontSize(11)
          .fontColor(this.c().textMuted)
      } else {
        Text('该类型暂不内置预览，可下载后用系统应用打开。')
          .fontSize(11)
          .fontColor(this.c().textMuted)
      }

      Row({ space: 8 }) {
        if (output.image) {
          Button('预览')
            .fontSize(12)
            .height(30)
            .enabled(this.artifactUrl(output).length > 0)
            .onClick(() => {
              this.previewOutput = output;
              this.previewOpen = true;
              this.previewError = false;
            })
        }
        Button(
          this.downloadingKey === this.artifactKey(output)
            ? this.artifactActionBusyLabel(output)
            : this.artifactActionLabel(output),
        )
          .fontSize(12)
          .height(30)
          .enabled(this.downloadingKey.length === 0)
          .onClick(() => this.download(output))
      }
      .width('100%')
      .justifyContent(FlexAlign.End)
    }
    .alignItems(HorizontalAlign.Start)
    .padding(10)
    .backgroundColor(this.c().card)
    .borderRadius(14)
    .width('100%')
  }

  @Builder
  ArtifactPreview() {
    Row({ space: 8 }) {
      Button('返回列表')
        .fontSize(12)
        .height(30)
        .onClick(() => {
          this.previewOpen = false;
          this.previewError = false;
        })
      Text(this.previewOutput.displayName)
      .fontColor(this.c().textMuted)
        .fontSize(13)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .layoutWeight(1)
      Button(
        this.downloadingKey === this.artifactKey(this.previewOutput)
          ? this.artifactActionBusyLabel(this.previewOutput)
          : this.artifactActionLabel(this.previewOutput),
      )
        .fontSize(12)
        .height(30)
        .enabled(this.downloadingKey.length === 0)
        .onClick(() => this.download(this.previewOutput))
    }
    .width('100%')

    Stack() {
      if (!this.previewOutput.image) {
        Text('该类型暂不支持内置预览，请下载后打开。')
          .fontSize(12)
          .fontColor(this.c().text)
      } else if (this.previewError) {
        Text('图片预览失败，可能是网络中断或服务端返回了错误。')
          .fontSize(12)
          .fontColor(this.c().text)
          .padding(24)
      } else {
        Image(this.artifactUrl(this.previewOutput))
          .width('100%')
          .height('100%')
          .objectFit(ImageFit.Contain)
          .onError(() => {
            this.previewError = true;
          })
      }
    }
    .width('100%')
    .layoutWeight(1)
    .backgroundColor('#0E0F14')
    .borderRadius(14)
  }

  @Builder
  ArtifactPanel() {
    Column({ space: 8 }) {
      Row({ space: 8 }) {
        Button('返回任务')
          .fontSize(12)
          .height(30)
          .onClick(() => this.closeArtifacts())
        Column({ space: 2 }) {
          Text('任务产物')
            .fontSize(17)
            .fontWeight(FontWeight.Bold)
          Text(this.taskLabel(this.artifactTask))
            .fontSize(11)
            .fontColor(this.c().textDim)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
      }
      .width('100%')

      if (this.downloadNotice.length > 0) {
        Text(this.downloadNotice)
          .fontSize(11)
          .fontColor(this.downloadNoticeColor())
      }

      if (this.previewOpen) {
        this.ArtifactPreview()
      } else if (this.artifactTask.outputRefs.length === 0) {
        Text('该任务没有产物。').fontSize(12).fontColor(this.c().textMuted)
      } else {
        Scroll() {
          Column({ space: 8 }) {
            ForEach(this.artifactTask.outputRefs, (output: BridgeOutput) => {
              this.ArtifactCard(output)
            }, (output: BridgeOutput) => this.artifactKey(output))
          }
          .width('100%')
        }
        .layoutWeight(1)
        .width('100%')
      }
    }
    .padding({
      left: 12,
      right: 12,
      top: 12 + px2vp(this.statusBarPx),
      bottom: 12 + px2vp(this.navBarPx),
    })
    .width('100%')
    .height('100%')
    .backgroundColor(this.c().background)
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
  GradientAction(label: string, enabled: boolean, action: () => void) {
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
      .onClick(() => action())
  }

  @Builder
  GhostAction(label: string, enabled: boolean, action: () => void) {
    Button(label)
      .fontSize(12)
      .fontColor(this.c().textMuted)
      .height(36)
      .padding({ left: 14, right: 14 })
      .borderRadius(12)
      .backgroundColor(this.c().card)
      .border({ width: 1, color: this.c().border })
      .enabled(enabled)
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
            this.settingsOpen = true;
          })
        Text('诊断')
          .fontSize(12)
          .fontColor(this.c().textMuted)
          .padding({ left: 7, right: 7, top: 7, bottom: 7 })
          .borderRadius(8)
          .onClick(() => {
            this.settingsOpen = true;
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

            // 对应 AddressField 的输入框外观。历史下拉在鸿蒙侧还没有对应的状态，暂不渲染，
            // 输入框本身的行为与 Android 一致（失焦写入由 connect() 统一处理）。
            Row() {
              TextInput({ text: this.baseUrl, placeholder: 'http://192.168.1.10:8188' })
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
            }
            .width('100%')
            .padding({ left: 12, right: 12, top: 4, bottom: 4 })
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
                .onClick(() => {
                  animateTo({ duration: 180, curve: Curve.EaseOut }, () => {
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
                .onClick(() => {
                  this.selectedPath = '';
                  this.connectView = false;
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
            this.connectView = true;
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
            this.settingsOpen = true;
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
            this.tasksOpen = true;
          })
        Text('断开')
          .fontSize(12)
          .fontColor(this.running ? this.c().textMuted : this.c().textDim)
          .padding({ left: 7, right: 7, top: 7, bottom: 7 })
          .borderRadius(8)
          .enabled(this.running)
          .onClick(() => {
            this.disconnect();
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

  @Builder
  TasksPanel() {
    Column({ space: 8 }) {
      Row({ space: 8 }) {
        Text('任务')
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .layoutWeight(1)
        this.StatusPill()
        this.GhostAction('关闭', true, () => {
          this.tasksOpen = false;
        })
      }
      .width('100%')
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
    .backgroundColor(this.c().background)
  }

  @Builder
  SettingsPanel() {
    Row({ space: 8 }) {
      Text('设置')
        .fontSize(20)
        .fontWeight(FontWeight.Bold)
        .layoutWeight(1)
      this.GhostAction('返回', true, () => {
        this.settingsOpen = false;
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
        .onClick(() => this.setAppearance('system'))
      Button('深色')
        .layoutWeight(1)
        .height(36)
        .fontSize(12)
        .borderRadius(11)
        .backgroundColor(this.appearanceMode === 'dark' ? this.c().accentSoft : this.c().card)
        .fontColor(this.appearanceMode === 'dark' ? this.c().text : this.c().textDim)
        .border({ width: 1, color: this.appearanceMode === 'dark' ? this.c().accentBorder : this.c().border })
        .onClick(() => this.setAppearance('dark'))
      Button('浅色')
        .layoutWeight(1)
        .height(36)
        .fontSize(12)
        .borderRadius(11)
        .backgroundColor(this.appearanceMode === 'light' ? this.c().accentSoft : this.c().card)
        .fontColor(this.appearanceMode === 'light' ? this.c().text : this.c().textDim)
        .border({ width: 1, color: this.appearanceMode === 'light' ? this.c().accentBorder : this.c().border })
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
  }

  @Builder
  MainPanel() {
    Stack() {
      Column({ space: 10 }) {
        if (this.settingsOpen) {
          this.SettingsPanel()
        } else if (!this.running || this.connectView) {
          this.ConnectPanel()
        } else if (this.selectedPath.length > 0) {
          // 详情页自带滚动区与底部提交条，不能再套一层 Scroll。
          this.WorkflowDetail()
        } else {
          this.CatalogPanel()
        }
      }
      .padding({
        left: 14,
        right: 14,
        top: 14 + px2vp(this.statusBarPx),
        bottom: 14 + px2vp(this.navBarPx),
      })
      .width('100%')
      .height('100%')

      if (this.tasksOpen) {
        this.TasksPanel()
      }
    }
    .width('100%')
    .height('100%')
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      // 光谱底：用径向渐变（中心有色、边缘透明）做光晕。
      // 之前是「实心圆 + opacity + blur」，ArkUI 对空容器不生效 blur，所以看起来是三个硬边圆。
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
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.c().background)
    // 背景与光晕画到状态栏/导航条下面；文字内容由 MainPanel 的内边距避开系统栏。
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
