import { http } from '@kit.NetworkKit';

/** 一项都没拿到时用 -1 / ''，页面据此跳过单元格，绝不显示假的 0。 */
export interface ServerStats {
  ramPercent: number;
  vramPercent: number;
  cpuPercent: number;
  gpuPercent: number;
  temperature: number;
  version: string;
}

export function emptyServerStats(): ServerStats {
  return {
    ramPercent: -1,
    vramPercent: -1,
    cpuPercent: -1,
    gpuPercent: -1,
    temperature: -1,
    version: '',
  };
}

/** 只比较页面真正会显示的内容，避免每 2 秒用同样的值触发一次重绘。 */
export function sameServerStats(a: ServerStats, b: ServerStats): boolean {
  return Math.round(a.ramPercent) === Math.round(b.ramPercent) &&
    Math.round(a.vramPercent) === Math.round(b.vramPercent) &&
    Math.round(a.cpuPercent) === Math.round(b.cpuPercent) &&
    Math.round(a.gpuPercent) === Math.round(b.gpuPercent) &&
    Math.round(a.temperature) === Math.round(b.temperature) &&
    a.version === b.version;
}

/** ComfyUI `/system_stats` 里页面关心的字段；其余字段忽略。 */
interface SystemSection {
  ram_total?: number;
  ram_free?: number;
  comfyui_version?: string;
}

interface DeviceSection {
  vram_total?: number;
  vram_free?: number;
  torch_vram_total?: number;
  torch_vram_free?: number;
}

interface SystemStatsBody {
  system?: SystemSection;
  devices?: DeviceSection[];
  comfyui_version?: string;
}

/** Crystools 监控帧；不同版本字段名不一样，能认的都列出来。 */
interface MonitorGpu {
  gpu_utilization?: number;
  gpu_utilization_percent?: number;
  gpu_percent?: number;
  gpu_usage?: number;
  vram_used_percent?: number;
  vram_percent?: number;
  vram_usage?: number;
  temperature?: number;
  gpu_temperature?: number;
  temp?: number;
  vram_total?: number;
  vram_used?: number;
  vram_free?: number;
}

interface MonitorBody {
  cpu_utilization?: number;
  cpu_utilization_percent?: number;
  cpu_percent?: number;
  cpu_usage?: number;
  ram_used_percent?: number;
  ram_percent?: number;
  ram_usage?: number;
  gpu_utilization?: number;
  gpu_utilization_percent?: number;
  gpu_percent?: number;
  gpu_usage?: number;
  vram_used_percent?: number;
  vram_percent?: number;
  vram_usage?: number;
  temperature?: number;
  gpu_temperature?: number;
  temp?: number;
  gpus?: MonitorGpu[];
}

const STATS_TIMEOUT_MS: number = 4000;

async function getJson(client: http.HttpRequest, url: string): Promise<string> {
  try {
    const response = await client.request(url, {
      method: http.RequestMethod.GET,
      header: { 'Accept': 'application/json' },
      connectTimeout: STATS_TIMEOUT_MS,
      readTimeout: STATS_TIMEOUT_MS,
      expectDataType: http.HttpDataType.STRING,
      usingCache: false,
    });
    if (response.responseCode < 200 || response.responseCode >= 300) {
      return '';
    }
    return typeof response.result === 'string' ? response.result : '';
  } catch (err) {
    // 服务器离线、地址写错都走这里：静默降级为“这一项没有”。
    return '';
  }
}

function clampPercent(value: number): number {
  if (!Number.isFinite(value)) {
    return -1;
  }
  if (value < 0) {
    return 0;
  }
  if (value > 100) {
    return 100;
  }
  return value;
}

function pickNumber(values: Array<number | undefined>): number {
  for (let i = 0; i < values.length; i++) {
    const value = values[i];
    if (value !== undefined && Number.isFinite(value)) {
      return value;
    }
  }
  return -1;
}

function percentOf(used: number, total: number): number {
  if (!Number.isFinite(used) || !Number.isFinite(total) || total <= 0) {
    return -1;
  }
  return clampPercent((used / total) * 100);
}

function trimBase(baseUrl: string): string {
  let base = baseUrl.trim();
  while (base.endsWith('/')) {
    base = base.substring(0, base.length - 1);
  }
  return base;
}

/** `/system_stats`：内存、显存占比和版本号，是 ComfyUI 自带的接口。 */
function parseSystemStats(raw: string, stats: ServerStats): boolean {
  let body: SystemStatsBody;
  try {
    body = JSON.parse(raw) as SystemStatsBody;
  } catch (err) {
    return false;
  }
  let found = false;
  const system = body.system;
  if (system !== undefined) {
    const total = pickNumber([system.ram_total]);
    const free = pickNumber([system.ram_free]);
    if (total > 0 && free >= 0) {
      const percent = percentOf(total - free, total);
      if (percent >= 0) {
        stats.ramPercent = percent;
        found = true;
      }
    }
    if (system.comfyui_version !== undefined && system.comfyui_version.trim().length > 0) {
      stats.version = system.comfyui_version.trim();
      found = true;
    }
  }
  if (stats.version.length === 0 &&
    body.comfyui_version !== undefined && body.comfyui_version.trim().length > 0) {
    stats.version = body.comfyui_version.trim();
    found = true;
  }
  const devices = body.devices;
  if (devices !== undefined && devices.length > 0) {
    const device = devices[0];
    let total = pickNumber([device.vram_total]);
    let free = pickNumber([device.vram_free]);
    if (total <= 0) {
      total = pickNumber([device.torch_vram_total]);
      free = pickNumber([device.torch_vram_free]);
    }
    if (total > 0 && free >= 0) {
      const percent = percentOf(total - free, total);
      if (percent >= 0) {
        stats.vramPercent = percent;
        found = true;
      }
    }
  }
  return found;
}

