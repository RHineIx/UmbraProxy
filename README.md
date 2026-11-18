<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="88" height="88" alt="UmbraProxy">
</p>

<h1 align="center">UmbraProxy</h1>

<p align="center">A local MTProto proxy for Telegram on Android, built with Kotlin and Go.</p>

<p align="center">
  <a href="README.md"><img src="https://img.shields.io/badge/English-2563EB?style=for-the-badge" alt="English"></a>
  <a href="README.ar.md"><img src="docs/assets/language-ar.svg" alt="العربية"></a>
</p>

## Download

Requires **Android 8.0 or later**.

| Build | Choose this for | Download |
| --- | --- | --- |
| ARM64 | Most modern Android phones | [APK](https://github.com/RHineIx/UmbraProxy/releases/latest/download/UmbraProxy-arm64-v8a.apk) |
| ARMv7 | Devices running 32-bit ARM Android | [APK](https://github.com/RHineIx/UmbraProxy/releases/latest/download/UmbraProxy-armeabi-v7a.apk) |
| Universal | Both ARM architectures, if you are unsure | [APK](https://github.com/RHineIx/UmbraProxy/releases/latest/download/UmbraProxy-universal.apk) |

[All releases and checksums](https://github.com/RHineIx/UmbraProxy/releases)

## Get started

1. Open UmbraProxy and start the proxy.
2. Tap **Launch Telegram**.
3. Confirm the proxy connection in Telegram and check that messages load.

Telegram must be installed on the same phone: the proxy listens on `127.0.0.1`.

Use the floating **palette** to change the theme or select Arabic / English.
Tap the **version number** at the bottom to check for updates and download a matching APK.

## Features

- MTProto proxy with WebSocket transport, optional Cloudflare routing, and TCP fallback.
- Optional FakeTLS camouflage and configurable proxy secrets.
- Live connection status, session counts, and upload / download statistics.
- Searchable logs with level filters, pause / follow controls, and text / JSONL export.
- Quick Settings tile, network change recovery, and optional startup after reboot.
- Light, dark, and system themes, with Arabic and English interfaces.

## Build from source

<details>
<summary>Requirements and build commands</summary>

Use **Linux or macOS**, **JDK 17**, and **Go 1.26**. Android tools: **SDK 35**,
**Build Tools 36.0.0**, and **NDK 27.2.12479018**. Set `JAVA_HOME` to JDK 17 and
add Go and Android SDK command-line tools to `PATH`.

```sh
git clone https://github.com/RHineIx/UmbraProxy.git
cd UmbraProxy

export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
sdkmanager 'platforms;android-35' 'build-tools;36.0.0' 'ndk;27.2.12479018'

bash scripts/build-native.sh
bash gradlew testDebugUnitTest lintDebug assembleDebug
```

Debug APKs are generated in `app/build/outputs/apk/debug/`.
Rebuild the native libraries after changing Go code; Gradle does not build them automatically.

To run Go checks:

```sh
go vet ./...
go test -race ./...
```

</details>

## Contact

[Telegram](https://t.me/RHineix) · [GitHub](https://github.com/RHineIx)
