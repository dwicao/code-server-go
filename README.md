# CodeServer Go

A native Android client for **code-server** (VS Code in the browser) — built for Samsung DeX,
phones and tablets. **No root, no Termux**: it either connects to a code-server somewhere else, or
installs and runs one **on the phone itself** with a bundled proot runtime.

Verified on a Samsung Galaxy Z Fold 6 (Android 16): the local editor installs, boots, and opens
VS Code with `/sdcard/Projects` as the workspace.

```
┌─────────────────────────────┐        ┌──────────────────────────────┐
│  Samsung DeX / phone        │  HTTPS │  your PC / VPS / NAS         │
│  CodeServer Go (this app)   │ ─────► │  code-server  ──► ~/projects │
└─────────────────────────────┘        └──────────────────────────────┘
```

## Why a dedicated app instead of a browser tab?

A browser tab loses your editor session every time the DeX window is resized, has no way to pin a
self-signed LAN certificate, cannot send `Esc` / `Tab` / `Ctrl` from an on-screen keyboard, and
cannot upload files from local storage into the editor reliably. This app fixes exactly those
things, and nothing else — it is a thin, fast shell around the real code-server UI.

## Features

**DeX and large screens**
- Free-form / resizable windows (`resizeableActivity`), no forced orientation.
- Window resizes and display moves are handled with `configChanges`, so the WebView — and the
  running VS Code instance, terminals and unsaved buffers — is **never reloaded**.
- Left rail with one-tap switching between servers when the window is ≥ 720 dp wide.
- Full-screen mode with an always-reachable exit affordance.
- Hardware keyboard: `Alt+←` / `Alt+→` navigate, `F5` / `Ctrl+R` reload, `Esc` leaves full screen.
- Optional desktop user agent so VS Code serves its full desktop layout instead of the touch UI.

**The keys a phone keyboard cannot send**
- A scrollable key row (visible only when there is no physical keyboard): `Esc`, `Tab`, arrows and
  ready-made chords — `Ctrl+S`, `Ctrl+P`, `Ctrl+Shift+P`, `` Ctrl+` ``, `Ctrl+B`, `Ctrl+F`, `Ctrl+/`.
- Each chip dispatches real `KeyEvent`s into the page, so VS Code's own keybindings handle them.

**Networking and security**
- Cleartext HTTP is allowed, because LAN code-server usually runs plain `http://host:8080`.
- Self-signed / private-CA certificates: the app shows the SHA-256 fingerprint, and lets you
  *continue once* or *trust this host* (pinned in app prefs — never a global "ignore all TLS errors").
- HTTP basic-auth prompt support for code-server behind a reverse proxy.
- Cookie + localStorage + IndexedDB persistence, so you sign in once per server.
- Clear cookies for the current host from the menu.

**Files on the device**
- Every `<input type=file>` inside the editor opens Android's document picker (multi-select), so
  you can push files from `/sdcard/Projects`, Downloads whatever into the workspace.
- The picker remembers your last folder and defaults to `primary:Projects` (i.e. `/sdcard/Projects`).
- Editor downloads go to `/sdcard/Download/CodeServerGo/` through `DownloadManager` (survives the
  app being closed, shows a system notification).

**Editor shell quality of life**
- Connection state dot, title from the page, error screen with retry, automatic recovery when
  Android kills the renderer process (out of memory) without losing your server settings.
- Per-server zoom (CSS zoom, persisted), "keep screen on", shareable address copy.
- Dark / light / follow-system theme, applied without destroying the live session.

## CI & releases

GitHub Actions builds every push and PR (`.github/workflows/ci.yml`): toolchain install, unit
tests, signed APK. Pushing a tag `vX.Y.Z` (`.github/workflows/release.yml`) runs the same pipeline,
creates a GitHub release and attaches the APK plus an enriched `version.json` (with `apkUrl` and
`sha256`). The tag must match `version.json` → `versionName`, so bump `version.json` first.

To cut a release:

```bash
# version.json contains versionCode, versionName and notes
git commit -am "v1.2.1"
git tag v1.2.1 && git push && git push --tags
```

Releases are signed with the **AOSP test key** (`tools/aosp-testkey/`), the same key every CI
release and local build uses. That is what makes in-app updates possible; the test key is public
and insecure by design (see the file header there) — do not put a store-grade build here.

**Upgrading from a build signed with the old local key:** Android will refuse the update. You must
uninstall once and reinstall (the app stores no server-side state worth keeping; your server list
lives in app prefs and would be lost once).

## In-app updates

The app polls `releases/latest/download/version.json` (once a day, or immediately from
*Settings → Check for updates*). When `versionCode` is newer than the installed one it offers to
download the APK via `DownloadManager`, verifies the SHA-256 hash, and hands the result to the
system package installer — no root, no permissions beyond installing unknown apps.

## Build

The toolchain has no Gradle: on an arm64 device (or inside a proot like this one) `aapt2`, `d8` and
`apksigner` are all that is needed, and `build.sh` drives them directly.

```bash
# dependencies (Ubuntu 24.04 arm64 / proot)
apt-get install -y openjdk-17-jdk-headless android-sdk-build-tools apksigner unzip python3

# the two large build inputs (android.jar + r8 dexer) are not committed
bash tools/fetch-tools.sh

# build + sign
bash build.sh                                   # -> dist/CodeServerGo-1.1.6.apk
VERSION_NAME=1.2.0 VERSION_CODE=20 bash build.sh # or bump the version
```

