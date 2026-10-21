import { nativeVersion } from 'libentry.so';
import { common } from '@kit.AbilityKit';
import { BridgeState, BridgeTask, ComfyBridge, TaskRestoreResult } from '../bridge/ComfyBridge';

// 只用于联调：mock 服务和 ComfyUI 都会接受这个最小图（真实 ComfyUI 会因缺少采样器参数拒绝）。
const DEMO_WORKFLOW: string = '{"3":{"class_type":"KSampler","inputs":{}}}';

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
  private bridge: ComfyBridge = new ComfyBridge();
  private abi: number = 0;

  aboutToAppear(): void {
    this.abi = nativeVersion();
    this.bridge.onState = (state: BridgeState) => {
      this.label = `${state.label} · 第 ${state.generation} 代 · ${state.network}`;
      this.generation = state.generation;
      this.network = state.validated ? `${state.network}（已验证）` : state.network;
      this.tasks = state.tasks;
    };
    this.bridge.onNotice = (text: string) => {
      this.notice = text;
      this.log(text);
    };
    this.bridge.onLog = (line: string) => this.log(line);
    this.bootstrap();
  }

  /** 先把本地历史读回来，读不到也要让用户能连——只是重启后会丢。 */
  private bootstrap(): void {
    const host = this.getUIContext().getHostContext();
    if (host === undefined) {
      this.storageReady = true;
      this.storageLabel = '拿不到 Ability 上下文，任务只在内存里';
      return;
    }
    const context = host as common.Context;
    this.bridge.init(context).then((result: TaskRestoreResult) => {
      this.storageReady = true;
      if (!result.storage) {
        this.storageLabel = `本地存储不可用（${result.error}），任务只在内存里`;
      } else {
        this.storageLabel = result.restored > 0 ? `已恢复 ${result.restored} 个历史任务` : '本地存储就绪，没有历史任务';
      }
      // 历史先显示出来，连不连接是下一步的事。
      this.bridge.prepare(this.baseUrl);
    }).catch((err: Error) => {
      this.storageReady = true;
      this.storageLabel = `本地存储初始化失败：${err.message}`;
    });
  }

  aboutToDisappear(): void {
    this.bridge.stop();
    this.running = false;
  }

  private log(line: string): void {
    const stamp = new Date().toTimeString().substring(0, 8);
    this.logs = [`${stamp} ${line}`, ...this.logs].slice(0, 40);
  }

  private connect(): void {
    const url = this.baseUrl.trim();
    if (url.length === 0) {
      this.notice = '地址不能为空';
      return;
    }
    if (!this.storageReady) {
      this.notice = '本地存储还在初始化，请稍等';
      return;
    }
    this.running = true;
    this.log(`开始连接 ${url}`);
    this.bridge.start(url);
  }

  private disconnect(): void {
    this.bridge.stop();
    this.running = false;
    this.label = '未连接';
    this.tasks = [];
    this.log('已断开');
  }

  build() {
    Column({ space: 10 }) {
      Row({ space: 8 }) {
        Text('ComfyPilot')
          .fontSize(22)
          .fontWeight(FontWeight.Bold)
        Text(`ABI ${this.abi}`)
          .fontSize(12)
          .fontColor('#888888')
      }
      .width('100%')

      Text('HarmonyOS 阶段二 · 共享层联调')
        .fontSize(12)
        .fontColor('#666666')
        .width('100%')

      Text(this.storageLabel)
        .fontSize(12)
        .fontColor('#666666')
        .width('100%')

      TextInput({ text: this.baseUrl, placeholder: 'http://127.0.0.1:18188' })
        .onChange((value: string) => {
          this.baseUrl = value;
        })
        .enabled(!this.running)
        .width('100%')

      Row({ space: 8 }) {
        Button(this.running ? '重连' : '连接')
          .onClick(() => this.connect())
          .enabled(this.storageReady)
          .layoutWeight(1)
        Button('断开')
          .onClick(() => this.disconnect())
          .enabled(this.running)
          .layoutWeight(1)
        Button('提交示例')
          .onClick(() => {
            this.bridge.submit(DEMO_WORKFLOW);
            this.log('已提交示例工作流');
          })
          .enabled(this.running)
          .layoutWeight(1)
      }
      .width('100%')

      Text(this.label)
        .fontSize(15)
        .fontColor(this.running ? '#1B8A3A' : '#666666')
        .width('100%')

      if (this.notice.length > 0) {
        Text(this.notice)
          .fontSize(12)
          .fontColor('#B26A00')
          .width('100%')
      }

      List({ space: 6 }) {
        ForEach(this.tasks, (task: BridgeTask) => {
          ListItem() {
            Column({ space: 2 }) {
              Text(`${task.state} · ${task.percent}% · 产物 ${task.outputs}`)
                .fontSize(13)
              Text(task.promptId ?? task.id)
                .fontSize(11)
                .fontColor('#888888')
            }
            .alignItems(HorizontalAlign.Start)
            .width('100%')
          }
        }, (task: BridgeTask) => `${task.id}:${task.state}:${task.percent}:${task.outputs}`)
      }
      .width('100%')
      .layoutWeight(1)
      .backgroundColor('#F7F7F7')
      .borderRadius(8)
      .padding(8)

      Scroll() {
        Column({ space: 2 }) {
          ForEach(this.logs, (line: string) => {
            Text(line)
              .fontSize(11)
              .fontFamily('monospace')
              .width('100%')
          }, (line: string, index: number) => `${index}-${line}`)
        }
        .alignItems(HorizontalAlign.Start)
      }
      .width('100%')
      .height(150)
      .backgroundColor('#F0F0F0')
      .borderRadius(8)
      .padding(8)

      Text('联调页面：只覆盖前台连接与任务跟踪，没有后台长任务，切后台可能被系统暂停。')
        .fontSize(11)
        .fontColor('#888888')
        .width('100%')
    }
    .padding(16)
    .width('100%')
    .height('100%')
  }
}
