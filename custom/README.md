# custom/

Bill 自己的功能代码与文档。子目录约定见根 CLAUDE.md「目录约定」。

- `docs/FLASH_GUIDE.md` — 刷机指南(vivo X Fold 5 专项准备 + 上游四步 + 停止条件 + 实刷记录表)
- `scripts/env.sh` — 项目内工具链环境(JDK 17、Android SDK 36、apktool、REA),跑 build/adb/apktool 前 `source`
- `scripts/sign_guarded.sh` — C 段 C0:对 B 段 guarded 包签名并验证(Bill 自己终端跑,apksigner 交互要口令;指纹必须不变,`verify_apk --guarded` 必须 exit 0),产出 `local/build/TurboIO-RayNeo-1.0.6-guarded-signed.apk`(2026-10-19)
- `docs/A4_SIGN_INSTALL.md` — A 段第 4 步:自签名 keystore、zipalign/apksigner、卸官方装自签、分层验收、回退(2026-10-18)

## glasses-gateway（2026-10-19）

眼镜问答网关。`python3 custom/glasses-gateway/gateway.py` 监听 127.0.0.1:8650；行情问题直答（天勤数据经 futures-kb `kb quotes --json`，20 秒缓存），其余转发 Hermes 8642。
验收：`curl -N http://127.0.0.1:8650/v1/chat/completions`（带 Hermes 的 Bearer）问「自选行情」首个 data 在 1 秒内；问「你是谁」走 Hermes 原样流式。**盯盘推流（2026-10-20）**：`watch.py`（排版 + `kb stream` 子进程管理，纯 stdlib、可单测）+ gateway 两条路由，同一把 Bearer：`GET /v1/watch/names` → 65 个品种名与短名；`GET /v1/watch/resolve?q=铁矿 2701` → `{label:"铁矿石2701",display:"铁矿2701",symbol:"DCE.i2701"}`（解析交给 futures-kb `quotes.resolve_contracts`，郑商所 3 位年月；无月份 = 主连），404 = 未识别；`GET /v1/watch/stream?names=螺纹钢,铁矿石2701`（≤10，品种或品种+月份，可 %XX 编码）→ SSE，`data: {"type":"quote","text":"10:31:05\n螺纹 3063 -0.55\n…","asof":…,"rows":[…]}`，≥1 s 一帧、永远最新快照，无变化每 ≤10 s 发 `: hb`，出错发 `{"type":"error"}` 后关；≤4 个品种首行加时钟，5 个不占位。每个品种组合一个 `futures-kb venv/bin/python -m kb stream …` 子进程，最后一个客户端走后 30 s 回收，exit 1 退避重启、exit 2 置错。验收：`python3 -m unittest discover -s custom/glasses-gateway/tests -v`；`curl -N -H "Authorization: Bearer $KEY" 'http://127.0.0.1:8650/v1/watch/stream?names=螺纹钢'`（Key 从 `~/.hermes/.env` 读进变量，不打印）；断开 30 s 后 `pgrep -f "kb stream"` 为 0。
常驻用 `com.dyb.turboio-gateway.plist`(2026-10-19 已装到 `~/Library/LaunchAgents`,python 固定 Homebrew 3.11 绝对路径;改模板后 `cp` 过去再 `launchctl bootout gui/$(id -u)/com.dyb.turboio-gateway && launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.dyb.turboio-gateway.plist`)。

## 背单词（2026-10-20）

有道词典单词本 → 眼镜业务 19 文字面板循环显示「单词 / 音标 / 释义」，**不出声**。排版、节奏、顺序、词库全部在网关（改了即生效），插件只把盯盘的 SSE→面板壳换一个流地址（`WatchController.task`）。

**1. 拿到单词本（Bill 亲手，一次）**：Chrome 登录 https://dict.youdao.com/wordbook/wordlist （`/wordbook/` 本身是 404，2026-10-20 实测）→ 按 F12 打开 DevTools → Network 标签 → 刷新页面 → 点任意一条名字含 `webapi/words` 的请求 → 右侧 Headers → Request Headers → 找到 `Cookie:` 一行，复制冒号后面整段 → 存成 `local/config/youdao-cookie.txt`（一行，gitignored；不要贴进聊天）。
备选：有道 PC 版「单词本 → 导出」得到 XML，用 `--xml 文件` 读。

**2. 同步**：`python3 custom/glasses-gateway/youdao_sync.py`（或 `--xml 导出.xml`）→ 写 `local/words/wordbook.json`，原始响应落 `local/words/raw/<时间>.json`。打印「写入 … N 词；单词本 {…}；无音标 M 词」，N 应等于有道网页端的总数。Cookie 过期时报「接口返回 0 条」退出 2，**不覆盖**旧词库——重新复制 Cookie 再跑。网关按文件 mtime 热重载，同步后不用重启。

**3. 路由**（同一把 Bearer）：`GET /v1/words/status` → `{total, position, current_id, book_counts, updated, width, ascii_per_cell}`；`GET /v1/words/stream?seconds=6&order=seq|shuffle&probe=0|1` → SSE `data: {"type":"panel","text":"单词  /音标/\n释义…","index":n,"total":N}`，每 `seconds`（3–60）一帧，一个词释义太长拆成多帧（后续帧首行仍是单词），帧间隔 >10 s 补 `: hb`；`probe=1` 循环发标尺帧（数字 50 / 小写 52 / 汉字 24 / IPA / 混排）不动游标；词库空发 `{"type":"error"}` 后关。游标 `local/words/state.json`：顺序模式按添加时间倒序循环、断线重连与网关重启都接着上次；随机模式一轮内不重复。

**4. 排版常量**（`words.py` 顶部）：`WIDTH=19` 汉字格/行（P0-2 实测）、`MAX_LINES=5`、`ASCII_PER_CELL=2`（一个汉字格放几个英文/音标字符，**未实测**，手机「背单词 › 开始宽度探针」在眼镜上数每行能看清第几个字符后改这里，LaunchAgent `kickstart` 生效，不用重装 App）。

**验收**：`python3 -m unittest discover -s custom/glasses-gateway/tests -v`（test_words 20 条）；scratch 实例 `GATEWAY_PORT=8651 WORDS_DIR=<含 wordbook.json 的目录> python3 custom/glasses-gateway/gateway.py` 下 curl 三帧 / 重连接着上次 / probe 标尺 / 无 Key 401 已于 2026-10-20 通过。手机端：首页「背单词 ›」→ 每帧秒数（4/6/8/10/15）、随机开关、开始/停止、宽度探针；眼镜语音「开始背单词 / 停止背单词」。

