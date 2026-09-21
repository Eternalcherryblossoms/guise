# Guise · 拟态

**简体中文** · [English](#english)

> 按**设备档案**而不是单个字段重写系统上报的硬件信息，让各通道之间保持自洽。
>
> Rewrites what the platform reports about the hardware, driven by a **coherent device
> profile** rather than a bag of independently editable fields.

---

<a id="简体中文"></a>

# 简体中文

## 这是什么

安卓应用从来不直接观察硬件，它观察的是**系统告诉它的东西**。而这条"告知链"上的每一层都是软件，都可以被改写。

多数同类工具的做法是：给你一张字段表，你自己填 `Build.MODEL`、`Build.BRAND`、`Build.DEVICE`……各自独立。结果是很容易造出一台"指纹说小米、GPU 说 Pixel、产品代号说三星"的设备——**而跨通道矛盾正是指纹识别 SDK 最容易抓的东西**。

Guise 的做法不同：

> **一台被伪装的设备是一个完整自洽的档案，不是一个字段集合。**

档案里派生出来的值（指纹、构建号年代）是**计算**出来的，不是**存储**的，所以它不可能和别的字段打架。

## 功能

### 覆盖的通道（9 个）

| 通道 | 覆盖内容 |
|---|---|
| `Build.*` | 品牌、厂商、型号、设备代号、产品代号、主板、硬件、指纹、引导器、显示版本、构建 ID、标签、类型、构建主机、构建用户、构建时间 |
| `Build.VERSION.*` | release、增量版本、安全补丁级别 |
| `Build.getSerial()` | 序列号（真正被调用的那个入口，不是那个自 API 26 起恒为 `UNKNOWN` 的常量） |
| `SystemProperties` | 23 个 `ro.*` 键，覆盖 `get` / `getInt` / `getLong` / `getBoolean` —— 很多完整性检查直接读属性而不读 `Build` |
| `Settings.Secure` | SSAID（`ANDROID_ID`），按档案稳定合成 |
| GPU | `GLES20/30/31.glGetString` 的 vendor 与 renderer —— 由真实驱动提供，是最便宜的交叉校验点 |
| 电话 | IMEI / MEID，合成且通过 Luhn 校验 |
| WiFi | `WifiInfo.getMacAddress`、`NetworkInterface.getHardwareAddress` |

两个**需手动开启**的附加通道（默认关闭，理由见下）：

| 通道 | 说明 |
|---|---|
| 编解码器厂商前缀 | `MediaCodecList` 的组件名带芯片厂商前缀（`c2.mtk.` / `c2.qti.`），来自 vendor 配置，不覆盖就会暴露真实芯片。**列表只能过滤不能增加**，隐藏后可能让目标应用找不到它需要的解码器（表现为播放异常），所以做成逐应用开关 |
| 屏幕密度 | 改写密度会改变目标应用的布局。分辨率完全不改——`DisplayMetrics` 有太多读取路径不经过 `Resources.updateConfiguration`，半改比不改更糟 |

### 隐私数据（默认关闭）

有些应用要的权限和它的功能毫无关系——一个计算器非要读通讯录，不给就不让用。**撤销权限解决不了这个问题**：应用会在自己的权限门上直接拒绝运行。

所以 Guise 反过来做：

> **权限真的授予（你在系统设置里点允许），但数据源被掏空。应用正常启动，然后读到 0 条。**

每一项在管理界面里是**独立开关**，逐个应用配置（入口：应用详情页 → 「隐私数据」卡片）。

| 数据 | 需要授予的权限 | 应用读到什么 |
|---|---|---|
| **通讯录** | `READ_CONTACTS` | 0 条联系人 |
| **通话记录** | `READ_CALL_LOG` | 0 条记录。**建议与通讯录同时开启**——只清空联系人却留着通话记录，等于告诉对方「这个人有来电但没有联系人」 |
| **日历** | `READ_CALENDAR` | 0 个日历、0 条日程 |
| **相册** | `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO`（Android 13 起拆成三个，都要授权） | 0 张图片、0 个视频、0 首音频 |
| **短信** | `READ_SMS` | 0 条短信与彩信。**只拦「读取」**——短信送达是系统推给应用的，hook 不到，所以等验证码的应用照样收得到 |
| **文件（系统选择器）** | `MANAGE_EXTERNAL_STORAGE` | 通过系统文件选择器（SAF）浏览时看到 0 个文件，含「下载」目录 |

**为什么是空数据而不是可信假数据**：空的通讯录是一种**常见状态**（真有人通讯录是空的），所以**没有东西可以被交叉比对**。而伪造的联系人只有在**完全自洽**时才有意义——姓名要符合地区、号码段要对应伪装的定位、还要和通话记录呼应。那需要一套「人格档案」和地区语料库，是下一步而不是这一步。

### 关于「所有文件访问权限」

这是最常被问的一项，也是 Guise 目前**没有完全解决**的一项，说清楚边界比含糊承诺重要：

- **能做到的**：走**内容提供者**（content provider）的路径全部被掏空。现代应用读共享存储基本都走这条路——分区存储（scoped storage）把它们逼到了 `MediaStore` 和 SAF，所以相册、SAF 文件浏览器读到 0 条。
- **做不到的**：持 `MANAGE_EXTERNAL_STORAGE` 的应用**直接按路径**用 `java.io.File` 打开 `/storage/emulated/0/...`。这条路径根本不经过提供者，所以这里拦不到。
- **为什么不在这里硬拦**：在 Java 层替换 `File` 的读取既**漏**（原生代码绕过，`open()`/`fopen()` 走的是另一套）又**危险**（框架自己在同一进程里也用 `File`，改坏了是整个系统不稳）。真正的「黑盒」——把应用关进一个只剩指定文件夹的**挂载命名空间**——是原生层（Zygisk/内核）的活，不是 LSPosed 层的活。

也就是说：**「把应用关进一个只能看见指定外部文件夹的黑盒」这个想法是对的，但它的正确实现位置在挂载层**。Guise 目前在 LSPosed 层做到的是「凡是经过 Android 数据访问 API 的，都掏空」；按路径直读的留给后续的原生模块，路线记在 `docs/FINDINGS.md`。

> 有人会问：那我不给「所有文件访问权限」不就行了？——**不行，这就是这个功能存在的理由**。撤掉权限，应用会卡在自己的权限门上不让你用；而授予权限再掏空数据源，应用才会**正常启动然后读到 0 条**。

**已知风险**：假设至少有一行的应用（`cursor.moveToFirst()` 后不判空就 `getString()`）会崩溃。这类应用在真实用户通讯录为空时同样会崩，是应用自己的 bug——但用户会认为是 Guise 弄坏的。

### 一致性保证

1. **指纹是派生的，不是存储的** —— `BRAND/PRODUCT/DEVICE:RELEASE/ID/INCREMENTAL:TYPE/TAGS` 由其它字段算出来，不可能自相矛盾
2. **版本与构建号年代取自运行设备** —— API 级别不能安全改写（改了会让应用走本机不存在的代码路径而崩溃），所以 release 必须跟它一致，构建号的版本代号也必须跟着走。这避免了 `Android 16 配 Android 13 构建号` 这种一眼可见的破绽
3. **档案按 SoC 复用** —— GPU、平台、ABI、编解码器前缀都属于芯片，不是机型。几十个 SoC 覆盖上千机型，且共享芯片的机型**自动共享**那些必须一致的值

### 按应用粒度

每个应用独立配置。作用域**按需增长**：你在界面里选了一个目标，模块才通过 `XposedService` 把它加进 LSPosed 作用域——而不是预先勾选一堆你没打算碰的应用。

### 机型库

内置 **174 个档案 / 11 个 SoC / Android 11–17**，全部来自真实构建数据，没有一条是编的：

| 来源 | 条数 | 怎么来的 |
|---|---|---|
| Google 官方 OTA 元数据 | 53 | 直接读 OTA 包头部 2 KB 里的 `post-build`（第一手） |
| 固件 `build.prop` 转储 | 112 | 厂商固件里自己的属性（第三方提取） |
| 手工录入 | 9 | 早期条目 |

每个档案是「一台机型 + 一个 Android 版本 + 一个内存配置」：

- **一个版本一个档案** —— 构建号属于唯一一个 Android 版本。`alignToRelease` 只能修版本代号，构建号里的**日期**和安全补丁仍然属于它们出厂的那个年代——把 Android 12 的档案穿在 Android 16 上，描述的是一台从没出厂过的机器。所以分开成档案，管理端也会直接告诉你哪些不相容。
- **一个内存配置一个档案** —— 内存不出现在任何构建产物里，所以同一机型的不同内存版本**共用同一份构建**，只有标称容量不同。
- **内存可以「未知」** —— 固件属性里根本没有内存。这不影响伪装：**内存不是被上报的值，而是相容性约束**，任何一层都改不了它，所以「不声明」不等于「声明错了」。少掉的只是预筛选时的告警。密度同理（能从 `ro.sf.lcd_density` 拿到就带上，拿不到就留空，不猜）。
- **抓取本机** —— 从真实设备读取全部字段生成档案，100% 准确，记录的 RAM 也是真的。

数据来源与逐条出处见 [`catalog/PROVENANCE.md`](catalog/PROVENANCE.md)（生成物）。每一条都写明来源，并标注**是否经过核实**：174 条里 120 条未核实——它们读自真实固件，但经的是第三方提取而非厂商自己的渠道，PROVENANCE 里明确区分这两类。**这不是形式主义**——凭空编的 build ID 会造出「世界上只有你有」的指纹，比用一个常见的真指纹更显眼。

机型库是**生成**的，不是手写的（见「从源码构建」），并且**支持远程更新**（见「下载与更新」）。生成器会打印还有哪些覆盖空洞：目前 **8 个「内存档 × 版本」格子是空的**，其中 `16 GB / Android 16` 一台可穿的都没有。

**为什么不是更多**：还有 2,957 个固件转储被拒收，原因只有一个——它们的芯片不在表里，而 **GPU renderer 字符串不存在于任何已发布文件里**（`glGetString(GL_RENDERER)` 是驱动在运行时回答的）。一个 SoC 条目只能靠在真机上跑过一次来创建。生成器把这批芯片按出现次数列出来（`msmnile` 274、`holi` 213、`sm6150` 194……），而不是假装它们不存在。

### 诊断探针（独立应用）

这是本项目最特别的部分。**它不做真假判断**——没有客户端能做到，这正是本项目的核心论点。它测量两件客户端**能**测的事：

| 检查 | 作用 |
|---|---|
| **哈希聚合（三源交叉）** | 同一批事实分别从 Java API、`SystemProperties`、`/proc`+`/sys` 读三遍并各自哈希。哪个哈希不等，就**指名道姓**说出哪条通道没跟上——这测的是覆盖面，不需要机型数据库 |
| Java / 属性层一致性 | 逐字段对拍。只 hook 了 Java 层、属性层漏了——伪装模块半残时最常见的形态 |
| 指纹结构自洽性 | 格式、分段、release↔API 级别、release↔构建号年代 |
| 内核 SoC vs 框架声明 | `/sys/devices/soc0/machine` 由内核提供，Java 层 hook 够不着 |
| 编解码器厂商前缀 | 列出实际存在的厂商前缀，以及与声明芯片是否矛盾 |
| 注入痕迹可见性 | `/proc/self/maps` 可疑条目（报**实际路径**，不是 token）、Hook 框架类 |
| Root 痕迹 | `su` 路径、Root 管理器包、挂载表、`ro.boot.*` |
| 物理事实 | 内存、ABI、核心数、主频——并指出这些**无法伪装** |
| 硬件证明状态 | 明确标注天花板在哪 |
| **基线对照** | 首次运行记录身份哈希，之后对比。**"模块到底生效了没"这个问题没有基线就无法回答** |
| 报告导出 | 一键复制全文，便于反馈 |

### 关于 root

> **Guise 自己不需要 root。** 它不调用 `su`，不申请 root 权限，只有 LSPosed 一层。
>
> 但它运行在 LSPosed 之上，而 LSPosed 需要一个已解锁 bootloader 并已 root（Magisk / KernelSU）的设备。

经过真机验证，**没有加入 root 层和 Zygisk 层**。详细理由见 [`docs/FINDINGS.md`](docs/FINDINGS.md)——简言之：已有的隐藏模块生态做得更好，重造只会与它们冲突；而 Zygisk 的必要性是**平台相关**的（高通有观测点，联发科没有）。

## 前置要求

| 项 | 要求 |
|---|---|
| Android 版本 | **9.0 及以上**（API 28+） |
| 设备状态 | 已解锁 bootloader |
| Root | Magisk 或 KernelSU（**因为 LSPosed 需要**，不是 Guise 需要） |
| 框架 | **LSPosed**，API 101 及以上（即较新的版本） |
| 安装包 | `Guise-*.apk`（模块 + 管理界面）；`GuiseProbe-*.apk`（探针，**可选但强烈建议**） |
| 从源码构建 | JDK **17–21**、Android SDK **platform 36**、**build-tools 36.0.0** |

## 安装

1. 从 [**Releases**](https://github.com/Eternalcherryblossoms/guise/releases) 下载两个 APK
2. 安装 `Guise-*.apk`（模块本体 + 管理界面）
3. 安装 `GuiseProbe-*.apk`（探针，用于验证效果）
4. 打开 **LSPosed 管理器 → 模块 → 启用 Guise**
5. 重启手机（让模块加载）——**只需这一次**

> 当前发布的是 debug 构建，包名为 `io.guise.debug` 和 `io.guise.probe.debug`。

## 使用

### 1. 选目标应用

打开 **Guise** → 右下角 **`+`** → 从应用列表里选一个要伪装的目标。

### 2. 选机型档案

从内置机型库里挑一台。挑选时注意两点：

- **Android 版本会取自你的设备**（不是档案的），所以版本差异不会造成矛盾
- **但内存容量和 ABI 列表无法伪装**，必须尽量匹配。例如你的设备是 12GB 而档案机型只有 8GB，带机型库的 SDK 可以直接识破

也可以点右下角 **「抓取」** 把本机存为档案——这是最可靠的做法。

### 3. 重启目标应用

**强制停止并重新打开那个应用**（不是重启手机）。配置是热更新的，但已经加载的类不会回退。

### 4. 验证效果（重要）

1. 回到 Guise，把 **`io.guise.probe.debug`**（探针）也加为目标应用
2. 打开探针，等它采集完成
3. 看两个数字：**「不一致 N 项」** 和 **「痕迹暴露 N 项」**

**理想结果是两个都是 0。** 不一致意味着平台自相矛盾（配置有问题）；痕迹暴露意味着改写机制本身被看见了。

> 探针第一次运行会记录基线。之后每次运行都会告诉你**具体哪些字段发生了变化**——这是"模块到底生效了没"唯一可靠的判据。

### 5. 反馈

探针页有 **「复制报告」**。带上这份报告开 issue，比只说"没效果"有用得多。

## 它不做什么

| | 为什么 |
|---|---|
| **不伪造硬件证明（Key Attestation）** | 证书在 TEE 内签名，`rootOfTrust` 由 bootloader 提供。**任何 hook 都够不到**。Play Integrity 的强完整性判定就在这一层 |
| **不伪造物理事实** | 内存容量（`ActivityManager` 走 binder）、ABI 列表（由 native ABI 派生）、核心数、主频——都改不了 |
| **不改 SIM 相关标识** | IMSI / SIM 序列号 / 运营商码属于 SIM 卡，不是手机。档案里没有运营商身份，编出来的值只会和真实 SIM 矛盾 |
| **不改 SSID / BSSID** | 那是网络环境，不是设备属性 |
| **不伪造传感器噪声、GPU 时序** | 那是硅片的物理特性。一台设备伪装成一百台，身体还是只有一副——这正是服务端聚类能抓到的原因 |

**一句话**：Guise 改变的是平台**上报**什么，不是**事实**是什么。

## 下载与更新

最新版在 [**Releases**](https://github.com/Eternalcherryblossoms/guise/releases)。资产由 CI 在每次打 tag 时构建。

应用内可检查更新（**关于** 页）。没有服务器、没有账号、没有推送通道——安卓上真正的推送意味着 Firebase、Google 服务和一个常驻设备标识符，**在一个专门用来少给标识符的模块里放这个是自相矛盾的**。

更新检查是**一次未认证的 HTTPS GET**，无设备标识、无账号、无参数，且只在打开「关于」页时才发起。

> ⚠️ **仅在仓库公开时有效。** 私有仓库的 Releases API 对未认证请求返回 404，应用会静默显示"未发现新版本"。

## 从源码构建

```bash
# 需要 JDK 17-21
./gradlew :core:test :app:assembleDebug :probe:assembleDebug
```

产物：`app/build/outputs/apk/debug/Guise-debug-*.apk`

**发布版本**：推送一个 `v*` tag，CI 会自动构建并发布。

```bash
git tag v5.2.0 && git push origin v5.2.0
```

### 机型库是生成出来的，不是手写的

`xposed/src/main/assets/catalog.json` **由 `:catalog-gen` 从 `catalog/seed/` 生成**。改机型库请改种子文件，然后重新生成：

```bash
./gradlew :catalog-gen:run --args="--write"    # 重新生成
./gradlew :catalog-gen:run --args="--check"    # CI 的门禁：对不上就红
```

**两半式流水线**：抓取与生成分开。联网/批量的一半是独立任务，产出提交进仓库的快照；生成器本身**永远离线、确定性**——所以 `--check` 才有意义，所以没有网络也能复现整个机型库。

```bash
# 第一半（需要网络 / 需要语料库，手动跑）
./gradlew :catalog-gen:fetchPixel -Pproxy=host:port          # → catalog/raw/pixel-ota.json
./gradlew :catalog-gen:extractBuildProps -Pargs="--dir <语料库> --override kona=sm8250,taro=sm8450"
                                                             # → catalog/raw/buildprops.json
# 第二半（永远离线）
./gradlew :catalog-gen:run --args="--write"
```

生成器有三道**不可跳过**的闸：

1. **每条都必须交代出处**（`source` 字段）。凭空的 build ID 会造出「世界上只有你有」的指纹，比用一个常见的真指纹**更**显眼——所以这一条是硬性的。
2. **过 `:core` 的同一套一致性校验器**（就是那 64 个测试用的那套）。生成器不带自己的规则副本——否则它会用自己的错误规则给自己的错误放行。
3. **覆盖策略**：必须覆盖的内存档位写进 `policy`；暂时覆盖不了的要带**书面理由**豁免；故意不管的也要写理由。设备数量不是指标——「14 台设备只覆盖 2 个内存档」正是这个指标被发明出来的原因。

每条目的出处汇总在 `catalog/PROVENANCE.md`（同样是生成的），里面会明确写出**有多少条是未经核实的**。

### 远程机型库更新

机型库可以**不发版就更新**：CI 把 `catalog.json` 作为 release 附件发布，文件名是 `catalog-<sha256>.json`——**完整摘要写在文件名里**，所以客户端不需要再取一份清单来信任，也不可能把半个下载当成机型库。

四道闸，任何一道不过就**拒绝整份**、继续用内置那份：

1. 文件名里的 SHA-256 与下载到的字节重新计算的摘要必须一致；
2. 格式版本必须被本版本理解（更新的格式可能带来本版本会读错的字段，而读错一个设备身份比留在旧数据上更糟）；
3. 整份机型库必须通过**同一套一致性校验器**——不是「大体没问题」，因为一条自相矛盾的条目就是一个观察者能抓住的指纹；
4. 先写文件、后写描述符；而读取时先看描述符、再开文件。所以写到一半失败留下的描述符指向的是上一份机型库，哈希对不上。

三个来源通过 `:core` 里**同一个函数**合并，管理端和注入进程各调一次同一个实现——两份实现会漂移，而漂移的症状是「选择页提供了注入进程从没听说过的档案」。

## 项目结构

```
core/        纯 JVM：档案模型、机型库、内存档位与相容性判定、配置编解码。无 Android 依赖，可在桌面单测
xposed/      LSPosed 层：模块入口、9 个通道、机型库 asset、META-INF/xposed 描述符
app/         Compose 管理界面、配置写入、机型抓取、更新检查
probe/       诊断探针（独立应用）
catalog/     机型库种子 + 生成的出处说明
catalog-gen/ 构建期工具：从种子生成机型库，并做出处与覆盖门禁（不随 APK 发布）
docs/        架构说明与实测发现
```

`core` 刻意不依赖 Android：**最容易出错的那部分逻辑，不需要一台 root 过的手机就能验证。**

## 常见问题

**选了档案但目标应用没变化？**
确认 LSPosed 里已启用 Guise、且该应用在模块作用域内，然后**强制停止并重开目标应用**。

**某些应用（尤其 Flutter / Unity / 带原生 SDK 的）完全没效果？**

大概率是**原生属性读取**这条路没被覆盖，而这是 Java 层 hook 结构上做不到的事。

`ro.*` 在运行时**不在文件里**：`init` 开机时读一次属性文件，填进一块**共享内存属性区**，之后每个进程只读映射它。两条读取路径：

| 路径 | 谁在用 | LSPosed 能拦吗 |
|---|---|---|
| `android.os.SystemProperties.get` | 框架、所有 Java/Kotlin 调用方 | **能**——它是个 Java 方法 |
| `__system_property_get` | NDK、`getprop`、**Flutter 引擎**、Unity、大多数原生指纹 SDK | **不能**——它是 libc 函数，直接读共享内存 |

所以模块可以在每一个 Java 表面上完全自洽，而对原生调用方完全不存在。

**先测，别猜**：把探针也加进 Guise 的作用域并选一个档案，然后看探针的 **「Java 属性 vs 原生属性」** 这一项。它逐字段告诉你原生调用方看到的是伪装值还是真值——注意这一项和「Java 层 vs 属性层」是两回事，后者的两侧都走同一个可被 hook 的 Java 方法，所以它们会互相一致而同时和现实不一致。

- **两端一致** → 这个应用的问题不在原生路径，在别处（作用域、进程、多进程）；
- **不一致** → 确认了，需要原生层 hook。

**一个看着可行、其实无效的办法**：把伪造的 `build.prop` 挂载到 `/system`。**它不可能有用**——那些文件只在**开机时被 `init` 读一次**，运行时的值来自内存，不来自文件。对已经启动的系统挂载假文件，什么都不会变。唯一能改原生属性读取的，是在**原生读取函数本身上**打 hook。

这正是为什么需要一个可选的 Zygisk 模块：它要干两件不同的原生层工作——**hook `__system_property_get`**（覆盖 Flutter 这类原生读取），和**挂载命名空间**（覆盖「所有文件访问权限」那类按路径直读）。两者都不属于 LSPosed 层。

**探针报「内核 SoC 描述不一致」？**
高通设备上已知。`/sys/devices/soc0/machine` 会说出真实芯片（如 `Snapdragon`）。当前版本没有覆盖它——需要 Zygisk 层在进程私有 mount namespace 里伪造该文件。**临时办法：选一个同平台的档案。**

**探针报内存或 ABI 不符？**
这两项无法伪装。**5.5 起管理端会主动标出来**：机型选择页按本机实测的内存档位和 ABI 给每个档案标注「相容 / 与本机不符」，并显示「本机可穿的档案：N / 总数」。选一个匹配的即可——若一个都没有，那是机型库的覆盖缺口，不是你的配置问题。

**探针报「注入痕迹可见」？**
`/proc/self/maps` 里出现了 Zygisk/LSPosed 的 `.so` 路径。装一个隐藏模块（如 Shamiko）即可。

**更新检查没反应？**
仓库为私有、尚无 Release、或当前无网络——三种情况表现相同。

## 许可

LGPL-3.0，见 [LICENSE](LICENSE)。本项目是对 [kingsollyu/AppEnv](https://github.com/kingsollyu/AppEnv) 的重建，沿用其许可证；**没有共享任何代码**。

---

<a id="english"></a>

# English

## What this is

An Android app never observes the hardware. It observes **what the platform tells it**, and
every layer of that telling is software, and every layer is writable.

Most tools in this space hand you a field table: set `Build.MODEL`, `Build.BRAND`,
`Build.DEVICE`, each independently. The result is easy to get wrong -- a device whose
fingerprint says Xiaomi, whose GPU says Pixel and whose product codename says Samsung. That
kind of cross-channel contradiction is exactly what a fingerprinting SDK looks for.

Guise takes a different approach:

> **A spoofed device is a complete, self-consistent profile -- not a set of fields.**

Values implied by other values (the build fingerprint, the era of the build ID) are *computed*
rather than *stored*, so they cannot contradict anything.

## Features

### Channels covered (9)

| Channel | What it covers |
|---|---|
| `Build.*` | brand, manufacturer, model, device, product, board, hardware, fingerprint, bootloader, display, build ID, tags, type, host, user, build time |
| `Build.VERSION.*` | release, incremental, security patch level |
| `Build.getSerial()` | the accessor apps actually call, not the constant that has read `UNKNOWN` since API 26 |
| `SystemProperties` | 23 `ro.*` keys across `get` / `getInt` / `getLong` / `getBoolean` -- plenty of integrity checks read properties rather than `Build` |
| `Settings.Secure` | SSAID (`ANDROID_ID`), synthesised stably per profile |
| GPU | `GLES20/30/31.glGetString` vendor and renderer -- supplied by the real driver, which makes it the cheapest cross-check available |
| Telephony | IMEI / MEID, synthesised and Luhn-valid |
| Wi-Fi | `WifiInfo.getMacAddress`, `NetworkInterface.getHardwareAddress` |

Two further channels are **opt-in per target** and off by default:

| Channel | Why it is opt-in |
|---|---|
| Codec vendor prefixes | `MediaCodecList` names its components with the silicon vendor (`c2.mtk.`, `c2.qti.`), and that list comes from vendor configuration, so nothing else hides the real chip. But the list can only be **filtered, never extended**, and hiding a codec an app needs shows up as broken playback. |
| Screen density | Rewriting density changes the target's layout. Resolution is not touched at all: `DisplayMetrics` is read from too many paths that never pass through `Resources.updateConfiguration`, and a half-changed display is worse than either extreme. |

### Privacy data (off by default)

Some apps ask for permissions that have nothing to do with what they do -- a calculator that
demands the address book and refuses to start otherwise. **Revoking the permission does not
solve that**: the app simply fails its own permission gate.

So Guise does the opposite:

> **The permission really is granted** (you tap allow in Android settings) **and the data source
> is emptied.** The app starts normally and then finds zero rows.

Each one is an **independent switch**, configured per app (entry point: app detail screen ->
"Privacy data" card).

| Data | Permission to grant | What the app reads |
|---|---|---|
| **Contacts** | `READ_CONTACTS` | zero contacts |
| **Call log** | `READ_CALL_LOG` | zero entries. **Turn this on alongside contacts** -- emptying contacts while leaving the call log says "this person receives calls but knows nobody" |
| **Calendar** | `READ_CALENDAR` | zero calendars, zero events |
| **Media** | `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO` (split into three since Android 13 -- grant all of them) | zero images, zero videos, zero audio |
| **SMS** | `READ_SMS` | zero texts and MMS. **Reads only** -- delivery is pushed to the app by the system and cannot be hooked, so an app waiting for a verification code still receives it |
| **Documents (system picker)** | `MANAGE_EXTERNAL_STORAGE` | zero files when browsing through the Storage Access Framework, including the Downloads folder |

**Why empty rather than plausible fake data:** an empty address book is an *ordinary state* --
plenty of real people have one -- so there is nothing to cross-check. A fabricated contact list
is only better if it is fully coherent: names matching the region, dialling codes matching the
faked location, consistent with the call log beside it. That needs a persona model and a region
corpus, which is the next step rather than this one.

### About "All files access"

This is the most frequently asked item and the one Guise does **not** fully cover yet. Stating the
boundary plainly beats a vague promise:

- **What works:** everything that goes through a **content provider** is emptied. Modern apps
  reach shared storage that way almost by default -- scoped storage pushed them onto `MediaStore`
  and the Storage Access Framework -- so galleries and SAF file browsers read zero rows.
- **What does not:** an app holding `MANAGE_EXTERNAL_STORAGE` that opens
  `/storage/emulated/0/...` **by path** through `java.io.File`. That path never touches a
  provider, so nothing here sees it.
- **Why it is not forced here:** replacing `File` reads at the Java layer is both **leaky**
  (native code bypasses it -- `open()`/`fopen()` are a different route) and **dangerous** (the
  framework uses `File` in the same process; breaking it destabilises everything). The real
  "black box" -- a **mount namespace** in which the app can only see one nominated folder -- is
  native-layer work (Zygisk/kernel), not LSPosed-layer work.

In other words: **"confine the app to a black box containing only a nominated external folder" is
the right idea, but its correct implementation site is the mount layer.** What Guise does today at
the LSPosed layer is "anything reaching Android's data-access APIs comes back empty"; path-based
direct reads are left to a future native module, with the route recorded in `docs/FINDINGS.md`.

> The obvious question: can I just not grant "All files access"? -- **No, and that is exactly why
> this feature exists.** Take the permission away and the app stops at its own permission gate and
> refuses to run; grant it and empty the data source, and the app **starts normally and then reads
> zero rows**.

**Known risk:** an app that assumes at least one row (`cursor.moveToFirst()` then `getString()`
with no null check) will crash on an empty cursor. Such an app also crashes for a real user with
an empty address book, so the bug is the app's -- but the user will blame Guise.

### Coherence guarantees

1. **The fingerprint is derived, not stored.** `BRAND/PRODUCT/DEVICE:RELEASE/ID/INCREMENTAL:TYPE/TAGS`
   is computed from the other fields, so it cannot disagree with them.
2. **Version and build-ID era come from the running device.** The API level cannot be safely
   rewritten -- raising it makes an app take code paths this ROM does not have, and lowering it
   hides real ones, both of which crash -- so the release has to match it, and the build ID's
   version token has to follow. This is what prevents a fingerprint like
   "Android 16 carrying an Android 13 build ID".
3. **Profiles are shared at the SoC level.** GPU renderer, platform, ABI list and codec prefixes
   belong to the chip, not the handset. A few dozen SoCs cover most phones, and every device
   sharing a chip automatically shares the values that must agree with it.

### Per-app granularity

Each target is configured independently. Scope **grows on demand**: the module adds a package
to LSPosed's scope through `XposedService` only when you configure it, rather than pre-selecting
apps you never meant to touch.

### Device catalog

**174 profiles across 11 SoCs and Android 11-17**, every one built from real build data:

| Source | Count | How |
|---|---|---|
| Google's own OTA metadata | 53 | read from `post-build` in the first 2 KB of the shipped OTA zip (first-party) |
| Firmware `build.prop` dumps | 112 | the vendor's own properties, via a third party's extraction |
| Hand-entered | 9 | early entries |

One profile is "one model + one Android release + one memory configuration":

- **One entry per release.** A build belongs to exactly one Android release. `alignToRelease` can
  repair a build ID's version token, but the *date* inside it and the security patch beside it
  still belong to the era they were made in -- an Android 12 profile worn on an Android 16 handset
  describes a machine that never shipped. So they are separate entries, and the picker says which
  ones do not fit.
- **One entry per memory configuration.** Memory appears in no build artifact, so two memory SKUs
  of one model share a build byte for byte and differ only in nominal capacity.
- **Memory may be unknown.** A firmware's properties state no memory at all, and that costs
  nothing: `ramBytes` is a *compatibility constraint, not a reported value* -- no layer can spoof
  physical memory, so declining to claim a capacity claims nothing false. Only the picker's
  warning is lost. Density is the same: taken from `ro.sf.lcd_density` when the firmware sets it,
  left empty otherwise rather than inferred.
- **Capture this device** -- reads every field from the real handset, including its memory.

Per-entry provenance is in [`catalog/PROVENANCE.md`](catalog/PROVENANCE.md), which states its
source and whether it is **verified**: 120 of the 174 are not. They are read from real firmware,
but through a third party's extraction rather than the vendor's own channel, and that file draws
the distinction. It is not bookkeeping -- an invented build ID yields a fingerprint matching no
shipped handset, and unique is more conspicuous than common.

The catalog is **generated, not written** (see "Building from source") and **can be refreshed
without shipping an APK** (see "Download and updates"). The generator also prints the coverage
holes: **8 (memory tier x release) cells are still empty**, including `16 GB / Android 16`, for
which no profile fits at all.

**Why not more:** 2,957 further firmware dumps were rejected for exactly one reason -- their chip
is not in the table, and the **GPU renderer string exists in no published file**, because
`glGetString(GL_RENDERER)` is answered by the driver at runtime. An SoC entry can only be created
by running on one of the chips. The generator lists those chips by frequency (`msmnile` 274,
`holi` 213, `sm6150` 194, ...) rather than pretending they are not there.

### The probe (a separate app)

This is the part of the project that is genuinely unusual. **It makes no truth judgements** --
no client can, which is the whole thesis. It measures the two things a client *can* measure:

| Check | Purpose |
|---|---|
| **Aggregate hash (three sources)** | The same facts are read from the Java APIs, from `SystemProperties`, and from `/proc`+`/sys`, and hashed separately. Where the hashes differ it **names the channel that failed to keep up** -- a coverage measurement that needs no device database |
| Java vs property mirror | Field-by-field. Patching the Java surface while leaving the property surface is the most common way for one of these modules to be half-working |
| Fingerprint shape | Format, segment counts, release vs API level, release vs build-ID era |
| Kernel SoC vs framework claim | `/sys/devices/soc0/machine` comes from the kernel and a Java hook cannot reach it |
| Codec vendor prefixes | Lists the prefixes actually present and whether they contradict the claimed chip |
| Injection visibility | Suspicious entries in `/proc/self/maps` (**actual paths**, not vendor tokens) and framework classes on the classpath |
| Root traces | `su` paths, root manager packages, mount table, `ro.boot.*` |
| Physical facts | RAM, ABI list, core count, clock -- and states plainly that these **cannot be spoofed** |
| Attestation status | Makes the ceiling explicit rather than implying it can be moved |
| **Baseline** | Records an identity hash on first run and diffs later. **"Did the module do anything?" cannot be answered without one** |
| Report export | One tap to copy the whole thing for a bug report |

### About root

> **Guise does not need root.** It never calls `su` and requests no root permission. There is
> one layer: LSPosed.
>
> It runs *on* LSPosed, and LSPosed needs an unlocked bootloader and a rooted device
> (Magisk or KernelSU).

A root layer and a Zygisk layer were designed and then **dropped on evidence** from real
hardware. The reasoning is in [`docs/FINDINGS.md`](docs/FINDINGS.md); briefly: the existing
concealment ecosystem does that job better and rebuilding it would only fight it, and the case
for Zygisk turns out to be **platform-dependent** (Qualcomm exposes the observation point,
MediaTek does not).

## Prerequisites

| | Requirement |
|---|---|
| Android | **9.0 or later** (API 28+) |
| Device | Bootloader unlocked |
| Root | Magisk or KernelSU -- **because LSPosed needs it, not because Guise does** |
| Framework | **LSPosed**, API 101 or newer |
| Packages | `Guise-*.apk` (module + management UI); `GuiseProbe-*.apk` (probe, optional but strongly recommended) |
| Building | JDK **17-21**, Android SDK **platform 36**, **build-tools 36.0.0** |

## Install

1. Download both APKs from [**Releases**](https://github.com/Eternalcherryblossoms/guise/releases)
2. Install `Guise-*.apk` (the module and its management UI)
3. Install `GuiseProbe-*.apk` (the probe, for verification)
4. Open **LSPosed manager -> Modules -> enable Guise**
5. Reboot -- **once**, to load the module

> The published APKs are debug builds, so their packages are `io.guise.debug` and
> `io.guise.probe.debug`.

## Usage

### 1. Pick a target app

Open **Guise**, tap **`+`**, and choose the app you want to disguise.

### 2. Pick a device profile

Choose one from the bundled catalog. Two things to watch:

- **The Android version comes from your device**, not from the profile, so a version difference
  cannot create a contradiction
- **RAM size and the ABI list cannot be spoofed**, so they should match. A handset with 12 GB
  claiming an 8 GB model is a device-database lookup away from being caught

You can also tap **Capture this device** to snapshot the handset you are holding. That is the
most reliable option.

### 3. Restart the target app

**Force-stop and reopen it** -- not a reboot. Configuration updates live, but classes already
loaded do not go back.

### 4. Verify (this is the important step)

1. Back in Guise, add **`io.guise.probe.debug`** as a target too
2. Open the probe and let it collect
3. Read two numbers: **incoherent items** and **exposed traces**

**The result you want is 0 and 0.** Incoherence means the platform is contradicting itself
(the configuration is wrong). Exposure means the rewriting machinery is visible.

> The probe records a baseline on first run. Every run after that tells you **exactly which
> fields changed** -- the only reliable answer to "did the module actually do anything?"

### 5. Reporting a problem

The probe has **Copy report**. An issue with that report attached is worth far more than
"it doesn't work".

## What it does not do

| | Why |
|---|---|
| **Forge hardware attestation** | The Key Attestation certificate is signed inside the TEE, and `rootOfTrust` comes from the bootloader. **No hook reaches it.** Play Integrity's strong verdict lives here |
| **Forge physical facts** | RAM (`ActivityManager` is a binder call), the ABI list (derived from the native ABI), core count and clock are all out of reach |
| **Touch SIM identifiers** | IMSI, SIM serial and operator codes belong to the SIM card, not the phone. A profile carries no carrier identity, so any invented value would contradict the real SIM |
| **Touch SSID / BSSID** | Those describe the network, not the device |
| **Forge sensor noise or GPU timing** | Those are properties of the silicon. A device presenting a hundred identities still has one body -- which is exactly what server-side clustering keys on |

**In one line**: Guise changes what the platform *reports*, not what is *true*.

## Download and updates

The latest build is on [**Releases**](https://github.com/Eternalcherryblossoms/guise/releases).
Assets are built by CI whenever a tag is pushed.

The app can check for updates from its **About** screen. There is no server, no account and no
push channel: real push on Android means Firebase, Play services and a permanent device
identifier -- out of place in a module whose whole point is to hand out fewer identifiers.

The check is **one unauthenticated HTTPS GET**, with no device identifier, no account and no
query string. It runs when you open the About screen, not on every launch.

> ⚠️ **This only works while the repository is public.** GitHub returns 404 for the releases API
> of a private repository, and the app treats that as "no update information" rather than an
> error.

## Building from source

```bash
# Requires JDK 17-21
./gradlew :core:test :app:assembleDebug :probe:assembleDebug
```

Output: `app/build/outputs/apk/debug/Guise-debug-*.apk`

**Releasing**: push a `v*` tag and CI builds and publishes it.

```bash
git tag v5.2.0 && git push origin v5.2.0
```

### The device catalog is generated, not written

`xposed/src/main/assets/catalog.json` is **generated by `:catalog-gen` from
`catalog/seed/corpus.json`**. To change the catalog, change the seed and regenerate:

```bash
./gradlew :catalog-gen:run --args="--write"    # regenerate
./gradlew :catalog-gen:run --args="--check"    # the CI gate: fails on drift
```

The generator has three gates and none of them can be skipped:

1. **Every entry is attributed** (the `source` field). An invented build ID produces a
   fingerprint that matches no shipped handset -- unique, and therefore *more* conspicuous than
   any common real one. So this one is not advisory.
2. **The same coherence validator `:core` uses** -- the one the 46 tests run. The generator
   carries no copy of the rules, because a generator with its own rules validates its own
   mistakes.
3. **The coverage policy**: required memory tiers are declared, gaps need a written waiver, and
   tiers deliberately not covered need a written reason. Device count is not the metric --
   "fourteen devices covering two memory tiers" is why the metric was invented.

`catalog/PROVENANCE.md` (also generated) collects the origin of every entry, and states plainly
how many of them are unverified.

## Layout

```
core/        pure JVM: profile model, catalog, memory tiers and compatibility, config codec.
             No Android dependencies, tested on the desktop
xposed/      the LSPosed layer: module entry, 9 channels, catalog asset, META-INF/xposed descriptors
app/         Compose management UI, config writer, device capture, update check
probe/       the diagnostic harness (a separate app)
catalog/     the catalog seed corpus and the generated provenance record
catalog-gen/ build-time only: generates the catalog from the seed and gates on
             attribution and coverage. Never shipped in the APK
docs/        architecture and findings
```

`core` has no Android dependencies on purpose: **the logic most likely to be wrong is the logic
that can be verified without a rooted handset.**

## FAQ

**I picked a profile but the app is unchanged.**
Check that Guise is enabled in LSPosed and that the app is in the module's scope, then
**force-stop and reopen the target app**.

**The probe reports an incoherent kernel SoC.**
Known on Qualcomm: `/sys/devices/soc0/machine` names the real chip (e.g. `Snapdragon`). This
build does not cover it -- that needs a Zygisk layer faking the file inside the process's
private mount namespace. **Workaround: choose a profile on the same platform.**

**The probe reports a RAM or ABI mismatch.**
Neither can be spoofed. **From 5.5 the app flags it for you**: the device picker judges every
profile against this handset's measured memory tier and ABI, marks each row "compatible" or "does
not fit this device", and shows a running count. Pick one that fits -- and if none does, that is a
coverage hole in the catalog, not a mistake in your configuration.

**Some apps -- especially Flutter, Unity, or anything with a native SDK -- are unaffected.**

Almost certainly the **native property route** is uncovered, and that is something a Java-layer
hook structurally cannot cover.

At runtime `ro.*` values are **not in a file**: `init` reads the property files once at boot,
loads them into a **shared-memory property area**, and every process maps that area read-only.
Two ways to read it:

| Route | Who uses it | Hookable from LSPosed? |
|---|---|---|
| `android.os.SystemProperties.get` | the framework, every Java/Kotlin caller | **yes** -- it is a Java method |
| `__system_property_get` | the NDK, `getprop`, the **Flutter engine**, Unity, most native fingerprinting SDKs | **no** -- a libc function reading shared memory |

So the module can be perfectly coherent on every Java surface and entirely absent for a native
reader.

**Measure it rather than guess**: put the probe in Guise's scope with a profile, then look at its
**"Java property vs native property"** row. It names each field the native caller sees, and it is
a different check from "Java vs property" -- that one's two sides both pass through the same
hookable Java method, so they agree with each other while both disagreeing with reality.

- **Both sides agree** -> the app's problem is not this path (check scope, process, multi-process).
- **They disagree** -> confirmed; a native hook is required.

**An idea that looks right and cannot work**: mount a forged `build.prop` over `/system`.
Those files are read by `init` **once, at boot**; the runtime value comes from memory, not from the
file. Mounting a fake file over a running system changes nothing. The only thing that changes a
native property read is a hook **on the native function itself**.

That is why an optional Zygisk module is needed, and it has two distinct native-layer jobs: **hook
`__system_property_get`** (Flutter and the like) and **a mount namespace** (the path-based
"all files access" case). Neither belongs in LSPosed.

**The probe reports visible injection traces.**
Zygisk/LSPosed `.so` paths are showing up in `/proc/self/maps`. Install a concealment module
such as Shamiko.

**The update check does nothing.**
Private repository, no release published, or no network -- all three look identical.

## Licence

LGPL-3.0. See [LICENSE](LICENSE). This project is a rebuild of
[kingsollyu/AppEnv](https://github.com/kingsollyu/AppEnv) and follows its licence; **no code is
shared with it**.

---

## 相关文档 / Further reading

| | |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 分层设计、插入点分类、通道归属契约 / Layer design, insertion points, channel ownership |
| [`docs/FINDINGS.md`](docs/FINDINGS.md) | 原版死因、真机实测发现（含否定结果）/ Why the original died, and what real hardware disproved |
