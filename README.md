# Guise

A ground-up rebuild of [kingsollyu/Guise](https://github.com/kingsollyu/Guise) (应用变量) for
modern Android and LSPosed.

Guise was last updated in **March 2019**. It does not build today: its build script still
resolves plugin and library dependencies from `jcenter()`, which shut down in 2021. Even if
that were repaired it would still not do anything, because the mechanism it uses to hand
configuration to the hooked process has been closed off by Android itself.

This project is not a port. The configuration transport is replaced, the hook framework is
migrated to the modern libxposed API, and the data model is redesigned around a single idea:

> **A spoofed device is a coherent profile, not a bag of independently editable fields.**

---

## Why the original stopped working

| # | Cause | Detail |
|---|---|---|
| 1 | **Config transport is dead** | `SettingsXposed` wrote a JSON file into the module's own data directory, or a world-readable copy in `/data/local/tmp`. Reading another app's data directory from a hooked process has been blocked by per-app SELinux labelling since Android 7 and by scoped storage since Android 11. The module could not read its own configuration. |
| 2 | **Ancient API** | Bundled `XposedBridgeApi-54.jar`. |
| 3 | **Narrow coverage** | Roughly nine hook points, all in `Build`, `TelephonyManager`, `WifiInfo` and `Resources`. No property reads, no GPU strings, no `Settings.Secure`. |
| 4 | **No coherence** | Every field was set independently. Nothing stopped a user from producing a "Pixel" whose fingerprint, product codename and GPU all said Xiaomi. |
| 5 | **Real bug** | It hooked `Settings.System.getString` for `android_id`. `ANDROID_ID` lives in `Settings.Secure`. That hook never fired once. |
| 6 | **Real bug** | It wrote `ro.product.manufacturer` into `Build.PRODUCT` and `Build.BRAND`, and `ro.product.model` into `Build.DEVICE`. Those properties are the product and device *codenames*. |
| 7 | **Dead field** | It set `Build.SERIAL`, which has read `"UNKNOWN"` since API 26. The real accessor is `Build.getSerial()`. |
| 8 | **Rotten build** | AGP 3.2.0, Gradle 4.10.1, Kotlin 1.3.21, `com.android.support`, jcenter, Umeng analytics, Walle channel packaging. |

---

## What changed

### Configuration transport

Replaced with LSPosed's **remote preferences**, which is the officially supported modern
mechanism and is specifically designed for this.

| | Old | New |
|---|---|---|
| Storage | Module's private data dir, or `chmod 777 /data/local/tmp` | LSPosed database, via `getRemotePreferences` |
| Readable from hooked app | No (Android 7+ / 11+) | Yes |
| Written by | The module app, hoping the path is readable | `XposedService` on the service side |
| Live updates | Requires a force-stop | Preference change listener invalidates the cache |

The device catalog is bulk data, so it travels differently: it is **bundled in the APK's
assets** and read straight out of the module APK by zip, and user-captured profiles go into
a **remote file** overlay.

### Data model

```
SocProfile     one chip    -> GPU renderer, platform, ABIs, codec prefixes, CPU part
     ^ referenced by
DeviceProfile  one handset -> identity + build metadata + display + socKey
     ^ resolved with
EffectiveProfile           -> the ONLY thing a hook channel may read
```

The SoC/device split is what makes the catalog maintainable: a few dozen SoCs cover most
handsets, and every device sharing a chip automatically shares the values that must agree
with it.

Values that are mechanically implied by others are **derived, not stored**. The build
fingerprint is the important case:

```kotlin
val fingerprint: String
    get() = "$brand/$product/$device:$release/$id/$incremental:$type/$tags"
```

It cannot drift out of agreement with `Build.MODEL` and friends, because it is not a
separate field. Overriding the model updates the fingerprint automatically.

### Hook coverage

Channels, each covering one observable surface:

| Channel | Surfaces |
|---|---|
| `build` | `Build.*` (16 fields), `Build.VERSION.*`, `Build.getSerial()` |
| `system-properties` | `SystemProperties.get/getInt/getLong/getBoolean` over 23 `ro.*` keys |
| `settings-secure` | `Settings.Secure` SSAID |
| `gpu` | `GLES20/30/31.glGetString` vendor + renderer |
| `telephony` | `getDeviceId` / `getImei` / `getMeid` |
| `wifi` | `WifiInfo.getMacAddress`, `NetworkInterface.getHardwareAddress` |
| `display-density` | `Resources.updateConfiguration` (opt-in) |

### Scope

Not declared statically. The management app calls `XposedService.requestScope()` when a
target is configured, so LSPosed never pre-selects apps the user did not ask to touch.

---

## Deliberate scope limits

These are choices, not omissions.

- **`Build.VERSION.SDK_INT` is not spoofed from the profile.** It is a `static final int`
  that the framework branches on everywhere. Raising it makes an app take code paths this
  ROM does not have; lowering it hides real ones. Both crash. It applies only when a user
  pins it explicitly, and the UI shows a warning when they do.
- **Screen density is opt-in.** Every app lays itself out from the values it is handed, so
  rewriting them is the most likely thing here to break a target's UI. Resolution is not
  rewritten at all: `DisplayMetrics` is read from too many places that never pass through
  `Resources.updateConfiguration`, so a partial rewrite produces a half-changed display.
- **SIM-bound identifiers are not touched.** IMSI, SIM serial and operator codes belong to
  the SIM, not the handset. The profile carries no carrier identity, so any invented value
  would be arbitrary -- and an IMSI whose MCC/MNC disagrees with the real SIM's operator
  code is a contradiction, not a disguise.
- **SSID and BSSID are not touched.** They describe the network, not the device.
- **No native channel yet.** `/proc/cpuinfo` and `/sys/class/net/*/address` are reachable
  from Java only weakly. Doing them properly needs a native library and NDK build.

## What this cannot do

Stated plainly, because the tool would be misleading otherwise:

- **Key Attestation cannot be forged.** The certificate is signed inside the TEE. No amount
  of hooking changes that, and `rootOfTrust` reports `verifiedBootState` which comes from
  the bootloader.
- **Widevine L1 keys cannot be forged.** They are in the TEE.
- **Physical behaviour cannot be disguised.** Sensor noise, GPU timing and memory bandwidth
  come from the silicon. A device spoofed into a hundred identities still shares one body,
  and that is what server-side clustering looks for.

This module rewrites what the platform *reports*. It does not, and cannot, defeat
hardware-backed attestation.

---

## Building

```bash
# Requires JDK 17-21 and an Android SDK with platform 36.
./gradlew :core:test :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/Guise-debug-4.0.0.apk`

### Toolchain notes

- **AGP 8.13.2 / Gradle 8.14.3 / Kotlin 2.4.20**, `compileSdk 36`, `minSdk 28`.
- **libxposed is pinned to 101.0.0, not 102.0.0.** Version 102 declares
  `minCompileSdk=37`, and the SDK repository does not publish platform 37. Version 101.0.0
  declares `minCompileSdk=36` and exposes every API used here (verified against its
  published sources: `Hooker`/`Chain`, `ExceptionMode`, remote preferences, remote files,
  and the full module lifecycle).
- A **debug keystore is committed under `keystore/`** so the build does not depend on a
  writable home directory.

## Layout and layers

Guise is layered rather than monolithic. Each layer is a separate insertion point into the
"what the system tells the app" pipeline, and they trade reach against detectability.

```
core/    pure JVM: profile model, catalog, config codec. Unit-tested off-device.
xposed/  LSPosed layer: module entry, Java-visible channels, catalog asset,
         META-INF/xposed descriptors.
app/     Compose management UI and the XposedService client.
probe/   the diagnostic harness (see below).
```

`core` has no Android dependencies on purpose, so the coherence rules that matter most are
tested on the desktop JVM rather than on a rooted handset.

The intended full architecture adds two more layers, documented in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md):

