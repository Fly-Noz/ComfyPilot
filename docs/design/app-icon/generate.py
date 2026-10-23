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


def background_rgb(px: int) -> np.ndarray:
    """深色底 + 极淡的极光与节点网格；保持小尺寸下只剩柔光。"""
    yy, xx = np.mgrid[0:px, 0:px].astype(np.float32)
    cx = cy = px / 2.0
    dx = (xx - cx) / (px / 2.0)
    dy = (yy - cy) / (px / 2.0)
    radius = np.sqrt(dx * dx + dy * dy)

    image = np.empty((px, px, 3), dtype=np.float32)
    image[:] = np.array(BG, dtype=np.float32)

    # 中心轻微提亮，避免纯黑底显得像被挖空。
    lift = np.clip(1.0 - radius, 0.0, 1.0) ** 2
    image += lift[..., None] * np.array([5.0, 6.0, 10.0], dtype=np.float32)

    glows = (
        (0.72, 0.24, 0.40, (244, 114, 182), 0.13),
        (0.27, 0.50, 0.48, (139, 92, 246), 0.12),
        (0.66, 0.80, 0.38, (94, 234, 212), 0.12),
    )
    for gx, gy, spread, color, strength in glows:
        distance = np.sqrt((dx - (gx * 2 - 1)) ** 2 + (dy - (gy * 2 - 1)) ** 2)
        weight = np.exp(-(distance / spread) ** 2 * 2.2)
        alpha = (weight * strength)[..., None]
        image = image * (1.0 - alpha) + np.array(color, dtype=np.float32) * alpha

    # 节点网格：线 3.5% 透明度，节点 15%；缩到 24px 时只剩一点纹理。
    step = px / 16.0
    grid_x = np.abs((xx % step) - step / 2.0)
    grid_y = np.abs((yy % step) - step / 2.0)
    lines = (grid_x < px * 0.00055) | (grid_y < px * 0.00055)
    nodes = (grid_x * grid_x + grid_y * grid_y) < (px * 0.0019) ** 2
    image[lines] = image[lines] * 0.965 + np.array([150.0, 160.0, 210.0]) * 0.035
    image[nodes] = image[nodes] * 0.85 + np.array([200.0, 210.0, 255.0]) * 0.15

    # 边缘压暗，保证圆角/裁切后仍有层次。
    edge = np.clip((radius - 0.62) / 0.55, 0.0, 1.0)
    image *= (1.0 - 0.38 * edge[..., None])
    return np.clip(image, 0.0, 255.0).astype(np.uint8)


def background_image(size: int, rounded: bool = False) -> Image.Image:
    px = size * 2
    image = Image.fromarray(background_rgb(px), "RGB").convert("RGBA")
    if rounded:
        mask = Image.new("L", (px, px), 0)
        ImageDraw.Draw(mask).rounded_rectangle(
            [0, 0, px - 1, px - 1], radius=int(px * 0.225), fill=255
        )
        image.putalpha(mask)
    return image.resize((size, size), Image.LANCZOS)


def rounded_master(size: int = 1024, ss: int = 2) -> Image.Image:
    px = size * ss
    canvas = Image.fromarray(background_rgb(px), "RGB").convert("RGBA")
    mask = Image.new("L", (px, px), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, px - 1, px - 1], radius=int(px * 0.225), fill=255
    )
    canvas.putalpha(mask)
    canvas.alpha_composite(ring_rgba(px, scale=1.0))
    return canvas.resize((size, size), Image.LANCZOS)


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
    background_image(432).save(OUT / "android_background_432.png")
    ring_image(432, scale=android_scale, ss=4, white=True).save(OUT / "android_monochrome_432.png")

    # HarmonyOS layered icon: foreground/background are both 1024.
    ring_image(1024, scale=1.0, ss=2).save(OUT / "harmony_foreground_1024.png")
    background_image(1024).save(OUT / "harmony_background_1024.png")

    size_sheet(master).save(OUT / "sizes.png")
    print("wrote", sorted(p.name for p in OUT.glob("*.png")))


if __name__ == "__main__":
    main()
