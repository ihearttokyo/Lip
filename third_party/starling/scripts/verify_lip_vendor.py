#!/usr/bin/env python3
"""Read-only integrity gate for Lip's explicitly manifested, already-patched source.

This verifies a trusted publication manifest, not the authenticity of arbitrary
source/manifest pairs. No Git, patch application, network or generated files.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys

MANIFEST = "VENDORED-SOURCE.json"
REQUIRED = {"scripts/verify_lip_vendor.py", "backends/native/CMakeLists.txt",
            "cpp/include/starling_ggml.h", "third_party/ggml/CMakeLists.txt"}
HEX40 = re.compile(r"[0-9a-f]{40}\Z")
HEX64 = re.compile(r"[0-9a-f]{64}\Z")


class VerificationError(ValueError):
    pass


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise VerificationError("duplicate JSON key")
        result[key] = value
    return result


def member_name(name):
    if not isinstance(name, str) or not name or any(ord(c) < 32 or ord(c) == 127 for c in name):
        raise VerificationError("invalid member name")
    path = PurePosixPath(name)
    if (path.is_absolute() or "\\" in name or ":" in name or ";" in name or
            path.as_posix() != name or any(p in ("", ".", "..", ".git") for p in name.split("/"))):
        raise VerificationError("non-canonical or forbidden member path")
    if name == MANIFEST:
        raise VerificationError("manifest must not include itself")
    return name


def open_member(root_fd, name):
    """FD-relative O_NOFOLLOW for EVERY component; never traverse a symlink."""
    current = os.dup(root_fd)
    try:
        parts = name.split("/")
        for part in parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=current)
            os.close(current)
            current = child
        result = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=current)
        if not stat.S_ISREG(os.fstat(result).st_mode):
            os.close(result)
            raise VerificationError("member is not a regular file: " + name)
        return result
    finally:
        os.close(current)


def ignored_output_directory(relative, members):
    # Normal in-source CMake output roots are not published source. Never ignore
    # a directory containing a manifest member, or arbitrary cpp/*/build dirs.
    parent, _, leaf = relative.rpartition("/")
    output = parent in ("", "third_party/ggml") and (
        leaf == "build" or leaf.startswith("build-") or leaf.startswith("cmake-build-"))
    return output and not any(name.startswith(relative + "/") for name in members)


def traversal_error(error):
    # os.walk otherwise silently skips unreadable/disappearing directories,
    # which would incorrectly certify a strict manifest superset check.
    raise VerificationError("cannot enumerate source tree: " + str(error))


def verify(root):
    root = Path(root).absolute()
    if root.is_symlink() or not root.is_dir():
        raise VerificationError("source root must be a real directory")
    root = root.resolve(strict=True)
    if os.open not in os.supports_dir_fd or not all(hasattr(os, key) for key in ("O_NOFOLLOW", "O_DIRECTORY", "O_NONBLOCK")):
        raise VerificationError("platform lacks safe FD-relative no-symlink verification")
    root_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        manifest_fd = open_member(root_fd, MANIFEST)
        with os.fdopen(manifest_fd, "rb") as stream:
            if os.fstat(stream.fileno()).st_size > 16 * 1024 * 1024:
                raise VerificationError("manifest exceeds 16 MiB limit")
            data = json.loads(stream.read(), object_pairs_hook=unique_object)
        if not isinstance(data, dict) or set(data) != {"schema", "upstream", "files"} or type(data["schema"]) is not int or data["schema"] != 1:
            raise VerificationError("unsupported manifest schema")
        pins = data["upstream"]
        if not isinstance(pins, dict) or set(pins) != {"starling", "ggml"} or any(not isinstance(v, str) or not HEX40.fullmatch(v) for v in pins.values()):
            raise VerificationError("invalid upstream commit pins")
        members = data["files"]
        if not isinstance(members, dict) or not REQUIRED.issubset(members):
            raise VerificationError("required source/verifier/CMake members missing")
        for name, entry in sorted(members.items()):
            member_name(name)
            if (not isinstance(entry, dict) or set(entry) != {"sha256", "bytes"} or
                    not isinstance(entry["sha256"], str) or not HEX64.fullmatch(entry["sha256"]) or
                    type(entry["bytes"]) is not int or not 0 <= entry["bytes"] < 2 ** 63):
                raise VerificationError("invalid member size/hash: " + name)
            fd = open_member(root_fd, name)
            with os.fdopen(fd, "rb") as stream:
                if os.fstat(stream.fileno()).st_size != entry["bytes"]:
                    raise VerificationError("member size mismatch: " + name)
                digest = hashlib.sha256()
                remaining = entry["bytes"]
                while remaining:
                    block = stream.read(min(64 * 1024, remaining))
                    if not block:
                        raise VerificationError("member changed during read: " + name)
                    remaining -= len(block)
                    digest.update(block)
                if stream.read(1) or digest.hexdigest() != entry["sha256"]:
                    raise VerificationError("member checksum mismatch: " + name)
        # Strict superset check: not merely a suffix allowlist. This catches
        # unlisted glob sources, includes, templates, shaders and helper scripts.
        for directory, dirs, files in os.walk(root, followlinks=False, onerror=traversal_error):
            relative_dir = Path(directory).relative_to(root).as_posix()
            prefix = "" if relative_dir == "." else relative_dir + "/"
            kept = []
            for leaf in sorted(dirs):
                if leaf == ".git":
                    continue  # Git metadata is not read or traversed.
                path = Path(directory, leaf)
                if path.is_symlink():
                    raise VerificationError("symlink directory: " + prefix + leaf)
                if not ignored_output_directory(prefix + leaf, members):
                    kept.append(leaf)
            dirs[:] = kept
            for leaf in sorted(files):
                name = prefix + leaf
                if leaf == ".git":
                    continue
                if Path(directory, leaf).is_symlink():
                    raise VerificationError("symlink file: " + name)
                if name != MANIFEST and name not in members:
                    raise VerificationError("unmanifested source member: " + name)
        return len(members), pins
    finally:
        os.close(root_fd)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).absolute().parent.parent)
    args = parser.parse_args()
    try:
        count, pins = verify(args.root)
    except (VerificationError, OSError, UnicodeError, TypeError) as error:
        print("Lip vendored source REJECTED: " + str(error), file=sys.stderr)
        return 1
    print("Lip vendored source verified: %d files; Starling %s; GGML %s" % (count, pins["starling"], pins["ggml"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
