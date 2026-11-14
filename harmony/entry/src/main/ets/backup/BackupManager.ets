import { common } from '@kit.AbilityKit';
import { fileIo, picker } from '@kit.CoreFileKit';
import { preferences } from '@kit.ArkData';
import { util } from '@kit.ArkTS';
import { AddressEntry, AddressStore } from '../bridge/AddressStore';
import { AppearanceStore } from '../bridge/AppearanceStore';
import { LocalWorkflowItem, LocalWorkflowStore } from '../bridge/LocalWorkflowStore';
import { PinnedWorkflowStore } from '../bridge/PinnedWorkflowStore';
import { SubmitPrefs } from '../bridge/SubmitPrefs';
import { TaskStorage } from '../bridge/TaskStorage';
import { UpdatePrefs } from '../bridge/UpdatePrefs';
import { WeakNetworkPrefs } from '../bridge/WeakNetworkPrefs';

const FORMAT: string = 'comfypilot-backup';
const VERSION: number = 1;
const MAX_BACKUP_BYTES: number = 30 * 1024 * 1024;

export interface BackupAddress {
  url: string;
  usedAtMs: number;
}

export interface BackupAddresses {
  last: string;
  history: BackupAddress[];
}

export interface BackupLocalWorkflow {
  path: string;
  name: string;
  importedAtMs: number;
  raw: string;
}

export interface BackupSettings {
  theme: string;
  forceSubmit: boolean;
  weakNetwork: boolean;
  updateMode: string;
  dismissedVersion: number;
}

export interface BackupDocument {
  format: string;
  version: number;
  exportedAtMs: number;
  platform: string;
  addresses: BackupAddresses;
  workflowDrafts: Record<string, string>;
  nodeNotes: Record<string, string>;
  pinnedNodes: Record<string, string>;
  pinnedWorkflows: string[];
  seedEvery: string;
  seedLast: string;
  tasks: Object[];
  localWorkflows: BackupLocalWorkflow[];
  settings: BackupSettings;
}

export interface BackupSaveResult {
  ok: boolean;
  cancelled?: boolean;
  message: string;
}

export interface BackupRestoreSummary {
  theme: string;
  forceSubmit: boolean;
  weakNetwork: boolean;
  updateMode: string;
  localCount: number;
}

/**
 * 本地备份：Android 与鸿蒙共用同一份 JSON 结构。
 *
 * 纯本机记录；不包含 AI API Key、clientId、诊断日志。
 */
export class BackupManager {

  async export(context: common.Context): Promise<BackupSaveResult> {
    try {
      const doc = await this.build(context);
      const name = `ComfyPilot-backup-${this.stamp()}.json`;
      const text = JSON.stringify(doc, null, 2);
      if (new util.TextEncoder().encodeInto(text).byteLength > MAX_BACKUP_BYTES) {
        return { ok: false, message: '备份超过 30 MB，请先删除部分本地工作流或任务历史' };
      }
      const options = new picker.DocumentSaveOptions();
      options.newFileNames = [name];
      options.fileSuffixChoices = ['.json'];
      const viewPicker = new picker.DocumentViewPicker(context);
      const uris = await viewPicker.save(options);
      if (uris.length === 0 || uris[0].length === 0) {
        return { ok: false, cancelled: true, message: '已取消' };
      }
      const uri = uris[0];
      const bytes = new util.TextEncoder().encodeInto(text);
      const file = await fileIo.open(
        uri,
        fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC,
      );
      try {
        await fileIo.write(file.fd, bytes.buffer);
      } finally {
        fileIo.closeSync(file);
      }
      return { ok: true, message: `已导出：${name}` };
    } catch (err) {
      return { ok: false, message: this.errorText(err) };
    }
  }

