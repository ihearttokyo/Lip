"""Create tiny deterministic silence/noise controls locally; no speech synthesis."""
import hashlib
import json
from pathlib import Path
import random
import struct
import wave


def prepare(root):
    root = Path(root)
    (root / "audio").mkdir(parents=True, exist_ok=True)
    random_source = random.Random(20261008)
    cases = []
    for kind in ("silence", "noise"):
        samples = b"\0\0" * 80000 if kind == "silence" else b"".join(
            struct.pack("<h", random_source.randint(-655, 655)) for _ in range(80000))
        path = root / "audio" / (kind + ".wav")
        if not path.exists():
            with wave.open(str(path), "wb") as audio:
                audio.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                audio.writeframes(samples)
        with wave.open(str(path), "rb") as audio:
            if audio.getparams()[:4] != (1, 2, 16000, 80000) or audio.readframes(80000) != samples:
                raise ValueError("Existing control differs; refusing to overwrite it")
        for language in ("en", "ja", "zh"):
            cases.append({"id": kind + "-" + language, "language": language,
                          "origin": "negative_control", "audio": "audio/" + path.name,
                          "size_bytes": path.stat().st_size,
                          "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                          "reference_raw": "", "max_error_rate": 0,
                          "checks": [], "license": "MIT", "source": {
                              "generator": "eval/controls.py", "seed": 20261008,
                              "duration_seconds": 5, "sample_rate": 16000,
                              "kind": kind, "noise_amplitude_int16": 655 if kind == "noise" else 0}})
    manifest = {"schema_version": 1, "id": "lip-nonspeech-controls-v1", "cases": cases}
    path = root / "controls.json"
    encoded = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    if path.exists() and path.read_text(encoding="utf-8") != encoded:
        raise ValueError("Existing control manifest differs; refusing to overwrite it")
    path.write_text(encoded, encoding="utf-8")
    return manifest


if __name__ == "__main__":
    manifest = prepare(Path(__file__).parent)
    print(json.dumps({"cases": len(manifest["cases"]), "unique_audio_bytes": 320088}))
