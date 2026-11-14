# iBridge

一个 **arm64-v8a** 的 Android iOS 设备工具箱：通过 USB OTG 连接 iPhone / iPad，内置
`usbmuxd` / `idevice*` / `zsign` 工具链，完成 **lockdownd 配对、IPA 安装、应用列表、卸载应用、
IPA 重签**，并支持签名后一键安装。

界面基于 [Miuix](https://github.com/compose-miuix-ui/miuix)（Compose Multiplatform）构建，
使用 HyperOS 风格的卡片、底部导航、弹窗与开关，支持跟随系统 / 浅色 / 深色主题以及 Monet 主题色。

> **开箱即用**
>
> 仓库已内置 6 个 arm64-v8a 原生工具（`usbmuxd`、`idevice_id`、`ideviceinfo`、`idevicepair`、
> `ideviceinstaller`、`zsign`），除 bionic 系统库外均为静态链接。下载 CI 产物 APK 安装后，
> 授予 Root 权限即可直接配对、安装、卸载与重签。工具构建来源与许可证见
> [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 功能

- **USB OTG 设备管理**
  - `USB_DEVICE_ATTACHED/DETACHED` 热插拔监听 + `UsbManager` 快照双重检测
  - Apple VID `0x05AC` 过滤，可选申请 USB 访问权限
  - 设备名称、型号、iOS 版本、序列号、UDID、配对状态实时展示
- **lockdownd 配对**
  - `idevicepair validate / pair`，带“请在 iPhone 上点击信任”提示与超时控制
- **应用管理**
  - `ideviceinstaller` 新旧两套 CLI 自动识别（`install PATH` 与 `-i PATH`）
  - 应用列表（plist XML 解析，失败自动回退文本解析）、搜索、占用展示
  - IPA 通过 SAF 选择 → 复制到私有目录 → 推送安装，解析百分比进度
  - 卸载二次确认
- **IPA 重签**
  - `zsign -k <p12> -p <pwd> -m <mobileprovision> -o <out> [-b <BundleID>] <ipa>`
  - 可自定义 BundleID，实时解析 zsign 进度
  - 签名完成一键进入安装流程
- **守护进程**
  - `usbmuxd -f -v -S 127.0.0.1:<port> -P <pidfile> -l <logfile>`，通过 `su` 启动
  - 动态选择空闲端口（27015–27099），`USBMUXD_SOCKET_ADDRESS` 同步给 idevice* 子进程
  - pid 文件 + `/proc/<pid>` + TCP 探活，支持启动 / 停止 / 重启 / 复用旧实例
- **日志**
  - 内存环形缓冲（2500 条）+ 会话文件落盘
  - 按级别着色、级别筛选、关键字搜索、自动滚动、保存到 SAF、清空
- **工具链**
  - 启动时自动从 assets 释放，ELF 魔数 + `EM_AARCH64` 校验
  - 私有目录执行失败时的 `su` 执行路径、`LD_LIBRARY_PATH` 兼容动态链接产物
  - 支持 zip 工具包导入、强制重新释放、缺失项提示
- **细节**
  - 明暗主题与 7 种主题色、状态栏图标自动反色
  - 文件缓存自动清理（保留最近 12 个 / 3 天）
  - 所有耗时操作有全局进度条与 Snackbar 异常反馈

## 技术栈

| 组件 | 版本 |
| --- | --- |
| Android Gradle Plugin | 9.5.1 |
| Gradle | 9.9.1 |
| Kotlin | 2.4.21 |
| Miuix | 0.9.4 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |
| JDK | 21 |
| ABI | arm64-v8a |

Miuix `0.9.4` 的 AAR 声明了 `minCompileSdk=37`，因此本项目使用 Android 37 SDK（安装时需
`sdkmanager --channel=3`）。

## 项目结构

```text
iBridge/
├── .github/workflows/android-release.yml   # CI：构建 + 制品 + Release
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/bin/arm64-v8a/           # 原生工具（默认仅 README）
│       ├── kotlin/com/ibridge/otg/
│       │   ├── core/                       # AppController / LogBus / RootShell / Prefs
│       │   ├── tools/                      # BinaryManager / ToolRunner / ToolId
│       │   ├── usb/                        # UsbMonitor
│       │   ├── daemon/                     # UsbmuxdManager
│       │   ├── device/                     # IosDeviceManager / PlistParser
│       │   ├── sign/                       # IpaSigner
│       │   └── ui/                         # theme / components / screens
│       └── res/                            # 主题、图标、USB 过滤器
├── scripts/fetch-binaries.sh               # 可选：CI / 本地替换内置工具链
├── THIRD_PARTY_NOTICES.md                  # 内置二进制的来源与许可证
├── gradle/libs.versions.toml
├── settings.gradle.kts
└── README.md
```

## 构建

```bash
# 需要 JDK 21 与 Android SDK 37
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

./gradlew :app:assembleRelease
# 若无签名配置，自动回退 debug 签名，保证 APK 可直接安装
```

### 可选：替换原生工具链

仓库已内置可直接使用的工具链。如需替换：

```bash
export IBRIDGE_BINARIES_URL="https://example.com/ibridge-toolchain.zip"
bash scripts/fetch-binaries.sh
```

或直接把 6 个文件复制到 `app/src/main/assets/bin/arm64-v8a/`。

期望文件：

```text
usbmuxd  idevice_id  ideviceinfo  idevicepair  ideviceinstaller  zsign
```

需要从源码重建内置工具链时（NDK r28c，自动构建 OpenSSL / libimobiledevice / libusb /
usbmuxd / libzip / ideviceinstaller / zsign）：

```bash
FORCE_BUILD=1 bash scripts/build-toolchain.sh
```

## GitHub Actions

推送到 `main` / `master`、推送 `v*` tag 或手动触发 `workflow_dispatch` 时：

1. 安装 JDK 21、Android SDK 37 / Build Tools 37；
2. 可选下载 `IBRIDGE_BINARIES_URL` 指定的工具链；
3. 可选解码签名证书；
4. `./gradlew :app:assembleRelease`；
5. 上传 `iBridge-<version>-arm64-v8a.apk` 制品；
6. 自动创建 / 更新 GitHub Release 并附上 APK。

版本规则：tag `v1.2.3` → `versionName=1.2.3`；普通分支推送 → `1.0.<run_number>`，
tag 自动使用 `v1.0.<run_number>`。

### Secrets（均可选）

| Secret | 说明 |
| --- | --- |
| `IBRIDGE_BINARIES_URL` | 工具链 zip/tar.gz 直链，构建前自动解压 |
| `IBRIDGE_KEYSTORE_BASE64` | release keystore 的 Base64 |
| `IBRIDGE_KEYSTORE_PASSWORD` | keystore 密码 |
| `IBRIDGE_KEY_ALIAS` | key alias |
| `IBRIDGE_KEY_PASSWORD` | key 密码 |

未提供 keystore 时会使用 debug 签名，方便个人侧载；不同 CI 机器生成的 debug key 不同，
覆盖安装前请先卸载旧版本，或配置固定证书。

## 使用流程

1. 手机通过 USB OTG 连接 iPhone，解锁 iPhone；
2. 应用自动检测设备，获取 Root 后自动启动 `usbmuxd`；
3. 未配对时点击「配对设备」，在 iPhone 上点击「信任」并输入锁屏密码；
4. 设备页 / 应用页安装 IPA，或进入签名页选择 IPA + p12 + mobileprovision 重签；
5. 签名完成点击「安装到设备」。

## 说明与排查

- **必须 Root**：`usbmuxd` 需要直接访问 USB 设备节点；应用通过 `su -c` 执行全部原生工具。
- **Android 10+ W^X**：如果 ROM 禁止从应用私有目录执行文件，可把工具放在
  `/data/local/tmp`（或在工具包中提供动态链接产物并使用 `LD_LIBRARY_PATH`），再通过
  “导入工具包”重新释放。当前实现已通过 Root 执行并注入 `LD_LIBRARY_PATH`。
- **端口冲突**：usbmuxd 不使用编译期固定端口，运行时挑选空闲端口；若 27015 被占用会自动
  切换到 27015–27099 之间的可用端口，idevice* 通过 `USBMUXD_SOCKET_ADDRESS` 跟随。
- **配对失败**：确认 iPhone 已解锁、信任弹窗已点「信任」；应用已把 `HOME` / `TMPDIR` 指向
  私有目录，并尝试将 `/var/lib/lockdown` 软链到私有目录（rootfs 可写时）。若你的
  libimobiledevice 使用其他编译期固定路径，请自行 patch 后再放入工具链。
- **安装失败**：检查 IPA 是否签名、描述文件是否包含设备 UDID、证书是否过期。
- 日志页会记录所有命令与退出码，便于排查；可保存为 `.log` 分享。

## 许可与免责声明

本项目代码使用 MIT License。内置的 `libimobiledevice`、`usbmuxd`、`zsign` 等二进制遵循各自
许可证，来源与构建方式见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。请仅管理你本人
拥有的 iOS 设备，并遵守 Apple 开发者协议与当地法律法规。
