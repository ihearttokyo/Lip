"""Injector input/security contracts, not guest-audio or ASR evidence."""
import hashlib
import json
import os
from contextlib import nullcontext
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import wave

import inject_audio as injector


class InjectorTest(unittest.TestCase):
    def test_blocking_cli_never_delays_and_preserves_every_byte_and_zero_tail(self):
        payload = bytes(range(256)) * 250
        received = []
        stopped = unittest.mock.Mock()
        stopped.wait.return_value = stopped.is_set.return_value = False
        pb = SimpleNamespace(AudioFormat=lambda **values: SimpleNamespace(**values),
                             AudioPacket=lambda **values: SimpleNamespace(**values))
        pb.AudioFormat.Mono, pb.AudioFormat.AUD_FMT_S16, pb.AudioFormat.MODE_UNSPECIFIED = 0, 1, 0
        stub = SimpleNamespace(injectAudio=lambda packets, **_: received.extend(packets))
        modules = {"grpc": SimpleNamespace(insecure_channel=lambda _: nullcontext(None), RpcError=RuntimeError),
                   "emulator_controller_pb2": pb,
                   "emulator_controller_pb2_grpc": SimpleNamespace(EmulatorControllerStub=lambda _: stub)}
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / "proto.sha256").write_text(injector.PROTO_SHA256)
            argv = ["inject_audio.py", "--emulator-pid", "123", "--endpoint", "127.0.0.1:8554",
                    "--discovery", str(folder / "pid_123_info.ini"), "--avd-dir", str(folder),
                    "--wav", str(folder / "fixture.wav"), "--wav-sha256", "0" * 64,
                    "--bindings", str(folder), "--duration-seconds", "4"]
            with patch("sys.argv", argv), patch.dict("sys.modules", modules), \
                    patch("inject_audio.wav_pcm", return_value=payload), \
                    patch("inject_audio.read_discovery", return_value={}), \
                    patch("inject_audio.verify_live_target") as verified, \
                    patch("inject_audio.jwt_identity", return_value=nullcontext(None)), \
                    patch("inject_audio.auth_checks") as authenticated, \
                    patch("inject_audio.metadata", return_value=()), \
                    patch("inject_audio.threading.Event", return_value=stopped), \
                    patch("inject_audio.signal.signal"), patch("builtins.print"):
                injector.main()
            stopped.wait.assert_not_called()
            self.assertEqual(b"".join(packet.audio for packet in received), payload + bytes(64_000))
            self.assertTrue(all(packet.format.mode == 0 and len(packet.audio) == 1280 for packet in received))
            authenticated.assert_called_once()
            self.assertEqual(verified.call_count, 2)

    def _runtime_cli(self, root, proto_hash):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            wav = folder / "fixture.wav"
            with wave.open(str(wav), "wb") as audio:
                audio.setparams((1, 2, 16000, 0, "NONE", ""))
                audio.writeframes(bytes(1280))
            (folder / "proto.sha256").write_text(proto_hash)
            argv = ["inject_audio.py", "--emulator-root", str(root), "--emulator-pid", "123",
                    "--endpoint", "127.0.0.1:8554", "--discovery", str(folder / "pid_123_info.ini"),
                    "--avd-dir", str(folder), "--wav", str(wav), "--wav-sha256",
                    hashlib.sha256(wav.read_bytes()).hexdigest(), "--bindings", str(folder),
                    "--duration-seconds", "0.04"]
            # Stop before process checks, auth, networking or native emulator calls.
            with patch("sys.argv", argv), patch("inject_audio.read_discovery", side_effect=RuntimeError("runtime validated")):
                injector.main()

    def test_only_two_frozen_runtime_proto_selections_reach_discovery(self):
        for root, sha in ((injector.SDK / "emulator", injector.PROTO_SHA256),
                          (Path("/Users/jared/Developer/Lip/validation/emulator-37.2.12/emulator"),
                           "564e00a929e7e4a7af78269a738ea715f32d88c0fc9f92659bb8a1251c9d558f")):
            with self.subTest(root=root), self.assertRaisesRegex(RuntimeError, "runtime validated"):
                self._runtime_cli(root, sha)

    def test_unknown_runtime_changed_proto_version_policy_or_binding_is_refused(self):
        candidate = Path("/Users/jared/Developer/Lip/validation/emulator-37.2.12/emulator")
        sha = "564e00a929e7e4a7af78269a738ea715f32d88c0fc9f92659bb8a1251c9d558f"
        with self.assertRaises(ValueError):
            self._runtime_cli(Path("/tmp/unreviewed-emulator"), sha)
        with self.assertRaises(ValueError):
            self._runtime_cli(candidate, injector.PROTO_SHA256)
        original = Path.read_bytes
        for leaf in ("lib/emulator_controller.proto", "source.properties", "lib/emulator_access.json"):
            def changed(path, leaf=leaf):
                return b"changed runtime provenance" if path == candidate / leaf else original(path)
            with self.subTest(leaf=leaf), patch.object(Path, "read_bytes", changed), self.assertRaises(ValueError):
                self._runtime_cli(candidate, sha)

    def test_candidate_engine_requires_explicit_matching_pinned_runtime(self):
        candidate = Path("/Users/jared/Developer/Lip/validation/emulator-37.2.12/emulator")
        binary = candidate / "qemu/darwin-aarch64/qemu-system-aarch64"
        line = f"123 {os.getuid()} {binary} -avd fixture -grpc 8554 -grpc-use-jwt"
        injector.validate_process(line, 123, "fixture", emulator_root=candidate)
        for root in (injector.SDK / "emulator", Path("/tmp/unreviewed-emulator")):
            with self.assertRaises(ValueError):
                injector.validate_process(line, 123, "fixture", emulator_root=root)
        for bad in (line + " -allow-host-audio", line.replace("-grpc-use-jwt", ""), line + " -no-audio"):
            with self.assertRaises(ValueError):
                injector.validate_process(bad, 123, "fixture", emulator_root=candidate)

    def test_live_pid_validation_carries_the_selected_runtime(self):
        candidate = Path("/Users/jared/Developer/Lip/validation/emulator-37.2.12/emulator")
        binary = candidate / "qemu/darwin-aarch64/qemu-system-aarch64"
        process = f"123 {os.getuid()} {binary} -avd fixture -grpc 8554 -grpc-use-jwt"
        with patch("inject_audio.subprocess.run", side_effect=[
                SimpleNamespace(stdout=process), SimpleNamespace(stdout="p123\nn127.0.0.1:8554\n")]):
            injector.verify_live_target(123, {"avd.id": "fixture"}, "127.0.0.1:8554", candidate)

    def test_loopback_endpoint_only(self):
        for endpoint in ("127.0.0.1:8554", "localhost:8554", "[::1]:8554"):
            self.assertEqual(injector.endpoint_port(endpoint), 8554)
        for endpoint in ("192.168.1.1:8554", "0.0.0.0:8554", "*:8554",
                         "user@localhost:8554", "localhost:8554/path",
                         "localhost:0", "localhost:65536"):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                injector.endpoint_port(endpoint)

    def test_wav_is_exact_pcm16_mono_16k_and_fingerprint(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "audio.wav"
            payload = bytes(range(256)) * 5
            for channels, width, rate in ((1, 2, 16000), (2, 2, 16000),
                                          (1, 1, 16000), (1, 2, 44100)):
                with wave.open(str(path), "wb") as audio:
                    audio.setparams((channels, width, rate, 0, "NONE", ""))
                    audio.writeframes(payload)
                digest = hashlib.sha256(path.read_bytes()).hexdigest()
                if (channels, width, rate) == (1, 2, 16000):
                    self.assertEqual(injector.wav_pcm(path, digest), payload)
                    with self.assertRaises(ValueError):
                        injector.wav_pcm(path, "0" * 64)
                else:
                    with self.assertRaises(ValueError):
                        injector.wav_pcm(path, digest)

    def test_truncated_pcm_refused_before_auth(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "audio.wav"
            with wave.open(str(path), "wb") as audio:
                audio.setparams((1, 2, 16000, 0, "NONE", ""))
                audio.writeframes(bytes(1280))
            path.write_bytes(path.read_bytes()[:-2])
            with self.assertRaises(ValueError):
                injector.wav_pcm(path, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_unaligned_wav_data_is_not_silently_dropped(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "audio.wav"
            with wave.open(str(path), "wb") as audio:
                audio.setparams((1, 2, 16000, 0, "NONE", ""))
                audio.writeframes(bytes(1281))
            with self.assertRaises(ValueError):
                injector.wav_pcm(path, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_localhost_cannot_resolve_outside_loopback(self):
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("192.0.2.1", 8554))]):
            with self.assertRaises(ValueError):
                injector.endpoint_port("localhost:8554")

    def test_pcm_payload_is_unchanged_and_tail_is_silence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "audio.wav"
            payload = bytes(range(256)) * 5
            with wave.open(str(path), "wb") as audio:
                audio.setparams((1, 2, 16000, 0, "NONE", ""))
                audio.writeframes(payload)
            pcm = injector.wav_pcm(path, hashlib.sha256(path.read_bytes()).hexdigest())
            chunks = list(injector.pcm_chunks(pcm, 1500))
            self.assertEqual(b"".join(chunks), payload + bytes(3000 - len(payload)))
            self.assertTrue(all(len(chunk) <= 1280 and len(chunk) % 2 == 0 for chunk in chunks))
            for frames in (639, injector.RATE * injector.MAX_SECONDS + 1):
                with self.assertRaises(ValueError):
                    list(injector.pcm_chunks(pcm, frames))
            for bad in (b"", b"\0", bytearray(payload)):
                with self.assertRaises(ValueError):
                    list(injector.pcm_chunks(bad, 640))

    def test_verified_wav_survives_path_replacement(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "audio.wav"
            payload = bytes(range(256)) * 5
            with wave.open(str(path), "wb") as audio:
                audio.setparams((1, 2, 16000, 0, "NONE", ""))
                audio.writeframes(payload)
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            pcm = injector.wav_pcm(path, digest)
            stream = injector.pcm_chunks(pcm, 640)
            with wave.open(str(path), "wb") as audio:
                audio.setparams((1, 2, 16000, 0, "NONE", ""))
                audio.writeframes(bytes(len(payload)))
            self.assertEqual(b"".join(stream), payload)

    def test_requested_host_must_belong_to_verified_pid_listener(self):
        binary = injector.SDK / "emulator/qemu/darwin-aarch64/qemu-system-aarch64"
        process = f"123 {os.getuid()} {binary} -avd fixture -grpc 8554 -grpc-use-jwt"
        with patch("inject_audio.subprocess.run", side_effect=[
                SimpleNamespace(stdout=process), SimpleNamespace(stdout="p123\nn127.0.0.1:8554\n")]):
            with self.assertRaises(ValueError):
                injector.verify_live_target(123, {"avd.id": "fixture"}, "127.0.0.2:8554")

    def test_listener_hosts_include_every_resolved_request_address(self):
        injector.validate_listener("n[::ffff:127.0.0.1]:8554\n", "127.0.0.1:8554")
        resolved = [(2, 1, 6, "", ("127.0.0.1", 8554)), (10, 1, 6, "", ("::1", 8554))]
        with patch("socket.getaddrinfo", return_value=resolved):
            injector.validate_listener("n127.0.0.1:8554\nn[::1]:8554\n", "localhost:8554")
            with self.assertRaises(ValueError):
                injector.validate_listener("n127.0.0.1:8554\n", "localhost:8554")
        with patch("socket.getaddrinfo", return_value=[]), self.assertRaises(ValueError):
            injector.validate_listener("n127.0.0.1:8554\n", "localhost:8554")

    def test_wav_snapshot_read_has_a_hard_byte_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "audio.wav"
            with wave.open(str(path), "wb") as audio:
                audio.setparams((1, 2, 16000, 0, "NONE", ""))
                audio.writeframes(bytes(1280))
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            with patch("inject_audio.MAX_WAV_BYTES", path.stat().st_size - 1):
                with self.assertRaises(ValueError):
                    injector.wav_pcm(path, digest)

    def test_discovery_binds_pid_port_avd_and_private_jwks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            avd = root / "fixture.avd"; avd.mkdir()
            keys = root / "123/jwks/session"; keys.mkdir(parents=True, mode=0o700)
            path = root / "pid_123_info.ini"
            entries = {"avd.dir": str(avd), "avd.id": "fixture", "grpc.port": "8554",
                       "grpc.jwks": str(keys), "grpc.jwk_active": str(keys / "active.jwk")}
            def write(values):
                path.write_text("".join(key + "=" + value + "\n" for key, value in values.items()))
            write(entries)
            self.assertEqual(injector.read_discovery(path, 123, avd, "127.0.0.1:8554"), entries)
            for pid, endpoint in ((124, "127.0.0.1:8554"), (123, "127.0.0.1:8555")):
                with self.assertRaises(ValueError):
                    injector.read_discovery(path, pid, avd, endpoint)
            for bad in ({**entries, "avd.dir": str(root)},
                        {**entries, "grpc.address": "0.0.0.0:8554"},
                        {**entries, "grpc.jwk_active": str(root / "unrelated.jwk")}):
                write(bad)
                with self.assertRaises(ValueError):
                    injector.read_discovery(path, 123, avd, "127.0.0.1:8554")
            write(entries); keys.chmod(0o755)
            with self.assertRaises(ValueError):
                injector.read_discovery(path, 123, avd, "127.0.0.1:8554")
            keys.chmod(0o700)
            path.write_text(path.read_text() + "grpc.port=8554\n")
            with self.assertRaises(ValueError):
                injector.read_discovery(path, 123, avd, "127.0.0.1:8554")

    def test_real_process_identity_and_listener_required(self):
        binary = injector.SDK / "emulator/qemu/darwin-aarch64/qemu-system-aarch64"
        line = f"123 {os.getuid()} {binary} -avd fixture -grpc 8554 -grpc-use-jwt"
        injector.validate_process(line, 123, "fixture")
        for bad in (line.replace("fixture", "other"), line + " -allow-host-audio",
                    line.replace("-grpc-use-jwt", ""), line + " -no-audio",
                    line.replace(str(binary), "/bin/sh")):
            with self.assertRaises(ValueError):
                injector.validate_process(bad, 123, "fixture")
        injector.validate_listener("p123\nf10\nn127.0.0.1:8554\nn[::1]:8554\n", "127.0.0.1:8554")
        for bad in ("", "n*:8554\n", "n[::]:8554\n", "n127.0.0.1:8555\n"):
            with self.assertRaises(ValueError):
                injector.validate_listener(bad, "127.0.0.1:8554")

    def test_jwt_signing_and_only_public_jwks_is_persisted(self):
        import jwt
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            active = root / "active.jwk"
            active.write_text(json.dumps({"keys": [{"kid": "fixed-kid"}]}))
            untouched = root / "other.jwk"; untouched.write_text("keep")
            entries = {"grpc.jwks": str(root), "grpc.jwk_active": str(active)}
            with patch("inject_audio.uuid.uuid4", return_value="fixed-kid"):
                with injector.jwt_identity(entries) as identity:
                    public = json.loads((root / "lip-audio-fixed-kid.jwk").read_text())
                    self.assertEqual(set(public["keys"][0]), {"kty", "crv", "alg", "use", "kid", "x", "y"})
                    token = injector.metadata(identity, injector.METHOD + "injectAudio", 60)[0][1].removeprefix("Bearer ")
                    claims = jwt.decode(token, identity[0].public_key(), algorithms=["ES256"],
                                        audience=injector.METHOD + "injectAudio", issuer="gradle-utp-emulator-control")
                    self.assertEqual(claims["aud"], [injector.METHOD + "injectAudio"])
                    self.assertNotIn("typ", jwt.get_unverified_header(token))
                    self.assertEqual((root / "lip-audio-fixed-kid.jwk").stat().st_mode & 0o777, 0o600)
            self.assertFalse((root / "lip-audio-fixed-kid.jwk").exists())
            self.assertEqual(untouched.read_text(), "keep")

    def test_unregistered_public_key_is_cleaned_without_touching_other_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            untouched = root / "other.jwk"; untouched.write_text("keep")
            entries = {"grpc.jwks": str(root), "grpc.jwk_active": str(root / "active.jwk")}
            with patch("inject_audio.time.monotonic", side_effect=[0, 6]):
                with self.assertRaises(ValueError):
                    with injector.jwt_identity(entries):
                        self.fail("Unregistered key must not be usable")
            self.assertEqual(list(root.iterdir()), [untouched])

    def test_negative_auth_probes_do_not_accept_network_failure(self):
        import grpc
        class Rejected(grpc.RpcError):
            def __init__(self, code): self.status = code
            def code(self): return self.status
        class Stub:
            def __init__(self, negative): self.calls = 0; self.negative = negative
            def getStatus(self, *_args, **_kwargs):
                self.calls += 1
                if self.calls > 1 and self.negative is not None:
                    raise Rejected(self.negative)
                return type("Status", (), {"booted": True})()
        with patch("inject_audio.metadata", return_value=(("authorization", "not-a-real-token"),)):
            for code in (grpc.StatusCode.UNAUTHENTICATED, grpc.StatusCode.PERMISSION_DENIED):
                stub = Stub(code)
                injector.auth_checks(stub, None, None, grpc)
                self.assertEqual(stub.calls, 3)
            for code in (None, grpc.StatusCode.UNAVAILABLE, grpc.StatusCode.DEADLINE_EXCEEDED):
                with self.assertRaises(ValueError):
                    injector.auth_checks(Stub(code), None, None, grpc)


if __name__ == "__main__":
    unittest.main()
