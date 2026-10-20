# A4 自签名与装机操作清单(2026-10-18)

> **历史文档(2026-10-20 注)**:对象是 A 段的 `unsigned.apk`。B 段起一律签 **guarded** 包,流程固化为 `bash custom/scripts/sign_guarded.sh`(SHA 核对 → zipalign → v3 签名 → 证书指纹不变 → `verify_apk --guarded` → SHA 记 SUMS),见 CLAUDE.md「目录约定」。本文保留 keystore 生成、停止条件、回退步骤作参照;第 0 节的 SHA 钉值已过期。

对象:`android-addon/build/TurboIO-RayNeo-1.0.6-unsigned.apk`(SHA-256 `3139e8a3dd4df565ae7ac6ab8ba3b0ecb0ba80877663016668f78fd6453b6d6e`,审查修复后版本;旧 `56cbe200…` 作废不许签)。
分工:**Claude 只做 0、2、3 中的校验;1 的口令、5 的卸载/安装、6 的手机操作全部 Bill 亲手。** 任一停止条件命中就停,不绕。
所有命令在仓库根目录、先 `source custom/scripts/env.sh`。

## 0 · 前提核对(停止条件)

- [ ] `shasum -a 256 android-addon/build/TurboIO-RayNeo-1.0.6-unsigned.apk` = `3139e8a3…`,否则重新跑 `node android-addon/package.mjs local/downloads/official-android-1.0.6-216/RayNeoAI-1.0.6-216-official.apk`
- [ ] `cat android-addon/build/package-report-1.0.6.json` 里 `changes` 含「no OTA adapter installed」且 `outputSha256` 与上面一致
- [ ] 手机现装官方版仍是 1.0.6(216):`adb shell dumpsys package com.rayneo.venus.pub | grep -E 'versionName|versionCode'`。不是 216 → 停(宿主变了,A2/A3 要重做)
- [ ] 眼镜当前能正常配对、官方功能正常(装机后要拿这个对照)

## 1 · 生成签名 keystore(Bill 自己终端跑,口令只记在自己的密码管理器)

```bash
mkdir -p local/keys
keytool -genkeypair -v -storetype PKCS12 -keystore local/keys/turboio-bill.p12 -alias turboio \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=TurboIO Bill, OU=private, O=private, C=CN"
```
- 交互输入口令两次。**口令不发进聊天、不写进任何文件。**
- `local/` 已 gitignored;另外把 `turboio-bill.p12` 备份一份到密码管理器附件。**丢了 keystore = 以后每次升级插件都得再卸载重装一次(数据全丢)。**
- 一次性生成,以后 1.0.6 之后的每个版本都用同一个 keystore,这样才能覆盖安装不丢数据。

## 2 · 对齐 + 签名(Bill 跑,apksigner 会交互要口令)

```bash
mkdir -p local/build
zipalign -p -f 4 android-addon/build/TurboIO-RayNeo-1.0.6-unsigned.apk local/build/TurboIO-RayNeo-1.0.6-aligned.apk
zipalign -c -p 4 local/build/TurboIO-RayNeo-1.0.6-aligned.apk && echo ALIGN-OK
apksigner sign --ks local/keys/turboio-bill.p12 --ks-key-alias turboio \
  --out local/build/TurboIO-RayNeo-1.0.6-signed.apk local/build/TurboIO-RayNeo-1.0.6-aligned.apk
```
停止条件:`zipalign -c` 不输出 ALIGN-OK;apksigner 报错。

## 3 · 验签并登记(Claude 可跑)

```bash
apksigner verify --verbose --print-certs local/build/TurboIO-RayNeo-1.0.6-signed.apk
shasum -a 256 local/build/TurboIO-RayNeo-1.0.6-signed.apk >> local/downloads/SHA256SUMS.local
```
- [ ] 输出 `Verified using v2 scheme: true`(v3 也 true 更好);证书 CN 是 TurboIO Bill
- [ ] 顺手记证书指纹(`--print-certs` 的 SHA-256)到本文第 7 节记录表,以后比对用
- [ ] `python3 android-addon/verify_apk.py local/downloads/official-android-1.0.6-216/RayNeoAI-1.0.6-216-official.apk local/build/TurboIO-RayNeo-1.0.6-signed.apk --public-assets`:对签名包再审一次,`unexpected_paths` 必须为空(签名条目在 META-INF 下,脚本自动放过)

## 4 · 装机前准备(Bill 亲手,卸载不可逆)