/**
 * `/crystools/monitor`：CPU / GPU / 温度。Crystools 不是 ComfyUI 的必备扩展，
 * 字段名也随版本变过，所以全部按"尽力认"处理，认不出来就留空。
 */
function parseMonitor(raw: string, stats: ServerStats): boolean {
  let body: MonitorBody;
  try {
    const root = JSON.parse(raw) as Object;
    if (Array.isArray(root)) {
      // 个别 Crystools 版本直接返回数组，取第一帧即可。
      const frames = root as MonitorBody[];
      if (frames.length === 0) {
        return false;
      }
      body = frames[0];
    } else {
      body = root as MonitorBody;
    }
  } catch (err) {
    return false;
  }
  const gpu: MonitorGpu | null = body.gpus !== undefined && body.gpus.length > 0 ? body.gpus[0] : null;
  let found = false;

  const cpu = pickNumber([body.cpu_utilization, body.cpu_utilization_percent, body.cpu_percent, body.cpu_usage]);
  if (cpu >= 0 && stats.cpuPercent < 0) {
    stats.cpuPercent = clampPercent(cpu);
    found = true;
  }

  if (stats.ramPercent < 0) {
    const ram = pickNumber([body.ram_used_percent, body.ram_percent, body.ram_usage]);
    if (ram >= 0) {
      stats.ramPercent = clampPercent(ram);
      found = true;
    }
  }

  if (stats.gpuPercent < 0) {
    let gpuValue = pickNumber([
      body.gpu_utilization, body.gpu_utilization_percent, body.gpu_percent, body.gpu_usage,
    ]);
    if (gpuValue < 0 && gpu !== null) {
      gpuValue = pickNumber([gpu.gpu_utilization, gpu.gpu_utilization_percent, gpu.gpu_percent, gpu.gpu_usage]);
    }
    if (gpuValue >= 0) {
      stats.gpuPercent = clampPercent(gpuValue);
      found = true;
    }
  }

  if (stats.vramPercent < 0) {
    let vram = pickNumber([body.vram_used_percent, body.vram_percent, body.vram_usage]);
    if (vram < 0 && gpu !== null) {
      vram = pickNumber([gpu.vram_used_percent, gpu.vram_percent, gpu.vram_usage]);
      if (vram < 0) {
        const total = pickNumber([gpu.vram_total]);
        const used = pickNumber([gpu.vram_used]);
        if (total > 0 && used >= 0) {
          vram = percentOf(used, total);
        } else {
          const free = pickNumber([gpu.vram_free]);
          if (total > 0 && free >= 0) {
            vram = percentOf(total - free, total);
          }
        }
      }
    }
    if (vram >= 0) {
      stats.vramPercent = clampPercent(vram);
      found = true;
    }
  }

  if (stats.temperature < 0) {
    let temp = pickNumber([body.temperature, body.gpu_temperature, body.temp]);
    if (temp < 0 && gpu !== null) {
      temp = pickNumber([gpu.temperature, gpu.gpu_temperature, gpu.temp]);
    }
    // 温度只接受物理上说得通的范围，避免把 `temperature: -1` 之类的哨兵值画出来。
    if (temp >= 0 && temp <= 300) {
      stats.temperature = temp;
      found = true;
    }
  }

  return found;
}

function hasAnyValue(stats: ServerStats): boolean {
  return stats.ramPercent >= 0 || stats.vramPercent >= 0 || stats.cpuPercent >= 0 ||
    stats.gpuPercent >= 0 || stats.temperature >= 0 || stats.version.length > 0;
}

/**
 * 取一次服务器性能快照。
 *
 * 两个接口都失败（离线、超时、404）时返回 null，页面据此整块不渲染；
 * 只有部分字段拿到时照常返回，缺的项由页面跳过。调用方复用同一个
 * `http.HttpRequest` 句柄，并在页面离开时 `destroy()`。
 */
export async function fetchServerStats(
  client: http.HttpRequest,
  baseUrl: string,
): Promise<ServerStats | null> {
  const base = trimBase(baseUrl);
  if (base.length === 0) {
    return null;
  }
  const stats = emptyServerStats();
  let any = false;

  const systemRaw = await getJson(client, `${base}/system_stats`);
  if (systemRaw.length > 0 && parseSystemStats(systemRaw, stats)) {
    any = true;
  }

  // Crystools 的路径在部分版本上带尾斜杠，先试不带、再试带。
  let monitorRaw = await getJson(client, `${base}/crystools/monitor`);
  if (monitorRaw.length === 0) {
    monitorRaw = await getJson(client, `${base}/crystools/monitor/`);
  }
  if (monitorRaw.length > 0 && parseMonitor(monitorRaw, stats)) {
    any = true;
  }

  if (!any || !hasAnyValue(stats)) {
    return null;
  }
  return stats;
}