# 官方 Android App 1.0.6(216)宿主适配证据(A2,2026-10-18)

把上游 `android-addon` 钉死在 1.0.5(201)的混淆类/方法,重定位到 Bill 手机现装的官方 1.0.6(216)。
本文只记「找到了什么、凭什么认定」;改代码在 A3(见 TODO.md)。

## 输入

| 项 | 1.0.5(上游基线) | 1.0.6(Bill 手机) |
|---|---|---|
| 文件 | `local/downloads/android-105-guard07-tap1-test01/TurboIO-RayNeo-1.0.5-GUARD07-PUBLIC.apk` | `local/downloads/official-android-1.0.6-216/RayNeoAI-1.0.6-216-official.apk` |
| SHA-256 | d39312a2…24b0 | `4c1c57ae811cb959478efb5ec510000f7f51ddcb427afff369b1582c66b60206`(手机端 sha256sum 一致) |
| 大小 | 426,640,538 B | 408,900,916 B |
| 包名 / versionCode | com.rayneo.venus.pub / 201 | com.rayneo.venus.pub / 216 |
| minSdk / targetSdk | 29 / 36 | 29 / 36 |
| 类 / 资源 | 19,813 / 3,981 | 21,322 / 4,006 |
| smali 工作副本 | `local/work/host-105-guard07/` | `local/work/host-106/` |

说明:1.0.5 用的是上游 GUARD-07 包(已合并插件,但官方宿主类仍保留原样,可当 1.0.5 官方宿主的参照)。
工具:`apktool d --no-res`(各约 5 秒)+ grep;REA(jadx 引擎)用 `inspect-android-class` / `trace-android-references` 复核。

## 认定方法

不按混淆名找,按「接口方法名 + 字串常量 + 方法签名形状 + 方法体调用链」找,每项要求全 APK 唯一命中。

## 类对应表

| 角色 | 1.0.5 | 1.0.6 | 依据 |
|---|---|---|---|
| ASR 结果监听(内部类) | `H7.c$c` | `p8.c$c` | 唯一 `.implements AiResultListener` 且同时有 `onAsrResult(String,Z,String)`、`onNlpResult(NlpResult)`、`onResponseComplete()` 的类。`l8.x` 只实现 AskAIResultListener,排除 |
| 主 Activity | `com.rayneo.venus.MainActivity` | 同(未混淆) | `onPostResume()` 在 |
| 事件桥 | `com.rayneo.rayneo_venus_sdk_plugin.j` | 同(未混淆) | `z(String,Map)` 在;`MARS_FOTA`/`AI_SUBTITLE` 常量在 |
| 消息发送单例 | `E3.u` | `X3.u` | 唯一有 `static volatile h:L<self>`、`static g()List`、`(File,String,<核心枚举>,String)String` 的类;实现 3 接口、字段 a–f 形状一致 |
| 消息对象 | `E3.Q` | `X3.Q` | 字段 a–k 顺序/类型一致;10 参构造 `([B,String,String,<业务枚举>,<L枚举>,<优先级枚举>,Z,Z,<p>,Function2)` 唯一 |
| 发送队列 | `E3.V` | `X3.U` | 唯一有 `static o(L<消息对象>)V` 且方法体调 `h/g/l` 的类;`turboioOriginal_o` 体 24 条 invoke 与 1.0.6 的 `o` 逐条同构 |
| 业务类型枚举 | `P3.h` | `i4.h` | 字母→名映射完全相同(9 个:RESERVED/MARS_FOTA/VOICE_ASSISTANT/RECORDING_SERVICE/CONNECTION_PAIRING/FILE_TRANSFER/AI_SUBTITLE/TELEPROMPTER/FILE_TRANSFER_LAUNCHER) |
| 回调错误枚举 | `P3.A` | `i4.A` | 字母→名映射完全相同(21 个);代码用的 `r`=MESSAGE_SEND_FAILED 两版都是 r |
| 优先级枚举 | `E3.b` | `X3.b` | 完全相同(d=NORMAL、e=HIGH) |
| 核心档位枚举 | `Q3.q` | `j4.q` | 完全相同(b=SmallCore、c=LargeCore) |
| 高德地图三类 | `com.amap.api.*` | 同(未混淆) | MapsInitializer/AMapLocationClient/ServiceSettings 的 setApiKey、getInstance 都在 |

四个枚举的字母→名映射两版零差异,说明 ProGuard 枚举命名稳定,代码里按名字 `valueOf("MARS_FOTA")` 的调法无需改。

## 方法名位移(关键坑)