卸载官方 App = 它的私有数据全删,包括:登录态、眼镜在 App 内的绑定记录、**已录音频与转写(存在 filesDir / app_flutter / externalFilesDir,Android 16 下 adb 拉不出来)**。
- [ ] 在官方 App 里把要留的录音/转写逐条导出或分享到飞书/本机,确认收到后再往下
- [ ] 记下登录方式(手机号/第三方)和账号,装完要重新登录
- [ ] 眼镜电量 ≥50%,手机电量 ≥50%,数据线在手边
- [ ] vivo OriginOS:设置 → 开发者选项 → 「USB 安装」打开;设置 → 安全 → 「纯净模式」关闭(否则拦非商店签名的包);FLASH_GUIDE.md 第 2 节的后台管控 8 条照做(高耗电、自启动、锁定任务)
- [ ] `adb devices` 显示 V2436A 为 device(不是 unauthorized)

## 5 · 卸官方、装自签(Bill 亲手逐条跑,每条看结果)

```bash
adb uninstall com.rayneo.venus.pub          # 期望 Success;Failure 就停,别用 pm uninstall --user 0 绕
adb install local/build/TurboIO-RayNeo-1.0.6-signed.apk   # 409 MB,约 1 分钟,期望 Success
adb shell dumpsys package com.rayneo.venus.pub | grep -E 'versionName|versionCode|signatures'
```
停止条件:
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` → 官方版没卸干净,`adb shell pm list packages | grep rayneo` 查
- `INSTALL_FAILED_VERIFICATION_FAILURE` / 手机弹「检测到风险」→ 纯净模式或应用检测没关,关掉再装;**不要**点「仍然安装」之外的任何「修复/替换」
- 其他任何 Failure → 停,把完整输出给 Claude 看,不重试

## 6 · 验收(手机上操作,结果分层写进 TODO.md,「装上」≠「跑通」)

按顺序,每层失败就停在那层记录:
1. **启动不崩**:打开 App,登录,进首页。崩 → `adb logcat -d | grep -iE 'turboio|AndroidRuntime' > local/build/crash.log` 给 Claude
2. **官方功能不坏**:配对眼镜(重新绑定)、语音问答、录音、设置页。任何一项比装机前差 → 记录,考虑第 8 节回退
3. **插件入口**:首页右上角出现「Turbo IO」按钮;点开 → 「模型与对话」能打开并保存设置。没按钮 → `adb logcat -d | grep TurboIOAddon`,看是否打了「宿主版本不受支持」(说明 versionCode 读取或映射有问题)
4. **OTA 已禁用**:首页「实验固件升级 · 默认锁定」点开只弹「本安装包没有为当前宿主版本安装 OTA 队列/文件门禁」提示,**不出现**导入/准备/开始按钮。出现了 → 立即退出,不点任何按钮,给 Claude(这是审查修复 #3 的验收点)
5. **录音导出**:插件「导出」页能列出官方 App 落盘的音频/文本,系统分享可用(阶段 6 的输入)
6. **自有模型(Hermes)**:A4 只验「设置能填、保存不崩」;真正走通要等阶段 3 的 HTTPS 入口,不在本清单

## 7 · 记录表(装完填)

| 项 | 值 |
|---|---|
| 签名包 SHA-256 | `0862d4dccc0bd0cf85df9c1a858e90d04d5f507ad52a40c9924f9b0f13958bc5`(v3 签名,2026-10-18 23:45) |
| 证书 SHA-256 指纹 | `4f941f050c77edce03b2d4771b9d56f5dcf59552e98492e25a58fc83240e8478` |
| 装机时间 / 手机系统版本 | 2026-10-19 14:51(手机时钟)· vivo X Fold 5 V2436A · Android 16 · 第 5 步两条 Success;装后 base.apk sha256 `0862d4dc…` 与签名包一致,installer=null(sideload)|
| 第 6 节到哪一层 | 2026-10-19:①②③④⑥ 通过;⑤ 第一轮无入口(上游死代码),补按钮后 r2 签名包 `abe3b10f…`(cert 同)于 15:14 `adb install -r` 覆盖成功;⑤ 入口通但 1.0.6 无可扫音频(原始录音非文件形态,分享时才生成 `cache/VoiceMemo….mp3`,插件不扫 cacheDir)→ 定案音频走官方分享,A4 收尾 ✅ |
| logcat 异常摘要 | |

## 8 · 回退(Bill 亲手)

```bash
adb uninstall com.rayneo.venus.pub
adb install local/downloads/official-android-1.0.6-216/RayNeoAI-1.0.6-216-official.apk   # SHA 4c1c57ae…,装前先 shasum 核
```
回退同样丢 App 数据,要再登录、再配对。回退只恢复手机侧;眼镜固件本清单从未触碰(1.0.5.5 不变)。
