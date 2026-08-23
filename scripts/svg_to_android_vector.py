from __future__ import annotations

import re
import sys
from pathlib import Path
from xml.etree import ElementTree


TRANSLATE_PATTERN = re.compile(
    r"translate\(\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\)"
)


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: svg_to_android_vector.py INPUT.svg OUTPUT.xml")

    source = Path(sys.argv[1])
    output = Path(sys.argv[2])
    root = ElementTree.parse(source).getroot()
    width = root.attrib["width"]
    height = root.attrib["height"]

    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="32dp"',
        '    android:height="32dp"',
        f'    android:viewportWidth="{width}"',
        f'    android:viewportHeight="{height}">',
    ]

    paths = list(root.findall("{http://www.w3.org/2000/svg}path"))
    for index, path in enumerate(paths):
        transform = path.attrib.get("transform", "")
        match = TRANSLATE_PATTERN.fullmatch(transform)
        if match is None:
            raise SystemExit(f"unsupported transform on path {index}: {transform!r}")
        tx, ty = match.groups()
        path_data = " ".join(path.attrib["d"].split())
        lines.extend(
            [
                f'    <group android:name="part_{index}" android:translateX="{tx}" android:translateY="{ty}">',
                '        <path',
                '            android:fillColor="#FF77777F"',
                '            android:fillType="nonZero"',
                f'            android:pathData="{path_data}" />',
                '    </group>',
            ]
        )

    lines.append("</vector>")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"converted {len(paths)} paths -> {output}")


if __name__ == "__main__":
    main()
