# Turbo-IO(Bill 私用分支)项目规范

上游:Turbo1123/Turbo-IO(雷鸟 iO / RayNeo iO 非官方 SDK,PolyForm Noncommercial)。本仓库是 Bill 的 fork(idengy/Turbo-IO),
**目的:给 Bill 自己的雷鸟 iO 眼镜用,在上游基础上加自己的功能,仅个人非商业使用**。2026-10-16 建。

## 已定事项(2026-10-16 Bill 定)

- **手机:Android**。走「Android 官方扩展」路线,用上游 Release 已合并好的完整实验 APK(GUARD-07),不自己合并宿主。
- **愿意刷实验固件**(TAP1-TEST-01)。上游明确:无回滚、无救砖保证,风险自担。
- **先做三件事**:①期货行情/持仓仪表盘(数据来自 futures-kb / futures-lhb)→ 眼镜卡片或小应用;②眼镜问答接自己的 AI 模型;
  ③飞书/知识库消息(notes-bot、日报、告警)上镜。
- **第一阶段不改 APK、不碰 Java/apktool**:三件事都能在「上游 APK + 自托管 dashboard-service + App ZIP」范围内做——
  仪表盘和小应用走 Server/ZIP,模型在 App 里自己配。只有需要改手机端行为时才进入第二阶段(重编译、自签名,见下)。
- **2026-10-18 Bill 定:自己做 Android 版「新固件插件」,分 A/B/C 三段推进,先做 A 段**(宿主适配到 Bill 手机现用的官方 APK 版本,不刷机;
  B 段把 OTA 门禁换成 TMIG1;C 段 Bill 亲手实刷)。段落定义与进度见 TODO.md。逆向分析用 REA(morluto/rea),**只配项目级 `.mcp.json`,不配全局**。

## 硬约束

- **刷机、安装 APK、卸载官方 App、改蓝牙绑定:只有 Bill 亲手做**。Claude 只准备文件、校验 SHA-256、给出逐步说明,
  不替他点「开始安装」。上游每一步的停止条件(版本/SHA/门禁不匹配就停,不改白名单硬上)照抄,不绕。
- **版本锁死**:宿主 RayNeo AI **1.0.5(201)**、眼镜 Strix OS **1.0.4.12**、固件 **TAP1-TEST-01**、APK **GUARD-07-PUBLIC**。
  动手前先核对眼镜与 App 当前版本;不匹配就停,不拿别的候选凑。
