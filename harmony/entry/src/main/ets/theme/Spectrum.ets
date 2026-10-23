/**
 * 光谱（Spectrum）主题的鸿蒙侧实现。
 *
 * token 与 Android `Spectrum.kt` 的 DarkSpectrum / LightSpectrum 一一对应，
 * 页面通过 `Spectrum.palette(isDark)` 取当前调色板，不写死颜色。
 */
export interface Palette {
  isDark: boolean;
  background: string;
  card: string;
  cardStrong: string;
  dialog: string;
  border: string;
  borderStrong: string;
  text: string;
  textMuted: string;
  textDim: string;
  accent: string;
  accentInk: string;
  accentSoft: string;
  accentBorder: string;
  ok: string;
  okSoft: string;
  warn: string;
  warnSoft: string;
  err: string;
  errSoft: string;
  gradientStart: string;
  gradientMid: string;
  gradientEnd: string;
  glowPurple: string;
  glowTeal: string;
  glowPink: string;
}

class DarkPalette implements Palette {
  isDark: boolean = true;
  background: string = '#0A0B10';
  card: string = 'rgba(255,255,255,0.055)';
  cardStrong: string = 'rgba(255,255,255,0.094)';
  dialog: string = '#14151B';
  border: string = 'rgba(255,255,255,0.09)';
  borderStrong: string = 'rgba(255,255,255,0.16)';
  text: string = '#F3F4F8';
  textMuted: string = '#A9AEBE';
  textDim: string = '#7C8196';
  accent: string = '#A78BFA';
  accentInk: string = '#0B0614';
  accentSoft: string = 'rgba(139,92,246,0.16)';
  accentBorder: string = 'rgba(139,92,246,0.44)';
  ok: string = '#5EEAD4';
  okSoft: string = 'rgba(94,234,212,0.10)';
  warn: string = '#FBBF24';
  warnSoft: string = 'rgba(251,191,36,0.10)';
  err: string = '#FB7185';
  errSoft: string = 'rgba(251,113,133,0.12)';
  gradientStart: string = '#5EEAD4';
  gradientMid: string = '#8B5CF6';
  gradientEnd: string = '#F472B6';
  glowPurple: string = '#8B5CF6';
  glowTeal: string = '#5EEAD4';
  glowPink: string = '#F472B6';
}

class LightPalette implements Palette {
  isDark: boolean = false;
  background: string = '#F5F4F8';
  card: string = 'rgba(255,255,255,0.65)';
  cardStrong: string = 'rgba(255,255,255,0.85)';
  dialog: string = '#FFFFFF';
  border: string = 'rgba(20,21,26,0.09)';
  borderStrong: string = 'rgba(20,21,26,0.16)';
  text: string = '#14151A';
  textMuted: string = '#545666';
  textDim: string = '#6F7182';
  accent: string = '#7C3AED';
  accentInk: string = '#FFFFFF';
  accentSoft: string = 'rgba(124,58,237,0.10)';
  accentBorder: string = 'rgba(124,58,237,0.30)';
  ok: string = '#0B7F6A';
  okSoft: string = 'rgba(11,127,106,0.08)';
  warn: string = '#A16207';
  warnSoft: string = 'rgba(161,98,7,0.08)';
  err: string = '#BE123C';
  errSoft: string = 'rgba(190,18,60,0.08)';
  gradientStart: string = '#2DD4BF';
  gradientMid: string = '#7C3AED';
  gradientEnd: string = '#DB2777';
  glowPurple: string = '#8B5CF6';
  glowTeal: string = '#2DD4BF';
  glowPink: string = '#F472B6';
}

const DARK: Palette = new DarkPalette();
const LIGHT: Palette = new LightPalette();

export class Spectrum {
  static palette(isDark: boolean): Palette {
    return isDark ? DARK : LIGHT;
  }

  /** 主按钮/进度条用的渐变 stops。 */
  static gradientColors(c: Palette): Array<[string, number]> {
    const stops: Array<[string, number]> = [
      [c.gradientStart, 0.0],
      [c.gradientMid, 0.55],
      [c.gradientEnd, 1.0],
    ];
    return stops;
  }

  static radiusCard: number = 16;
  static radiusPill: number = 999;
}