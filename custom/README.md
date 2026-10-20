# custom/

Bill 自己的功能代码与文档。子目录约定见根 CLAUDE.md「目录约定」。

- `docs/FLASH_GUIDE.md` — 刷机指南(vivo X Fold 5 专项准备 + 上游四步 + 停止条件 + 实刷记录表)
- `scripts/env.sh` — 项目内工具链环境(JDK 17、Android SDK 36、apktool、REA),跑 build/adb/apktool 前 `source`
- `scripts/sign_guarded.sh` — C 段 C0:对 B 段 guarded 包签名并验证(Bill 自己终端跑,apksigner 交互要口令;指纹必须不变,`verify_apk --guarded` 必须 exit 0),产出 `local/build/TurboIO-RayNeo-1.0.6-guarded-signed.apk`(2026-10-19)
- `docs/A4_SIGN_INSTALL.md` — A 段第 4 步:自签名 keystore、zipalign/apksigner、卸官方装自签、分层验收、回退(2026-10-18)

## glasses-gateway（2026-10-19）

眼镜问答网关。`python3 custom/glasses-gateway/gateway.py` 监听 127.0.0.1:8650；行情问题直答（天勤数据经 futures-kb `kb quotes --json`，20 秒缓存），其余转发 Hermes 8642。
验收：`curl -N http://127.0.0.1:8650/v1/chat/completions`（带 Hermes 的 Bearer）问「自选行情」首个 data 在 1 秒内；问「你是谁」走 Hermes 原样流式。**盯盘推流（2026-10-20）**：`watch.py`（排版 + `kb stream` 子进程管理，纯 stdlib、可单测）+ gateway 两条路由，同一把 Bearer：`GET /v1/watch/names` → 65 个品种名与短名；`GET /v1/watch/stream?names=螺纹钢,铁矿石`（≤10，可 %XX 编码）→ SSE，`data: {"type":"quote","text":"10:31:05\n螺纹 3063 -0.55\n…","asof":…,"rows":[…]}`，≥1 s 一帧、永远最新快照，无变化每 ≤10 s 发 `: hb`，出错发 `{"type":"error"}` 后关；≤4 个品种首行加时钟，5 个不占位。每个品种组合一个 `futures-kb venv/bin/python -m kb stream …` 子进程，最后一个客户端走后 30 s 回收，exit 1 退避重启、exit 2 置错。验收：`python3 -m unittest discover -s custom/glasses-gateway/tests -v`；`curl -N -H "Authorization: Bearer $KEY" 'http://127.0.0.1:8650/v1/watch/stream?names=螺纹钢'`（Key 从 `~/.hermes/.env` 读进变量，不打印）；断开 30 s 后 `pgrep -f "kb stream"` 为 0。
常驻用 `com.dyb.turboio-gateway.plist`(2026-10-19 已装到 `~/Library/LaunchAgents`,python 固定 Homebrew 3.11 绝对路径;改模板后 `cp` 过去再 `launchctl bootout gui/$(id -u)/com.dyb.turboio-gateway && launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.dyb.turboio-gateway.plist`)。

