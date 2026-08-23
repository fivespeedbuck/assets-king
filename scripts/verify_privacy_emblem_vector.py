from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


def silhouette(path: Path, threshold: int) -> np.ndarray:
    image = Image.open(path).convert("RGB")
    pixels = np.asarray(image, dtype=np.uint8)
    luma = (
        pixels[..., 0].astype(np.uint32) * 299
        + pixels[..., 1].astype(np.uint32) * 587
        + pixels[..., 2].astype(np.uint32) * 114
    ) // 1000
    return luma < threshold


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: verify_privacy_emblem_vector.py MASK RENDER OUTPUT_DIR")

    mask_path = Path(sys.argv[1])
    render_path = Path(sys.argv[2])
    output_dir = Path(sys.argv[3])
    output_dir.mkdir(parents=True, exist_ok=True)

    source = silhouette(mask_path, 128)
    vector = silhouette(render_path, 180)
    if source.shape != vector.shape:
        raise SystemExit(f"size mismatch: source={source.shape}, vector={vector.shape}")

    intersection = source & vector
    union = source | vector
    iou = float(intersection.sum() / union.sum())
    source_coverage = float(intersection.sum() / source.sum())
    vector_precision = float(intersection.sum() / vector.sum())

    overlay = np.full((*source.shape, 3), 255, dtype=np.uint8)
    overlay[intersection] = (85, 85, 92)
    overlay[source & ~vector] = (220, 55, 55)
    overlay[vector & ~source] = (48, 112, 220)
    Image.fromarray(overlay).save(output_dir / "privacy-emblem-vector-overlay.png")

    render = Image.open(render_path).convert("RGBA")
    for size in (24, 28, 32):
        canvas = Image.new("RGBA", (size * 6, size * 6), (226, 246, 232, 255))
        icon = render.resize((size * 4, size * 4), Image.Resampling.LANCZOS)
        canvas.alpha_composite(icon, (size, size))
        draw = ImageDraw.Draw(canvas)
        draw.rectangle((0, 0, canvas.width - 1, canvas.height - 1), outline=(180, 210, 188, 255))
        canvas.save(output_dir / f"privacy-emblem-{size}dp-preview.png")

    print(f"iou={iou:.8f}")
    print(f"source_coverage={source_coverage:.8f}")
    print(f"vector_precision={vector_precision:.8f}")
    print(f"source_pixels={int(source.sum())}")
    print(f"vector_pixels={int(vector.sum())}")


if __name__ == "__main__":
    main()
