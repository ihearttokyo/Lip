#!/usr/bin/env python3
"""Read-only portrait dark-keyboard pixel measurement, method v1.

Caller must independently identify the normal five-row LETTERS page in an owned
synthetic editor. This is not semantic page recognition, touch-target measurement,
landscape/physical qualification or a product score. Thresholds are fixed before
candidate measurements; wrong/ambiguous frames fail rather than infer a pitch.
Requires existing Pillow; never installs dependencies or changes input images.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
from PIL import Image


def measure(path: Path) -> dict:
    if not path.is_file() or path.stat().st_size > 32 * 1024 * 1024:
        raise ValueError('Expected bounded local PNG')
    raw = path.read_bytes()
    with Image.open(io.BytesIO(raw)) as source:
        width, height = source.size
        if source.format != 'PNG' or not (1 <= width < height and width * height <= 16_000_000):
            raise ValueError('Method v1 requires a bounded portrait PNG')
        picture = source.convert('RGB')
    pixels = picture.load()
    runs = []
    # Solid gray editor background has no black gaps; navigation has too little
    # graphite. Five rounded keycap bands have both across most of each row.
    for y in range(height // 2, height):
        black = gray = 0
        for x in range(width):
            c = pixels[x, y]
            black += max(c) <= 6
            gray += max(c) - min(c) <= 8 and 48 <= sum(c) <= 330
        if black / width >= .03 and gray / width >= .45:
            if runs and runs[-1][1] == y - 1:
                runs[-1][1] = y
            else:
                runs.append([y, y])
    bands = [r for r in runs if r[1] - r[0] + 1 >= 30]
    result = dict(method='portrait-keycap-bands-v1', imageSha256=hashlib.sha256(raw).hexdigest(),
                  width=width, height=height, bands=bands, valid=False)
    if len(bands) != 5:
        result['reason'] = 'Expected exactly five independently identified keycap bands'
        return result
    pitches = [bands[i + 1][0] - bands[i][0] for i in range(4)]
    result['pitchesPx'] = pitches
    if min(pitches) <= 0 or max(pitches) - min(pitches) > 1:
        result['reason'] = 'Ambiguous or nonuniform band pitch'
        return result
    result.update(valid=True, rowPitchPx=sum(pitches) / 4,
                  observedKeycapExtentPx=bands[-1][1] - bands[0][0] + 1)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('png', type=Path)
    args = parser.parse_args()
    try:
        result = measure(args.png)
    except (OSError, ValueError, Image.DecompressionBombError) as error:
        print(json.dumps(dict(method='portrait-keycap-bands-v1', valid=False,
                              reason=type(error).__name__)))
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0 if result['valid'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
