# Changelog

## 1.2.0 — CI, in-app updates, unit tests
- **New:** unit tests for URL handling, version comparison and the tar.gz extractor
  (`app/src/test`, run with `tools/test.sh`). 15 tests covering the dialects that caused the
  native-module loss.
- **New:** in-app updater — polls `releases/latest/download/version.json`, compares
  `versionCode`, downloads via DownloadManager, verifies SHA-256, hands to the package installer.
  Available from Settings → Check for updates and automatically once a day.
- **New:** GitHub Actions `ci` workflow builds every push/PR; pushing a tag `v*` creates a
  release with the signed APK and an enriched `version.json`.
- **Changed:** release builds are signed with the AOSP test key (`tools/aosp-testkey/`), the same
  key the CI uses, so every build is signature-compatible. **Upgrading from a build signed with the
  old local key requires uninstalling once** (Android refuses the mismatch).
- Bundled runtime: proot + Ubuntu rootfs + code-server (works on the Samsung Fold 6 / Android 16).


## 1.1.6 — argument parsing
- **Fixed:** `--auth none` was passed as a single argv token, so code-server answered
  `Unknown option --auth none` and never bound the port. It is now two arguments.


## 1.1.5 — extractor reads long link names (real root cause)
- **Fixed:** tar hard links whose target path exceeds 100 bytes store it in a pax `linkpath=`
  record or a GNU `K` longlink block. The extractor only read the 100-byte header field, so those
  links pointed at a truncated path and the files were silently skipped — which is why
  `argon2.node` (and the `@parcel/watcher` native module) went missing and code-server refused to
  boot. Extraction is now byte-for-byte at parity with GNU tar: 10548/10548 entries.
- **Added:** `argon2.node` and `watcher.node` ship inside the APK and are restored on Start, so an
  already-installed runtime heals without downloading 133 MB again.


## 1.1.3 — ship proot's loader (the actual blocker)
- **Fixed:** proot could not execute *anything* inside the rootfs (`execve(...): Permission denied`)
  because the loader binary it injects — `libexec/proot/loader` in Termux's package — was missing.
  It is now shipped in the APK and pointed at through `PROOT_LOADER` / `PROOT_LOADER_32`.
- proot files are re-copied on every start, so an existing install heals itself.


## 1.1.2 — launch-mode auto-detection
- The launcher now self-tests the guest runtime (`node --version`) with two proot invocations —
  direct exec, and exec through the guest's own `/lib/ld-linux-aarch64.so.1` — and uses whichever
  works, logging the choice.
- code-server is started through `lib/node out/node/entry.js` instead of the `bin/code-server`
  shell script, so proot never has to translate a shebang.
- Probe report now checks the real loader chain (`lib/ld-linux-aarch64.so.1`), counts the extracted
  tree, and includes a `proot -v 3` trace.


## 1.1.0 — bundled local runtime
- **New:** the app can install and run code-server **by itself** — no PC, no Termux, no root.
  A "Local editor" card on the home screen downloads an Ubuntu base rootfs plus the code-server
  arm64 bundle (~133 MB, unpacked by the app's own tar.gz extractor) and starts/stops it on demand.
- **How:** proot (arm64, bionic, shipped in the APK with its three libraries) emulates filesystem and
  process syscalls, so Node and VS Code's extension host run inside the app sandbox; `/sdcard` is
  bound into the rootfs, so `/sdcard/Projects` is the workspace.
- **Note:** the app now targets API 28 — the only way Android permits executing binaries from app
  storage. Sideload-only consequence.
- **New:** proot smoke test at install time, and log export to `Download/CodeServerGo/`.
- Verified off-device: the exact payload (rootfs + code-server 4.96.4) boots and serves the editor.


## 1.0.2 — first build that survives launch
- **Fixed:** `HomeActivity` never created its `ServerStore`, so `onResume` → `render()` threw an
  NPE and the app closed immediately after opening. (`BrowserActivity` had the field; this one did
  not.)
- **Added:** crash reporter (`CrashLog`) writing stack traces to `Download/CodeServerGo/crash-*.txt`
  via MediaStore, plus an on-screen trace instead of a silent exit.
- **Added:** `uiReady` guards so a failed start-up can never be followed by view access in
  `onResume` / `onConfigurationChanged`.
- **Fixed:** restored per-server zoom is applied before the first page load.

## 1.0.1
- Crash reporting + safe-mode screen.

## 1.0.0
- Initial release: server list, DeX-aware editor shell, TLS pinning, SAF uploads,
  downloads, extra key row, dark/light theme.
