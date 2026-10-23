/**
 * 光谱（Spectrum）设计语言的鸿蒙侧 token。
 *
 * 与 Android `app/src/main/kotlin/com/comfypilot/app/ui/theme/Spectrum.kt` 的
 * DarkSpectrum 一一对应：深色底 + 玻璃卡面 + 青绿→紫→粉渐变。
 * 页面只引用这里的常量，不写死颜色，改主题只改这一个文件。
 */
export class Spectrum {
  // 背景与卡面
  static readonly background: string = '#0A0B10';
  static readonly card: string = 'rgba(255,255,255,0.055)';
  static readonly cardStrong: string = 'rgba(255,255,255,0.094)';
  static readonly dialog: string = '#14151B';
  static readonly border: string = 'rgba(255,255,255,0.09)';
  static readonly borderStrong: string = 'rgba(255,255,255,0.16)';

  // 文字
  static readonly text: string = '#F3F4F8';
  static readonly textMuted: string = '#A9AEBE';
  static readonly textDim: string = '#7C8196';

  // 强调色
  static readonly accent: string = '#A78BFA';
  static readonly accentInk: string = '#0B0614';
  static readonly accentSoft: string = 'rgba(139,92,246,0.16)';
  static readonly accentBorder: string = 'rgba(139,92,246,0.44)';

  // 状态色
  static readonly ok: string = '#5EEAD4';
  static readonly okSoft: string = 'rgba(94,234,212,0.10)';
  static readonly warn: string = '#FBBF24';
  static readonly warnSoft: string = 'rgba(251,191,36,0.10)';
  static readonly err: string = '#FB7185';
  static readonly errSoft: string = 'rgba(251,113,133,0.12)';

  // 强调渐变：青绿 → 紫 → 粉
  static readonly gradientStart: string = '#5EEAD4';
  static readonly gradientMid: string = '#8B5CF6';
  static readonly gradientEnd: string = '#F472B6';

  /** 主按钮/进度条用的渐变 stops。 */
  static gradientColors(): Array<[string, number]> {
    const stops: Array<[string, number]> = [
      [Spectrum.gradientStart, 0.0],
      [Spectrum.gradientMid, 0.55],
      [Spectrum.gradientEnd, 1.0],
    ];
    return stops;
  }

  /** 卡面通用描边。 */
  static cardBorderWidth: number = 1;
  static radiusCard: number = 16;
  static radiusPill: number = 999;
}