发送单例 `u` 的实例方法,1.0.5 → 1.0.6 整体错了一位:

| 功能 | 1.0.5 签名 | 1.0.6 签名 | 代码里的引用 |
|---|---|---|---|
| 带回调发消息 | `u(E3.Q, Function2)V` | `t(X3.Q, Function2)V` | NativeTransfer 等按名 `"u"` 反射调 → 要改成 `"t"` |
| 发文件 | `v(File,String,Q3.q,String)String` | `u(File,String,j4.q,String)String` | NativeTransfer 按名 `"v"` 反射调 → 要改成 `"u"`;ota-hooks 的 fileHook header 同改 |
| 入队(static) | `V.o(E3.Q)V` | `U.o(X3.Q)V` | 名 `o` 不变,只换类 |

字段名 `h`(单例)、`a`/`b`(设备记录)、`g`(static 列表)、`k`(消息回调字段)两版一致,仅 `u`/`v`/`t` 这组实例方法要跟着位移。核对依据:两版方法体逐条 invoke 同构(见 A2 grep 记录),只是被调类名随混淆改变。

## A3 要改的落点(两类,别漏 Java 侧)

1. **smali 补丁器**(改混淆描述符):
   - `android-addon/host-profiles.mjs` — 新增 1.0.6(216)的 host profile,SHA 用上面的 `4c1c57ae…`;`verifyHost` 里 9 处 `expect(...)` 的描述符按上表换(`LH7/c$c;`→`Lp8/c$c;`、`LE3/u;`→`LX3/u;`、`LE3/Q;`→`LX3/Q;`、`LE3/V;`→`LX3/U;`、`LP3/h;`→`Li4/h;`、`LP3/A;`→`Li4/A;`、`LE3/b;`→`LX3/b;`);`v(...)` 方法签名里 `LQ3/q;`→`Lj4/q;`。注意上游 `classFile()` 的 `host-105`/`host-106` 目录名也要加。
   - `android-addon/ota-hooks.mjs` — queueHook 的 `LE3/V;->o` 换 `LX3/U;->o`,所有 `LE3/V;->turboioOriginal_o`、`LE3/u;->turboioOriginal_v` 同步换类名,且 fileHook 的方法名 `v`→`u`。
   - `android-addon/package.mjs` — 第 15 行 `profile.version!=='1.0.5'` 的硬门禁要放行 1.0.6;事件桥 `z` 仍叫 j.z,不变。
2. **Java 反射字串**(改 `NavReflect.type("…")` / `call(…, "u"/"v")`):
   - `NativeTransfer.java`、`NavGlasses.java`、`NewsTele.java`、`OtaController.java`、`OfficialOtaBridge.java`、`EntryProbe.java`、`MapConfiguration.java` 里 `"E3.u"`→`"X3.u"`、`"P3.h"`→`"i4.h"`、`"E3.b"`→`"X3.b"`、`"Q3.q"`→`"j4.q"`、`"P3.A"`→`"i4.A"`、`make("E3.Q",…)`→`make("X3.Q",…)`;发消息 `call(manager,"u",…)`→`"t"`、发文件 `call(manager,"v",…)`→`"u"`。
   - 这些类名/方法名若要随 APK 版本切,最好抽成一张表按 host profile 选,别散在各文件硬编码(A3 设计时定)。

## A3 实现结果(2026-10-18)

### 代码改动(分支 bill,未提交)

1. **`android-addon/host-profiles.mjs`**
   - 新增 `NAMES_106` 名称表(上表 11 个角色),`PROFILES` 加 1.0.6 条目,`ota:false`(A 段不装官方 OTA 适配器:其 libapp.so 偏移只对 1.0.5)。
   - `verifyHost` 的描述符全部改为从 `profile.names` 取值,1.0.5/1.0.6 共用同一套检查;枚举常量按名字校验。
   - `classFile()` 重写为逐级大小写/`.1` 后缀解析:APFS 大小写不敏感,apktool 3.x 会把与 `I4/` 冲突的 `i4/` 写成 `i4.1/`,把 `U.smali` 冲突的 `u.smali` 写成 `u.1.smali`;多级目录也要逐段解析。**同一段可能同时匹配 `I4` 和 `i4.1`,必须两类目录都扫,再按 `.class` 行精确匹配**,不能取第一个。
