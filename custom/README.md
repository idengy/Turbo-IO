# custom/

Bill 自己的功能代码与文档。子目录约定见根 CLAUDE.md「目录约定」。

- `docs/FLASH_GUIDE.md` — 刷机指南(vivo X Fold 5 专项准备 + 上游四步 + 停止条件 + 实刷记录表)
- `scripts/env.sh` — 项目内工具链环境(JDK 17、Android SDK 36、apktool、REA),跑 build/adb/apktool 前 `source`
- `scripts/sign_guarded.sh` — C 段 C0:对 B 段 guarded 包签名并验证(Bill 自己终端跑,apksigner 交互要口令;指纹必须不变,`verify_apk --guarded` 必须 exit 0),产出 `local/build/TurboIO-RayNeo-1.0.6-guarded-signed.apk`(2026-10-19)
- `docs/A4_SIGN_INSTALL.md` — A 段第 4 步:自签名 keystore、zipalign/apksigner、卸官方装自签、分层验收、回退(2026-10-18)

## glasses-gateway（2026-10-19）

眼镜问答网关。`python3 custom/glasses-gateway/gateway.py` 监听 127.0.0.1:8650；行情问题直答（天勤数据经 futures-kb `kb quotes --json`，20 秒缓存），其余转发 Hermes 8642。
验收：`curl -N http://127.0.0.1:8650/v1/chat/completions`（带 Hermes 的 Bearer）问「自选行情」首个 data 在 1 秒内；问「你是谁」走 Hermes 原样流式。常驻用 `com.dyb.turboio-gateway.plist`。

