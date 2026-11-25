import { updateManager } from '@kit.AppGalleryKit';
import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';

export enum AppUpdateOutcome {
  NO_UPDATE = 0,
  DIALOG_SHOWN = 1,
  FAILED = 2,
}

/**
 * 鸿蒙应用市场官方更新能力。
 *
 * 必须先从 checkAppUpdate 拿到「有新版本」，才能调用 showUpdateDialog；
 * 弹窗、稍后提醒和版本记忆都由应用市场维护，应用内不保存版本状态。
 */
export class AppUpdateService {
  async checkAndShow(context: common.UIAbilityContext): Promise<AppUpdateOutcome> {
    let stage: string = 'checkAppUpdate';
    try {
      const check = await updateManager.checkAppUpdate(context);
      if (check.updateAvailable !== updateManager.UpdateAvailableCode.LATER_VERSION_EXIST) {
        return AppUpdateOutcome.NO_UPDATE;
      }
      stage = 'showUpdateDialog';
      const shown = await updateManager.showUpdateDialog(context);
      return shown === updateManager.ShowUpdateResultCode.SHOW_DIALOG_SUCCESS
        ? AppUpdateOutcome.DIALOG_SHOWN
        : AppUpdateOutcome.FAILED;
    } catch (err) {
      const error = err as BusinessError;
      console.error(`[ComfyPilot] ${stage} failed: ${error.code} ${error.message}`);
      return AppUpdateOutcome.FAILED;
    }
  }
}
