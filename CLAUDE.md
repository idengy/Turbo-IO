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
- **2026-10-20 B/C 段完成**:vivo X Fold 5 + 官方 1.0.6(216)宿主 + guarded r2 自签包(`27010e75…`)把 TMIG1 刷进 Strix OS 1.0.5.5 眼镜,236 片全回执、拦截 0、释放保护后 LOCKED;新菜单、官方语音/录音/字幕、Hermes 全部正常。这是 Android 投递 TMIG1 的首次实刷记录(`custom/docs/FLASH_GUIDE.md` 第 4 节)。**现状(2026-10-21):眼镜固件 = TMIG1,手机 = bill 分支最新 guarded 自签包(r18,之后每次重打按 `*.rN-日期` 留档、SHA 记 SUMS);不要再次授权刷写,官方 App 自动更新保持关闭。覆盖安装用 `adb install -r --no-incremental`(增量安装会被 vivo 记成全新安装、丢设置)。**
- **2026-10-19 Bill 定:A 段验收完成后直接做 B/C 段**。同日查清两件事并记入 TODO:①原厂固件的仪表盘股票组件可改源喂期货(S 段,1 分钟刷新、最多 6 项),②tick 级行情不靠固件、靠原厂 business 19 文字通道(盯盘模式);两者都列为可选,排在 B/C 之后。

## 硬约束

- **刷机、安装 APK、卸载官方 App、改蓝牙绑定:只有 Bill 亲手做**。Claude 只准备文件、校验 SHA-256、给出逐步说明,
  不替他点「开始安装」。上游每一步的停止条件(版本/SHA/门禁不匹配就停,不改白名单硬上)照抄,不绕。
- **版本锁死(2026-10-19 B 段起)**:宿主 RayNeo AI **1.0.6(216)**(Bill 手机现装官方版,`4c1c57ae…6206`)、眼镜 Strix OS **1.0.5.5**、固件 **TMIG1**(上游 iOS 实刷原样 ZIP,SHA `f1d3616c…`)、插件 = bill 分支自签 **guarded** 包(`package-official-ota-preparation.py --guarded` 产出,标记 `assets/turboio/official-ota-guarded.txt`)。
  动手前先核对眼镜与 App 当前版本;不匹配就停,不拿别的候选凑。上游旧组合(1.0.5(201) / 1.0.4.12 / TAP1-TEST-01 / GUARD-07-PUBLIC)在本仓库只作历史参照。
