from __future__ import annotations

import argparse
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


FOG_GRAY = (119, 119, 127, 255)


def shift(mask: np.ndarray, dy: int, dx: int) -> np.ndarray:
    result = np.zeros_like(mask)
    y_src = slice(max(0, -dy), mask.shape[0] - max(0, dy))
    x_src = slice(max(0, -dx), mask.shape[1] - max(0, dx))
    y_dst = slice(max(0, dy), mask.shape[0] - max(0, -dy))
    x_dst = slice(max(0, dx), mask.shape[1] - max(0, -dx))
    result[y_dst, x_dst] = mask[y_src, x_src]
    return result


def dilate(mask: np.ndarray, iterations: int) -> np.ndarray:
    value = mask.copy()
    for _ in range(iterations):
        value = np.logical_or.reduce(
            [value, shift(value, -1, 0), shift(value, 1, 0), shift(value, 0, -1), shift(value, 0, 1)]
        )
    return value


def erode(mask: np.ndarray) -> np.ndarray:
    return np.logical_and.reduce(
        [mask, shift(mask, -1, 0), shift(mask, 1, 0), shift(mask, 0, -1), shift(mask, 0, 1)]
    )


def skeletonize(mask: np.ndarray) -> np.ndarray:
    work = mask.copy()
    skeleton = np.zeros_like(mask)
    while work.any():
        reduced = erode(work)
        opened = dilate(reduced, 1)
        skeleton |= work & ~opened
        work = reduced
    return skeleton


def distance_to_segment(
    width: int,
    height: int,
    start: tuple[float, float],
    end: tuple[float, float],
) -> np.ndarray:
    y, x = np.ogrid[:height, :width]
    x1, y1 = start
    x2, y2 = end
    dx = x2 - x1
    dy = y2 - y1
    length_sq = dx * dx + dy * dy
    t = np.clip(((x - x1) * dx + (y - y1) * dy) / length_sq, 0.0, 1.0)
    nearest_x = x1 + t * dx
    nearest_y = y1 + t * dy
    return np.hypot(x - nearest_x, y - nearest_y)


def build(gray_source: Path, layer_source: Path, output: Path) -> None:
    gray = np.asarray(Image.open(gray_source).convert("RGB"), dtype=np.int32)
    layers = np.asarray(Image.open(layer_source).convert("RGB"), dtype=np.int32)
    if gray.shape != layers.shape:
        raise ValueError("Source images must share the same dimensions")

    height, width, _ = gray.shape
    luma = (gray[:, :, 0] * 299 + gray[:, :, 1] * 587 + gray[:, :, 2] * 114) // 1000
    full_emblem = luma < 210

    red = layers[:, :, 0]
    green = layers[:, :, 1]
    blue = layers[:, :, 2]
    rear_original = (blue - red > 10) & (red - green > 5) & (red < 235)

    # Remove the generated middle fragments as one band. The straight ray is redrawn below,
    # then the foreground mask naturally creates the correct collinear visible fragments.
    left_ray = ((65.0, 684.0), (470.0, 562.0))
    right_ray = ((width - 65.0, 684.0), (width - 470.0, 562.0))
    rear_without_middle = rear_original.copy()
    rear_without_middle[distance_to_segment(width, height, *left_ray) < 24.0] = False
    rear_without_middle[distance_to_segment(width, height, *right_ray) < 24.0] = False

    # The pale foreground remains on top; the dark-violet source identifies the rear framework.
    foreground = full_emblem & ~rear_original
    rear_skeleton = skeletonize(rear_without_middle)
    rear_uniform = dilate(rear_skeleton, 6)  # fixed 13 px rear-framework stroke

    line_layer = Image.fromarray((rear_uniform.astype(np.uint8) * 255), mode="L")
    draw = ImageDraw.Draw(line_layer)

    # 60-degree short segment + 220-degree reflex angle => 100-degree long segment.
    left_polyline = [(568.0, 336.68), (578.0, 354.0), (550.36, 510.74)]
    right_polyline = [(width - x, y) for x, y in left_polyline]
    draw.line(left_polyline, fill=255, width=13)
    draw.line(right_polyline, fill=255, width=13)

    # Three collinear visible fragments per side. Their explicit empty margins end
    # before the foreground rather than continuing underneath it.
    left_fragments = [
        ((65.0, 684.0), (298.0, 613.8)),
        ((329.0, 604.5), (378.0, 589.7)),
        ((409.0, 580.4), (458.0, 565.7)),
    ]
    for start, end in left_fragments:
        draw.line([start, end], fill=255, width=13)
        draw.line([(width - start[0], start[1]), (width - end[0], end[1])], fill=255, width=13)

    rear_uniform = np.asarray(line_layer) > 0
    final_mask = rear_uniform | foreground
    rgba = np.zeros((height, width, 4), dtype=np.uint8)
    rgba[final_mask] = FOG_GRAY

    result = Image.fromarray(rgba, mode="RGBA").resize((512, 512), Image.Resampling.LANCZOS)
    output.parent.mkdir(parents=True, exist_ok=True)
    result.save(output)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gray-source", type=Path, required=True)
    parser.add_argument("--layer-source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    build(args.gray_source, args.layer_source, args.output)


if __name__ == "__main__":
    main()
