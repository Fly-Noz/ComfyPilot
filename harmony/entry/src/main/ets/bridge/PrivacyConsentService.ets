import { privacyManager } from '@kit.AppGalleryKit';
import { common } from '@kit.AbilityKit';

export enum PrivacyConsentStatus {
  UNSUPPORTED = 0,
  AGREED = 1,
  DISAGREED = 2,
  NEED_RESIGN = 3,
}

/**
 * HarmonyOS 应用市场隐私协议托管。
 *
 * 首次启动的标准化弹窗由应用市场负责；这里只查询签署状态，并在协议版本
 * 变更需要重新签署时调用官方 requestAppPrivacyConsent，不使用自绘弹窗。
 */
export class PrivacyConsentService {
  private supported(): boolean {
    try {
      const info = privacyManager.getAppPrivacyMgmtInfo();
      return info.type !== privacyManager.AppPrivacyMgmtType.UNSUPPORTED;
    } catch (err) {
      return false;
    }
  }

  private statusFromResults(results: privacyManager.AppPrivacyResult[]): PrivacyConsentStatus {
    let sawResult = false;
    let allAgreed = true;
    seedLoop:
    for (let i = 0; i < results.length; i++) {
      const item = results[i];
      if (item.type !== privacyManager.AppPrivacyType.PRIVACY_AGREEMENT &&
        item.type !== privacyManager.AppPrivacyType.USER_AGREEMENT) {
        continue;
      }
      sawResult = true;
      if (item.result === privacyManager.AppPrivacyResultType.REQUIRE_RESIGNING_VERSION_UPDATE) {
        return PrivacyConsentStatus.NEED_RESIGN;
      }
      if (item.result !== privacyManager.AppPrivacyResultType.FULL_MODE_AGREED) {
        allAgreed = false;
      }
    }
    if (!sawResult) {
      return PrivacyConsentStatus.DISAGREED;
    }
    return allAgreed ? PrivacyConsentStatus.AGREED : PrivacyConsentStatus.DISAGREED;
  }

  /** 查询当前签署状态；服务不可用或调用失败时按不支持处理，不阻塞使用。 */
  status(): PrivacyConsentStatus {
    if (!this.supported()) {
      return PrivacyConsentStatus.UNSUPPORTED;
    }
    try {
      return this.statusFromResults(privacyManager.getAppPrivacyResult());
    } catch (err) {
      return PrivacyConsentStatus.UNSUPPORTED;
    }
  }

  /** 主动再次拉起官方隐私弹窗（例如协议版本升级需要重新签署）。 */
  async request(context: common.UIAbilityContext): Promise<PrivacyConsentStatus> {
    if (!this.supported()) {
      return PrivacyConsentStatus.UNSUPPORTED;
    }
    try {
      const result = await privacyManager.requestAppPrivacyConsent(context);
      return this.statusFromResults(result.results);
    } catch (err) {
      return PrivacyConsentStatus.UNSUPPORTED;
    }
  }
}
