import { asset } from '@kit.AssetStoreKit';
import { util } from '@kit.ArkTS';

const ALIAS: string = 'comfypilot.ai.key';

function toBytes(text: string): Uint8Array {
  return new util.TextEncoder().encodeInto(text);
}

/** API Key 是 ASCII，直接按字节还原，避免依赖 TextDecoder 的版本差异。 */
function fromBytes(data: Uint8Array): string {
  let result = '';
  for (let i = 0; i < data.length; i++) {
    result += String.fromCharCode(data[i]);
  }
  return result;
}

/**
 * 用系统 Asset Store 存 API Key：不下发明文到 preferences，
 * 仅在请求头里使用。
 */
export class AiSecretStore {
  load(): string {
    try {
      const query: asset.AssetMap = new Map<asset.Tag, asset.Value>();
      query.set(asset.Tag.ALIAS, toBytes(ALIAS));
      query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
      const found: asset.AssetMap[] = asset.querySync(query);
      if (found.length === 0) {
        return '';
      }
      const secret = found[0].get(asset.Tag.SECRET);
      return secret instanceof Uint8Array ? fromBytes(secret) : '';
    } catch (err) {
      return '';
    }
  }

  save(key: string): void {
    this.clear();
    if (key.length === 0) {
      return;
    }
    const attributes: asset.AssetMap = new Map<asset.Tag, asset.Value>();
    attributes.set(asset.Tag.SECRET, toBytes(key));
    attributes.set(asset.Tag.ALIAS, toBytes(ALIAS));
    attributes.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_FIRST_UNLOCKED);
    asset.addSync(attributes);
  }

  clear(): void {
    try {
      const query: asset.AssetMap = new Map<asset.Tag, asset.Value>();
      query.set(asset.Tag.ALIAS, toBytes(ALIAS));
      asset.removeSync(query);
    } catch (err) {
      // 不存在时忽略
    }
  }
}
