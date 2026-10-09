"""Inject fixture PCM into an owned emulator microphone, never ASR results."""
import argparse
import base64
from contextlib import contextmanager
import hashlib
import io
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import shlex
import signal
import socket
import stat
import subprocess
import sys
import threading
import time
from urllib.parse import urlsplit
import uuid
import wave

SDK = Path("/Users/jared/Library/Android/sdk")
PROTO_SHA256 = "8a086dc39d71e11ce7a65cc7e0634d3e268361b1eb97d656bfb8543b6e70c99e"
EMULATOR_ROOT = SDK / "emulator"
RUNTIME_PINS = {
    EMULATOR_ROOT: {
        "version": "35.6.11", "build_id": "13610412",
        "source.properties": "079ad1b37329b5b93beeb26448f0302521dd08a3d244c77c6395b5004542b924",
        "lib/emulator_controller.proto": PROTO_SHA256,
        "lib/emulator_access.json": "db82a6740146921ed52029164cc493fcefa07608a4b3c22e051bf2abc15160da",
    },
    Path("/Users/jared/Developer/Lip/validation/emulator-37.2.12/emulator"): {
        "version": "37.2.12", "build_id": "16428233",
        "source.properties": "ba13085f647fb8389a4d06a1de0e4104ac4e841733e50f67223ac230e1ce685a",
        "lib/emulator_controller.proto": "564e00a929e7e4a7af78269a738ea715f32d88c0fc9f92659bb8a1251c9d558f",
        "lib/emulator_access.json": "c70a962250289c5840c3fd414a63382048de93a0e97cade9bef3d60257004fc3",
    },
}
METHOD = "/android.emulation.control.EmulatorController/"
RATE = 16_000
MAX_SECONDS = 600
MAX_WAV_BYTES = RATE * 2 * MAX_SECONDS + 65_536


def loopback(host):
    if host == "localhost":
        return True
    try:
        address = ipaddress.ip_address(host)
        return address.is_loopback or bool(getattr(address, "ipv4_mapped", None)
                                           and address.ipv4_mapped.is_loopback)
    except ValueError:
        return False


def endpoint_port(endpoint):
    parsed = urlsplit("grpc://" + endpoint)
    if (not loopback(parsed.hostname or "") or parsed.username is not None
            or parsed.password is not None or parsed.path or parsed.query or parsed.fragment
            or parsed.port is None or not 1 <= parsed.port <= 65_535):
        raise ValueError("Expected a loopback host:port without credentials or a URL path")
    if parsed.hostname == "localhost" and any(not loopback(address[4][0]) for address in
                                              socket.getaddrinfo("localhost", parsed.port, type=socket.SOCK_STREAM)):
        raise ValueError("Localhost resolves outside loopback")
    return parsed.port


def owned_path(path, directory=False, private=False):
    info = Path(path).lstat()
    correct_type = stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)
    if (not correct_type or info.st_uid != os.getuid()
            or info.st_mode & (0o077 if private else 0o022)):
        raise ValueError("Expected an owned, non-symlink, non-shared-writable path")
    return info


def runtime_provenance(emulator_root):
    root = Path(emulator_root)
    pin = RUNTIME_PINS.get(root)
    if pin is None or root.resolve(strict=True) != root:
        raise ValueError("Emulator runtime is not one of the two reviewed exact roots")
    owned_path(root, directory=True)
    for relative in ("source.properties", "lib/emulator_controller.proto", "lib/emulator_access.json"):
        path = root / relative
        if owned_path(path).st_size > 131_072:
            raise ValueError("Emulator runtime provenance file exceeds its bound")
        if hashlib.sha256(path.read_bytes()).hexdigest() != pin[relative]:
            raise ValueError("Emulator version, proto or authentication-policy provenance changed")
    return pin


