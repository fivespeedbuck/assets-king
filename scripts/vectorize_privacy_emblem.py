from __future__ import annotations

import argparse
import math
import re
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
import vtracer


def fitted_bottom_apex(mask: np.ndarray) -> tuple[float, float]:
    height = mask.shape[0]
    rows = np.arange(height - 64, height)
    left = np.array([np.flatnonzero(mask[y])[0] for y in rows], dtype=np.float64)
    right = np.array([np.flatnonzero(mask[y])[-1] for y in rows], dtype=np.float64)
    left_fit = np.polyfit(rows, left, 1)
    right_fit = np.polyfit(rows, right, 1)
    apex_y = (right_fit[1] - left_fit[1]) / (left_fit[0] - right_fit[0])
    apex_x = left_fit[0] * apex_y + left_fit[1]
    return float(apex_x), float(apex_y)


def vectorize(source: Path, mask_output: Path, svg_output: Path) -> None:
    rgba = np.asarray(Image.open(source).convert("RGBA"), dtype=np.uint8)

    # The gray/white differences are hand-drawn additions, not separate colors.
    # Alpha is the sole source of geometry so every added segment is retained and
    # the final vector receives one uniform fog-gray fill.
    emblem = rgba[:, :, 3] >= 64
    ys, xs = np.where(emblem)
    if not len(xs):
        raise ValueError("source image contains no visible emblem pixels")

    apex_x, apex_y = fitted_bottom_apex(emblem)
    top_margin = int(ys.min())
    side = int(math.ceil(apex_y)) + top_margin + 1
    x_shift = int(round(side / 2 - apex_x))

    repaired = Image.new("1", (side, side), 0)
    repaired.paste(Image.fromarray(emblem), (x_shift, 0))

    # The source is clipped at the lower canvas edge. Extend the two existing
    # straight sides to their fitted intersection, producing a real sharp point.
    base_y = emblem.shape[0] - 16
    base_pixels = np.flatnonzero(emblem[base_y])
    ImageDraw.Draw(repaired).polygon(
        [
            (x_shift + int(base_pixels[0]), base_y),
            (x_shift + int(base_pixels[-1]), base_y),
            (x_shift + int(round(apex_x)), int(round(apex_y))),
        ],
        fill=1,
    )

    repaired_array = np.asarray(repaired, dtype=bool)
    mask = np.full((*repaired_array.shape, 3), 255, dtype=np.uint8)
    mask[repaired_array] = (0, 0, 0)
    mask_output.parent.mkdir(parents=True, exist_ok=True)
    svg_output.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(mask, mode="RGB").save(mask_output)

    vtracer.convert_image_to_svg_py(
        str(mask_output),
        str(svg_output),
        colormode="binary",
        hierarchical="stacked",
        mode="spline",
        filter_speckle=3,
        corner_threshold=60,
        length_threshold=4.5,
        max_iterations=10,
        splice_threshold=45,
        path_precision=3,
    )

    svg = svg_output.read_text(encoding="utf-8")
    svg = re.sub(r'fill="(?:#000000|black)"', 'fill="#77777F"', svg, flags=re.IGNORECASE)
    svg_output.write_text(svg, encoding="utf-8")
    print(f"restored_apex=({apex_x:.2f}, {apex_y:.2f}) canvas={side}x{side}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--mask-output", type=Path, required=True)
    parser.add_argument("--svg-output", type=Path, required=True)
    args = parser.parse_args()
    vectorize(args.source, args.mask_output, args.svg_output)


if __name__ == "__main__":
    main()
