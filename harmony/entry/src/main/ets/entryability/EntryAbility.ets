import { AbilityConstant, Configuration, ConfigurationConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';

const DOMAIN = 0x0000;

export default class EntryAbility extends UIAbility {
  private win: window.Window | undefined = undefined;
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    PersistentStorage.persistProp('appearanceMode', 'system');
    this.context.getApplicationContext().setColorMode(ConfigurationConstant.ColorMode.COLOR_MODE_NOT_SET);
    AppStorage.setOrCreate('systemDark',
      this.context.config.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK);
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onCreate');
  }

  /** 系统栏透明 + 图标颜色跟随深浅色；内容通过 expandSafeArea 画到栏下面。 */
  private applySystemBars(): void {
    if (this.win === undefined) {
      return;
    }
    const dark = AppStorage.get<boolean>('systemDark') !== false;
    const props: window.SystemBarProperties = {
      statusBarColor: '#00000000',
      navigationBarColor: '#00000000',
      statusBarContentColor: dark ? '#FFFFFF' : '#000000',
      navigationBarContentColor: dark ? '#FFFFFF' : '#000000',
    };
    this.win.setWindowSystemBarProperties(props);
  }

  /** 状态栏/导航条高度写进 AppStorage，页面据此留安全内边距。 */
  private publishInsets(win: window.Window): void {
    const top = win.getWindowAvoidArea(window.AvoidAreaType.TYPE_SYSTEM).topRect.height;
    const bottom = win.getWindowAvoidArea(window.AvoidAreaType.TYPE_NAVIGATION_INDICATOR).bottomRect.height;
    AppStorage.setOrCreate('statusBarPx', top);
    AppStorage.setOrCreate('navBarPx', bottom);
  }
  onConfigurationUpdate(newConfig: Configuration): void {
    // 系统深浅色变化时实时同步，页面里 「跟随系统」 会跟着重算。
    AppStorage.setOrCreate('systemDark',
      newConfig.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK);
    this.applySystemBars();
    if (this.win !== undefined) {
      this.publishInsets(this.win);
    }
  }

  onDestroy(): void {
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onDestroy');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    // Main window is created, set main page for this ability
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onWindowStageCreate');

    windowStage.getMainWindow().then((win: window.Window) => {
      this.win = win;
      win.setWindowLayoutFullScreen(true);
      this.applySystemBars();
      this.publishInsets(win);
    });

    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        hilog.error(DOMAIN, 'testTag', 'Failed to load the content. Cause: %{public}s', JSON.stringify(err));
        return;
      }
      hilog.info(DOMAIN, 'testTag', 'Succeeded in loading the content.');
    });
  }

  onWindowStageDestroy(): void {
    // Main window is destroyed, release UI related resources
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onWindowStageDestroy');
  }

  onForeground(): void {
    // Ability has brought to foreground
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onForeground');
  }

  onBackground(): void {
    // Ability has back to background
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onBackground');
  }
}
