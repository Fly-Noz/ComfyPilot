import { nativeVersion, nativeSelfTest } from 'libentry.so';

@Entry
@Component
struct Index {
  @State version: number = 0;
  @State report: string = '';
  @State passed: boolean = false;

  aboutToAppear(): void {
    this.version = nativeVersion();
    this.runSelfTest();
  }

  private runSelfTest(): void {
    const raw: string = nativeSelfTest();
    this.report = raw;
    // 这里只做展示，不解析 JSON，避免 ArkTS 侧再维护一份数据契约。
    this.passed = raw.indexOf('"ok":true') >= 0;
  }

  build() {
    Column({ space: 14 }) {
      Text('ComfyPilot')
        .fontSize(26)
        .fontWeight(FontWeight.Bold)

      Text('HarmonyOS 阶段二 · 共享层桥接验证')
        .fontSize(14)
        .fontColor('#666666')

      Text(`C ABI 版本：${this.version}`)
        .fontSize(14)

      Row({ space: 12 }) {
        Text(this.passed ? '自检通过' : '自检失败')
          .fontSize(16)
          .fontColor(this.passed ? '#1B8A3A' : '#C62828')

        Button('重新运行')
          .onClick(() => this.runSelfTest())
      }

      Scroll() {
        Text(this.report)
          .fontSize(12)
          .fontFamily('monospace')
          .width('100%')
          .textAlign(TextAlign.Start)
      }
      .width('100%')
      .layoutWeight(1)
      .backgroundColor('#F5F5F5')
      .borderRadius(8)
      .padding(12)

      Text('当前仅验证共享层可加载与状态机自检，尚未接入网络与任务提交。')
        .fontSize(12)
        .fontColor('#888888')
    }
    .padding(20)
    .width('100%')
    .height('100%')
  }
}
