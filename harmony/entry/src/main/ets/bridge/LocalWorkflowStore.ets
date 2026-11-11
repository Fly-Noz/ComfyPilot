import { common } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';

export class LocalWorkflowItem {
  path: string = '';
  name: string = '';
  importedAtMs: number = 0;
}

/**
 * 本地导入的工作流：原文只存 App 沙盒，不写 ComfyUI 主机。
 * index.json 只记展示信息；路径用 `local://<id>`。
 */
export class LocalWorkflowStore {
  private dir: string = '';
  private indexFile: string = '';

  async open(context: common.Context): Promise<void> {
    this.dir = `${context.filesDir}/local_workflows`;
    this.indexFile = `${this.dir}/index.json`;
    if (!this.exists(this.dir)) {
      fileIo.mkdirSync(this.dir, true);
    }
  }

  async list(): Promise<LocalWorkflowItem[]> {
    if (this.indexFile.length === 0 || !this.exists(this.indexFile)) {
      return [];
    }
    try {
      const text = await this.readText(this.indexFile);
      const items = JSON.parse(text) as LocalWorkflowItem[];
      if (!Array.isArray(items)) {
        return [];
      }
      return items
        .filter((item: LocalWorkflowItem) => {
          return item.path.length > 0 &&
            item.name.length > 0 &&
            this.exists(this.fileFor(item.path));
        })
        .sort((a: LocalWorkflowItem, b: LocalWorkflowItem) => b.importedAtMs - a.importedAtMs);
    } catch (err) {
      return [];
    }
  }

  async save(name: string, raw: string): Promise<LocalWorkflowItem> {
    if (!this.exists(this.dir)) {
      fileIo.mkdirSync(this.dir, true);
    }
    const id = `${Date.now()}-${Math.floor(Math.random() * 1000000)}`;
    const item = new LocalWorkflowItem();
    item.path = `local://${id}`;
    item.name = name.length > 0 ? name : '本地工作流';
    item.importedAtMs = Date.now();
    await this.writeText(this.fileFor(item.path), raw);
    const items = await this.list();
    items.unshift(item);
    await this.writeIndex(items);
    return item;
  }

  async read(path: string): Promise<string | null> {
    const file = this.fileFor(path);
    if (!this.exists(file)) {
      return null;
    }
    try {
      return await this.readText(file);
    } catch (err) {
      return null;
    }
  }

  async delete(path: string): Promise<void> {
    const file = this.fileFor(path);
    try {
      if (this.exists(file)) {
        await fileIo.unlink(file);
      }
    } catch (err) {
      // 文件已不存在也不影响索引清理。
    }
    const items = (await this.list()).filter((item: LocalWorkflowItem) => item.path !== path);
    await this.writeIndex(items);
  }

  private fileFor(path: string): string {
    const id = path.substring(path.lastIndexOf('/') + 1);
    return `${this.dir}/${id}.json`;
  }

  private async writeIndex(items: LocalWorkflowItem[]): Promise<void> {
    if (!this.exists(this.dir)) {
      fileIo.mkdirSync(this.dir, true);
    }
    await this.writeText(this.indexFile, JSON.stringify(items));
  }

  private exists(path: string): boolean {
    try {
      return fileIo.accessSync(path);
    } catch (err) {
      return false;
    }
  }

  private async writeText(path: string, text: string): Promise<void> {
    const bytes = new util.TextEncoder().encodeInto(text);
    const file = await fileIo.open(
      path,
      fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC,
    );
    try {
      await fileIo.write(file.fd, bytes.buffer);
    } finally {
      fileIo.closeSync(file);
    }
  }

  private async readText(path: string): Promise<string> {
    const stat = await fileIo.stat(path);
    if (stat.size <= 0) {
      return '';
    }
    const buffer = new ArrayBuffer(stat.size);
    const file = await fileIo.open(path, fileIo.OpenMode.READ_ONLY);
    try {
      await fileIo.read(file.fd, buffer);
    } finally {
      fileIo.closeSync(file);
    }
    const decoder = util.TextDecoder.create('utf-8', { ignoreBOM: true });
    return decoder.decodeWithStream(new Uint8Array(buffer), { stream: false });
  }
}