2. **`android-addon/package.mjs`** — 放行 1.0.6;`profile.ota===false` 时不写 queue/file 钩子、不注入 `EVENT_GUARD`;report 的 changes 按实际写入。
3. **`android-addon/assemble_apk.py` / `verify_apk.py`** — 1.0.6 本身已带 `classes4.dex`,addon dex 改为取「下一个空闲的 classesN.dex」(本次为 `classes5.dex`),两个脚本用同一规则。
4. **Java(`src/com/turboio/addon/`)**
   - `NavReflect`:调用点保留上游 1.0.5 逻辑名(`E3.u`/`P3.h`/…),`select(versionCode)` 按宿主 201/216 映射到实际混淆名;`call()` 只对 sender 类做方法名位移(`u→t`、`v→u`)。默认 1.0.5,保证所有单测的 fake NavReflect 不变。
   - `TurboAddon.install`:先 `select()`;未知 versionCode 直接 `return`(fail closed,不装入口、不对错误 ABI 反射),打一条 warn 日志。

### 构建与产物(实测)

- `bash android-addon/build.sh` exit 0(全部上游测试;classes.dex `d1916191…`)。
- `node android-addon/package.mjs local/downloads/official-android-1.0.6-216/RayNeoAI-1.0.6-216-official.apk` exit 0:
  - `android-addon/build/TurboIO-RayNeo-1.0.6-unsigned.apk`,409 MB,SHA-256 `3139e8a3dd4df565ae7ac6ab8ba3b0ecb0ba80877663016668f78fd6453b6d6e`(2026-10-18 审查修复后重打包;修复前 `56cbe200…` 作废,classes.dex 由 `d1916191…` 变为 `102b589f…`);
  - verify_apk:added 只有 `classes5.dex` + allowlisted assets;changed 只有 `classes2.dex` + `AndroidManifest.xml`;签名条目全清(未签名);
  - `classes2.dex` 里只有 4 个 `turboioOriginal_*` 方法名,无 `OfficialOtaBridge`/`OtaController` 引用 —— 1.0.6 产物没有任何 OTA 钩子;
  - 构建幂等:重复跑 package 会先剥掉上次生成的 wrapper 再加。
- 1.0.5 回归:用改后的 `verifyHost` 对 `local/work/host-105-guard07` 复核仍通过(ota 钩子路径不变)。

### 构建夹具的坑(不进 git)

上游 `tests/OfficialOtaSourceTest.mjs` 需要 `android-addon/build/host-105/lib/arm64-v8a/libapp.so`(未打补丁的 1.0.5 libapp.so,SHA `78214ba0…`)。本机只有 GUARD-07 APK,里面的 libapp.so 已是打过 OTA 路由补丁的版本(SHA `851e0c59…`,与 `package-official-ota-preparation.py` 的 PATCHED 一致)。
做法:按该脚本的两条 SLOT 记录把 patched 字符串原位还原成原始 URL,重建出的文件 SHA 正好等于 `78214ba0…`,放入 gitignored 的 `android-addon/build/host-105/lib/arm64-v8a/`。换机重建同样处理,不需要下载源 APK。

### 验收分层

| 层 | 状态 |
|---|---|
| 构建(apktool 反编译 + verifyHost + 重打包) | 通过(2026-10-18) |
| 静态产物审计(verify_apk) | 通过 |
| 装机(自签名、覆盖官方 1.0.6) | **未做**(A4,Bill 亲手) |
| 手机运行(登录/配对/官方功能/Hermes/录音导出) | **未做** |

### 审查修复(2026-10-18,A3 之后)

