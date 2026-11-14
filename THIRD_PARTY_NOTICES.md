# Third-party notices

iBridge bundles the following arm64-v8a executables, built from upstream source with the Android
NDK and statically linked except for bionic system libraries.

| Component | Version | License | Source |
| --- | --- | --- | --- |
| libimobiledevice | 1.3.0 | LGPL-2.1-or-later | https://github.com/libimobiledevice/libimobiledevice |
| libusbmuxd | 2.1.1 | LGPL-2.1-or-later | https://github.com/libimobiledevice/libusbmuxd |
| libplist | 2.8.1 | LGPL-2.1-or-later | https://github.com/libimobiledevice/libplist |
| libimobiledevice-glue | 1.3.0 | LGPL-2.1-or-later | https://github.com/libimobiledevice/libimobiledevice-glue |
| libusb | 1.0.28 | LGPL-2.1-or-later | https://github.com/libusb/libusb |
| usbmuxd | 1.1.1 | GPL-2.0-or-later | https://github.com/libimobiledevice/usbmuxd |
| ideviceinstaller | 1.1.1 | GPL-2.0-or-later | https://github.com/libimobiledevice/ideviceinstaller |
| libzip | 1.11.4 | BSD-3-Clause | https://github.com/nih-at/libzip |
| OpenSSL | 3.5.1 | Apache-2.0 | https://github.com/openssl/openssl |
| zsign | upstream main | MIT | https://github.com/zhlynn/zsign |

Build configuration used for the bundled binaries:

- Android NDK r28c, `aarch64-linux-android26-clang`
- OpenSSL: `android-arm64 no-shared no-tests no-apps no-engine no-zlib`
- All other libraries: `--enable-static --disable-shared`
- usbmuxd: `--without-preflight --without-udev --without-systemd`
- libimobiledevice patched only to honor `IBRIDGE_LOCKDOWN_DIR` on Android for pair record storage
- Binaries stripped with `llvm-strip --strip-all`

Full license texts are available at the upstream repositories above. `zsign` is MIT licensed.
This project's own source code is MIT licensed; see [LICENSE](LICENSE).
