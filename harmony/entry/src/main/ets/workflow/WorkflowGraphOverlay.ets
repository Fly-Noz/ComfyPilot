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
  @Prop @Watch('onGraphDataChanged') graph: WorkflowGraphData;
  /** 当前工作流路径：只有换文档才重新适配视野。 */
  @Prop @Watch('onDocumentChanged') documentKey: string;
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
  /** 画布逻辑尺寸（vp）：onAreaChange 给出，比 ctx.width 可靠。 */
  private viewW: number = 0;
  private viewH: number = 0;
  private userInteracted: boolean = false;
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

  /** 节点开关、连线编辑都会刷新 graph：只重绘，保持当前缩放/位置。 */
  onGraphDataChanged(): void {
    this.draw();
  }

  /** 换工作流才重新适配。 */
  onDocumentChanged(): void {
    this.fitted = false;
    this.pendingFit = true;
    setTimeout(() => {
      this.tryFit();
      this.draw();
    }, 0);
  }

  private pendingFit: boolean = true;

  /** 画布尺寸或数据未就绪时先记着，等两者都到齐再适配。 */
  private tryFit(): void {
    if (this.viewW > 0 && this.viewH > 0 && this.graph.nodes.length > 0) {
      this.fitToScreen();
      this.pendingFit = false;
    }
  }

  private nodesById(): Map<number, GraphNodeView> {
    const map: Map<number, GraphNodeView> = new Map();
    this.graph.nodes.forEach((node: GraphNodeView) => map.set(node.id, node));
    return map;
  }

  private isActive(node: GraphNodeView): boolean {
    if (this.bypassedIds.includes(node.id)) {
      return false;
    }
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
    const w = this.viewW;
    const h = this.viewH;
    if (w <= 0 || h <= 0 || this.graph.nodes.length === 0) {
      return;
    }
    // 优先以「启用节点」为中心：整图全显会把主图压成一小团。
    const focus = this.graph.nodes.filter((node: GraphNodeView) =>
      this.graph.activeNodeIds.includes(node.id) && node.outputs.length > 0);
    const framed = focus.length > 0 ? focus : this.graph.nodes;
    let minX = Number.MAX_VALUE;
    let minY = Number.MAX_VALUE;
    let maxX = -Number.MAX_VALUE;
    let maxY = -Number.MAX_VALUE;
    framed.forEach((node: GraphNodeView) => {
      minX = Math.min(minX, node.x);
      minY = Math.min(minY, node.y);
      maxX = Math.max(maxX, node.x + node.w);
      maxY = Math.max(maxY, node.y + node.h);
    });
    const bw = Math.max(1, maxX - minX);
    const bh = Math.max(1, maxY - minY);
    const fit = Math.min(w / bw, h / bh) * 0.9;
    // 大盘整图适配会把节点压到看不清：低于可读比例时改为定位到主图中心，
    // 让用户从「能看清」的缩放开始，再自己缩小看全貌。
    const readable = 0.34;
    if (fit >= readable) {
      this.zoom = Math.min(1, fit);
      this.originX = (w - bw * this.zoom) / 2 - minX * this.zoom;
      this.originY = (h - bh * this.zoom) / 2 - minY * this.zoom;
    } else {
      let sumX = 0;
      let sumY = 0;
      framed.forEach((node: GraphNodeView) => {
        sumX += node.x + node.w / 2;
        sumY += node.y + node.h / 2;
      });
      const centerX = sumX / Math.max(1, framed.length);
      const centerY = sumY / Math.max(1, framed.length);
      this.zoom = readable;
      this.originX = w / 2 - centerX * this.zoom;
      this.originY = h / 2 - centerY * this.zoom;
    }
    this.fitted = true;
  }

  private draw(): void {
    const ctx = this.ctx;
    const w = ctx.width;
    const h = ctx.height;
    if (w <= 0 || h <= 0) {
      return;
    }
    if (!this.fitted || this.pendingFit) {
      this.tryFit();
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
        // 全屏统一字号：跟缩放走但夹在可读区间，同一界面里所有标题一样大
        const px = Math.min(15, Math.max(10, 30 * this.zoom));
        ctx.font = `${px}px sans-serif`;
        ctx.fillStyle = '#FFFFFF';
        ctx.textBaseline = 'middle';
        // 超长标题裁在标题栏内，不压缩字号，保证比例一致
        ctx.save();
        ctx.beginPath();
        ctx.rect(left, top, nw, headerH);
        ctx.clip();
        ctx.fillText(title, left + 5, top + headerH / 2);
        ctx.restore();
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
    const viewW = this.viewW / this.zoom;
    const viewH = this.viewH / this.zoom;
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
    this.originX = this.viewW / 2 - worldX * this.zoom;
    this.originY = this.viewH / 2 - worldY * this.zoom;
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
    // 圆点半径跟节点宽度走：缩小时一起变小，不会显得越来越大
    const baseRadius = Math.max(1.1, Math.min(3.2, nw * 0.018));
    ctx.beginPath();
    ctx.fillStyle = highlighted ? this.c().accent : 'rgba(124,92,255,0.8)';
    ctx.arc(x, y, highlighted ? baseRadius + 1.4 : baseRadius, 0, Math.PI * 2);
    ctx.fill();
    if (highlighted) {
      ctx.strokeStyle = 'rgba(124,92,255,0.45)';
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.arc(x, y, baseRadius + 5, 0, Math.PI * 2);
      ctx.stroke();
    }
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
    // 参数名与标题同一套规则：全屏一致，缩放时整体变化
    const px = Math.min(11, Math.max(7, 22 * this.zoom));
    if (stepPx < 5) {
      return;
    }
    ctx.font = `${px}px sans-serif`;
    ctx.fillStyle = this.c().textMuted;
    ctx.textBaseline = 'middle';
    ctx.save();
    ctx.beginPath();
    if (input) {
      ctx.rect(left, y - stepPx / 2, nw / 2 - 6, stepPx);
    } else {
      ctx.rect(left + nw / 2 + 6, y - stepPx / 2, nw / 2 - 6, stepPx);
    }
    ctx.clip();
    if (input) {
      ctx.fillText(name, left + 9, y);
    } else {
      ctx.textAlign = 'right';
      ctx.fillText(name, left + nw - 9, y);
      ctx.textAlign = 'left';
    }
    ctx.restore();
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
        if (stepPx >= 5) {
          const px = Math.min(11, Math.max(7, 22 * this.zoom));
          this.ctx.font = `${px}px sans-serif`;
          const width = this.ctx.measureText(input.name).width;
          // 文字整行都算命中（左右留 8px），不用精确点在字上
          if (x >= left + 4 && x <= left + 9 + width + 8 && Math.abs(y - cy) <= Math.max(9, stepPx / 2)) {
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
        if (stepPx >= 5) {
          const px = Math.min(11, Math.max(7, 22 * this.zoom));
          this.ctx.font = `${px}px sans-serif`;
          const width = this.ctx.measureText(output.name).width;
          if (x >= left + nw - 9 - width - 8 && x <= left + nw - 4 && Math.abs(y - cy) <= Math.max(9, stepPx / 2)) {
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
    this.originX = this.viewW / 2 - (node.x + node.w / 2) * this.zoom;
    this.originY = this.viewH / 2 - (node.y + node.h / 2) * this.zoom;
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
      // 有些工作流只在 links 里记录连接、节点 inputs 的 link 字段为空：
      // 以 links 为准反查，避免明明有线却显示未连接。
      let linkId = input.linkId;
      this.graph.links.forEach((link: GraphLinkView) => {
        if (link.targetId === node.id && link.targetSlot === slot.index && link.id !== 0) {
          linkId = link.id;
        }
      });
      const info: SlotInfo = { node: node, name: input.name, type: input.type, linkId: linkId, input: true };
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
          this.pendingFit = true;
          this.tryFit();
          this.draw();
        })
        .onAreaChange((oldValue: Area, newValue: Area) => {
          this.viewW = Number(newValue.width);
          this.viewH = Number(newValue.height);
          // 用户还没动过视野时，尺寸变化就重新适配一次
          if (!this.userInteracted) {
            this.fitted = false;
            this.pendingFit = true;
          }
          this.tryFit();
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
                this.userInteracted = true;
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
                this.userInteracted = true;
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
                  this.pinchAnchorX = this.viewW / 2;
                  this.pinchAnchorY = this.viewH / 2;
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
        .position({ x: 14, y: 56 + this.topInset })
        .width('calc(100% - 28vp)')
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
          if (this.slotLinkedId() !== 0) {
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
    .width('calc(100% - 28vp)')
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

        // 注意：@Builder 的参数不是响应式的，动态文案必须直接写在 Text 里，
        // 否则点「关闭节点」后按钮文字不会跟着状态变。
        Row({ space: 8 }) {
          Text(this.nodeBypassBlocked() ? '节点图未启用' : (this.nodeEnabled() ? '关闭节点' : '启用节点'))
            .fontSize(12)
            .fontColor(this.nodeBypassBlocked()
              ? this.c().textDim
              : (this.nodeEnabled() ? this.c().warn : this.c().ok))
            .padding({ left: 12, right: 12, top: 7, bottom: 7 })
            .borderRadius(9)
            .backgroundColor(this.c().card)
            .border({ width: 1, color: this.c().border })
            .onClick(() => {
              if (!this.nodeBypassBlocked()) {
                this.onToggleNode(this.selectedNode);
              }
            });
          Text(this.cardExpanded ? '收起参数' : '展开参数')
            .fontSize(12)
            .fontColor(this.c().accent)
            .padding({ left: 12, right: 12, top: 7, bottom: 7 })
            .borderRadius(9)
            .backgroundColor(this.c().card)
            .border({ width: 1, color: this.c().border })
            .onClick(() => {
              this.cardExpanded = !this.cardExpanded;
            });
          Text('跳转')
            .fontSize(12)
            .fontColor(this.c().textMuted)
            .padding({ left: 12, right: 12, top: 7, bottom: 7 })
            .borderRadius(9)
            .backgroundColor(this.c().card)
            .border({ width: 1, color: this.c().border })
            .onClick(() => {
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
    .width('calc(100% - 28vp)')
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
    .width('calc(100% - 28vp)')
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
    // 新建的连线在共享层用负 id（合成 id），所以不能用 > 0 判断
    return `类型 ${info.type} · ${info.linkId !== 0 ? '已连接' : '未连接'}`;
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
    const node = this.selectedNodeData();
    if (node === null) {
      return false;
    }
    if (this.bypassedIds.includes(node.id)) {
      return false;
    }
    if (node.outputs.length === 0) {
      return true;
    }
    if (this.graph.disabledNodeIds.includes(node.id)) {
      return false;
    }
    return this.graph.activeNodeIds.includes(node.id);
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


  private nodeBypassBlocked(): boolean {
    const node = this.selectedNodeData();
    return node === null ? false : this.graph.disabledNodeIds.includes(node.id);
  }  private nodeFields(): NodeFieldRow[] {
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