def read_discovery(path, pid, avd_dir, endpoint):
    path, avd_dir = Path(path), Path(avd_dir).resolve(strict=True)
    owned_path(path); owned_path(path.parent, directory=True)
    if not re.fullmatch(rf"pid_{pid}(?:_info)?\.ini", path.name):
        raise ValueError("Discovery filename does not match the emulator PID")
    if path.stat().st_size > 65_536:
        raise ValueError("Discovery file exceeds the size bound")
    entries = {}
    for line in path.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith(("#", ";")):
            continue
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or key in entries or "\0" in line:
            raise ValueError("Invalid or duplicate emulator discovery entry")
        entries[key] = value
    if Path(entries.get("avd.dir", "")).resolve() != avd_dir:
        raise ValueError("Discovery does not identify the requested AVD directory")
    if str(endpoint_port(endpoint)) != entries.get("grpc.port"):
        raise ValueError("Endpoint port does not match emulator discovery")
    if "grpc.address" in entries and endpoint_port(entries["grpc.address"]) != endpoint_port(endpoint):
        raise ValueError("Advertised gRPC address does not match the endpoint")
    key_dir = Path(entries.get("grpc.jwks", ""))
    active = Path(entries.get("grpc.jwk_active", ""))
    owned_path(key_dir, directory=True, private=True)
    if (not key_dir.is_absolute() or not key_dir.resolve().is_relative_to(path.parent.resolve())
            or active != key_dir / "active.jwk"):
        raise ValueError("JWT discovery paths are not owned by this emulator discovery directory")
    if not entries.get("avd.id"):
        raise ValueError("Emulator discovery has no AVD identity")
    return entries


def validate_process(line, pid, avd_id, emulator_root=EMULATOR_ROOT):
    runtime_provenance(emulator_root)
    fields = line.strip().split(maxsplit=2)
    if len(fields) != 3 or fields[:2] != [str(pid), str(os.getuid())]:
        raise ValueError("Emulator process PID/user does not match")
    args = shlex.split(fields[2])
    binary = Path(args[0]).resolve(strict=True)
    if (not binary.is_relative_to(Path(emulator_root) / "qemu")
            or not binary.name.startswith("qemu-system-")):
        raise ValueError("Process is not the resident SDK emulator engine")
    if "-avd" not in args or args[args.index("-avd") + 1:args.index("-avd") + 2] != [avd_id]:
        raise ValueError("Emulator process has a different AVD identity")
    if "-grpc-use-jwt" not in args or "-allow-host-audio" in args or "-no-audio" in args or "-noaudio" in args:
        raise ValueError("Require JWT and guest audio without physical host microphone access")


def endpoint_addresses(endpoint):
    port = endpoint_port(endpoint)
    host = urlsplit("grpc://" + endpoint).hostname
    hosts = [value[4][0] for value in socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)] if host == "localhost" else [host]
    if not hosts or any(not loopback(value) for value in hosts):
        raise ValueError("Endpoint does not resolve exclusively to loopback")
    addresses = {ipaddress.ip_address(value) for value in hosts}
    return {getattr(address, "ipv4_mapped", None) or address for address in addresses}


def validate_listener(output, endpoint):
    listeners = [line[1:] for line in output.splitlines() if line.startswith("n")]
    port = endpoint_port(endpoint)
    if not listeners or any(endpoint_port(address) != port for address in listeners):
        raise ValueError("Emulator listener is missing, non-loopback or on a different port")
    bound = set().union(*(endpoint_addresses(address) for address in listeners))
    if not endpoint_addresses(endpoint) <= bound:
        raise ValueError("Requested endpoint host is not bound by the verified emulator PID")


def verify_live_target(pid, entries, endpoint, emulator_root=EMULATOR_ROOT):
    process = subprocess.run(["/bin/ps", "-ww", "-p", str(pid), "-o", "pid=", "-o", "uid=", "-o", "command="],
                             check=True, capture_output=True, text=True, timeout=5)
    validate_process(process.stdout, pid, entries["avd.id"], emulator_root)
    listeners = subprocess.run(["/usr/sbin/lsof", "-nP", "-a", "-p", str(pid),
                                f"-iTCP:{endpoint_port(endpoint)}", "-sTCP:LISTEN", "-Fn"],
                               check=True, capture_output=True, text=True, timeout=5)
    validate_listener(listeners.stdout, endpoint)


