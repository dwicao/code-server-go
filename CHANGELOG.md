# Changelog

## 1.4.0 — in-app editor updates
- **New:** "Update" button on the local editor card. It checks the latest GitHub release of
  code-server, shows an update dialog only when a newer one exists, then downloads it, unpacks it
  in-place, keeps the old one aside until the swap succeeds, and applies the change while the
  editor server is stopped — restarting it afterwards when it was running.
- Installed-version tracking: the runtime now records the installed bundle's version in
  `opt/cs/build-version`; fresh installs read 4.96.4 as before.
- **Fixed:** no automatic apply of a mismatched-bundle upgrade. The update path refuses bundles
  that look incomplete (no `out/node/entry.js`).


## 1.3.1 — installer actually installs now
- **Fixed:** after an update downloads, the system installer never opened — missing
  `REQUEST_INSTALL_PACKAGES` permission meant the app could not trigger one.
- **Fixed:** install flow now shows a clear "Allow installs from this app" dialog (with the exact
  toggle name) when the user has not granted unknown-apps permission, rather than a fragile
  "open unknown sources" settings screen.
- **Changed:** install uses `ACTION_INSTALL_PACKAGE` first, `ACTION_VIEW` as fallback.
- **Removed:** the 64dp DeX server rail — useless on phone and too wide on DeX. The top-bar
  switch button remains the only server-switcher.


## 1.3.0 — pinned updater, slimmed BrowserActivity, DeX chrome
- **Security:** in-app updater now refuses any downloaded APK signed by a certificate other than
  the pinned release key (AOSP test key, `Signers.EXPECTED_CERT_SHA256`) — protects against casual
  compromise of the release channel. Existing installs keep the old behavior for the current APK.
- **Refactor:** `BrowserActivity` split: `EditorOverlay` (connect/error full-screen overlay) and
  `EditorRail` (DeX server rail) extracted; behaviour is unchanged.
- **Tests:** `ServerTest` added; `TarGzTest` extended to deferred hard links, GNU long-name paths,
  ustar prefix/name splits, archive-of-only-directories, mode-bit preservation (19 tests).
- **CI:** new `tools/verify-release.sh` asserts package name, versionName/versionCode match
  `version.json`, and signer fingerprint equals the pin on every build (ci + release workflows).
  Both workflows also now exit nonzero and block archiving if the APK fails the check.
- **DeX polish:** rail widened to 64dp; real status pill next to the title (accent/"offline");
  fading "Esc exit · F5 reload" hint in full screen.
- **Docs:** README "Security model" section with the threat table (what the pin blocks and what it
  doesn't); note on `MANAGE_EXTERNAL_STORAGE` grant.


## 1.2.1 — storage access + release-pipeline fix
- **Fixed:** the app could not list files on `/sdcard` — code-server showed empty folders.
  The app now declares `MANAGE_EXTERNAL_STORAGE` and opens the system grant screen when asked;
  Start is blocked (with a prompt) until the user allows management of all files.
- **Fixed:** the release workflow's `Generate version.json` step failed instantly (it redirected
  into the very `version.json` the generator reads — 0 ms failures, steps `⑧`).
  The generator now reads an explicit source copy (`SRC_VERSION_JSON`), and the workflow stages
  it, generates, then publishes so the source committed to git stays clean.
- **Changed:** `version.json` is the in-app-update manifest (`versionCode`/`versionName`/`notes`);
  attached release assets add `apkUrl`/`apkName`/`sha256`/`releaseUrl`.


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
