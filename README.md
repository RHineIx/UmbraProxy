# UmbraProxy

Android MTProto proxy for Telegram, built with Kotlin/Jetpack Compose and a Go core
loaded through JNA. The listener binds to `127.0.0.1`, so Telegram must run on the
same device. This is a Telegram proxy, not a device-wide VPN.

**[التقييم التقني وخطة التطوير بالعربية](docs/REVIEW_AR.md)**

## Features

- Telegram WebSocket transport with optional third-party Cloudflare routing and TCP fallback.
- Optional FakeTLS transport camouflage and generated 128-bit proxy secrets.
- Foreground service, default-network change detection, Quick Settings tile, and optional boot startup.
- English/Arabic resources, light/dark/system themes, live counters, and a structured log console with search, level filters and file export.

“Proxy running” means the local listener started. It does not prove Telegram is
reachable. FakeTLS provides transport camouflage; it is not a guarantee of
anonymity. Third-party routing availability depends on the configured providers.

## Build

Requirements: JDK 17, Go 1.26 (latest patch recommended), Android SDK Platform 35,
Build Tools 36.0.0, and Android NDK `27.2.12479018`. Linux and macOS are supported by
the native build script. The app supports Android 8.0+ and ARM64/ARMv7 devices.

```sh
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
sdkmanager 'platforms;android-35' 'build-tools;36.0.0' 'ndk;27.2.12479018'
bash scripts/build-native.sh
bash gradlew testDebugUnitTest lintDebug assembleDebug
```

Set `JAVA_HOME` to JDK 17 and put Go on `PATH`. Alternatively, set `sdk.dir` in an
untracked `local.properties`. Build the native libraries again after Go changes;
Gradle does not compile the Go core automatically. APK packaging checks that both
`libumbraproxy.so` files exist in `app/src/main/jniLibs/`, but you must rebuild
them after Go changes to avoid packaging stale libraries.

The script targets native API 26, matching `minSdk`, and requests 16 KB ELF load
segment alignment. JNA is updated to 5.19.1. Test the packaged APK on a 16 KB page
size device before claiming runtime compatibility.

## Verify

```sh
go test -race -cover ./...
go vet ./...
go test -run='^$' -fuzz=FuzzUpgradeParser -fuzztime=10s -parallel=2
bash gradlew testDebugUnitTest lintDebug
```

Tests use loopback connections and do not depend on live Telegram servers. The
Go module uses only the standard library. `go.sum` is not needed until external
Go dependencies are added. The optional desktop entry point now requires a valid
`UMBRA_PROXY_SECRET` environment variable; it no longer starts with a known
default secret.

## Logs

The viewer records original event time, severity, source, PID and exception traces
directly from Kotlin and Go. It supports search, independent level filters,
optional DEBUG capture, pause/follow controls, and text/JSONL export. It keeps the
last 1,000 events in process memory and reports retention/queue losses explicitly.
See [logging details and limits](docs/LOGGING.md).

## Connect

1. Open the app and start the proxy.
2. Tap **Launch Telegram** to apply the local proxy link.
3. Confirm connectivity in Telegram. The local listener can be healthy while all
   upstream routes are unavailable.

Custom routing domains are base domains that must provide the expected
`kws<dc>.<base-domain>` endpoints and valid certificates. Enter a hostname, without
`https://`, a port, or a path. FakeTLS requires a valid SNI domain. Invalid saved
secrets are replaced atomically with fresh random secrets; existing Telegram
proxy entries must then be updated.

The connection pool selector is hidden because the existing pool is not connected
to the client transport path. Its implementation remains available for future
work. Boot startup is optional and subject to Android/vendor background limits.

## Releases

Pull requests and pushes to `master` run Go checks and Android unit tests, lint,
and debug APK assembly. Creating and pushing a new tag such as `v1.4.2` runs the
release workflow, builds and verifies all three APKs (ARM64, ARMv7, and universal),
signs them, and publishes a GitHub Release with generated notes and SHA-256
checksums. Tags with a suffix such as `v1.4.2-rc.1` publish a prerelease. The tag
must include the updated workflow and native build script.

Configure these repository **Actions secrets** using the existing production
signing key, with the same names used by Penumbra:

- `KEYSTORE_BASE64`: base64-encoded signing keystore.
- `KEYSTORE_PASSWORD`: keystore password.
- `KEY_ALIAS`: signing key alias.
- `KEY_PASSWORD`: signing key password.

The workflow fails before building if any signing secret is missing. It does not
fall back to unsigned APKs or a generated signing key. Repository secrets are
configured separately for each repository; Penumbra's secrets are not copied.
The temporary keystore is deleted even if a step fails.

```sh
git tag v1.4.2
git push origin v1.4.2
```

The APK `versionName` comes from the tag without `v`; `versionCode` is `5` (the
previous application code) plus the number of fetched `v*` tags, following the
Penumbra workflow's counting scheme. Keep release tags and create them in release
order so version codes increase. The manual workflow accepts an **existing tag**
for rebuilding a release; the checkout must match that tag's commit. It does not
create or move tags. Rebuilding an older tag after adding more tags recalculates
its version code from the current count.

For a locally signed release, set these properties in untracked `local.properties`:

```properties
KEYSTORE_FILE=/absolute/path/to/your-existing-release-key.jks
KEYSTORE_PASSWORD=...
KEY_ALIAS=...
KEY_PASSWORD=...
```

Run `bash gradlew assembleRelease` after building the native libraries. Use the
existing app signing key for updates. For local version overrides, pass
`-PAPP_VERSION_NAME=1.4.2 -PAPP_VERSION_CODE=100` (choose a code higher than your
last published build). Verify signing and installation before publishing. Keep signing keys and proxy secrets out of source control.

The repository does not currently declare a license. Confirm the provenance and
license of reused core code before public redistribution.

## Custom connection links

`127.0.0.1` refers to the same phone and matches the local listener. A display
name or arbitrary text is not a routable replacement for this address. A custom
hostname would have to resolve to loopback, and DNS rebinding protections or
client resolvers may block it; this is less reliable than the numeric address.
A branded HTTPS sharing link can redirect to the existing Telegram proxy link
without changing its local server address, but needs a domain/hosting you control.
Changing this field to a remote domain does not turn the phone's local listener
into a remote proxy.