- **历史:2026-10-18 核对眼镜为 Strix OS 1.0.5.5,刷机曾暂停**。上游 Android 路线(GUARD-07 + TAP1-TEST-01)只适配 1.0.4.12;TMIG1 只有 iOS 1.0.6 插件实刷过,上游明说「没有发布 Android 1.0.6 APK,也没验证旧 Android APK 与新固件的全量兼容」。**不降级**:1.0.5.5 多了 bt_controller 负载,往回刷 1.0.4.12 底座没人验证过。2026-10-19 Bill 定:不等上游,自己把 Android 插件的 OTA 链路换成 TMIG1(B 段),Android 投递 TMIG1 为首次、无回滚保证。
- **密钥不进仓库**:模型 Key、地图 Key、签名 keystore、Server token 一律放 `local/`(gitignored)或手机 App 内填写;
  不发进聊天、不写进 commit、不写进日志。上游 .gitignore 已挡 *.apk/*.keystore/*.key/.env,`local/` 另加。
- **上游代码不直接改**:自己的东西放 `custom/`;必须改上游文件时在分支 `bill` 上改并在本文记录,方便 `git merge upstream/main`。
- 安装包、固件 zip 等二进制放 `local/downloads/`,不进 git;每次下载记 SHA-256 到 `local/downloads/SHA256SUMS.local`。

## bill 分支对上游文件的改动记录(merge upstream 时逐条对照)

- `android-addon/host-profiles.mjs`、`package.mjs`、`assemble_apk.py`、`verify_apk.py`、`tests/{HostProfilesTest.mjs,ApkAssemblyTest.py,otaflow/NavReflect.java}`:1.0.6 宿主 profile、`ota:false`、DEX 空闲槽位、显式描述符表(2026-10-18,A3 + 审查修复)
- `android-addon/src/com/turboio/addon/{NavReflect,TurboAddon,OtaController,OtaUI}.java`:按 versionCode 懒加载反射名表、install fail closed、`otaGatesInstalled()` 三处 OTA 门禁(2026-10-18)
- `android-addon/src/com/turboio/addon/EditorialUI.java`:「设置」标签加两个按钮调 `RecordingExports.show`;上游新首页没接这个页面,旧 `showLegacyHome` 是死代码(2026-10-19)
- **B 段(2026-10-19)**:`android-addon/src/com/turboio/addon/{OtaPackage,OfficialOtaBridge,OfficialOtaPreparation,OfficialOtaGate,OfficialOtaFeed,OtaSession,OtaController,OtaUI}.java`:固件钉值、目录名、口令、官方查询版本号全部由 TAP1/1.0.4.12 换成 TMIG1/1.0.5.5,流程代码不动;`NavReflect.otaGatesInstalled()` 改为「guarded 标记资产存在 或 versionCode 201」(2026-10-20 C1 实机发现标记检测放在 `ensure()` 里永远不跑——正常启动 `install` 先调 `select()`;改为独立的 `guarded()` 打开 `turboio/official-ota-guarded.txt` 比对完整钉死文本);`official-ota-source.mjs`、`package-official-ota-preparation.py`、`tests/OfficialOtaSourceTest.mjs` 重钉到 1.0.6 libapp.so(`e9ab4326…`,偏移 `0x885c9a`/`0xe1ffc`);`ota-hooks.mjs` 按宿主名表生成(`tests/OtaHooksTest.mjs` 两套名表);`host-profiles.mjs` 1.0.6 `ota:true` + `relocate`(X3/U、X3/u 搬进 `smali_classes5`,`relocateClasses()`);`package.mjs` 传名表、先搬类再包钩子、给 assemble/verify 传 `--relocated classes5.dex`;**新文件 `src/com/turboio/addon/OtaGates.java`**(三段门禁各收成一个静态调用,策略顺序照抄上游 smali);`assemble_apk.py`/`verify_apk.py` 只在显式声明时接受 apktool 多出的 classesN.dex(未声明仍报冲突),`tests/ApkAssemblyTest.py` 加用例;`package-official-ota-preparation.py` 自动识别 relocated dex;`verify_apk.py --guarded` 档位(放行且只放行钉死文本的 guarded 标记文件与补丁后 SHA `96cc9a9f…` 的 libapp.so,guarded 包校验有明确的 exit 0;钉值与 preparation 脚本一致由 `ApkAssemblyTest` 断言);`build.sh` `OTA_CANDIDATE` 可覆盖、默认 TMIG1;OTA 相关 Java 测试钉值同步。**原因:官方 1.0.6 classes2.dex 方法引用 65,524,上限 65,536,上游钩子体放不下**(见 `custom/docs/HOST_1.0.6.md`「B 段」)
- **盯盘模式(2026-10-20 起)**:`android-addon/src/com/turboio/addon/NavGlasses.java`(字号 `captionFont` 可调、type3 关闭帧 SDK 回执计数 `closeCompleted/closeFailed` 进 `status()`;`renew()`/autoIdle 续期路径:关闭帧被 SDK 确认送达即自动回 idle,其他调用方语义不变)(2026-10-20 r8:`stop()` 改为等在途 type5 回执→静置 1 s→再发 type3,`clearBeforeClose` 诊断开关、入站本会话 t3 计数;所有调用方的停止都多 1–3.5 s,语义不变)、`EditorialUI.java`(首页「期货盯盘 ›」按钮)、`TurboAddon.java`(`WatchController.init`;`onNlp` 在 `eligible` 之前加「开始/停止盯盘」本地命令;r8:本地命令模板钉 chat/chat/workflow + HasNextRound=false;r9:本地命令终态改为等官方本轮 onResponseComplete 后再发(`onComplete` 触发 `localFinal`,8 s 兜底),`status()` 附「最近本地指令」时序——r8 实机语音框仍不关,Flutter 侧不读 hasNextRound,差别只剩回灌时序)、(r10 2026-10-20:`NavGlasses.captionLines` 开放 type7 `max_lines` 5/8/10;`WatchController.MAX_NAMES` 5→12,流地址加 `mode=scroll|rotate&page_seconds&page_size`,新探针 `startProbe("lines")` 12 行每 5 s 更新;`WatchUI` 加「超过一屏时」卡与每屏行数按钮——插件收不到眼镜滚轮事件,超一屏靠固件自己的字幕历史翻看或网关轮播;r12 实机:滚轮翻看每次更新被拉回最后一页、max_lines 8/10 都只显示 7 行 → `captionLines` 默认 8、`visibleLines()`=7、`rotate()` 默认 true、网关 PAGE_SIZE 7;r14:行情行只见 6 行 → `pageSize()` prefs 默认 6 可调,网关 PAGE_SIZE 6;`WatchUI` 多合约一次添加、↑↓ 调序)、(r11 2026-10-20:`TurboAddon` 本地指令终态后若官方原件 hasNextRound=true 则反射调宿主 `p8/c.s(null,true,false)`(stop_assistant+phone_exit)关语音框,`localStop` 诊断开关在 `WatchUI`;依据:Flutter 读的是原件 next 标志经 `s8/b` 开下一轮,模板副本改了无效)、`MusicPlayer.java`(`background()` 未 held 则停盯盘)、`BackgroundWork.java`(帮助文案)、`build.sh`(WatchTest);**新文件** `WatchController/WatchUI/WatchSession/WatchStream/WatchVoice.java`、`tests/WatchTest.java`。网关侧 `custom/glasses-gateway/watch.py` + `/v1/watch/*` 路由(见 custom/README;r10 `MAX_NAMES=12`、`format_page/pages/page_of` 轮播排版,futures-kb `kb stream` MAX_SYMBOLS 同步 12)。
- **背单词模式(2026-10-20 起)**:`WatchController.java`(`task` 参数化 + `startWords()`,盯盘与背单词共用同一个 SSE→面板壳)、`WatchStream.java`(`type:panel` 与 `quote` 同义)、`WatchVoice.java`(「开始/停止背单词」词表;r13 2026-10-20 匹配前同音错字归一化 `HOMOPHONES`:ASR 把「盯盘」听成「定盘」「订牌」)、`TurboAddon.java`(`onNlp` 四路本地命令)、`EditorialUI.java`(首页「背单词 ›」)、`BackgroundWork.java`(帮助文案)、`MusicPlayer.java`(`background()` 退后台的租约检查改按 `WatchController.task`,原来只认「盯盘」,背单词一息屏就被停——2026-10-20 实机发现,r15)、`tests/WatchTest.java`;**新文件** `WordsUI.java`。网关侧 `custom/glasses-gateway/words.py`、`youdao_sync.py` + `/v1/words/*` 路由。
- **微信读书页读 Markdown(2026-10-20 r15)**:**新文件** `android-addon/src/com/turboio/addon/MarkdownText.java`(Markdown → 纯文本:去 front matter/标记、表格拍平、代码块保留、Obsidian 双链取别名,第一个标题当书名)+ `tests/MarkdownTextTest.java`;`ReaderLibrary.importText` 在 `looksLikeMarkdown` 时先转换再入书架(书名/作者「本机 Markdown」);`build.sh` 加 javac+java 一对。公开版正文来源仍只有本机文件,未碰网页正文。r16:`ReaderLibrary.importText` 导入后 `first` 跳到新书页;**r17 新文件 `ReaderShelfUI.java`**(书架管理窗口:全部书 ↑↓/置顶/删除)+ `ReaderLibrary.move/remove/renumber/editable`(改完重编 token 并 save,删书连正文文件)+ `ReaderUI` 按钮「管理书架 · 删除 / 排序」;**r18(2026-10-21)**:`ReaderLibrary.event` 收到眼镜菜单发起的书架请求(event 1、sid=0)直接 `send(0)`(未在阅读则先 `ReaderBridge.reset()`),与上游 iOS `ReaderBridge.m` 一致,原 Android 版只提示「请在手机点击发送」;`ReaderUI` 去掉书名列表与上一页/下一页,改为书架总数一行,按钮「从手机发送书架到眼镜」固定从第一页发;逐本菜单(打开正文并发送 / 导入对应 TXT)挪到 `ReaderShelfUI` 点书名
- **入站记录诊断(2026-10-21 r19)**:**新文件** `android-addon/src/com/turboio/addon/InboundTrace.java`(会话期间宿主事件只读记录);`NativeTransfer.event` 入口加一行调用;`WatchController.begin/stop` 插分隔行;`WatchUI`(`traceButtons` 复制/清空)、`WordsUI` 状态行显示;`tests/hostevents/HostEventTest.java` 加 `InboundTrace` 桩;r20 `InboundTrace.launcher` 解业务 15 的 type/cmd/JSON 前 200 字;**r21 长按退出 = 停止**:`WatchSession.subtitleExit`(业务 15 type 24 `glass_preview` aiSubtitle action=2)、`WatchController.init` 注册 `NativeTransfer.receive`(ready 且非续期才停)、`WatchUI` 文案、`tests/WatchTest.java` 7 条;**r22 背单词旋钮回看**:`WatchSession.wheelScroll`(业务 15 type 76 analytics_log action_type=scroll,分不出方向)、`WatchController.wordsBack()` → 网关 `/v1/words/back`、`WordsUI` 文案;网关 `WordsControl` + `Deck.history/back_entry`、`words.BACK_PAUSE=15`;**r23** 撤掉 `WatchUI`/`WordsUI` 的诊断卡与入站记录显示,`NativeTransfer.event` 不再调 `InboundTrace`,`InboundTrace.java` 已删(Bill 同意);**r24** `WatchController.init` 监听只注册一次(`listening`)+ `wordsBack` 200 ms 去抖;**r25 审查修复**:`NavGlasses.stop()` 外部入口在盯盘/背单词运行时不受理、REUSE 拒绝、内部改私有 `halt()`;`WatchController.stop` 未运行不碰会话、`spawn` 建连后核 epoch、语音 `voicePending`;网关 `_watch` `_peer_gone`、`StreamHub` 换合约立即回收旧流
- `.gitignore`:加 `local/`、`/.mcp.json`

## 目录约定

- `custom/` — Bill 自己的功能代码与配置模板(进 git)
  - `custom/dashboard/` — futures-kb / futures-lhb → 仪表盘卡片 / App ZIP 的数据映射脚本(调上游 `dashboard-service` 的 SDK)
  - `custom/notify/` — 飞书/知识库消息 → 眼镜的桥
  - `custom/glasses-gateway/` — **眼镜问答网关（2026-10-19）+ 盯盘推流（2026-10-20，`watch.py`、`/v1/watch/names|stream`，子进程 `kb stream`，详见 custom/README）**：挡在 Hermes API 前的本机 HTTP 服务（8650，纯标准库 `gateway.py`），Tailscale serve 指向它。行情类问题（含 行情/多少钱/现价/报价/价格/盘面/自选 且不含 为什么/分析/怎么看 等）不经 LLM，直接读 futures-kb `kb quotes --json` 的 20 秒缓存，SSE 流式 1 秒内回；其余请求原样转发 127.0.0.1:8642。原因：Hermes 调工具时首个可见字 11 秒才到，眼镜回答区早已关闭（1.7 秒的普通问答正常）。LaunchAgent 模板 `com.dyb.turboio-gateway.plist`，日志 `local/logs/gateway.log`。**背单词（2026-10-20）**：`words.py`（排版、牌组游标、探针帧，`WIDTH=19` 汉字格、`ASCII_PER_CELL` 探针后定；**眼镜字体无 IPA 字形，音标经 `safe_ipa` 转 ASCII 近似上镜**，2026-10-20 实机定）+ `youdao_sync.py`（有道网页版单词本 www.youdao.com/webwordbook/wordlist 的接口 `wordbook/webapi/v2/word/list` 用 Cookie 分页拉取、公开 `jsonapi` 补音标并缓存 `local/words/phonetic-cache.json`，或 `--xml` 读 PC 版导出）+ `/v1/words/status|stream`；词库 `local/words/wordbook.json`、游标 `local/words/state.json`、原始响应 `local/words/raw/`、Cookie `local/config/youdao-cookie.txt`（全部 gitignored）
  - `custom/docs/` — 自己整理的操作文档:`FLASH_GUIDE.md`(刷机准备、vivo X Fold 5 专项、四步、停止条件、实刷记录表)、`GLASSES_PROMPT.md`(眼镜问答个人提示词原文 + 模型与对话页三项填法,重装丢设置时照抄)
  - `custom/README.md` — 每个子目录做什么、怎么跑、验收到哪一步
- `local/` — 不进 git:`downloads/`(APK、固件、SHA)、`tokens/`(dashboard-service 签发的 token)、`config/`(Key 的本机副本)、
  `tools/`(apktool 3.0.3 jar+启动器、REA 用的 jadx-headless-mcp 0.7.1 jar)、`android-sdk/`(platform-tools、platforms/android-36、build-tools/36.0.0,
  用 Google cmdline-tools 装的项目内 SDK,`_dl/` 是下载缓存)。**Android 工具链全部装在这里,不装全局**(2026-10-18 定)。
- `custom/scripts/sign_guarded.sh` — C0 签名脚本,Bill 在自己终端跑(apksigner 交互要口令,口令不进聊天):SHA 核对 → zipalign → v3 签名 → 证书指纹必须不变 → `verify_apk --guarded` → SHA 记 SUMS。
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