| Layer | State | Insertion point | Granularity |
|---|---|---|---|
| `:core` | implemented | -- shared coherence substrate -- | -- |
| `:xposed` | implemented | Java APIs in the target process | per app |
| `:probe` | implemented | reads what an observer reads | per app |
| `:app` | implemented | config writer, root bridge | -- |
| `:zygisk` | designed | libc, `/proc`, `/sys`, raw syscalls | per process |
| `:magisk` | designed | boot-time properties, root concealment | whole device |

**The contract between them is that a channel has exactly one owner.** If two layers faked
the same observable they would race; if one rewrote `/proc/cpuinfo` while another reported a
`Build.BOARD` for a different SoC, they would contradict each other -- the exact incoherence
this project exists to remove. The rationale for each assignment, and an honest account of
what the root layer does and does not buy, is in the architecture document.

## The probe

`probe/` is a diagnostic harness, not part of the module. It claims **no permissions** and
reads only what any ordinary app can read, because its whole value depends on seeing exactly
what an observer sees.

It cannot tell truth from a coherent lie -- no client can, which is this project's central
claim. So it measures two things it *can* measure:

1. **Self-consistency.** The same facts are read from three independent sources -- Java APIs,
   `SystemProperties`, and `/proc`/`/sys` -- hashed separately, and compared. A module that
   rewrites only the Java surface (by far the most common way for one of these to be
   half-working) leaves the other two saying something else, and the disagreement **names the
   leaking field** rather than merely flagging that one exists.