`tools/r8.jar` (dexer, pure Java) and `tools/android-34/android.jar` are downloaded by
`tools/fetch-tools.sh`; after that the build works offline. Override anything with `AAPT2=`, `ANDROID_JAR=`, `D8_JAR=`, `ZIPALIGN=`,
`APKSIGNER=`, `VERSION_NAME=`, `VERSION_CODE=`.

The signing key is generated on first build at `keystore/codeservergo.jks` — keep it, otherwise
Android refuses to update an installed copy.

### Installing

```bash
adb install -r dist/CodeServerGo-1.0.2.apk
```

Without a cable: copy the APK to the phone and tap it (allow "install unknown apps" for your file
manager once).

### Using Android Studio / Gradle instead

`build.sh` is the canonical build here because the `AndroidManifest.xml` carries a `package=`
attribute (aapt2 needs it). AGP 8 derives the package from `namespace` instead and rejects that
attribute. If you want an IDE build, create an empty Compose-less "No Activity" project with
namespace `dev.codeservergo`, copy `app/src/main/{java,res}` in, and remove the `package` attribute
from the manifest.

Unit tests (pure-JVM, no Android) live in `app/src/test` and cover the URL handling, version
comparison and the tar.gz extractor that defeated us in earlier releases:

```bash
bash tools/test.sh
```

## Local editor (runs on the phone, no PC needed)

The home screen has a **Local editor** card. *Set up* downloads ~133 MB and unpacks:

| piece | source | size |
|---|---|---|
| proot (arm64, bionic) + libs | shipped in the APK (`assets/proot`) | 355 KB |
| Ubuntu base rootfs 24.04 arm64 | cdimage.ubuntu.com | 28 MB |
| code-server 4.96.4 arm64 bundle | github.com/coder/code-server | 104 MB |

*Start* then runs `proot -r rootfs /opt/cs/bin/code-server` on `127.0.0.1:8080`; the card offers
**Open**. Installed footprint: ~600 MB.

Why no root is needed: proot rewrites paths and process syscalls, so a normal glibc userland
(Node, extension host, terminals) runs inside the app's own sandbox. `/sdcard` is bound into the
rootfs, so the workspace is your real `/sdcard/Projects`.

Why the app targets API 28: Android only lets apps targeting ≤ 28 execute binaries stored in their
own data directory — the same trade-off Termux makes. Sideload only.

## Quick start (tested setup on this phone)

A code-server instance is already installed in this machine's Ubuntu environment and serves
`/sdcard/Projects`. In the app:

1. **+ Add your first server**
2. Name: `This phone`
3. Address: `http://127.0.0.1:8080/?folder=/sdcard/Projects`
4. Save, then tap the row → VS Code opens with `/sdcard/Projects` as the workspace, so the
   explorer shows your real sdcard project folders.

If it ever stops, restart it with `bash tools/start-code-server.sh`.

## Using it with any other machine

1. On the server:

   ```bash
   # LAN only, no login (simplest, trusted network)
   code-server --auth none --bind-addr 0.0.0.0:8080

   # or with a password
   code-server --bind-addr 0.0.0.0:8080
   ```

2. In the app: **+** → name (`home lab`) → address (`http://192.168.1.20:8080`) → Save → tap the row.
3. Optional: append a folder to open a specific workspace —
   `http://192.168.1.20:8080/?folder=/home/me/projects`.
4. In DeX, drag the window edges freely; nothing reloads. Use the key row for `Ctrl+S`, `Ctrl+P`.

### Notes on "my files are in /sdcard"

The phone's storage is not the server's storage: code-server can only open files that exist on the
machine it runs on. What this app gives you is the reliable path between the two — pick files from
`/sdcard/...` with the document picker and the editor uploads them into the open workspace, and
exports come back to `/sdcard/Download/CodeServerGo`. Bind-mounting or syncing the phone folder onto
the server (rsync, Syncthing, `adb`) is what makes "edit my sdcard projects" a real workflow.

## Project layout

```
app/src/main/
  AndroidManifest.xml          activities, resizeableActivity, cleartext, configChanges
  java/dev/codeservergo/
    HomeActivity.java          server list, add/edit/delete, settings, about
    BrowserActivity.java       the editor shell: WebView, TLS pinning, uploads, DeX modes
    KeyStrip.java              synthetic Esc/Tab/arrow/Ctrl chords
    Downloads.java             DownloadManager integration
    Server.java                model + URL normalisation
    ServerStore.java           JSON persistence of the server list
    Prefs.java                 settings, per-server zoom, trusted fingerprints
    Palette.java               runtime dark/light palette
    Ui.java                    dp/ripple/shape helpers (no AndroidX)
  res/                         layouts, vector icons, adaptive launcher icon, theme
build.sh                       aapt2 + javac + d8 + zipalign + apksigner
tools/                         vendored android.jar and r8.jar
keystore/                      generated on first build (do not delete)
dist/                          signed APKs
```

Total app size: ~80 KB. No AndroidX, no third-party libraries, no analytics, no network calls of
its own — the only traffic is what the editor page makes.

## Roadmap ideas

- Multiple editor tabs inside one window (two servers side by side in DeX).
- Long-press context menu pass-through for mouse users.
- Flatpak/remote-host presets with VSCode remote-SSH URIs.
- Optional SAF-backed "local folder" mirror over the extension API.

## Licence

MIT — do what you want, no warranty.
