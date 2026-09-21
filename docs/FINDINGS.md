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

## The memory field that was never read

The row above says a memory mismatch is "a profile-selection problem, not a code problem". That
was half right, and the half it got wrong is instructive.

Memory was modelled on `SocProfile` as `totalRamBytes`. Nothing read it -- not the coherence
validator, not the probe, not the picker. It was a field that documented an intent and enforced
nothing.

That was not merely inert, it was actively wrong, for a reason the SoC-level placement hides: **a
chip is not sold with one memory size.** The Snapdragon 865 shipped in 6, 8, 12 and 16 GB
handsets. Pinning memory to the chip flattened four distinct machines into one figure, and because
nine of the eleven catalog SoCs happened to carry 8 GiB, twelve of the fourteen devices inherited
it. The catalog advertised fourteen devices while covering **two** memory tiers.

The consequence was already in this document and had not been connected to it: device A reports
11 GB and device B 14 GB, and no catalog entry could serve either. The failure was not
probabilistic ("what if too many users pick the same profile") but structural -- a handset
configuration with nothing to wear, and no code path that noticed.

Three things were fixed, and they generalise:

1. **Memory moved to the device**, where it is a SKU property: `DeviceProfile.ramBytes`, the
   nominal capacity the handset shipped with. A profile per memory configuration is the correct
   granularity, even when several configurations ship byte-identical builds -- memory does not
   appear in `build.prop`, so those entries reuse the same captured values and differ only in
   `ramBytes`. Nothing is fabricated.
2. **Reported values map up, not to the nearest tier.** The kernel subtracts reserved memory
   before the platform can count it, so a 12 GB handset reports about 11. Rounding to the nearest
   tier would place that handset in the 12 GB tier by luck and an 11.4 GB one in the 8 GB tier by
   accident. The rule is "the smallest capacity not below what was reported", and it is a pure
   function with tests rather than a heuristic in the UI.
3. **An unreadable figure is `unknown`, never a match.** `Compatibility.ramIssue` returns null
   both when it agrees *and* when it could not tell. Those are different answers, and conflating
   them is how a diagnostic starts reporting reassurance it has not earned.

The catalog's coverage is now measured in tiers rather than in devices
(`DeviceCatalog.coveredRamGiB` / `uncoveredRamGiB`), because device count was the number that
looked reassuring while the 16 GB tier stood empty. A test pins that hole so it cannot be
forgotten, and the generator is meant to close it.

---

## Reading real build data out of Google's own index

Growing the catalog from fourteen hand-entered devices to a defensible number had exactly one
honest route: read shipped builds instead of transcribing specifications. Four things had to be
true, and three of them were not obvious.

**The index is static, but only on one host.** `developers.google.com/android/ota` renders its
table with JavaScript and returns a 70 KB shell containing **zero build IDs**; `?device=husky`
returns a byte-identical response. The China mirror `developers.google.cn` serves the same table
as plain HTML: on the run recorded here, **681,911 bytes, 2,092 OTA URLs, 49 device codenames,
643 distinct builds**, with marketing names in the section headings. Nothing about this is
documented; it was found by fetching both.

**Two kilobytes of a 2 GB file is the whole build identity.** An A/B OTA zip begins with a
plain-text `META-INF/com/android/metadata`, and `post-build=` in it is the complete canonical
fingerprint. A ranged GET of the first 2 KB returns, for example:

```
post-build=google/akita/akita:14/UD2A.231203.054/11501734:user/release-keys
post-build-incremental=11501734
post-sdk-level=34
post-security-patch-level=2024-03-05
```

That is every build field the catalog stores, from an artifact nobody downloaded. Factory images
do **not** contain the file -- verified by reading a factory zip's central directory -- so OTA
zips are the only usable form.

**The index host rate-limits ranged requests.** The first run got **HTTP 429 on all twelve**
attempts against `googledownloads.cn`, which looks like abuse protection and is actually the
wrong host. `dl.google.com` serves the identical path with `206 Partial Content`. The fix is a
host rewrite and nothing else, and it is the kind of failure that reads as "slow down" rather
than "you are asking the wrong server".

**Most of the older Nexus-era rows have no metadata.** 276 of 2,092 builds failed, and the
failures cluster by device rather than scattering: `ryu` 42, `shamu` 41, `sailfish` 33, `marlin`
33, `angler` 24, `bullhead` 23. All 2013-2016 hardware. Read as "the metadata convention arrived
with A/B updates", which is consistent with `ota-type=AB` appearing in every successful read.

The result: **1,816 usable builds across 42 devices and Android 8 through 17**, committed as a
1.5 MB snapshot so the catalog is reproducible without network access.