2. **Coverage and visibility.** Whether the kernel's own SoC description agrees with the
   framework's claim, and whether the rewriting machinery is visible in `/proc/self/maps` or
   on the classpath. Roots and injections that are visible make the spoof pointless: many
   apps refuse to run at all once they see them.

It also records a **baseline** on first run, because "did enabling the module change
anything?" cannot be answered without one.

Checks that cannot be passed -- hardware attestation, physical facts such as total memory and
core count -- are reported honestly as such, with the reason. Making the ceiling visible is
more useful than implying it can be moved.

## Catalog data

`xposed/src/main/assets/catalog.json` ships six handsets across six SoCs.

The identity fields reflect shipping configurations. The **build metadata fields
(`buildId`, `buildIncremental`, security patch level, build host) are format-correct
examples, not verified dumps** -- they cannot be transcribed reliably from a spec sheet.

Two things keep that honest: the fingerprint is derived from those fields so it is always
internally consistent, and the app offers **"Capture this device"**, which produces a
profile from a real handset with every field read from the platform. Prefer captures, and
treat the bundled entries as a starting point.

### Contributing a device

Captures are the only way a profile is trustworthy, so a contributed `catalog.json` fragment
from a real handset is worth more than a hand-written entry:

1. Run the probe app on the device and use **Copy report**.
2. In the management app, use **Capture this device**.
3. Open an issue with both. The report is what shows which channels the profile would still
   leak on, and the capture is the profile itself.

## What the probe found on real hardware

Recorded because negative results are as useful as positive ones, and because several of these
corrected a design assumption that looked reasonable on paper.

| Finding | Consequence |
|---|---|
| **Codec vendor prefixes leak the real chip on every device tried** (`c2.mtk.` on MediaTek, `c2.qti.`/`OMX.qcom.` on Qualcomm) | The one coverage gap that is universal. `MediaCodecChannel` addresses it, opt-in. |
| **`/sys/devices/soc0/machine` exists on Qualcomm and reads `Snapdragon`; it does not exist on MediaTek** | The case for a Zygisk layer is platform-dependent, not general. |
| **LSPosed via Zygisk leaves its `.so` paths in `/proc/self/maps` unless a concealment module hides them** | The injection check needs actual paths, not vendor tokens, to tell real from false. |
| **Magisk with concealment modules leaves nothing visible**: no `su` path, no mount, and it rewrites `ro.boot.*` to claim a locked bootloader | Building own root concealment would duplicate an ecosystem that already works, and would fight it. |
| **`ro.boot.verifiedbootstate=green` alongside an installed root manager is an impossible state** | A cross-check worth running: it means something is already rewriting those properties. |
| **Total RAM and the ABI list cannot be spoofed** (`ActivityManager` is a binder call, `SUPPORTED_ABIS` is derived from the native ABI) | A profile whose handset shipped with different memory or a different ABI set is unfixably inconsistent. A profile-selection problem, not a code problem. |
| **Fingerprint release must agree with the build ID's era** (AOSP names release branches by letter: `T`=13, `U`=14, `AP*`=15, `BP*`=16) | Caught a regression introduced while making the release come from the device. |

## Licence

LGPL-3.0. See [LICENSE](LICENSE).

The licence follows the original project that this one is a rebuild of,
[kingsollyu/AppEnv](https://github.com/kingsollyu/AppEnv), which is LGPL-3.0. No code is shared
with it -- the transport, the API target, the data model and every channel are new -- but the
purpose is the same and its source was read in the course of the rebuild, so the
conservative choice is to keep the same licence. Change it if you consider the rewrite
clean-room.