| # | 问题 | 修复 |
|---|---|---|
| 1 | `NavReflect` 的名表默认是 1.0.5,只在 `TurboAddon.install`(onPostResume)里切换。事件桥钩子 `j.z` 在 MainActivity 恢复前就可能触发 `NativeTransfer.event → connected()`,在 1.0.6 上会用 `E3.u` 查类,静默 ClassNotFound | `ensure()` 懒加载:首次用到名表时经 `ActivityThread.currentApplication` 读 versionCode 自选;未知版本抛异常,调用方都 catch → 功能关闭 |
| 2 | `install` 的 fail-closed 返回前已经把 `app` 赋了值,`onAsr` 只靠 `mode()==0`(prefs 为 null)兜底 | 版本校验通过后才赋 `app`/`activity`,`onAsr/onNlp` 的 `app==null` 守卫直接生效 |
| 3 | **1.0.6 产物没装 smali 的 OTA 队列/文件门禁,但 Java 侧 `OtaUI.show → OtaController.prepare/start/send` 照样可达**,`call(manager,"u")` 又被正确映射到 `t`,等于能在没有互斥门禁的情况下向眼镜发 MARS_FOTA 升级帧,违反「A 段产物不触发任何升级路径」 | 新增 `NavReflect.otaGatesInstalled()`(仅 201 为 true);`OtaUI.show` 弹提示并返回,`OtaController.prepare` 与 `send` 再各拦一次。`tests/otaflow/NavReflect.java` 桩同步加该方法 |
| 4 | `verifyHost` 用 `n.business.replace(/h;$/,'Q;')`、`n.core.replace(/q;$/,'p;')` 推导消息构造器的另外两个参数类型,换宿主时可能巧合通过 | 名表显式加 `messageExtra`/`coreOption`(1.0.5 `LP3/Q;`/`LQ3/p;`,1.0.6 `Li4/Q;`/`Lj4/p;`),对 build/host-106 真实 smali 复验通过 |
| 5 | `assemble_apk.py` 的 DEX 槽位按「原包 ∪ apktool 产物」算,`verify_apk.py` 只按原包算,两者可能不一致;且不再报任何冲突 | 两边都只按原包算;apktool 产物若已占该槽直接 `Addon DEX collision` 退出。`ApkAssemblyTest` 加 classes4 已占用 → 落到 classes5 的用例 |
| 6 | package report 在无 EVENT_GUARD 时仍写「exclusive business9 before-vendor interception」 | 按 `ota` 实际写入 |
| — | `NavReflect.call` 的 u→t / v→u 重映射对 `Class` 静态调用也生效(现无此用法) | 只对实例调用重映射 |

`HostProfilesTest` 加 1.0.6 profile 与目录 `.1` 孪生用例(13 断言)。`bash android-addon/build.sh` exit 0,重新 `package.mjs`,`verify_apk` 仍只 added `classes5.dex`+assets、changed `classes2.dex`+manifest。

## 未决 / 风险

- 上游 OTA 门禁只认 1.0.5(`official-ota-source.mjs` 第 1 行「Android 1.0.5 only」,libapp.so 两条 Dart 字符串偏移 0x86e1f5 / 0xd95b8 + sourceSHA 78214ba0…)。**A 段不碰 OTA**,1.0.6 的 libapp.so 偏移要等 B 段重核。A 段产物必须保证不触发任何升级路径。
- `android-addon/build.sh` 第 36 行要一个固件候选 ZIP `official-addon/build/app-ap-20260927-test-01/StrixOS-…CANDIDATE-NOT-APPROVED.zip`,本仓库没有(只有 `local/downloads/.../TurboIO-TAP1-TEST-01.zip`)。A 段不需要固件,跑 build 时要么跳过 OTA 相关测试段,要么把候选指向本机已有 ZIP——A3 决定。
- 1.0.6 比 1.0.5 多 1,509 个类,新增部分未审;A 段只关心上表 11 个角色,其余不动。


## B 段(2026-10-19):OTA 门禁换 TMIG1 时撞到的 DEX 64K 上限

- `apktool b` 重编译 `smali_classes2` 报 `Unsigned short value out of range: 65536`(写 `Lt5/b;->values()` 时)。实测 dex 头部:官方 1.0.6 `classes2.dex` method_ids = **65,524**(上限 65,536),A 段钩子(ASR 三处、onPostResume、事件桥)用掉 10 个 → 65,534;上游 OTA 钩子体(队列 4 个引用 + 文件 4 个 + 事件 3 个)放不下。`classes.dex` 65,345、`classes3.dex` 65,449 同样贴顶;`classes4.dex` 只有 11,158。
- 处理:①`OtaGates.java` 把三段策略收成 `queue/file/event` 三个静态方法,smali 每处只剩 1 个新引用(事件桥留在 classes2,用掉 1 个);②队列类 `LX3/U;` 与发送类 `LX3/u;`(含其钩子新方法 `turboioOriginal_o/u`)由 `package.mjs` 搬进新建的 `smali_classes5`,apktool 编成 `classes5.dex`(实测只含这两个类,299 个方法引用),插件 dex 顺延为 `classes6.dex`;原厂 classes3/4 原件不动。内部类 `U$a/b/c`、`u$a` 留在 classes2,跨 dex 按名解析(多 dex 自 API 21 起原生支持)。
- 结果:`classes2.dex` 65,518(反而更宽)、`classes5.dex` 299、`classes6.dex` 3,338。`verify_apk` 加 `--relocated classes5.dex` 显式声明,未声明仍按「插件槽位冲突」报错。
- 这是**手机端静态事实**,运行时(宿主能否正常加载搬走的类、钩子是否生效)属 C 段装机验收;A 段 r2 包无此改动。