### What this cost, and what it bought

One assumption died. The obvious bulk source -- `tadiphone-buildprop-archive`, advertised as
38,250 `build.prop` files -- turns out to be **partition-split**: of its 4,701
`system.system.build.prop` files, **only 250 contain `ro.build.fingerprint` and only 9 contain
`ro.board.platform`**. The real values live in the `product`/`vendor`/`odm` partitions. A source
that looks like a corpus is not one until you open it.

A second constraint turned out to be structural rather than temporary: **the GPU renderer string
exists in no published file.** `glGetString(GL_RENDERER)` is answered by the driver at runtime, so
an SoC entry can only be created by running on one of the chips. This is what limits the catalog,
not the availability of devices -- **30 models have real builds in the snapshot and still cannot
be emitted**, because their SoC is not in the table and inventing a renderer string would produce
a profile whose silicon contradicts its identity. The generator names all 30 rather than dropping
them quietly.

The pipeline closed both gaps the earlier round had opened, including the one this file recorded
as unfixable-by-selection:

- **16 GB** was covered by a memory-variant entry, since memory appears in no build artifact and
  two SKUs of one model legitimately share a build byte for byte.
- **One build per release** replaced "repair the version token with `alignToRelease`". A build
  belongs to exactly one Android release: `alignToRelease` fixes the token, but the date inside
  the build ID and the security patch beside it still name the era they were made in. Emitting one
  entry per (device, release) removes the impossible combination instead of disguising it, and
  `Compatibility.releaseIssue` tells the user rather than letting them pick it.

### The coverage measure that finally worked

Each of these was true at some point in this project, and none of them meant what it looked like:

| Measure | Why it misled |
|---|---|
| 14 devices | Covered 2 memory tiers; 12 entries had inherited one figure from their SoC |
| 4 memory tiers covered | Still left a 16 GB handset on Android 16 with nothing to wear, because the only 16 GB entry was an Android 14 build |
| 62 entries | 30 further models have real builds available and no entry at all |

Coverage is two-dimensional -- memory tier **and** release -- and the honest measure is the empty
cells. Eight of them remain, and `16 GB/Android 16` is one: that is device B's configuration, and
it is still unserved. The generator prints the list rather than a total.

---

## The corpus that is not a corpus

A second source was measured rather than assumed: `tadiphone-buildprop-archive`, a collection of
firmware `build.prop` files from public device dumps. The first look at it was wrong in a way
worth recording, because the wrong version of the numbers was the more attractive one.

**Read only the `system` partition and the source looks dead.** Of 4,701
`system.system.build.prop` files, 250 carried `ro.build.fingerprint` and **9** carried
`ro.board.platform`. On that basis the archive is useless.

**Read every partition and it looks healthy.** Properties are split across partitions by design:
the fingerprint lives in `system`, the platform in `vendor` or `odm`. Merging all 42,287 prop
files first, and grouping them into the 5,085 dumps they came from, gives **1,487 dumps with a
fingerprint and 1,419 with a platform** as well. The lesson is not "look harder"; it is that a
per-partition file format cannot be sampled by filename, and the first measurement was of the
sampling, not of the corpus.

### What it yielded, and why it stopped there

Of those 1,419, **598 resolve to an SoC already in the table** and become entries -- 112 profiles
after collapsing to one per (device, release), covering 67 devices. The other **2,957 were
rejected for exactly one reason**, and it is the same reason the Pixel source stops at twelve
models:

> **The GPU renderer string exists in no published file.**

`glGetString(GL_RENDERER)` is answered by the driver at runtime. A `build.prop` states a platform
codename (`msmnile`, `holi`, `sm6150`, `bengal`, `mt6765`, ...) and nothing about what the silicon
reports when asked. An SoC entry can therefore only be created by running on one of the chips.
That is not a data-acquisition problem that more scraping solves; it is a property of where the
value lives. The rejected platforms are printed by frequency rather than dropped quietly, because
that list *is* the work remaining, and it is the set a contributor with the hardware could close.

### What the source cannot say, and why that is allowed

Memory, panel resolution and the GPU renderer are all absent from every `build.prop`. Memory and
resolution are left **unknown** rather than filled in, which required relaxing two earlier
decisions:

- `DisplayProfile` used to require positive width, height and density. A firmware states density
  (`ro.sf.lcd_density`, present for 4,004 dumps) and never states resolution, so "known density,
  unknown panel" is a legitimate state. Zero now means unknown, resolution must be known on both
  axes or neither, and `DisplayChannel` already did the right thing -- it never rewrites geometry
  and skips density when the value is not positive.
