import { Palette, Spectrum } from '../theme/Spectrum';
import {
  GraphLinkOverride,
  NodeFieldRow,
  GraphLinkView,
  GraphNodeView,
  WorkflowGraphData,
} from '../model/WorkflowTypes';

const MODE_NORMAL: number = 0;

interface SlotRef {
  nodeId: number;
  index: number;
  input: boolean;
}

interface SlotInfo {
  node: GraphNodeView;
  name: string;
  type: string;
  linkId: number;
  input: boolean;
}

interface PendingPair {
  out: SlotRef;
  input: SlotRef;
}

function slotKey(slot: SlotRef): string {
  return `${slot.nodeId}:${slot.input ? 'i' : 'o'}:${slot.index}`;
}

function parseSlotKey(key: string): SlotRef | null {
  const parts = key.split(':');
  if (parts.length !== 3) {
    return null;
  }
  return { nodeId: Number(parts[0]), input: parts[1] === 'i', index: Number(parts[2]) };
}

/**
 * 节点图预览（鸿蒙端）。
 *
 * 数据全部来自共享层 `op=view` 的 `graph` 字段：坐标、尺寸、槽位、连线、
 * 以及本次会话的启用/断线状态都已经算好，这里只负责画和交互。
 */
@Component
export struct WorkflowGraphOverlay {
  @Prop graph: WorkflowGraphData;
  @Prop isDark: boolean;
  /** 状态栏高度（vp）：顶栏要避开系统通知栏。 */
  @Prop topInset: number;
  /** 参数名/值：节点卡片展开时显示真实值。 */
  @Prop fieldRows: NodeFieldRow[];
  @Prop bypassedIds: number[];
  @Prop enabledIds: number[];
  @Prop removedLinkIds: number[];
  @Prop linkOverrides: GraphLinkOverride[];
  onClose: () => void = () => {};
  onOpenNode: (id: number) => void = () => {};
  onToggleNode: (id: number) => void = () => {};
  onRemoveLink: (id: number) => void = () => {};
  onAddLink: (override: GraphLinkOverride) => void = () => {};

