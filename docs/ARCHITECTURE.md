# Guise architecture

## The problem being solved

An app never observes the hardware. It observes what the platform *tells* it, and that
telling is a pipeline:

```
[producer]                      [transport]              [consumer]
hardware / init / kernel   ->   framework / libc / HAL   ->   the app
   build.prop                     SystemProperties              Build.MODEL
   kernel drivers                 /proc, /sys                   /proc/cpuinfo
   HAL configs                    binder services               SensorManager
   TEE                            keystore2                     attestation cert
```

Every layer in that pipeline is software, and every layer is writable by something. That
is the entire opportunity, and it is also the entire difficulty: **depth is not
trustworthiness.** Being closer to the hardware does not make a value true, because root
owns every layer equally. The eFuse is genuinely immutable, but nothing reads the eFuse --
it reads a string the bootloader copied out of it.

Guise inserts at three points in that pipeline. Which point a value is faked at determines
what can detect it.

## Why three layers and not one

The insertion points trade reach against detectability, and no single point wins on both:

| Insertion point | Granularity | In-process trace | Reaches |
|---|---|---|---|
| Boot-time properties / mounts | **whole device** | none | what init reads before any process exists |
| Per-process native | **per process** | library in `maps` (hideable) | libc, `/proc`, `/sys`, raw syscalls |
| Per-app Java hooks | **per app** | Xposed classes, ART entry points | Java-visible APIs only |

LSPosed is the odd one out: it is the most granular and the most exposed. The Magisk layer
is the opposite. Zygisk sits between them and is the reason the design is worth building at
all -- it can rewrite `/proc/cpuinfo` **for one process** by unsharing that process's mount
namespace, which is a reach the Java layer cannot have and a granularity the Magisk layer
cannot have.

## The tension, stated plainly

> **The root layer is global. The Java layer is per-app. These are in direct conflict.**

A naive "add root for more power" design puts identity spoofing in the root layer and
immediately loses per-app granularity -- the improvement that matters most over the original
AppEnv. Worse, it spoofs the identity of the system UI and Settings, which is both
disruptive and maximally conspicuous: a device whose own settings app does not recognise it
is a device worth a second look.

So the layers are not ranked by strength. They are **partitioned by channel ownership.**

## The contract

**A channel has exactly one owner.** This is not a stylistic preference; it is what keeps
the profile coherent.

- If both Zygisk and LSPosed faked `Build.MODEL`, they would race and the winner would
  depend on call order.
- If Zygisk rewrote `/proc/cpuinfo` to a Snapdragon part number while LSPosed reported
  `Build.BOARD` for a different SoC, the two would contradict each other -- precisely the
  incoherence this project exists to eliminate.

| Channel | Owner |
|---|---|
| `Build.*`, `Build.VERSION.*`, `Build.getSerial()` | `:xposed` |
| `SystemProperties.get*` (Java callers) | `:xposed` |
| `Settings.Secure` SSAID | `:xposed` |
| `TelephonyManager` identifiers | `:xposed` |
| `WifiInfo` | `:xposed` |
| GL vendor/renderer strings | `:xposed` |
| `Resources.updateConfiguration` | `:xposed` |
| `/proc/cpuinfo` | `:zygisk` |
| `/sys/class/net/*/address` | `:zygisk` |
| libc `__system_property_*` | `:zygisk` |
| raw `syscall()` reads of the above | `:zygisk` |
| Properties init reads before zygote | `:magisk` |
| Root / injection concealment | `:magisk` + `:zygisk` |

Handlers are encouraged to return `false` for channels they do not own. Enforcing the
boundary in code is cheaper than discovering the violation as a fingerprint mismatch.

## What each layer is actually for

### `:magisk` -- delivery, boot baseline, concealment

This is the weakest layer for spoofing and should be treated that way.

- **Global by construction.** `resetprop` affects every process. Anything set here must be
  acceptable device-wide.
- **Boot-time only.** It runs before the framework exists, so it cannot consult per-app
  config. It can only apply a baseline.