  async import(context: common.Context): Promise<BackupRestoreSummary> {
    const options = new picker.DocumentSelectOptions();
    options.maxSelectNumber = 1;
    options.fileSuffixFilters = ['.json'];
    const viewPicker = new picker.DocumentViewPicker(context);
    const uris = await viewPicker.select(options);
    if (uris.length === 0 || uris[0].length === 0) {
      throw new Error('已取消选择');
    }
    const text = await this.readText(uris[0]);
    if (text.length > MAX_BACKUP_BYTES) {
      throw new Error('备份文件过大，最多 30 MB');
    }
    const doc = JSON.parse(text) as BackupDocument;
    if (doc.format !== FORMAT) {
      throw new Error('不是 ComfyPilot 本地备份');
    }
    if (doc.version !== VERSION) {
      throw new Error('备份版本不支持');
    }
    return await this.restore(context, doc);
  }

  private async build(context: common.Context): Promise<BackupDocument> {
    const addressStore = new AddressStore();
    await addressStore.load(context);
    const addressHistory: BackupAddress[] = addressStore.history().map((entry: AddressEntry): BackupAddress => {
      const item: BackupAddress = { url: entry.url, usedAtMs: entry.usedAt };
      return item;
    });

    const pinnedStore = new PinnedWorkflowStore();
    await pinnedStore.open(context);

    const taskStorage = new TaskStorage();
    await taskStorage.open(context);
    const taskRows = await taskStorage.load();

    const localStore = new LocalWorkflowStore();
    await localStore.open(context);
    const localItems = await localStore.list();
    const localWorkflows: BackupLocalWorkflow[] = [];
    for (let i = 0; i < localItems.length; i++) {
      const item = localItems[i];
      const raw = await localStore.read(item.path);
      if (raw === null) {
        continue;
      }
      localWorkflows.push({
        path: item.path,
        name: item.name,
        importedAtMs: item.importedAtMs,
        raw: raw,
      });
    }

    const appearance = await this.openStore(context, 'appearance');
    const submit = await this.openStore(context, 'submit');
    const weak = await this.openStore(context, 'weak_network');
    const update = await this.openStore(context, 'comfypilot_update');

    return {
      format: FORMAT,
      version: VERSION,
      exportedAtMs: Date.now(),
      platform: 'harmony',
      addresses: {
        last: addressStore.lastUrl(),
        history: addressHistory,
      },
      workflowDrafts: await this.readMap(context, 'workflow_drafts'),
      nodeNotes: await this.readMap(context, 'node_notes'),
      pinnedNodes: await this.readMap(context, 'pinned_nodes'),
      pinnedWorkflows: pinnedStore.load(),
      seedEvery: await this.readRaw(context, 'seed_mode', 'every', '{}'),
      seedLast: await this.readRaw(context, 'seed_mode', 'last', '{}'),
      tasks: taskRows.map((row) => row.payload),
      localWorkflows: localWorkflows,
      settings: {
        theme: appearance.getSync('mode', 'system') as string,
        forceSubmit: submit.getSync('force', false) as boolean,
        weakNetwork: weak.getSync('enabled', false) as boolean,
        updateMode: update.getSync('prompt_mode', 'always') as string,
        dismissedVersion: update.getSync('dismissed_version', 0) as number,
      },
    };
  }

