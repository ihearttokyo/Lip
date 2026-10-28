"""Fetch only the parent-admitted FLEURS clips; signed URLs never enter receipts."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import urllib.parse
import urllib.request

from benchmark import audio_duration


def verify_row(case, row, row_index):
    source = case["source"]
    expected = {"id": source["utterance_id"], "num_samples": source["num_samples"],
                "raw_transcription": source["source_raw_transcription"],
                "transcription": source["source_asr_transcription"],
                "gender": {"male": 0, "female": 1, "other": 2}[source["gender_label"]]}
    if row_index != source["row_index"] or any(row.get(key) != value for key, value in expected.items()):
        raise ValueError("Source row identity or transcript drift")
    if Path(row["path"]).name != source["source_file"]:
        raise ValueError("Source filename drift")
    url = row["audio"][0]["src"]
    parsed = urllib.parse.urlsplit(url)
    expected_path = ("/cached-assets/google/fleurs/--/" + source["revision"] + "/--/" +
                     source["config"] + "/" + source["split"] + "/" + str(row_index) + "/audio/audio.wav")
    if parsed.scheme != "https" or parsed.hostname != "datasets-server.huggingface.co" or parsed.path != expected_path:
        raise ValueError("Source delivery identity drift")
    return url


def verify_delivery(case, data, head_etag, get_etag):
    expected_etag = case["source"].get("cached_audio_etag")
    if len(data) != case["size_bytes"] or not head_etag or head_etag != get_etag:
        raise ValueError("Audio size or delivery ETag mismatch")
    if expected_etag is not None and expected_etag != get_etag:
        raise ValueError("Frozen source ETag drift")
    digest = hashlib.sha256(data).hexdigest()
    if case.get("sha256") is not None and case["sha256"] != digest:
        raise ValueError("Frozen audio SHA-256 drift")
    return digest


def fetch(manifest_path):
    manifest_path = Path(manifest_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    cases = manifest["cases"]
    if len(cases) != 18 or sum(c["size_bytes"] for c in cases) != 9615124:
        raise ValueError("Only the exact admitted 18-file set may be fetched")
    if any(c["max_error_rate"] != .05 for c in cases):
        raise ValueError("The parent-frozen 0.05 error gate must be retained")
    urls = {}
    for config in sorted({c["source"]["config"] for c in cases}):
        metadata = None
        for length in (36, 35):
            url = ("https://datasets-server.huggingface.co/rows?dataset=google/fleurs&config=" +
                   config + "&split=validation&offset=0&length=" + str(length))
            try:
                with urllib.request.urlopen(url, timeout=40) as response:
                    metadata = json.load(response)
                break
            except (OSError, ValueError):
                print("Metadata retry for " + config)
        if metadata is None:
            raise ValueError("Source metadata unavailable for " + config)
        rows = {item["row_idx"]: item["row"] for item in metadata["rows"]}
        for case in (c for c in cases if c["source"]["config"] == config):
            index = case["source"]["row_index"]
            urls[case["id"]] = verify_row(case, rows[index], index)
    root = manifest_path.parent.resolve()
    for case in cases:
        path = (root / case["audio"]).resolve()
        if not path.is_relative_to(root) or path.parent != root / "audio":
            raise ValueError("Audio target escapes admitted directory")
        url = urls[case["id"]]
        with urllib.request.urlopen(urllib.request.Request(url, method="HEAD"), timeout=30) as response:
            if int(response.headers.get("content-length", "0")) != case["size_bytes"]:
                raise ValueError("HEAD size mismatch for " + case["id"])
            head_etag = response.headers.get("etag")
        with urllib.request.urlopen(url, timeout=30) as response:
            if int(response.headers.get("content-length", "0")) != case["size_bytes"]:
                raise ValueError("GET size mismatch for " + case["id"])
            data, get_etag = response.read(case["size_bytes"] + 1), response.headers.get("etag")
        digest = verify_delivery(case, data, head_etag, get_etag)
        path.parent.mkdir(exist_ok=True)
        if path.exists() and path.read_bytes() != data:
            raise ValueError("Existing audio differs; refusing to overwrite " + case["id"])
        if not path.exists():
            path.write_bytes(data)
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError("Stored audio readback mismatch for " + case["id"])
        if audio_duration(path) != case["expected_num_samples"] / case["expected_sample_rate"]:
            raise ValueError("Actual WAVE duration differs from source for " + case["id"])
        case.update(sha256=digest, admission="downloaded_and_verified")
        case["source"]["cached_audio_etag"] = get_etag
        print("Verified " + case["id"] + " (" + str(len(data)) + " bytes)")
    manifest.update(status="complete_set_verified_no_engine_results",
                    admitted_utc=datetime.now(timezone.utc).isoformat())
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    try:
        fetch(args.manifest)
    except (OSError, ValueError, KeyError) as error:
        # Network exceptions may carry signed URLs. Never print their messages.
        print("Corpus admission failed: " + type(error).__name__)
        raise SystemExit(1)