  private settings: RenderingContextSettings = new RenderingContextSettings(true);
  private ctx: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);
  private miniCtx: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);
  private miniScale: number = 1;
  private miniOx: number = 0;
  private miniOy: number = 0;
  private miniMinX: number = 0;
  private miniMinY: number = 0;
  private miniNodes: GraphNodeView[] = [];

  @State private zoom: number = 0.5;
  @State private originX: number = 0;
  @State private originY: number = 0;
  @State private selectedNode: number = -1;
  @State private selectedSlot: string = '';
  @State private linkStart: string = '';
  @State private pendingLink: string = '';
  @State private cardExpanded: boolean = false;
  @State private query: string = '';

  private fitted: boolean = false;
  private panBaseX: number = 0;
  private panBaseY: number = 0;
  private pinchBase: number = 1;
  private pinchAnchorX: number = 0;
  private pinchAnchorY: number = 0;
  private pinchOriginX: number = 0;
  private pinchOriginY: number = 0;
  private pinching: boolean = false;

  private c(): Palette {
    return Spectrum.palette(this.isDark);
  }

  aboutToAppear(): void {
    this.fitted = false;
  }

  private nodesById(): Map<number, GraphNodeView> {
    const map: Map<number, GraphNodeView> = new Map();
    this.graph.nodes.forEach((node: GraphNodeView) => map.set(node.id, node));
    return map;
  }

  private isActive(node: GraphNodeView): boolean {
    if (!this.graph.activeNodeIds.includes(node.id)) {
      return false;
    }
    // 没有数据输出的节点（Note/Label/分组说明）始终亮着，方便阅读。
    if (node.outputs.length === 0) {
      return true;
    }
    return !this.graph.disabledNodeIds.includes(node.id);
  }

  private headerWorld(node: GraphNodeView): number {
    return node.h * 0.26;
  }

  private slotYWorld(node: GraphNodeView, index: number): number {
    const slots = Math.max(node.inputs.length, node.outputs.length);
    if (slots <= 0) {
      return node.y + this.headerWorld(node);
    }
    const usable = Math.max(1, node.h - this.headerWorld(node));
    const step = Math.min(22, Math.max(4, usable / slots));
    return node.y + this.headerWorld(node) + step * (index + 0.5);
  }

  private sx(x: number): number {
    return x * this.zoom + this.originX;
  }

  private sy(y: number): number {
    return y * this.zoom + this.originY;
  }

  private fitToScreen(): void {
    const w = this.ctx.width;
    const h = this.ctx.height;
    if (w <= 0 || h <= 0 || this.graph.nodes.length === 0) {
      return;
    }
    let minX = Number.MAX_VALUE;
    let minY = Number.MAX_VALUE;
    let maxX = -Number.MAX_VALUE;
    let maxY = -Number.MAX_VALUE;
    this.graph.nodes.forEach((node: GraphNodeView) => {
      minX = Math.min(minX, node.x);
      minY = Math.min(minY, node.y);
      maxX = Math.max(maxX, node.x + node.w);
      maxY = Math.max(maxY, node.y + node.h);
    });
    const bw = Math.max(1, maxX - minX);
    const bh = Math.max(1, maxY - minY);
    const next = Math.min(w / bw, h / bh) * 0.9;
    this.zoom = Math.min(1, Math.max(0.02, next));
    this.originX = (w - bw * this.zoom) / 2 - minX * this.zoom;
    this.originY = (h - bh * this.zoom) / 2 - minY * this.zoom;
    this.fitted = true;
  }

  private draw(): void {
    const ctx = this.ctx;
    const w = ctx.width;
    const h = ctx.height;
    if (w <= 0 || h <= 0) {
      return;
    }
    if (!this.fitted) {
      this.fitToScreen();
    }
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = this.c().background;
    ctx.fillRect(0, 0, w, h);

    const byId = this.nodesById();

    // 分组
    this.graph.groups.forEach((group) => {
      const left = this.sx(group.x);
      const top = this.sy(group.y);
      const gw = group.w * this.zoom;
      const gh = group.h * this.zoom;
      if (gw < 8 || gh < 8) {
        return;
      }
      ctx.fillStyle = 'rgba(120,120,140,0.08)';
      ctx.fillRect(left, top, gw, gh);
      ctx.strokeStyle = 'rgba(120,120,140,0.35)';
      ctx.lineWidth = 1;
      ctx.strokeRect(left, top, gw, gh);
      if (group.title.length > 0 && gw >= 120) {
        ctx.fillStyle = this.c().textMuted;
        ctx.font = '11px sans-serif';
        ctx.fillText(group.title, left + 6, top + 14);
      }
    });

    // 连线
    this.graph.links.forEach((link: GraphLinkView) => {
      if (this.removedLinkIds.includes(link.id)) {
        return;
      }
      const from = byId.get(link.originId);
      const to = byId.get(link.targetId);
      if (from === undefined || to === undefined) {
        return;
      }
      const startX = this.sx(from.x + from.w);
      const startY = this.sy(this.slotYWorld(from, Math.min(link.originSlot, Math.max(0, from.outputs.length - 1))));
      const endX = this.sx(to.x);
      const endY = this.sy(this.slotYWorld(to, Math.min(link.targetSlot, Math.max(0, to.inputs.length - 1))));
      const dx = Math.max(20, Math.abs(endX - startX) / 2);
      ctx.beginPath();
      ctx.moveTo(startX, startY);
      ctx.bezierCurveTo(startX + dx, startY, endX - dx, endY, endX, endY);
      ctx.strokeStyle = this.isActive(from) && this.isActive(to)
        ? 'rgba(124,92,255,0.55)'
        : 'rgba(140,140,150,0.28)';
      ctx.lineWidth = 1.6;
      ctx.stroke();
    });

    // 节点
    this.graph.nodes.forEach((node: GraphNodeView) => {
      const left = this.sx(node.x);
      const top = this.sy(node.y);
      const nw = node.w * this.zoom;
      const nh = node.h * this.zoom;
      if (nw < 2 || nh < 2) {
        return;
      }
      const active = this.isActive(node);
      const alpha = active ? 1 : 0.45;
      ctx.globalAlpha = alpha;
      ctx.fillStyle = this.c().cardStrong;
      ctx.fillRect(left, top, nw, nh);
      const headerH = nh * 0.26;
      ctx.fillStyle = active ? this.c().accent : this.c().textDim;
      ctx.fillRect(left, top, nw, headerH);
      ctx.strokeStyle = active ? 'rgba(124,92,255,0.8)' : 'rgba(140,140,150,0.5)';
      ctx.lineWidth = 1;
      ctx.strokeRect(left, top, nw, nh);

      // 标题：按宽度定字号，超宽时缩放
      if (headerH >= 6) {
        const title = node.title.length > 0 ? node.title : node.type;
        let px = Math.min(nw * 0.11, headerH * 0.62);
        ctx.font = `${px}px sans-serif`;
        const measured = ctx.measureText(title).width;
        const maxWidth = Math.max(8, nw - 10);
        if (measured > maxWidth && measured > 0) {
          px = Math.max(4, px * maxWidth / measured);
          ctx.font = `${px}px sans-serif`;
        }
        ctx.fillStyle = '#FFFFFF';
        ctx.textBaseline = 'middle';
        ctx.fillText(title, left + 5, top + headerH / 2, maxWidth);
      }

      // 参数槽位
      const stepPx = Math.max(4, (nh - headerH) / Math.max(1, Math.max(node.inputs.length, node.outputs.length)));
      node.inputs.forEach((input, index: number) => {
        this.drawSlot(node, index, true, input.name, left, top, nw, nh, headerH, stepPx);
      });
      node.outputs.forEach((output, index: number) => {
        this.drawSlot(node, index, false, output.name, left, top, nw, nh, headerH, stepPx);
      });
      ctx.globalAlpha = 1;
    });
    this.drawMini();
  }

  /** 小地图：整图缩略 + 当前视口框，点一下把视口移过去。 */
  private drawMini(): void {
    const ctx = this.miniCtx;
    const w = ctx.width;
    const h = ctx.height;
    if (w <= 0 || h <= 0 || this.graph.nodes.length === 0) {
      return;
    }
    ctx.clearRect(0, 0, w, h);
    let minX = Number.MAX_VALUE;
    let minY = Number.MAX_VALUE;
    let maxX = -Number.MAX_VALUE;
    let maxY = -Number.MAX_VALUE;
    this.graph.nodes.forEach((node: GraphNodeView) => {
      minX = Math.min(minX, node.x);
      minY = Math.min(minY, node.y);
      maxX = Math.max(maxX, node.x + node.w);
      maxY = Math.max(maxY, node.y + node.h);
    });
    const bw = Math.max(1, maxX - minX);
    const bh = Math.max(1, maxY - minY);
    const pad = 5;
    const s = Math.min((w - pad * 2) / bw, (h - pad * 2) / bh);
    this.miniScale = s;
    this.miniMinX = minX;
    this.miniMinY = minY;
    this.miniOx = pad + ((w - pad * 2) - bw * s) / 2;
    this.miniOy = pad + ((h - pad * 2) - bh * s) / 2;

    this.graph.nodes.forEach((node: GraphNodeView) => {
      const active = this.isActive(node);
      ctx.fillStyle = active ? 'rgba(124,92,255,0.75)' : 'rgba(150,150,160,0.5)';
      ctx.fillRect(
        this.miniOx + (node.x - minX) * s,
        this.miniOy + (node.y - minY) * s,
        Math.max(1, node.w * s),
        Math.max(1, node.h * s),
      );
    });

    // 当前视口框
    const viewLeft = -this.originX / this.zoom;
    const viewTop = -this.originY / this.zoom;
    const viewW = this.ctx.width / this.zoom;
    const viewH = this.ctx.height / this.zoom;
    ctx.strokeStyle = 'rgba(124,92,255,0.95)';
    ctx.lineWidth = 1.4;
    ctx.strokeRect(
      this.miniOx + (viewLeft - minX) * s,
      this.miniOy + (viewTop - minY) * s,
      viewW * s,
      viewH * s,
    );
  }

  /** 点小地图：把点击位置对应的世界坐标移到视口中心。 */
  private miniJump(x: number, y: number): void {
    if (this.miniScale <= 0) {
      return;
    }
    const worldX = (x - this.miniOx) / this.miniScale + this.miniMinX;
    const worldY = (y - this.miniOy) / this.miniScale + this.miniMinY;
    this.originX = this.ctx.width / 2 - worldX * this.zoom;
    this.originY = this.ctx.height / 2 - worldY * this.zoom;
    this.draw();
  }

  private drawSlot(
    node: GraphNodeView,
    index: number,
    input: boolean,
    name: string,
    left: number,
    top: number,
    nw: number,
    nh: number,
    headerH: number,
    stepPx: number,
  ): void {
    const ctx = this.ctx;
    const y = this.sy(this.slotYWorld(node, index));
    if (y < top || y > top + nh) {
      return;
    }
    const x = input ? left : left + nw;
    const key = slotKey({ nodeId: node.id, index: index, input: input });
    const highlighted = key === this.selectedSlot || key === this.linkStart || this.pendingLink.includes(key);
    ctx.beginPath();
    ctx.fillStyle = highlighted ? this.c().accent : 'rgba(124,92,255,0.8)';
    ctx.arc(x, y, highlighted ? 4 : 2.6, 0, Math.PI * 2);
    ctx.fill();
    if (highlighted) {
      ctx.strokeStyle = 'rgba(124,92,255,0.45)';
      ctx.lineWidth = 1.6;
      ctx.beginPath();
      ctx.arc(x, y, 8, 0, Math.PI * 2);
      ctx.stroke();
    }
    if (name.length === 0 || stepPx < 6) {
      return;
    }
    let px = Math.min(nw * 0.055, stepPx * 0.8);
    if (px < 5) {
      return;
    }
    ctx.font = `${px}px sans-serif`;
    const measured = ctx.measureText(name).width;
    const maxWidth = Math.max(24, nw / 2 - 12);
    if (measured > maxWidth && measured > 0) {
      px = Math.max(4, px * maxWidth / measured);
      ctx.font = `${px}px sans-serif`;
    }
    ctx.fillStyle = this.c().textMuted;
    ctx.textBaseline = 'middle';
    if (input) {
      ctx.fillText(name, left + 9, y, maxWidth);
    } else {
      ctx.textAlign = 'right';
      ctx.fillText(name, left + nw - 9, y, maxWidth);
      ctx.textAlign = 'left';
    }
  }

  /** 命中参数：按文字实际宽度判定，圆点再给 12px 热区。 */
  private hitSlot(x: number, y: number): SlotRef | null {
    let result: SlotRef | null = null;
    this.graph.nodes.forEach((node: GraphNodeView) => {
      if (result !== null) {
        return;
      }
      const left = this.sx(node.x);
      const top = this.sy(node.y);
      const nw = node.w * this.zoom;
      const nh = node.h * this.zoom;
      if (nw < 8 || nh < 8) {
        return;
      }
      const stepPx = Math.max(4, (nh - nh * 0.26) / Math.max(1, Math.max(node.inputs.length, node.outputs.length)));
      node.inputs.forEach((input, index: number) => {
        if (result !== null) {
          return;
        }
        const cy = this.sy(this.slotYWorld(node, index));
        const dotX = left;
        if (Math.abs(x - dotX) <= 12 && Math.abs(y - cy) <= Math.max(10, stepPx / 2)) {
          result = { nodeId: node.id, index: index, input: true };
          return;
        }
        if (stepPx >= 6) {
          const px = Math.min(nw * 0.055, stepPx * 0.8);
          this.ctx.font = `${px}px sans-serif`;
          const width = this.ctx.measureText(input.name).width;
          if (x >= left + 9 && x <= left + 9 + width && Math.abs(y - cy) <= px * 0.7) {
            result = { nodeId: node.id, index: index, input: true };
          }
        }
      });
      node.outputs.forEach((output, index: number) => {
        if (result !== null) {
          return;
        }
        const cy = this.sy(this.slotYWorld(node, index));
        const dotX = left + nw;
        if (Math.abs(x - dotX) <= 12 && Math.abs(y - cy) <= Math.max(10, stepPx / 2)) {
          result = { nodeId: node.id, index: index, input: false };
          return;
        }
        if (stepPx >= 6) {
          const px = Math.min(nw * 0.055, stepPx * 0.8);
          this.ctx.font = `${px}px sans-serif`;
          const width = this.ctx.measureText(output.name).width;
          if (x >= left + nw - 9 - width && x <= left + nw - 9 && Math.abs(y - cy) <= px * 0.7) {
            result = { nodeId: node.id, index: index, input: false };
          }
        }
      });
    });
    return result;
  }


  private searchResults(): GraphNodeView[] {
    const q = this.query.trim().toLowerCase();
    if (q.length === 0) {
      return [];
    }
    return this.graph.nodes
      .filter((node: GraphNodeView) =>
        node.type.toLowerCase().includes(q) ||
        node.title.toLowerCase().includes(q) ||
        `${node.id}`.includes(q))
      .slice(0, 8);
  }

  /** 把某个节点移到画布中心并选中。 */
  private centerOnNode(node: GraphNodeView): void {
    this.originX = this.ctx.width / 2 - (node.x + node.w / 2) * this.zoom;
    this.originY = this.ctx.height / 2 - (node.y + node.h / 2) * this.zoom;
    this.query = '';
    this.selectedNode = node.id;
    this.selectedSlot = '';
    this.linkStart = '';
    this.pendingLink = '';
    this.cardExpanded = false;
    this.draw();
  }  private hitNode(x: number, y: number): number {
    let hit = -1;
    this.graph.nodes.forEach((node: GraphNodeView) => {
      const left = this.sx(node.x);
      const top = this.sy(node.y);
      const nw = node.w * this.zoom;
      const nh = node.h * this.zoom;
      if (x >= left && x <= left + nw && y >= top && y <= top + nh) {
        hit = node.id;
      }
    });
    return hit;
  }

  private handleTap(x: number, y: number): void {
    const slot = this.hitSlot(x, y);
    const start = parseSlotKey(this.linkStart);
    if (start !== null && slot !== null) {
      const out = !start.input ? start : (!slot.input ? slot : null);
      const inn = start.input ? start : (slot.input ? slot : null);
      if (out !== null && inn !== null && out.nodeId !== inn.nodeId) {
        this.pendingLink = `${slotKey(out)}|${slotKey(inn)}`;
        this.linkStart = '';
        this.selectedSlot = '';
        this.selectedNode = -1;
        this.cardExpanded = false;
        this.draw();
        return;
      }
    }
    if (slot !== null) {
      this.selectedSlot = slotKey(slot);
      this.selectedNode = -1;
      this.linkStart = '';
      this.pendingLink = '';
      this.cardExpanded = false;
    } else {
      const node = this.hitNode(x, y);
      this.selectedNode = node;
      this.selectedSlot = '';
      this.linkStart = '';
      this.pendingLink = '';
      this.cardExpanded = false;
    }
    this.draw();
  }

  private slotInfo(key: string): SlotInfo | null {
    const slot = parseSlotKey(key);
    if (slot === null) {
      return null;
    }
    const node = this.nodesById().get(slot.nodeId);
    if (node === undefined) {
      return null;
    }
    if (slot.input) {
      const input = node.inputs[slot.index];
      if (input === undefined) {
        return null;
      }
      const info: SlotInfo = { node: node, name: input.name, type: input.type, linkId: input.linkId, input: true };
      return info;
    }
    const output = node.outputs[slot.index];
    if (output === undefined) {
      return null;
    }
    const info: SlotInfo = { node: node, name: output.name, type: output.type, linkId: 0, input: false };
    return info;
  }

  private nodeLabel(node: GraphNodeView): string {
    return node.title.length > 0 ? node.title : node.type;
  }

  @Builder
  actionChip(text: string, color: string, action: () => void) {
    Text(text)
      .fontSize(12)
      .fontColor(color)
      .padding({ left: 12, right: 12, top: 7, bottom: 7 })
      .borderRadius(9)
      .backgroundColor(this.c().card)
      .border({ width: 1, color: this.c().border })
      .onClick(action);
  }

  build() {
    Stack({ alignContent: Alignment.Bottom }) {
      Canvas(this.ctx)
        .width('100%')
        .height('100%')
        .onReady(() => {
          this.fitted = false;
          this.draw();
        })
        .gesture(
          GestureGroup(
            GestureMode.Parallel,
            PanGesture({ fingers: 1 })
              .onActionStart(() => {
                // 捏合时双指也会触发平移，直接忽略，避免和缩放抢 origin
                if (this.pinching) {
                  return;
                }
                this.panBaseX = this.originX;
                this.panBaseY = this.originY;
              })
              .onActionUpdate((event: GestureEvent) => {
                if (this.pinching) {
                  return;
                }
                this.originX = this.panBaseX + event.offsetX;
                this.originY = this.panBaseY + event.offsetY;
                this.draw();
              }),
            PinchGesture({ fingers: 2 })
              .onActionStart((event: GestureEvent) => {
                this.pinching = true;
                this.pinchBase = this.zoom;
                this.pinchOriginX = this.originX;
                this.pinchOriginY = this.originY;
                // 双指中心（组件局部坐标）：缩放时让这个点下面的内容保持不动
                const fingers = event.fingerList;
                if (fingers.length >= 2) {
                  this.pinchAnchorX = (fingers[0].localX + fingers[1].localX) / 2;
                  this.pinchAnchorY = (fingers[0].localY + fingers[1].localY) / 2;
                } else if (fingers.length === 1) {
                  this.pinchAnchorX = fingers[0].localX;
                  this.pinchAnchorY = fingers[0].localY;
                } else {
                  this.pinchAnchorX = this.ctx.width / 2;
                  this.pinchAnchorY = this.ctx.height / 2;
                }
              })
              .onActionUpdate((event: GestureEvent) => {
                const next = Math.min(3, Math.max(0.02, this.pinchBase * event.scale));
                const ratio = next / this.pinchBase;
                this.zoom = next;
                this.originX = this.pinchAnchorX - (this.pinchAnchorX - this.pinchOriginX) * ratio;
                this.originY = this.pinchAnchorY - (this.pinchAnchorY - this.pinchOriginY) * ratio;
                this.draw();
              })
              .onActionEnd(() => {
                // 松手后把平移基准对齐到当前 origin，下一次单指拖动不会跳。
                this.pinching = false;
                this.panBaseX = this.originX;
                this.panBaseY = this.originY;
                this.draw();
              })
          )
        )
        .onClick((event: ClickEvent) => {
          this.handleTap(event.x, event.y);
        })

      // 顶栏
      Row() {
        Column() {
          Text('节点图')
            .fontSize(17)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.c().text);
          Text(`${this.graph.nodes.length} 节点 · ${this.graph.groups.length} 分组 · ${this.graph.links.length} 连线`)
            .fontSize(10)
            .fontColor(this.c().textDim)
            .margin({ top: 2 });
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1);

        this.actionChip('－', this.c().text, () => {
          this.zoom = Math.max(0.02, this.zoom * 0.7);
          this.draw();
        });
        this.actionChip('＋', this.c().text, () => {
          this.zoom = Math.min(3, this.zoom * 1.4);
          this.draw();
        });
        this.actionChip('适配', this.c().accent, () => {
          this.fitted = false;
          this.fitToScreen();
          this.draw();
        });
        this.actionChip('关闭', this.c().textMuted, () => this.onClose());
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 12 + this.topInset, bottom: 10 })
      .backgroundColor(this.c().background)
      .position({ x: 0, y: 0 });

      TextInput({ placeholder: '搜索节点名 / 类型 / 编号', text: this.query })
        .fontSize(12)
        .fontColor(this.c().text)
        .placeholderColor(this.c().textDim)
        .backgroundColor(this.c().card)
        .borderRadius(12)
        .border({ width: 1, color: this.c().border })
        .height(36)
        .padding({ left: 12, right: 12 })
        .margin({ left: 14, right: 14 })
        .position({ x: 0, y: 56 + this.topInset })
        .width('100%')
        .onChange((value: string) => {
          this.query = value;
        });

      if (this.query.trim().length > 0) {
        Column() {
          ForEach(this.searchResults(), (node: GraphNodeView) => {
            Row() {
              Text(`#${node.id}`)
                .fontSize(10)
                .fontFamily('monospace')
                .fontColor(this.c().textDim)
                .width(48);
              Text(this.nodeLabel(node))
                .fontSize(12)
                .fontColor(this.c().text)
                .layoutWeight(1)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis });
            }
            .width('100%')
            .padding({ left: 12, right: 12, top: 9, bottom: 9 })
            .onClick(() => this.centerOnNode(node));
          }, (node: GraphNodeView) => `${node.id}`);
        }
        .width('100%')
        .padding({ left: 14, right: 14 })
        .position({ x: 0, y: 96 + this.topInset });
      }

      // 右下角小地图
      Column() {
        Canvas(this.miniCtx)
          .width(92)
          .height(118)
          .borderRadius(14)
          .backgroundColor(this.c().isDark ? 'rgba(24,26,34,0.82)' : 'rgba(255,255,255,0.82)')
          .border({ width: 1, color: this.c().borderStrong })
          .shadow({ radius: 12, color: 'rgba(0,0,0,0.18)', offsetX: 0, offsetY: 4 })
          .onReady(() => this.drawMini())
          .onClick((event: ClickEvent) => this.miniJump(event.x, event.y));
      }
      .width('100%')
      .height('100%')
      .justifyContent(FlexAlign.End)
      .alignItems(HorizontalAlign.End)
      .padding({ right: 14, bottom: 150 })
      .hitTestBehavior(HitTestMode.Transparent);

      // 底部卡片
      if (this.selectedSlot.length > 0) {
        this.slotCard();
      } else if (this.pendingLink.length > 0) {
        this.pendingCard();
      } else if (this.selectedNode >= 0) {
        this.nodeCard();
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.c().background);
  }

  @Builder
  slotCard() {
    Column({ space: 8 }) {
      if (this.slotInfo(this.selectedSlot) !== null) {
        Row() {
          Text(this.slotTitle())
            .fontSize(13)
            .fontColor(this.c().text)
            .layoutWeight(1);
          Text('✕')
            .fontSize(13)
            .fontColor(this.c().textMuted)
            .onClick(() => {
              this.selectedSlot = '';
              this.linkStart = '';
              this.draw();
            });
        }
        .width('100%');

        Text(this.slotSubtitle())
          .fontSize(10)
          .fontColor(this.c().textDim)
          .width('100%');

        if (this.linkStart === this.selectedSlot) {
          Text('已选中这个参数，去点另一个节点的输出/输入完成连线')
            .fontSize(11)
            .fontColor(this.c().accent)
            .width('100%');
        }

        Row({ space: 8 }) {
          if (this.linkStart === this.selectedSlot) {
            this.actionChip('取消连线', this.c().textMuted, () => {
              this.linkStart = '';
              this.draw();
            });
          } else {
            this.actionChip('连线', this.c().accent, () => {
              this.linkStart = this.selectedSlot;
              this.draw();
            });
          }
          if (this.slotLinkedId() > 0) {
            this.actionChip('断开此参数连线', this.c().warn, () => {
              const id = this.slotLinkedId();
              this.selectedSlot = '';
              this.linkStart = '';
              this.onRemoveLink(id);
              this.draw();
            });
          }
          Blank();
        }
        .width('100%');
      }
    }
    .width('100%')
    .borderRadius(18)
    .padding(14)
    .backgroundColor(this.c().isDark ? 'rgba(28,30,40,0.55)' : 'rgba(255,255,255,0.52)')
    .backdropBlur(28)
    .border({ width: 1, color: this.c().isDark ? 'rgba(255,255,255,0.18)' : 'rgba(255,255,255,0.78)' })
    .shadow({
      radius: 18,
      color: this.c().isDark ? 'rgba(0,0,0,0.35)' : 'rgba(31,41,55,0.16)',
      offsetX: 0,
      offsetY: 6,
    })
    .margin({ left: 14, right: 14, bottom: 16 });
  }

  @Builder
  nodeCard() {
    Column({ space: 8 }) {
      if (this.selectedNodeData() !== null) {
        Row() {
          Text(this.nodeTitle())
            .fontSize(13)
            .fontColor(this.c().text)
            .layoutWeight(1)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis });
          Text('✕')
            .fontSize(13)
            .fontColor(this.c().textMuted)
            .onClick(() => {
              this.selectedNode = -1;
              this.draw();
            });
        }
        .width('100%');

        Text(this.nodeSubtitle())
          .fontSize(10)
          .fontColor(this.c().textDim)
          .width('100%');

        Row({ space: 8 }) {
          this.actionChip(this.nodeEnabled() ? '关闭节点' : '启用节点', this.nodeEnabled() ? this.c().warn : this.c().ok, () => {
            this.onToggleNode(this.selectedNode);
          });
          this.actionChip(this.cardExpanded ? '收起参数' : '展开参数', this.c().accent, () => {
            this.cardExpanded = !this.cardExpanded;
          });
          this.actionChip('跳转', this.c().textMuted, () => {
            const id = this.selectedNode;
            this.selectedNode = -1;
            this.onOpenNode(id);
          });
          Blank();
        }
        .width('100%');

        if (this.cardExpanded) {
          ForEach(this.nodeFields(), (row: NodeFieldRow) => {
            Row() {
              Text(row.name)
                .fontSize(11)
                .fontColor(this.c().textMuted)
                .layoutWeight(1)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis });
              Text(row.value)
                .fontSize(11)
                .fontFamily('monospace')
                .fontColor(this.c().text)
                .layoutWeight(1.4)
                .maxLines(2)
                .textOverflow({ overflow: TextOverflow.Ellipsis });
            }
            .width('100%')
            .padding({ top: 4, bottom: 4 });
          }, (row: NodeFieldRow) => `${row.nodeId}:${row.name}`);
        }
      }
    }
    .width('100%')
    .borderRadius(18)
    .padding(14)
    .backgroundColor(this.c().isDark ? 'rgba(28,30,40,0.55)' : 'rgba(255,255,255,0.52)')
    .backdropBlur(28)
    .border({ width: 1, color: this.c().isDark ? 'rgba(255,255,255,0.18)' : 'rgba(255,255,255,0.78)' })
    .shadow({
      radius: 18,
      color: this.c().isDark ? 'rgba(0,0,0,0.35)' : 'rgba(31,41,55,0.16)',
      offsetX: 0,
      offsetY: 6,
    })
    .margin({ left: 14, right: 14, bottom: 16 });
  }

  @Builder
  pendingCard() {
    Column({ space: 8 }) {
      Text('新建连线')
        .fontSize(11)
        .fontColor(this.c().accent)
        .width('100%');
      Text(this.pendingTitle())
        .fontSize(13)
        .fontColor(this.c().text)
        .width('100%');
      Text(this.pendingSubtitle())
        .fontSize(10)
        .fontColor(this.c().textDim)
        .width('100%');
      Row({ space: 8 }) {
        this.actionChip('确认连线', this.c().ok, () => {
          const override = this.pendingOverride();
          this.pendingLink = '';
          if (override !== null) {
            this.onAddLink(override);
          }
          this.draw();
        });
        this.actionChip('取消', this.c().textMuted, () => {
          this.pendingLink = '';
          this.draw();
        });
        Blank();
      }
      .width('100%');
    }
    .width('100%')
    .borderRadius(18)
    .padding(14)
    .backgroundColor(this.c().isDark ? 'rgba(28,30,40,0.55)' : 'rgba(255,255,255,0.52)')
    .backdropBlur(28)
    .border({ width: 1, color: this.c().isDark ? 'rgba(255,255,255,0.18)' : 'rgba(255,255,255,0.78)' })
    .shadow({
      radius: 18,
      color: this.c().isDark ? 'rgba(0,0,0,0.35)' : 'rgba(31,41,55,0.16)',
      offsetX: 0,
      offsetY: 6,
    })
    .margin({ left: 14, right: 14, bottom: 16 });
  }

  private slotTitle(): string {
    const info = this.slotInfo(this.selectedSlot);
    if (info === null) {
      return '';
    }
    return `#${info.node.id} ${this.nodeLabel(info.node)} · ${info.name}`;
  }

  private slotSubtitle(): string {
    const info = this.slotInfo(this.selectedSlot);
    if (info === null) {
      return '';
    }
    return `类型 ${info.type} · ${info.linkId > 0 ? '已连接' : '未连接'}`;
  }

  private slotLinkedId(): number {
    const info = this.slotInfo(this.selectedSlot);
    return info === null ? 0 : info.linkId;
  }

  private selectedNodeData(): GraphNodeView | null {
    const node = this.nodesById().get(this.selectedNode);
    return node === undefined ? null : node;
  }

  private nodeTitle(): string {
    const node = this.selectedNodeData();
    return node === null ? '' : `#${node.id} ${this.nodeLabel(node)}`;
  }

  private nodeEnabled(): boolean {
    return !this.bypassedIds.includes(this.selectedNode);
  }

  private nodeSubtitle(): string {
    const node = this.selectedNodeData();
    if (node === null) {
      return '';
    }
    const state = this.nodeEnabled() ? '启用' : '已关闭';
    const graphState = this.graph.disabledNodeIds.includes(node.id) ? ' · 所在节点图未启用' : '';
    return `${node.type} · ${state}${graphState} · 输入 ${node.inputs.length} / 输出 ${node.outputs.length}`;
  }

  private nodeFields(): NodeFieldRow[] {
    return this.fieldRows.filter((row: NodeFieldRow) => row.nodeId === this.selectedNode);
  }

  private pendingPair(): PendingPair | null {
    const parts = this.pendingLink.split('|');
    if (parts.length !== 2) {
      return null;
    }
    const out = parseSlotKey(parts[0]);
    const input = parseSlotKey(parts[1]);
    if (out === null || input === null) {
      return null;
    }
    const pair: PendingPair = { out: out, input: input };
    return pair;
  }

  private pendingTitle(): string {
    const pair = this.pendingPair();
    if (pair === null) {
      return '';
    }
    const byId = this.nodesById();
    const outNode = byId.get(pair.out.nodeId);
    const inNode = byId.get(pair.input.nodeId);
    if (outNode === undefined || inNode === undefined) {
      return '';
    }
    const outName = outNode.outputs[pair.out.index]?.name ?? '?';
    const inName = inNode.inputs[pair.input.index]?.name ?? '?';
    return `${this.nodeLabel(outNode)}.${outName}  →  ${this.nodeLabel(inNode)}.${inName}`;
  }

  private pendingSubtitle(): string {
    const pair = this.pendingPair();
    if (pair === null) {
      return '';
    }
    const byId = this.nodesById();
    const outNode = byId.get(pair.out.nodeId);
    const inNode = byId.get(pair.input.nodeId);
    const type = outNode?.outputs[pair.out.index]?.type ?? inNode?.inputs[pair.input.index]?.type ?? '?';
    return `类型 ${type}`;
  }

  private pendingOverride(): GraphLinkOverride | null {
    const pair = this.pendingPair();
    if (pair === null) {
      return null;
    }
    const byId = this.nodesById();
    const inNode = byId.get(pair.input.nodeId);
    if (inNode === undefined) {
      return null;
    }
    const input = inNode.inputs[pair.input.index];
    if (input === undefined) {
      return null;
    }
    const outNode = byId.get(pair.out.nodeId);
    const type = outNode?.outputs[pair.out.index]?.type ?? input.type;
    return {
      nodeId: inNode.id,
      inputName: input.name,
      originNodeId: pair.out.nodeId,
      originSlot: pair.out.index,
      type: type,
    };
  }
}