def wav_pcm(path, expected_sha256):
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
        raise ValueError("Invalid WAV fingerprint or file size")
    with Path(path).open("rb") as source:
        snapshot = source.read(MAX_WAV_BYTES + 1)
    if len(snapshot) > MAX_WAV_BYTES:
        raise ValueError("WAV exceeds the file size bound")
    if hashlib.sha256(snapshot).hexdigest() != expected_sha256:
        raise ValueError("WAV SHA-256 differs from the frozen fixture")
    with wave.open(io.BytesIO(snapshot), "rb") as audio:
        if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getcomptype()) != (1, 2, RATE, "NONE"):
            raise ValueError("Require uncompressed PCM16 mono 16 kHz WAV")
        frames = audio.getnframes()
        if not 0 < frames <= RATE * MAX_SECONDS:
            raise ValueError("WAV duration is empty or exceeds the bound")
        pcm = audio.readframes(frames + 1)
        if len(pcm) != frames * 2:
            raise ValueError("WAV contains truncated or unaligned PCM")
        return pcm


def pcm_chunks(pcm, total_frames):
    if not isinstance(pcm, bytes) or not pcm or len(pcm) % 2:
        raise ValueError("Require immutable, nonempty aligned PCM")
    if total_frames < len(pcm) // 2 or total_frames > RATE * MAX_SECONDS:
        raise ValueError("Stream duration would truncate the fixture or exceed its bound")
    for offset in range(0, total_frames * 2, 1280):
        size = min(1280, total_frames * 2 - offset)
        chunk = pcm[offset:offset + size]
        yield chunk + bytes(size - len(chunk))


def public_jwk(private_key, kid):
    numbers = private_key.public_key().public_numbers()
    encode = lambda number: base64.urlsafe_b64encode(number.to_bytes(32, "big")).rstrip(b"=").decode()
    return {"kty": "EC", "crv": "P-256", "alg": "ES256", "use": "sig", "kid": kid,
            "x": encode(numbers.x), "y": encode(numbers.y)}


