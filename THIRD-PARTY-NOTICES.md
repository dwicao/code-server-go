# Third-party notices

CodeServer Go itself is MIT (see `LICENSE`). It bundles or downloads the following components so it
can run code-server on the device without root:

## Shipped inside the APK (`app/src/main/assets/`)

| component | files | license | upstream |
|---|---|---|---|
| proot (Termux build) | `proot/proot`, `proot/loader`, `proot/loader32` | GPL-2.0-or-later | https://github.com/termux/proot |
| libtalloc | `proot/libtalloc.so.2` | LGPL-3.0-or-later | https://talloc.samba.org/ |
| libandroid-shmem | `proot/libandroid-shmem.so` | Apache-2.0 | https://github.com/termux/libandroid-shmem |
| termux-exec | `proot/libtermux-exec.so` | see upstream | https://github.com/termux/termux-exec |
| argon2 native module | `cs/argon2.node` | MIT (node-argon2), CC0/Apache-2.0 (phc-winner-argon2) | https://github.com/ranisalt/node-argon2 |
| @parcel/watcher native module | `cs/watcher.node` | MIT | https://github.com/parcel-bundler/watcher |

Because proot is GPL-2.0-or-later and is redistributed here as a binary, the corresponding source is
available from the upstream repository linked above. The binaries are unmodified Termux packages
(extracted from `proot_5.1.107.102_aarch64.deb`, `libtalloc_2.5.0_aarch64.deb`,
`libandroid-shmem_0.7_aarch64.deb`, `termux-exec_2.5.0_aarch64.deb`).

## Downloaded at runtime (not redistributed)

| component | source | license |
|---|---|---|
| Ubuntu base rootfs 24.04 arm64 | cdimage.ubuntu.com | Ubuntu / free software licences |
| code-server 4.96.4 (arm64 bundle) | github.com/coder/code-server | MIT |

These are fetched by the user's device on first setup. The app does not modify them; it unpacks them
into its private storage and runs them through proot.