  private async restore(context: common.Context, doc: BackupDocument): Promise<BackupRestoreSummary> {
    await this.replaceMap(context, 'workflow_drafts', doc.workflowDrafts);
    await this.replaceMap(context, 'node_notes', doc.nodeNotes);
    await this.replaceMap(context, 'pinned_nodes', doc.pinnedNodes);

    const seed = await this.openStore(context, 'seed_mode');
    await seed.clear();
    await seed.put('every', doc.seedEvery.length > 0 ? doc.seedEvery : '{}');
    await seed.put('last', doc.seedLast.length > 0 ? doc.seedLast : '{}');
    await seed.flush();

    const pinnedStore = new PinnedWorkflowStore();
    await pinnedStore.open(context);
    await pinnedStore.save(doc.pinnedWorkflows);

    const addressStore = new AddressStore();
    await addressStore.load(context);
    const entries: AddressEntry[] = doc.addresses.history.map((entry: BackupAddress): AddressEntry => {
      const item: AddressEntry = { url: entry.url, usedAt: entry.usedAtMs };
      return item;
    });
    await addressStore.replace(doc.addresses.last, entries);

    const localStore = new LocalWorkflowStore();
    await localStore.open(context);
    const items: LocalWorkflowItem[] = [];
    const raws: Map<string, string> = new Map();
    doc.localWorkflows.forEach((entry: BackupLocalWorkflow) => {
      const item = new LocalWorkflowItem();
      item.path = entry.path;
      item.name = entry.name;
      item.importedAtMs = entry.importedAtMs;
      items.push(item);
      raws.set(entry.path, entry.raw);
    });
    await localStore.replaceAll(items, raws);

    const taskStorage = new TaskStorage();
    await taskStorage.open(context);
    const payloads: string[] = [];
    doc.tasks.forEach((entry: Object) => {
      if (typeof entry === 'string') {
        payloads.push(entry as string);
      } else {
        payloads.push(JSON.stringify(entry));
      }
    });
    await taskStorage.replaceAll(payloads);

    await new AppearanceStore().save(context, doc.settings.theme);
    await new SubmitPrefs().save(context, doc.settings.forceSubmit);
    await new WeakNetworkPrefs().save(context, doc.settings.weakNetwork);
    const updatePrefs = new UpdatePrefs();
    await updatePrefs.saveMode(context, doc.settings.updateMode === 'once' ? 'once' : 'always');
    if (doc.settings.dismissedVersion > 0) {
      await updatePrefs.markDismissed(context, doc.settings.dismissedVersion);
    } else {
      await updatePrefs.clearDismissed(context);
    }

    return {
      theme: doc.settings.theme,
      forceSubmit: doc.settings.forceSubmit,
      weakNetwork: doc.settings.weakNetwork,
      updateMode: doc.settings.updateMode,
      localCount: items.length,
    };
  }

  private async readMap(context: common.Context, name: string): Promise<Record<string, string>> {
    const result: Record<string, string> = {};
    try {
      const store = await this.openStore(context, name);
      const all = store.getAllSync() as Record<string, string>;
      Object.keys(all).forEach((key: string) => {
        const value = all[key];
        if (typeof value === 'string') {
          result[key] = value;
        }
      });
    } catch (err) {
      // 空 store 或打不开时按空备份处理。
    }
    return result;
  }

  private async replaceMap(context: common.Context, name: string, values: Record<string, string>): Promise<void> {
    const store = await this.openStore(context, name);
    await store.clear();
    const keys = Object.keys(values);
    for (let i = 0; i < keys.length; i++) {
      await store.put(keys[i], values[keys[i]]);
    }
    await store.flush();
  }

  private async readRaw(context: common.Context, name: string, key: string, fallback: string): Promise<string> {
    try {
      const store = await this.openStore(context, name);
      const value = store.getSync(key, fallback);
      return typeof value === 'string' ? value : fallback;
    } catch (err) {
      return fallback;
    }
  }

  private async openStore(context: common.Context, name: string): Promise<preferences.Preferences> {
    return await preferences.getPreferences(context, name);
  }

  private async readText(uri: string): Promise<string> {
    const stat = await fileIo.stat(uri);
    if (stat.size > MAX_BACKUP_BYTES) {
      throw new Error('备份文件过大，最多 30 MB');
    }
    const buffer = new ArrayBuffer(stat.size);
    const file = await fileIo.open(uri, fileIo.OpenMode.READ_ONLY);
    try {
      await fileIo.read(file.fd, buffer);
    } finally {
      fileIo.closeSync(file);
    }
    const decoder = util.TextDecoder.create('utf-8', { ignoreBOM: true });
    return decoder.decodeWithStream(new Uint8Array(buffer), { stream: false });
  }

  private stamp(): string {
    const d = new Date();
    const pad = (v: number): string => v < 10 ? `0${v}` : `${v}`;
    return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
  }

  private errorText(err: Object): string {
    if (err instanceof Error && err.message.length > 0) {
      return err.message;
    }
    return `${err}`;
  }
}