@contextmanager
def jwt_identity(entries):
    from cryptography.hazmat.primitives.asymmetric import ec
    key, kid = ec.generate_private_key(ec.SECP256R1()), str(uuid.uuid4())
    key_dir, active = Path(entries["grpc.jwks"]), Path(entries["grpc.jwk_active"])
    owned_path(key_dir, directory=True, private=True)
    public_file = key_dir / ("lip-audio-" + kid + ".jwk")
    descriptor = os.open(public_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    created = os.fstat(descriptor)
    try:
        with os.fdopen(descriptor, "w") as output:
            json.dump({"keys": [public_jwk(key, kid)]}, output)
        deadline = time.monotonic() + 5
        while True:
            if active.exists():
                owned_path(active)
                if active.stat().st_size > 1_048_576:
                    raise ValueError("Active public JWKS exceeds the size bound")
                try:
                    if any(value.get("kid") == kid for value in json.loads(active.read_text()).get("keys", [])):
                        break
                except json.JSONDecodeError:
                    pass  # The emulator may still be writing its public-key registry.
            if time.monotonic() >= deadline:
                raise ValueError("Emulator did not register this session's public key")
            time.sleep(0.1)
        yield key, kid
    finally:
        if public_file.exists():
            current = public_file.lstat()
            if (current.st_dev, current.st_ino) != (created.st_dev, created.st_ino):
                raise ValueError("Owned public-key file was replaced; refusing cleanup")
            public_file.unlink()


def metadata(identity, method, lifetime):
    import jwt
    key, kid = identity
    now = int(time.time())
    token = jwt.encode({"iss": "gradle-utp-emulator-control", "aud": [method],
                        "iat": now - 1, "exp": now + lifetime}, key, algorithm="ES256",
                       headers={"kid": kid, "typ": None})
    return (("authorization", "Bearer " + token),)


def auth_checks(stub, empty, identity, grpc):
    status = stub.getStatus(empty, metadata=metadata(identity, METHOD + "getStatus", 60), timeout=5)
    for headers in ((), metadata(identity, METHOD + "notAllowed", 60)):
        try:
            stub.getStatus(empty, metadata=headers, timeout=5)
        except grpc.RpcError as error:
            if error.code() not in (grpc.StatusCode.UNAUTHENTICATED, grpc.StatusCode.PERMISSION_DENIED):
                raise ValueError("Negative auth probe failed for a non-authentication reason") from None
        else:
            raise ValueError("Emulator accepted an anonymous or wrong-audience request")
    if not status.booted:
        raise ValueError("Verified emulator has not finished booting")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--emulator-pid", "--emulatorPID", type=int, required=True)
    parser.add_argument("--emulator-root", type=Path, default=EMULATOR_ROOT,
                        help="Only the reviewed resident SDK or isolated 37.2.12 runtime")
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--discovery", type=Path, required=True)
    parser.add_argument("--avd-dir", type=Path, required=True)
    parser.add_argument("--wav", type=Path, required=True)
    parser.add_argument("--wav-sha256", required=True)
    parser.add_argument("--bindings", type=Path, required=True)
    parser.add_argument("--duration-seconds", type=float, required=True,
                        help="Fixture plus silence tail, at most 600 seconds")
    args = parser.parse_args()
    if not math.isfinite(args.duration_seconds) or not 0 < args.duration_seconds <= MAX_SECONDS:
        raise ValueError("Invalid bounded stream duration")
    pcm = wav_pcm(args.wav, args.wav_sha256)
    frames = len(pcm) // 2
    total_frames = int(args.duration_seconds * RATE)
    if total_frames < frames:
        raise ValueError("Stream duration would truncate the fixture")
    pin = runtime_provenance(args.emulator_root)
    if (args.bindings / "proto.sha256").read_text().strip() != pin["lib/emulator_controller.proto"]:
        raise ValueError("Generated bindings do not match the selected runtime proto")
    entries = read_discovery(args.discovery, args.emulator_pid, args.avd_dir, args.endpoint)
    verify_live_target(args.emulator_pid, entries, args.endpoint, args.emulator_root)
    sys.path.insert(0, str(args.bindings.resolve(strict=True)))
    import grpc
    import emulator_controller_pb2 as pb
    import emulator_controller_pb2_grpc as rpc
    from google.protobuf.empty_pb2 import Empty
    stopped = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    counts = {"yielded_bytes": 0}
    def packets():
        fmt = pb.AudioFormat(samplingRate=RATE, channels=pb.AudioFormat.Mono,
                             format=pb.AudioFormat.AUD_FMT_S16, mode=pb.AudioFormat.MODE_UNSPECIFIED)
        next_progress = RATE * 2 * 5
        for chunk in pcm_chunks(pcm, total_frames):
            if stopped.is_set():
                break
            counts["yielded_bytes"] += len(chunk)
            yield pb.AudioPacket(format=fmt, audio=chunk)
            if counts["yielded_bytes"] >= next_progress:
                print(json.dumps({"event": "pcm_yielded", **counts, "guest_delivery_proven": False}), flush=True)
                next_progress += RATE * 2 * 5
    with jwt_identity(entries) as identity, grpc.insecure_channel(args.endpoint) as channel:
        stub = rpc.EmulatorControllerStub(channel)
        auth_checks(stub, Empty(), identity, grpc)
        verify_live_target(args.emulator_pid, entries, args.endpoint, args.emulator_root)
        print(json.dumps({"event": "authenticated", "anonymous_rejected": True,
                          "wrong_audience_rejected": True, "wav_sha256": args.wav_sha256,
                          "emulator_root": str(args.emulator_root), "emulator_version": pin["version"],
                          "emulator_build_id": pin["build_id"],
                          "proto_sha256": pin["lib/emulator_controller.proto"]}), flush=True)
        try:
            stub.injectAudio(packets(), metadata=metadata(identity, METHOD + "injectAudio",
                                                         math.ceil(args.duration_seconds) + 60),
                             timeout=args.duration_seconds + 20)
        except grpc.RpcError as error:
            raise ValueError("Injection RPC failed: " + error.code().name) from None
        finally:
            stopped.set()
        print(json.dumps({"event": "injection_completed", **counts,
                          "requested_bytes": total_frames * 2,
                          "guest_delivery_proven": False}), flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Never dump RPC/JWT exception details or metadata into a validation log.
        message = str(error) if isinstance(error, ValueError) else type(error).__name__
        print(json.dumps({"event": "injector_failed", "error": message}), file=sys.stderr)
        sys.exit(1)
