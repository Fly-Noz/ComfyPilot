"""Generate the ComfyPilot app icon set.

Geometry is fixed by docs/design/app-icon/README.md:
- 1024 canvas, 22.5% rounded corners
- ring centre radius 27%, stroke 15.5%
- gap 45deg..315deg (90deg on the right)
- gradient #5EEAD4 -> #8B5CF6 -> #F472B6 along the arc
- background #0A0B10

Run with the bundled Python:
    python docs/design/app-icon/generate.py
"""

from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent
BG = (10, 11, 16)
STOPS = (
    (0.0, (94, 234, 212)),   # #5EEAD4
    (0.5, (139, 92, 246)),   # #8B5CF6
    (1.0, (244, 114, 182)),  # #F472B6
)
START_DEG = 45.0
END_DEG = 315.0


def _gradient(t: np.ndarray) -> np.ndarray:
    """t in [0,1] -> RGB float array (H,W,3)."""
    channels = [
        np.interp(t, [s[0] for s in STOPS], [s[1][i] for s in STOPS])
        for i in range(3)
    ]
    return np.stack(channels, axis=-1).astype(np.float32)


def ring_rgba(px: int, scale: float = 1.0, white: bool = False) -> Image.Image:
    """Render a smooth anti-aliased ring at px x px (px is the supersampled size)."""
    coords = np.arange(px, dtype=np.float32) + 0.5
    x, y = np.meshgrid(coords, coords)
    cx = cy = px / 2.0
    radius = px * 0.27 * scale
    width = px * 0.155 * scale
    half = width / 2.0

    dx = x - cx
    dy = y - cy
    dist = np.sqrt(dx * dx + dy * dy)
    angle = (np.degrees(np.arctan2(dy, dx)) % 360.0).astype(np.float32)

    arc = (dist >= radius - half) & (dist <= radius + half)
    arc &= (angle >= START_DEG) & (angle <= END_DEG)

    def cap(deg: float) -> np.ndarray:
        rad = math.radians(deg)
        ex = cx + radius * math.cos(rad)
        ey = cy + radius * math.sin(rad)
        return (x - ex) ** 2 + (y - ey) ** 2 <= half * half

    cap_start = cap(START_DEG)
    cap_end = cap(END_DEG)
    mask = arc | cap_start | cap_end

    if white:
        rgb = np.full((px, px, 3), 255.0, dtype=np.float32)
    else:
        # Clamping the angle outside the arc makes the round caps take the
        # endpoint colour; pixels still inside the arc keep their gradient.
        t = np.clip((angle - START_DEG) / (END_DEG - START_DEG), 0.0, 1.0)
        rgb = _gradient(t)

    alpha = mask.astype(np.uint8) * 255
    rgba = np.dstack([rgb, alpha]).astype(np.uint8)
    return Image.fromarray(rgba, "RGBA")


def ring_image(size: int, scale: float = 1.0, ss: int = 4, white: bool = False) -> Image.Image:
    high = ring_rgba(size * ss, scale=scale, white=white)
    return high.resize((size, size), Image.LANCZOS)


def rounded_master(size: int = 1024, ss: int = 2) -> Image.Image:
    px = size * ss
    canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle(
        [0, 0, px - 1, px - 1],
        radius=int(px * 0.225),
        fill=BG + (255,),
    )
    canvas.alpha_composite(ring_rgba(px, scale=1.0))
    return canvas.resize((size, size), Image.LANCZOS)


def solid(size: int) -> Image.Image:
    return Image.new("RGBA", (size, size), BG + (255,))


def size_sheet(master: Image.Image) -> Image.Image:
    sizes = (160, 96, 72, 48, 32, 24)
    padding = 26
    gap = 24
    width = padding * 2 + sum(sizes) + gap * (len(sizes) - 1)
    height = 224
    sheet = Image.new("RGB", (width, height), (20, 21, 28))
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.load_default(size=14)
    except TypeError:
        font = ImageFont.load_default()

    x = padding
    for px in sizes:
        icon = master.resize((px, px), Image.LANCZOS)
        y = 178 - px
        sheet.paste(icon, (x, y), icon)
        label = str(px)
        box = draw.textbbox((0, 0), label, font=font)
        draw.text(
            (x + (px - (box[2] - box[0])) / 2, 186),
            label,
            fill=(180, 184, 200),
            font=font,
        )
        x += px + gap
    return sheet


def main() -> None:
    master = rounded_master()
    master.save(OUT / "master_1024.png")

    # Android adaptive icon: foreground content stays inside the 66dp safe area.
    # The full ring is 69.5% of the canvas; 0.86 keeps it within 66/108.
    android_scale = 0.86
    ring_image(432, scale=android_scale, ss=4).save(OUT / "android_foreground_432.png")
    solid(432).save(OUT / "android_background_432.png")
    ring_image(432, scale=android_scale, ss=4, white=True).save(OUT / "android_monochrome_432.png")

    # HarmonyOS layered icon: foreground/background are both 1024.
    ring_image(1024, scale=1.0, ss=2).save(OUT / "harmony_foreground_1024.png")
    solid(1024).save(OUT / "harmony_background_1024.png")

    size_sheet(master).save(OUT / "sizes.png")
    print("wrote", sorted(p.name for p in OUT.glob("*.png")))


if __name__ == "__main__":
    main()