- A test asserted that *every* bundled entry declares memory. That was right while every entry was
  hand-entered from a spec sheet and wrong once most entries come from firmware. The invariant
  that actually matters is that **no handset configuration is left without an entry it can be
  checked against** -- which is tier coverage, and it is asserted instead.

Unknown memory sounds like a gap and is not one, for the reason this file already established:
`ramBytes` is a compatibility constraint and never a reported value, and physical memory cannot be
spoofed by any layer. A profile that declines to claim a capacity claims nothing false; the module
reports the real figure either way. What is lost is the picker's *warning*, which is why
`Compatibility` returns null for "unknown" and not for "agrees" -- the distinction that has now
paid for itself twice.

### The measure that keeps being wrong

| Measure | What it said | What was true |
|---|---|---|
| 14 devices | a catalog | two memory tiers |
| 4 memory tiers covered | full coverage | a 16 GB phone on Android 16 had nothing |
| 62 profiles | a catalog | 30 models with real builds and no entry |
| 5,085 firmware dumps | a corpus | 250 fingerprints, until every partition was read |
| 1,419 usable dumps | 1,419 devices | 67 distinct devices across 598 builds |

Every one of those numbers was true and none of them meant what it looked like. The pattern is
consistent enough to be the rule: **count the thing the user experiences** -- can a handset in
this configuration wear a profile -- and print the list of failures, not the total of successes.

---

## The hole in the middle of the diagnostic

A user reported that the module did nothing at all for Flutter apps. The mechanism turned out to
be structural, and worse, **the probe was built in a way that could not see it.**

### Why a Java-layer hook cannot cover every reader

`ro.*` values do not live in a file at runtime. `init` reads the property files once at boot,
loads them into a **shared-memory property area**, and every process maps that area read-only.
Two ways to read it:

| Route | Who uses it | Hookable from LSPosed? |
|---|---|---|
| `android.os.SystemProperties.get` | the framework, and any Kotlin/Java caller | **yes** -- it is a Java method |
| `__system_property_get` / `__system_property_read_callback` | the NDK, `getprop`, the Flutter engine, Unity, most native fingerprinting SDKs | **no** -- it is a libc function reading shared memory |

So a module can be perfectly coherent on every Java surface and completely absent for a native
reader. Nothing about that is a bug in the hooks; it is where the value lives.

**A wrong idea worth writing down**, because it is the obvious one: mount a forged
`/system/build.prop` so the property reads pick it up. It cannot work. Those files are read by
`init` **once, at boot**; the runtime value comes from the shared memory that was populated then.
Mounting a different file over the real one changes nothing for an already-running system. The
only thing that changes a native property read is a hook on the native reader itself.

### Why the probe could not see it

`Props.get()` tried reflection into `SystemProperties` and **fell back** to `getprop` only if
reflection failed. With a hook installed, reflection succeeds -- returning the rewritten value --
so `getprop` was never consulted. And `PropertyMirrorCheck` compares the Java `Build.*` fields
against the Java property route, so **both of its sides pass through the same hooked method**:
they agreed with each other, and disagreed with reality, and the check reported COHERENT.

That is the shape of the mistake worth remembering. The diagnostic was not missing a
measurement; it was *structurally incapable* of the measurement, in a way that produced a
passing verdict rather than an error.

### The fix

The two routes are now two first-class readings, and `NativePropertyCheck` compares them
key by key. `getprop` is a world-executable binary that reads the property area directly, so
running it in a subprocess is how an ordinary app asks the question that matters. Three outcomes,
kept distinct on purpose:

- **coherent** -- native readers see the spoof too;
- **incoherent** -- they see the truth, and the check names every field that differs, so "the
  module only covers Java" becomes a list rather than a suspicion;
- **unsupported** -- `getprop` produced nothing, which is *not* the same as agreement.

The check also carries the correction above in its own output, since a user who sees it is
exactly the user who would otherwise reach for the forged-`build.prop` idea.

### What this means for the layering

Two native-layer jobs now exist, and they are different mechanisms that happen to want the same
delivery vehicle:

1. **Native property hook** -- inline or PLT hook of `__system_property_get` inside the target
   process, for every reader that is not Java.
2. **Mount namespace** -- bind-mount a confined view of shared storage, for the file-access
   problem the provider-level hooks cannot reach.

Neither belongs in LSPosed, and both belong in one optional Zygisk module. The earlier round's
research already established the shipping shape for the second (a root companion that `setns`es
into the target's mount namespace); the first is the same kind of in-process native work.

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
