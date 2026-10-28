# Vulkan build dependency

Lip vendors the complete original `include/` tree of Khronos Vulkan-Headers
**v1.4.335**, commit `2fa203425eb4af9dfc6b03f97ef72b0b5bcb8350`, plus upstream license
metadata/texts. Original per-file copyright and SPDX declarations are unchanged.
Headers include copyright 2015–2025 The Khronos Group Inc.; individual notices and
additional authors remain authoritative.

Source: https://github.com/KhronosGroup/Vulkan-Headers/tree/2fa203425eb4af9dfc6b03f97ef72b0b5bcb8350
Archive SHA256: `66e0dc7932203def85d94bf65a4fa3a3b29e61366713404f0465cdeb02e6ffd3`.
All49 selected headers permit Apache-2.0. Lip chooses Apache-2.0 for headers marked
`Apache-2.0 OR MIT`; original alternate MIT text remains in `LICENSES/MIT.txt`.
The full Apache license is in `LICENSES/Apache-2.0.txt` and already packaged in
Lip's notices assets as `apache-2.0.txt`. No upstream NOTICE file accompanies this
selected pin. `source.json` records every selected original byte hash and size.

Only headers are compiled. No Vulkan SDK binaries, compiler binaries, loader,
model weights, account code or runtime download is redistributed by this directory.
Android supplies `libvulkan.so`; the pinned Android NDK supplies build-only glslc,
loader stubs and SPIR-V headers. Lip's CMake fails closed on missing/inconsistent
inputs, with no automatic fetch or substitute toolchain.

The three Lip-owned packaging files `source.json`, this notice and
`verify_headers.cmake` describe/check the selected upstream content. They are not
upstream generated headers and are covered by Lip's source license.
