"""Freeze gain/noise holdouts before inference; never classify or discard speech."""
import argparse
from array import array
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import platform
import random
import subprocess
import sys
import wave

from benchmark import verify_audio
from observe_vad import pcm_samples

RATE = 16_000
MAX_SAMPLES = 30 * RATE
PAUSE_SAMPLES = 20 * RATE
EDGE_SAMPLES = RATE // 2
GAIN_DBS = (-24, -42)
NOISE_SEED = 20261008
NOISE_AMPLITUDE = 655
HUMAN_IDS = ("fleurs-en-011", "fleurs-ja-010", "fleurs-zh-001")
WORDS = {"en": ("Samantha", "No."), "ja": ("Kyoko", "いいえ。"),
         "zh": ("Tingting", "不是。")}


def quantize_pcm(samples, gain_db):
    if (type(gain_db) not in (int, float) or gain_db not in (0, *GAIN_DBS) or
            not 0 < len(samples) <= MAX_SAMPLES):
        raise ValueError("Require a nonempty bounded source and frozen gain")
    if any(type(value) not in (float, int) or not math.isfinite(value) or
           not -1 <= value <= 1 for value in samples):
        raise ValueError("Source samples must be finite normalized numbers")
    factor, clipped, result = 10 ** (gain_db / 20), 0, array("h")
    for value in samples:
        integer = round(value * factor * 32768)
        limited = max(-32768, min(32767, integer))
        clipped += integer != limited
        result.append(limited)
    if sys.byteorder != "little":
        result.byteswap()
    return result.tobytes(), clipped


def layout_pcm(source_pcm, position, noise_pcm):
    if (not isinstance(source_pcm, bytes) or not source_pcm or len(source_pcm) % 2 or
            not isinstance(noise_pcm, bytes) or len(noise_pcm) != PAUSE_SAMPLES * 2 or
            position not in ("before", "after")):
        raise ValueError("Require complete PCM16, one 20-second noise span and a frozen layout")
    count = len(source_pcm) // 2
    if count + PAUSE_SAMPLES + EDGE_SAMPLES > MAX_SAMPLES:
        raise ValueError("Full source and pauses exceed 30 seconds; refusing to trim source")
    edge = noise_pcm[:EDGE_SAMPLES * 2]
    prefix, suffix = (noise_pcm, edge) if position == "before" else (edge, noise_pcm)
    start = len(prefix) // 2
    return prefix + source_pcm + suffix, start, start + count


def write_wave(path, pcm):
    # All targets belong to a newly created exclusive output directory.
    with path.open("xb") as output, wave.open(output, "wb") as audio:
        audio.setparams((1, 2, RATE, 0, "NONE", ""))
        audio.writeframes(pcm)


