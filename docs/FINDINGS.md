# Findings

Two kinds of record, kept because both are load-bearing.

The first is why the module this project replaces stopped working -- which is not a story about
code rot but about Android closing a door.

The second is what the diagnostic probe measured on real handsets. It is here in full,
**including the results that killed features**, because several of those had looked entirely
reasonable on paper.

---

## Why the original stopped working

[kingsollyu/AppEnv](https://github.com/kingsollyu/AppEnv) last built in **March 2019**.

| # | Cause | Detail |
|---|---|---|
| 1 | **Config transport is dead** | `SettingsXposed` wrote a JSON file into the module's own data directory, or a world-readable copy in `/data/local/tmp`. Reading another app's data directory from a hooked process has been blocked by per-app SELinux labelling since Android 7, and by scoped storage since Android 11. The module could not read its own configuration. |
| 2 | **Ancient API** | Bundled `XposedBridgeApi-54.jar`. |
| 3 | **Narrow coverage** | Roughly nine hook points, all in `Build`, `TelephonyManager`, `WifiInfo` and `Resources`. No property reads, no GPU strings, no `Settings.Secure`. |
| 4 | **No coherence** | Every field was set independently. Nothing stopped a user from producing a "Pixel" whose fingerprint, product codename and GPU all said Xiaomi. |
| 5 | **Real bug** | It hooked `Settings.System.getString` for `android_id`. `ANDROID_ID` lives in `Settings.Secure`. That hook never fired once. |
| 6 | **Real bug** | It wrote `ro.product.manufacturer` into `Build.PRODUCT` and `Build.BRAND`, and `ro.product.model` into `Build.DEVICE`. Those properties are the product and device *codenames*, not the brand and model. |
| 7 | **Dead field** | It set `Build.SERIAL`, which has read `"UNKNOWN"` since API 26. The real accessor is the permission-gated `Build.getSerial()`. |
| 8 | **Rotten build** | AGP 3.2.0, Gradle 4.10.1, Kotlin 1.3.21, `com.android.support`, jcenter, Umeng analytics, Walle channel packaging. Its build script resolves dependencies from `jcenter()`, which shut down in 2021. |

Cause 1 is the fatal one, and it is not a matter of updating a dependency: the mechanism itself
was closed off. A module that cannot read its configuration does nothing at all, however
correct its hooks are.

---

## What the probe found on real hardware

Measured on two handsets:

- **Device A** -- Redmi Note 11T Pro, MediaTek Dimensity 8100, Android 14, Magisk with several concealment modules
- **Device B** -- a Qualcomm handset, Android 16, KernelSU with no concealment

| Finding | Consequence |
|---|---|
| **Codec vendor prefixes leak the real chip on every device tried** -- `c2.mtk.`/`OMX.MTK.` on MediaTek, `c2.qti.`/`OMX.qcom.` on Qualcomm, 45 of 100 and 56 of 113 components respectively | The one coverage gap that is universal. `MediaCodecChannel` addresses it, opt-in. |
| **`/sys/devices/soc0/machine` exists on Qualcomm and reads `Snapdragon`; it does not exist on MediaTek** (that path is Qualcomm SMEM) | The case for a Zygisk layer is **platform-dependent, not general**. On device A the observation point does not exist at all. |
| **LSPosed via Zygisk leaves its `.so` paths in `/proc/self/maps` unless a concealment module hides them** -- device B showed `/data/adb/modules/zygisk_lsposed/zygisk/arm64-v8a.so` | The injection check must report actual paths, not vendor tokens. An earlier version matched the bare token `guise`, which also matched the probe's own APK path and reported itself. |
| **Magisk with concealment modules leaves nothing visible**: no `su` path, no suspicious mount, and it rewrites `ro.boot.*` to claim a locked bootloader | Building our own root concealment would duplicate an ecosystem that already works, **and fight it**. The root layer was dropped. |
| **`ro.boot.verifiedbootstate=green` alongside an installed root manager is an impossible state** | Installing a root manager requires an unlocked bootloader, which reports `orange`. Something was already rewriting those properties. Worth cross-checking; also means a future root layer must not rewrite them again. |
| **Total RAM and the ABI list cannot be spoofed** -- `ActivityManager.MemoryInfo` is a binder call, `Build.SUPPORTED_ABIS` is derived from the native ABI | A profile whose handset shipped with different memory or a different ABI set is unfixably inconsistent. Device A reports 11 GB and device B 14 GB while both claimed an 8 GB Pixel 7. **A profile-selection problem, not a code problem.** |
| **Fingerprint release must agree with the build ID's era** -- AOSP names release branches by letter: `R`=11 `S`=12 `T`=13 `U`=14, then `AP*`=15 `BP*`=16 | Caught a regression introduced *while fixing the version handling*: making the release come from the device while the build ID still came from the profile produced `...:16/TQ1A.221205.011:...`. |
| **`ro.secure` is not a root indicator** -- `adbd` reads it as `GetBoolProperty("ro.secure", true)`, so an unset value behaves exactly like `1` | It was in the probe's root check on an assumption. Absent is normal, and modern AOSP no longer generates the property at all. |
| **`ro.secure` and `ro.debuggable` were empty on device B (Android 16) but `1`/`0` on device A (Android 14)** | An earlier check showed both cases as "(empty)". Absent and present-but-empty are different facts, so the probe now distinguishes them. |

### What this table cost

Three design decisions were reversed by it:

1. **A Zygisk layer** was argued for on the grounds that `/proc` and `/sys` leak the real SoC. True on Qualcomm; **the observation point does not exist on MediaTek**, so the argument does not generalise.
2. **A root layer** was argued for on the grounds that LSPosed traces are visible. That finding was **the probe matching its own APK path**. Once fixed, injection was concealed on device A, and device B's visible traces are already handled by existing concealment modules.
3. **`ro.secure` in the root check** was an assumption, not a measurement.

The pattern is consistent enough to be the point: every claim in this project that was not
checked against a real device eventually had to be revised.

---

## The one boundary the privacy layer cannot cross

Emptied data sources cover every domain backed by a **content provider** -- contacts, call log,
calendar, `MediaStore`, SMS/MMS, and the Storage Access Framework. That is not a coincidence: it
is the shape of modern Android. Scoped storage pushed apps onto `MediaStore` and SAF, so a hook on
`ContentResolver.query` sits on the road nearly everyone drives on.

`MANAGE_EXTERNAL_STORAGE` is the exception, and it is worth writing down why rather than leaving a
feature request open forever.

An app holding that permission can open `/storage/emulated/0/DCIM/x.jpg` **by path** through
`java.io.File`. No provider is involved, so `PrivacyChannel` never sees the query. Three candidate
fixes were considered and all three were rejected at this layer:

| Candidate | Why not |
|---|---|
| **Hook `java.io.File` / `FileInputStream` in the target process** | Leaky *and* dangerous. Leaky because native code does not call Java: `open()`/`fopen()` reach the kernel directly, so anything with an `.so` -- which is every app with a media or download library -- walks around it. Dangerous because the framework uses `File` in the same process for its own bookkeeping; a partial edit destabilises the app and the process hosting it. |
| **Revoke `MANAGE_EXTERNAL_STORAGE`** | This is the failure mode the whole privacy feature exists to avoid. The app hits its own permission gate and refuses to run, which is exactly the "calculator demanding my contacts" complaint. |
| **Redirect path strings** (`/storage/emulated/0` -> a private sandbox) | Strings are just data. The kernel resolves the path, not the app's string, and plenty of code addresses storage through a file descriptor or an `fdsan`-tracked handle obtained earlier. |

**What actually works is a mount namespace.** Give the target process a private mount table in
which `/storage/emulated/0` is a bind mount of one nominated folder, and the app cannot name a
path outside it -- not through `File`, not through `open()`, not through a native library. The
kernel enforces it; there is nothing to bypass. That is the "black box" the feature request asks
for, and its correct implementation site is the **native/Zygisk layer**, not LSPosed.

The consequences, stated plainly so the README is not read as promising more than it delivers:

1. Guise at the LSPosed layer currently guarantees **"anything reaching Android's data-access APIs
   comes back empty"**. Path-based direct reads are out of scope today.
2. A Guise privacy domain for `MANAGE_EXTERNAL_STORAGE` therefore covers the **SAF picker** path
   (`com.android.externalstorage.documents`, `com.android.providers.downloads.documents`) and
   nothing else. Its label and explanation say so.
3. The download-manager provider (`com.android.providers.downloads`) is deliberately **not**
   covered: emptying it would break downloading inside the target app, which is a functional
   regression disguised as a privacy win.
4. A future native module would be additive, not a replacement: provider-level emptying would stay
   as the cheap path that works without a kernel component installed.
