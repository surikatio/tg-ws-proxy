"""
Генератор иконок для страниц приложения в магазинах.

Рисует в 2048 px и уменьшает с LANCZOS — края получаются гладкими, в отличие
от растягивания icon.ico, где максимум 256 px. Буква нарисована геометрически,
а не шрифтом, чтобы результат не зависел от того, какие шрифты установлены.

    python make_icons.py [размер]     # по умолчанию 512
"""
from __future__ import annotations

import sys

from PIL import Image, ImageDraw

BLUE = (33, 150, 243, 255)
WHITE = (255, 255, 255, 255)
SUPERSAMPLE = 2048

# Pillow 10 перенесло константы в Image.Resampling, сохранив старые алиасы.
LANCZOS = getattr(Image, "Resampling", Image).LANCZOS  # type: ignore[attr-defined]


def draw_letter(img: Image.Image, size: int) -> None:
    """Пропорции сняты с icon.ico проекта."""
    d = ImageDraw.Draw(img)
    bar_w = size * 0.50
    bar_h = size * 0.115
    stem_w = size * 0.155
    top = size * 0.255
    bottom = size * 0.755
    radius = size * 0.028
    cx = size / 2
    d.rounded_rectangle([cx - bar_w / 2, top, cx + bar_w / 2, top + bar_h],
                        radius=radius, fill=WHITE)
    d.rounded_rectangle([cx - stem_w / 2, top, cx + stem_w / 2, bottom],
                        radius=radius, fill=WHITE)


def main() -> None:
    out_size = int(sys.argv[1]) if len(sys.argv) > 1 else 512
    s = SUPERSAMPLE

    # Залитый квадрат: магазины скругляют углы сами.
    square = Image.new("RGBA", (s, s), BLUE)
    draw_letter(square, s)
    square.resize((out_size, out_size), LANCZOS).convert("RGB").save(
        f"icon-{out_size}-square.png")

    # Круг на белом — ближе к исходной иконке проекта.
    circle = Image.new("RGBA", (s, s), WHITE)
    ImageDraw.Draw(circle).ellipse([0, 0, s - 1, s - 1], fill=BLUE)
    draw_letter(circle, s)
    circle.resize((out_size, out_size), LANCZOS).convert("RGB").save(
        f"icon-{out_size}-circle.png")

    # С прозрачностью — на случай, если магазин её допускает.
    transparent = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ImageDraw.Draw(transparent).ellipse([0, 0, s - 1, s - 1], fill=BLUE)
    draw_letter(transparent, s)
    transparent.resize((out_size, out_size), LANCZOS).save(
        f"icon-{out_size}-transparent.png")

    print(f"готово: icon-{out_size}-square.png, "
          f"icon-{out_size}-circle.png, icon-{out_size}-transparent.png")


if __name__ == "__main__":
    main()