- **⚠️ 2026-10-18 实际核对:Bill 的眼镜已是 Strix OS 1.0.5.5,刷机暂停**。上游 Android 路线(GUARD-07 + TAP1-TEST-01)只适配 1.0.4.12;1.0.5.5 的适配 TMIG1 只有 iOS 1.0.6 插件,上游明说「没有发布 Android 1.0.6 APK,也没验证旧 Android APK 与新固件的全量兼容」,旧 Android 门禁不能刷 TMIG1。**不降级**:1.0.5.5 多了 bt_controller 负载,往回刷 1.0.4.12 底座没人验证过,上游也不保证回退。等上游出 Android 版 TMIG1(关注 upstream 提交与 Release),或改走 iOS。眼镜端功能(仪表盘/小应用/新菜单)因此全部挂起;手机侧功能照常推进。
- **密钥不进仓库**:模型 Key、地图 Key、签名 keystore、Server token 一律放 `local/`(gitignored)或手机 App 内填写;
  不发进聊天、不写进 commit、不写进日志。上游 .gitignore 已挡 *.apk/*.keystore/*.key/.env,`local/` 另加。
- **上游代码不直接改**:自己的东西放 `custom/`;必须改上游文件时在分支 `bill` 上改并在本文记录,方便 `git merge upstream/main`。
- 安装包、固件 zip 等二进制放 `local/downloads/`,不进 git;每次下载记 SHA-256 到 `local/downloads/SHA256SUMS.local`。

## bill 分支对上游文件的改动记录(merge upstream 时逐条对照)

- `android-addon/host-profiles.mjs`、`package.mjs`、`assemble_apk.py`、`verify_apk.py`、`tests/{HostProfilesTest.mjs,ApkAssemblyTest.py,otaflow/NavReflect.java}`:1.0.6 宿主 profile、`ota:false`、DEX 空闲槽位、显式描述符表(2026-10-18,A3 + 审查修复)
- `android-addon/src/com/turboio/addon/{NavReflect,TurboAddon,OtaController,OtaUI}.java`:按 versionCode 懒加载反射名表、install fail closed、`otaGatesInstalled()` 三处 OTA 门禁(2026-10-18)
- `android-addon/src/com/turboio/addon/EditorialUI.java`:「设置」标签加两个按钮调 `RecordingExports.show`;上游新首页没接这个页面,旧 `showLegacyHome` 是死代码(2026-10-19)
- `.gitignore`:加 `local/`、`/.mcp.json`

## 目录约定

- `custom/` — Bill 自己的功能代码与配置模板(进 git)
  - `custom/dashboard/` — futures-kb / futures-lhb → 仪表盘卡片 / App ZIP 的数据映射脚本(调上游 `dashboard-service` 的 SDK)
  - `custom/notify/` — 飞书/知识库消息 → 眼镜的桥
  - `custom/docs/` — 自己整理的操作文档:`FLASH_GUIDE.md`(刷机准备、vivo X Fold 5 专项、四步、停止条件、实刷记录表)
  - `custom/README.md` — 每个子目录做什么、怎么跑、验收到哪一步
- `local/` — 不进 git:`downloads/`(APK、固件、SHA)、`tokens/`(dashboard-service 签发的 token)、`config/`(Key 的本机副本)、
  `tools/`(apktool 3.0.3 jar+启动器、REA 用的 jadx-headless-mcp 0.7.1 jar)、`android-sdk/`(platform-tools、platforms/android-36、build-tools/36.0.0,
  用 Google cmdline-tools 装的项目内 SDK,`_dl/` 是下载缓存)。**Android 工具链全部装在这里,不装全局**(2026-10-18 定)。
- `custom/scripts/env.sh` — `source` 后 JAVA_HOME(Homebrew openjdk@17)、ANDROID_SDK_ROOT、PATH(adb/apksigner/zipalign/apktool)、REA_* 全部指向上面;
  跑 `android-addon/build.sh`、adb、apktool 前先 source。
- `.mcp.json`(项目根,gitignored,含本机绝对路径)— REA 的 MCP 注册:`npx -y rea-agents@6.7.0 mcp`,env 给 JAVA_HOME / REA_JADX_MCP_JAR /
  REA_ADB_PATH / REA_APKTOOL_COMMAND / REA_MCP_INPUT_SCHEMA_PROFILE=compact。换机器照 env.sh 的路径重写一份即可。REA 源码克隆在 /Users/dyb/PROJECT/rea 只当文档看,不从源码跑。
- 上游目录含义见根 README「源码布局」;本项目实际会用到的只有 `android-addon/docs/INSTALL_AND_FLASH.md`(刷机教程)、
  `dashboard-service/`(自托管 API + Python SDK + `turbo-app` CLI)、`app-gallery/`(20 个模板)、`skills/turboio-developer/`(开发 Skill)。

## 分支与上游同步

- `main` 只跟上游(`upstream` = https://github.com/Turbo1123/Turbo-IO.git),不在 main 上提交自己的东西。
- 工作分支 `bill`:CLAUDE.md、TODO.md、`custom/` 都在这里。同步上游:`git fetch upstream && git checkout main && git merge --ff-only upstream/main && git checkout bill && git merge main`。
- push 到 origin(idengy/Turbo-IO)按 Bill 全局红线先问。

## 阶段与验收

1. **环境与离线预览**(不碰手机、眼镜):`dashboard-service` `uv sync --locked`;`turbo-app gallery build/check/preview` 跑通一个模板;
   本机起 `turbo-dashboard serve`,用 writer token 发一张卡片草稿。验收 = 命令全绿 + SVG 预览能看。
2. **装 APK + 刷固件**(Bill 亲手,按 `android-addon/docs/INSTALL_AND_FLASH.md` 四步:安装连接 → 下载校验 → 预检授权 → 自然重启、只读回查、释放保护)。
   验收 = 眼镜菜单出现「显示TEST」,App 右上角 Turbo IO 入口可用。
3. **接自己的模型**:App 内填模型地址/ID/Key,眼镜问答走通。验收 = 眼镜上看到回答。
4. **期货仪表盘**:`custom/dashboard/` 从 futures-kb 的 `data/kb.sqlite`(prices/main_contracts)和 futures-lhb 取数,生成卡片或 App ZIP;
   先 ZIP 手动导入验收,再评估 Server 直连(**手机只接受 HTTPS、不忽略证书错误**,需要本机 HTTPS 入口,这是已知门槛)。
5. **飞书/知识库消息上镜**:先弄清上游 Android 端「主动自定义文字通知」现状(README 功能表写「扩展主动通知未完整移植」),
   再定走通知、卡片还是小应用。

每阶段结果**如实分层记**:构建通过 ≠ 手机通过 ≠ 眼镜看到。写进 TODO.md。

## 验证命令

- 上游原创代码自测(不需要官方 APK):`cd dashboard-service && uv run pytest`;`node --test codex-bridge/bridge.test.mjs display-observer/*.test.mjs`
- 模板 ZIP:`cd dashboard-service && uv run turbo-app gallery build <id> --out .state/x.zip && uv run turbo-app check .state/x.zip`
- 第二阶段 / A 段:`source custom/scripts/env.sh && bash android-addon/build.sh`(JDK、apktool、Android SDK 36、C 编译器,2026-10-18 已齐,全在 local/)
- REA 自检(不开 MCP 也能跑,CLI 与 MCP 同一套工具):`source custom/scripts/env.sh && npx -y rea-agents@6.7.0 inspect-adb-client --json`、
  `… inspect-apktool-client --json`、`… inspect-android-package <apk> --json`(2026-10-18 实测 GUARD-07 APK 10 秒装载 19,813 类)。
  定位混淆类:`… search-android-classes <apk> H7.c`、`… inspect-android-class <apk> E3.V`、`… inspect-android-method <apk> <class> <method>`、`… trace-android-references …`。

## 手机(2026-10-16 Bill 告知)

vivo X Fold 5(型号 V2436A):骁龙 8 Gen 3(arm64-v8a)。**2026-10-18 adb 实测:Android 16(SDK 36)**,不是之前估的 Android 15。满足 APK 的 minSdk 29 + arm64-v8a。
手机现装官方 RayNeo AI **1.0.6(versionCode 216)**,包名 com.rayneo.venus.pub,单一 base.apk 无 split,来自 vivo 应用商店;这是 A 段的宿主目标版本。上游只在三星 Fold3 / Android 15 实刷过,
X Fold 5 无验证记录;OriginOS 后台管控严,刷机前的专项准备(允许后台高耗电、自启动、锁定任务、不折叠切屏)见 `custom/docs/FLASH_GUIDE.md`。

## 本机环境(2026-10-18 更新)

macOS 26.3、uv 0.11.15、node 26、python 3.11.15 有;Xcode 只有 CommandLineTools(iOS 路线不需要)。
JDK:Homebrew 早已装 openjdk@17(17.0.20)与 openjdk 26,只是没进 PATH、`/usr/libexec/java_home` 找不到,项目统一用 `/opt/homebrew/opt/openjdk@17`。
adb 37.0.1 / platform 36 / build-tools 36.0.0 / apktool 3.0.3 / jadx-headless-mcp 0.7.1 于 2026-10-18 装进 `local/`(见目录约定),全局未装任何新东西。
REA 走 `npx -y rea-agents@6.7.0`(npx 缓存,非全局 npm 包);仅 Turbo-IO 目录生效,其他项目看不到。