def identity(path):
    with path.open("rb") as source:
        data = source.read(MAX_SAMPLES * 4 + 65_537)
    if len(data) > MAX_SAMPLES * 4 + 65_536:
        raise ValueError("Source or derivative file exceeds the frozen audio bound")
    return data, {"size_bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def build(source_manifest, output_root):
    source_manifest, output_root = Path(source_manifest).resolve(strict=True), Path(output_root)
    if output_root.exists():
        raise FileExistsError("Frozen output already exists; choose a new directory")
    with source_manifest.open("rb") as source:
        source_bytes = source.read(1024 * 1024 + 1)
    if len(source_bytes) > 1024 * 1024:
        raise ValueError("Source manifest exceeds the bound")
    entries = json.loads(source_bytes)["cases"]
    sources = []
    for source_id, language in zip(HUMAN_IDS, ("en", "ja", "zh")):
        matched = [case for case in entries if case["id"] == source_id]
        if len(matched) != 1 or matched[0]["language"] != language:
            raise ValueError("Required public source is missing, duplicated or mislabeled")
        original = matched[0]
        path = verify_audio(original, source_manifest.parent)
        data, audio_identity = identity(path)
        if audio_identity != {key: original[key] for key in ("size_bytes", "sha256")}:
            raise ValueError("Public source changed after verification")
        samples = pcm_samples(data)
        if len(samples) != original["source"]["num_samples"]:
            raise ValueError("Public source sample count differs from its frozen provenance")
        # Check the complete source fits before creating output or invoking any synthesizer.
        layout_pcm(bytes(len(samples) * 2), "before", bytes(PAUSE_SAMPLES * 2))
        sources.append({"id": source_id, "language": language, "origin": "human_recording",
                        "reference_raw": original["reference_raw"],
                        "checks": deepcopy(original["checks"]), "license": original["license"],
                        "samples": samples, "data": data,
                        "source": dict(deepcopy(original["source"]), case_id=source_id,
                                       audio_sha256=original["sha256"], num_samples=len(samples))})

    output_root.mkdir(parents=True, exist_ok=False)
    (output_root / "sources").mkdir()
    (output_root / "audio").mkdir()
    for source in sources:
        path = output_root / "sources" / (source["id"] + ".wav")
        with path.open("xb") as output:
            output.write(source.pop("data"))
        source["source"]["frozen_audio"] = "sources/" + path.name
    say_hash = hashlib.sha256(Path("/usr/bin/say").read_bytes()).hexdigest()
    for language, (voice, text) in WORDS.items():
        source_id = "short-negation-" + language
        path = output_root / "sources" / (source_id + ".wav")
        command = ["/usr/bin/say", "-v", voice, "-r", "150", "-o", str(path),
                   "--file-format=WAVE", "--data-format=LEI16@16000", "--channels=1", text]
        subprocess.run(command, check=True, capture_output=True, timeout=60)
        data, audio_identity = identity(path)
        samples = pcm_samples(data)
        layout_pcm(bytes(len(samples) * 2), "before", bytes(PAUSE_SAMPLES * 2))
        sources.append({"id": source_id, "language": language, "origin": "synthetic_speech",
                        "reference_raw": text, "checks": [{"name": "negation", "target": "raw",
                        "required": [{"en": r"(?i)\bno\b", "ja": "いいえ", "zh": "不是"}[language]]}],
                        "license": "system_voice_output_local_eval_only_redistribution_not_assessed",
                        "samples": samples, "source": {"case_id": source_id,
                        "frozen_audio": "sources/" + path.name, "audio_sha256": audio_identity["sha256"],
                        "num_samples": len(samples), "generator": "/usr/bin/say", "command": command,
                        "voice": voice, "rate_wpm": 150, "text": text,
                        "say_sha256": say_hash, "macos_version": platform.mac_ver()[0]}})

    rng = random.Random(NOISE_SEED)
    noise = array("h", (rng.randint(-NOISE_AMPLITUDE, NOISE_AMPLITUDE)
                        for _ in range(PAUSE_SAMPLES)))
    if sys.byteorder != "little":
        noise.byteswap()
    noise_pcm = noise.tobytes()
    noise_path = output_root / "sources" / "seeded-noise-20s.wav"
    write_wave(noise_path, noise_pcm)
    _, noise_identity = identity(noise_path)
    noise_provenance = dict(noise_identity, frozen_audio="sources/" + noise_path.name,
                            kind="seeded_uniform_pcm16", seed=NOISE_SEED,
                            amplitude_int16=NOISE_AMPLITUDE, actual_room_tone=False,
                            shares_seed_with_existing_controls=True)
    cases = []
    for source in sources:
        for gain_db in GAIN_DBS:
            attenuated, clipped = quantize_pcm(source["samples"], gain_db)
            for position in ("before", "after"):
                pcm, start, end = layout_pcm(attenuated, position, noise_pcm)
                case_id = f"{source['id']}-minus{-gain_db}db-pause-{position}"
                path = output_root / "audio" / (case_id + ".wav")
                write_wave(path, pcm)
                _, audio_identity = identity(path)
                cases.append(dict(audio_identity, id=case_id, language=source["language"],
                    origin=source["origin"], audio="audio/" + path.name,
                    reference_raw=source["reference_raw"], max_error_rate=.05,
                    checks=deepcopy(source["checks"]), license=source["license"],
                    source=deepcopy(source["source"]),
                    transform={"gain_db": gain_db, "gain_factor": 10 ** (gain_db / 20),
                               "clipped_samples": clipped, "sample_rate": RATE,
                               "quantization": "round_nearest_ties_even_then_saturate_pcm16_no_renormalization",
                               "noise": deepcopy(noise_provenance), "pause_position": position,
                               "long_pause_samples": PAUSE_SAMPLES, "opposite_edge_samples": EDGE_SAMPLES},
                    truth={"source_envelope_samples": [start, end],
                           "voiced_alignment_claimed": False,
                           "known_synthetic_noise_samples": [[0, start], [end, len(pcm) // 2]],
                           "reference_raw": source["reference_raw"]}))
    manifest = {"schema_version": 1, "id": "lip-pre-frozen-vad-gain-noise-v1",
                "scope": "transformed_existing_human_utterances_and_synthetic_negations_not_real_room_tone",
                "inference_performed": False, "frozen_at_utc": datetime.now(timezone.utc).isoformat(),
                "builder_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                "source_manifest_sha256": hashlib.sha256(source_bytes).hexdigest(),
                "gains_db": list(GAIN_DBS), "cases": cases}
    with (output_root / "manifest.json").open("x", encoding="utf-8") as output:
        json.dump(manifest, output, ensure_ascii=False, allow_nan=False, indent=2)
        output.write("\n")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-manifest", type=Path, default=Path(__file__).parent / "corpus.json")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest = build(args.source_manifest, args.output)
    path = args.output / "manifest.json"
    print(json.dumps({"kind": "frozen_before_inference", "cases": len(manifest["cases"]),
                      "manifest": str(path), "manifest_sha256": hashlib.sha256(path.read_bytes()).hexdigest()}))


if __name__ == "__main__":
    main()
