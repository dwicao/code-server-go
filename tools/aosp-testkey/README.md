# AOSP test key (public, insecure by design)

`testkey.pk8` + `testkey.x509.pem` are the AOSP **test key**, shipped in every Android source tree.
They are not a secret and cannot authenticate anything — anyone on earth can sign with them.
They exist so CI and users can produce *consistently signed* APKs: an app updates in place only when
the new APK is signed with the same key, so the release builds and the local builds all sign with
this key.

Do **not** use this key for anything sensitive — it exists only because the app is sideloaded.

`build.sh` derives `testkey.p12` from these files on first use (requires `openssl`), signs with it,
and removes nothing. The derived files are gitignored.