- **A bad `resetprop` bootloops the device.** This layer is the one that can brick a phone.

Its real jobs, in order of importance:

1. **Host and deliver the Zygisk module.** This is the reason it exists at all.
2. **Conceal root and injection.** Most apps that do serious device checking simply refuse
   to run on a rooted device. If root is visible, spoofing is moot -- nothing reads it.
   This is the sleeper benefit of the root layer, and it is worth more than any property
   it could set.
3. **A small, deliberately reviewed set of boot-time properties** that must exist before
   zygote starts and are safe to change globally.

### `:zygisk` -- per-process system-level spoofing

The interesting layer. It runs inside each forked app process, which means it can:

- **Unshare the mount namespace and bind-mount fake files for that process only.** A fake
  `/proc/cpuinfo` that no other process can see. The app performs an ordinary `open()` and
  reads the fake, with no hook to detect and no file on disk changed.
- **Hook at the libc layer**, below anything a Java-level integrity check can observe.
- **Survive checks that bypass libc** by hooking the syscall path itself.
- **Refuse to act** for packages that are not configured, because it knows the package name
  before the app's code runs.

### `:xposed` -- per-app Java surface

Where per-app granularity lives. It covers the Java-visible APIs, which is most of what
ordinary fingerprinting code touches, and it is the only layer that can be configured
per package trivially.

## The coherence substrate

All layers read the same `:core` model, from the same config, and therefore cannot disagree
about what a profile means.

```
                 io.guise:core
        DeviceProfile / SocProfile / EffectiveProfile
                        |
        +---------------+---------------+
        |               |               |
     :xposed         :zygisk         :magisk
   (per app)      (per process)      (boot baseline)
        |               |               |
        +------ same config blob -------+
          libxposed remote prefs / a file
          both layers can read
```

This is why `:core` is a pure-JVM module with no Android dependencies: it is the shared
definition of coherence, and its invariants are enforced by tests that run on the desktop,
not on a rooted handset.

`SocProfile` exists precisely for this. GPU renderer, platform, ABI list and codec prefixes
are properties of the *chip*. If each layer or each device entry carried its own copy, they
would drift, and drift between layers is exactly what a cross-channel check detects.

## Configuration transport

| Data | Where | Why |
|---|---|---|
| Per-app config | libxposed remote preferences | The supported modern mechanism; readable from hooked processes, writable only through the framework service |
| Device catalog | Bundled in the APK assets | Bulk data; read straight out of the module APK by zip |
| User-captured profiles | A remote file | Bulk data that changes at runtime |
| Root layer baseline | A plain file under `/data/adb/guise/` | The boot scripts are shell and cannot read remote preferences |

The root layer reads a **rendered baseline** rather than the config itself: the app decides
what is safe to apply globally and writes only that. The boot script does not interpret the
profile; it applies a decision already made.

## What no layer can do

Stated here so the design is not oversold:

- **Key Attestation.** The certificate is signed inside the TEE. `rootOfTrust` reports
  `verifiedBootState`, which comes from the bootloader, and nothing in the Android software
  stack can alter what the secure world signs.
- **Widevine L1 keys.** Also in the TEE.
- **Physical behaviour.** Sensor noise, GPU timing and memory bandwidth come from the
  silicon. A device presenting a hundred identities still has one body, and that is what
  server-side clustering looks for.

Guise changes what the platform *reports*. It does not and cannot defeat hardware-backed
attestation, and the `:probe` app reports attestation status honestly rather than pretending
otherwise.

## Build order and current state

| Layer | State |
|---|---|
| `:core` | Implemented, 22 unit tests |
| `:xposed` | Implemented, 7 channels, builds to a verified APK |
| `:app` | Implemented: Compose UI, config writer, device capture |
| `:probe` | In progress: the diagnostic harness that makes the above verifiable |
| `:magisk` | Designed; payload not yet written |
| `:zygisk` | Designed; native module not yet written (NDK 28.2 and CMake 3.22.1 are available) |
