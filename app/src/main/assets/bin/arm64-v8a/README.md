# 内置原生工具（arm64-v8a）

本目录已包含 6 个 aarch64 / Android 可执行 ELF，应用启动时会自动释放到私有目录并设置 `0755`：

| 文件 | 来源 |
| --- | --- |
| `usbmuxd` | usbmuxd 1.1.1（静态链接 libusb / libplist / glue） |
| `idevice_id` | libimobiledevice 1.4.0 |
| `ideviceinfo` | libimobiledevice 1.4.0 |
| `idevicepair` | libimobiledevice 1.4.0 |
| `ideviceinstaller` | ideviceinstaller master（子命令 CLI，静态链接 libzip） |
| `zsign` | zsign v1.1.3（静态链接 OpenSSL 3.5.1、内置 zlib/minizip） |

除 bionic 系统库（`libc.so` / `libm.so` / `libdl.so` / `liblog.so` / `libz.so`）外，
这些二进制已静态链接第三方依赖，不依赖 Termux 或外部 `.so`。

## 自定义 / 替换

- 直接覆盖同名文件后重新构建 APK；
- 或在应用 **设置 → 工具链 → 导入工具包** 中导入 zip（支持附带 `.so` 依赖）；
- 或通过 CI Secret `IBRIDGE_BINARIES_URL` 注入新工具链。

应用会把工具释放到 `filesDir/tools/arm64-v8a/`，通过 `su -c` 执行，并通过
`IBRIDGE_LOCKDOWN_DIR` 将配对记录写入应用私有目录。
